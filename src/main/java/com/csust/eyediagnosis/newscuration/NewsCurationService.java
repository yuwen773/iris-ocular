package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional WP-03 curation orchestrator enforcing reserve-before-IO paid-call receipts,
 * daily call/token/cost ceilings, auth circuit breaking, unknown-paid-outcome recovery, and
 * immutable evidence binding.
 *
 * <p>Canonical lock ordering across all write transactions:
 * {@code t_news_curation_budget -> t_news_model_call_receipt -> t_news_curation_candidate}.
 */
@Slf4j
@Service
public class NewsCurationService {

    private static final String DEFAULT_SCOPE = "GLOBAL";
    private static final DateTimeFormatter DB_UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<NewsCurationModels.CurationClaim>> CLAIM_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<NewsCurationModels.EvidenceViolation>> VIOLATION_LIST_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NewsCurationProperties properties;
    private final NewsCurationModelClient modelClient;
    private final NewsEvidenceVerifier verifier;
    private final NewsCurationPromptBuilder promptBuilder;
    private final ObjectMapper mapper;
    private final NewsModelProtocol modelProtocol;
    private final NewsEvidenceLedgerStore ledger;

    @Autowired
    public NewsCurationService(JdbcTemplate jdbc,
                               PlatformTransactionManager transactionManager,
                               NewsCurationProperties properties,
                               NewsCurationModelClient modelClient,
                               NewsEvidenceVerifier verifier,
                               NewsCurationPromptBuilder promptBuilder,
                               @Qualifier("newsProtocolObjectMapper") ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.modelClient = modelClient;
        this.verifier = verifier;
        this.promptBuilder = promptBuilder;
        this.mapper = mapper;
        this.modelProtocol = new NewsModelProtocol(mapper);
        this.ledger = new NewsEvidenceLedgerStore(jdbc);
    }

    public NewsCurationService(JdbcTemplate jdbc,
                               NewsCurationProperties properties,
                               NewsCurationModelClient modelClient,
                               NewsEvidenceVerifier verifier,
                               NewsCurationPromptBuilder promptBuilder,
                               ObjectMapper mapper) {
        this(jdbc, new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),
                properties, modelClient, verifier, promptBuilder, mapper);
    }

    public void configureDailyBudget(LocalDate budgetDate,
                                     String scope,
                                     int maxCalls,
                                     long maxTokens,
                                     long maxCostMicros) {
        if (budgetDate == null || scope == null || scope.isBlank()
                || maxCalls < 0 || maxTokens < 0 || maxCostMicros < 0) {
            throw new IllegalArgumentException("Invalid budget configuration");
        }
        String normalizedScope = scope.trim();
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO t_news_curation_budget
                    (budgetDate, scope, maxCalls, maxTokens, maxCostMicros)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    maxCalls = VALUES(maxCalls),
                    maxTokens = VALUES(maxTokens),
                    maxCostMicros = VALUES(maxCostMicros)
                """, budgetDate.toString(), normalizedScope, maxCalls, maxTokens, maxCostMicros));
    }

    public Optional<NewsCurationModels.BudgetSnapshot> getBudget(LocalDate budgetDate, String scope) {
        List<NewsCurationModels.BudgetSnapshot> rows = jdbc.query("""
                SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, scope,
                       maxCalls, usedCalls, reservedCalls,
                       maxTokens, usedTokens, reservedTokens,
                       maxCostMicros, usedCostMicros, reservedCostMicros,
                       circuitReason IS NOT NULL AS circuitBroken, circuitReason
                FROM t_news_curation_budget LEFT JOIN t_news_curation_circuit USING (scope)
                WHERE budgetDate = ? AND scope = ?
                """, (rs, rowNum) -> mapBudgetRow(rs), budgetDate.toString(), normalizeScope(scope));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void resetCircuitBreaker(LocalDate budgetDate, String scope, String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId is required");
        }
        String budgetScope = normalizeScope(scope);
        ensureBudgetRowExists(budgetDate, budgetScope);
        ensureCircuitRowExists(budgetScope);
        transactions.executeWithoutResult(status -> {
            lockCircuit(budgetScope);
            jdbc.update("""
                    UPDATE t_news_curation_circuit SET circuitReason = NULL WHERE scope = ?
                    """, budgetScope);
            log.info("Reset curation circuit breaker for date={}, scope={}, operatorId={}",
                    budgetDate, normalizeScope(scope), operatorId.trim());
        });
    }

    public NewsCurationModels.CurationResult curateRevision(long materialRevisionId,
                                                            String policyVersion,
                                                            LocalDate budgetDate,
                                                            String budgetScope) {
        requireInvocationBoundary();
        String effectivePolicy = effectivePolicy(policyVersion);
        LocalDate effectiveDate = budgetDate != null ? budgetDate : dbUtcDate();
        String effectiveScope = normalizeScope(budgetScope);

        NewsIngestionService.VisibleRevision revision = requireVisibleRevision(materialRevisionId);
        return executeCall(revision, promptBuilder.buildSnapshot(revision), effectivePolicy,
                effectiveDate, effectiveScope, null, null).candidate();
    }

    public record ModelRun(NewsModelProtocol.Identity identity, NewsCurationModels.CallReceiptSnapshot receipt,
                           JsonNode frozenOutput, boolean replayed) {
    }

    /** Runs one frozen public-literature stage. Results remain in the receipt ledger pending editorial workflow. */
    public ModelRun runModel(long materialRevisionId, String policyVersion, String budgetScope,
                             NewsModelProtocol.Selection selection) {
        return runModel(materialRevisionId, policyVersion, budgetScope, selection, null);
    }

    public ModelRun retryDefinitiveModel(long materialRevisionId, String policyVersion, String budgetScope,
                                        NewsModelProtocol.Selection selection, String failedCallId) {
        if (!NewsModelProfile.identifier(failedCallId, 64)) {
            throw new IllegalArgumentException("Invalid failed model receipt identifier");
        }
        return runModel(materialRevisionId, policyVersion, budgetScope, selection, failedCallId);
    }

    private ModelRun runModel(long materialRevisionId, String policyVersion, String budgetScope,
                              NewsModelProtocol.Selection selection, String retryCallId) {
        requireInvocationBoundary();
        NewsModelProfile profile = properties.getModelProfiles().get(selection.profileKey());
        if (profile == null) {
            throw new IllegalStateException("MODEL_CLIENT_UNCONFIGURED");
        }
        String policy = effectivePolicy(policyVersion);
        String scope = normalizeScope(budgetScope);
        if (!NewsModelProfile.identifier(scope, 64)) {
            throw new IllegalArgumentException("Invalid budget scope");
        }
        NewsIngestionService.VisibleRevision revision = requireVisibleRevision(materialRevisionId);
        NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(revision);
        var prepared = modelProtocol.prepare(selection, profile, policy, snapshot, previousTrial(revision),
                frozenUpstream(selection, revision.revisionId(), policy),
                ledger.overlay(materialRevisionId));
        Execution execution = executeCall(revision, snapshot, policy, dbUtcDate(), scope, prepared, retryCallId);
        var receipt = getCallReceipt(execution.callId()).orElseThrow();
        return new ModelRun(prepared.identity(), receipt, readFrozenOutput(execution.callId()), execution.replayed());
    }

    private Execution executeCall(NewsIngestionService.VisibleRevision revision,
                                  NewsCurationModels.EvidenceSnapshot snapshot, String effectivePolicy,
                                  LocalDate effectiveDate, String effectiveScope, NewsModelProtocol.Prepared prepared,
                                  String retryCallId) {

        String snapshotJson = promptBuilder.canonicalSnapshotJson(snapshot);
        String snapshotSha256 = NewsMaterialCanonicalizer.sha256Hex(snapshotJson.getBytes(StandardCharsets.UTF_8));
        String pendingCallId = UUID.randomUUID().toString();

        // Ensure budget row exists outside reserve transaction so SELECT ... FOR UPDATE hits a real row without gap locks.
        ensureBudgetRowExists(effectiveDate, effectiveScope);
        ensureLifetimeBudgetRowExists();
        ensureCircuitRowExists(effectiveScope);

        ReservationOutcome reserved = transactions.execute(status -> reserveAttempt(
                revision, snapshot, snapshotJson, snapshotSha256,
                effectivePolicy, effectiveDate, effectiveScope, pendingCallId, prepared, retryCallId));

        if (reserved.replayedCallId != null || reserved.replayedResult != null) {
            return new Execution(reserved.replayedCallId, reserved.replayedResult, true);
        }

        String dispatchFailure = transactions.execute(status ->
                dispatchReservedCall(reserved.request.callId(), effectiveDate, effectiveScope));
        if ("FAILED_TO_MARK_DISPATCHED".equals(dispatchFailure)) {
            transactions.execute(status -> reclaimSingleStaleCall(reserved.request.callId()));
        }
        if (dispatchFailure != null) {
            throw new IllegalStateException(dispatchFailure);
        }

        NewsCurationModels.ModelCallOutcome outcome;
        try {
            outcome = modelClient.invoke(reserved.request);
            if (outcome == null) {
                outcome = new NewsCurationModels.UnknownOutcome("NULL_OUTCOME", null, null, 0);
            }
        } catch (Exception ex) {
            log.warn("Model call threw exception after dispatch, marking OUTCOME_UNKNOWN: callId={}, type={}",
                    reserved.request.callId(), ex.getClass().getSimpleName());
            outcome = new NewsCurationModels.UnknownOutcome("CLIENT_EXCEPTION", null, null, 0);
        }

        final NewsCurationModels.ModelCallOutcome finalOutcome = outcome;
        SettlementResult settled = transactions.execute(status -> settleOutcome(
                reserved.request, snapshot, snapshotJson, snapshotSha256,
                reserved.reservedTokens, reserved.reservedCostMicros,
                effectiveDate, effectiveScope, finalOutcome, prepared));
        if (settled == null) {
            throw new IllegalStateException("MODEL_CALL_SETTLEMENT_MISSING");
        }
        if (settled.definitiveFailureMessage() != null) {
            throw new IllegalStateException(settled.definitiveFailureMessage());
        }
        return new Execution(reserved.request.callId(), settled.result(), false);
    }

    private void requireInvocationBoundary() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("NEWS_CURATION_DISABLED");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("MODEL_CALL_REQUIRES_COMMITTED_RESERVATION");
        }
    }

    private String effectivePolicy(String policyVersion) {
        return (policyVersion == null || policyVersion.isBlank()) ? properties.getPolicyVersion() : policyVersion.trim();
    }

    private NewsIngestionService.VisibleRevision requireVisibleRevision(long id) {
        return loadVisibleRevision(id).orElseThrow(() -> new IllegalArgumentException("REVISION_NOT_VISIBLE"));
    }

    private NewsEditorialEvidence previousTrial(NewsIngestionService.VisibleRevision revision) {
        if (!"clinicaltrials-gov".equals(revision.source())) {
            return null;
        }
        return jdbc.query("""
                SELECT id FROM t_news_material_revision
                WHERE materialId = ? AND id < ? AND visibleAt IS NOT NULL
                ORDER BY id DESC LIMIT 1
                """, (rs, row) -> rs.getLong(1), revision.materialId(), revision.revisionId())
                .stream().findFirst().flatMap(this::loadVisibleRevision).map(promptBuilder::buildSnapshot)
                .map(previous -> NewsEditorialEvidence.from(previous, mapper)).orElse(null);
    }

    private NewsModelProtocol.Upstream frozenUpstream(NewsModelProtocol.Selection selection, long revision, String policy) {
        if (selection.upstreamCallId() == null) {
            return null;
        }
        return jdbc.query("""
                SELECT callIdentityJson, responseSha256, frozenResponseJson
                FROM t_news_model_call_receipt
                WHERE callId = ? AND materialRevisionId = ? AND state = 'SUCCEEDED'
                    AND logicalCallSha256 IS NOT NULL
                """, (rs, row) -> {
            var identity = readIdentity(rs.getString("callIdentityJson"));
            String output = rs.getString("frozenResponseJson");
            String digest = rs.getString("responseSha256");
            if (!selection.namespace().equals(identity.namespace()) || !policy.equals(identity.policyVersion())
                    || identity.stage() != NewsModelProtocol.Stage.SCREENING
                    || output == null || !NewsModelProtocol.sha256(output).equals(digest)) {
                throw new IllegalArgumentException("UPSTREAM_MODEL_RESULT_UNAVAILABLE");
            }
            return new NewsModelProtocol.Upstream(digest, readTree(output));
        }, selection.upstreamCallId(), revision).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UPSTREAM_MODEL_RESULT_UNAVAILABLE"));
    }

    private JsonNode readFrozenOutput(String callId) {
        return jdbc.queryForObject("""
                SELECT frozenResponseJson, responseSha256 FROM t_news_model_call_receipt WHERE callId = ?
                """, (rs, row) -> {
            String raw = rs.getString("frozenResponseJson");
            if (raw == null) {
                return null;
            }
            if (!NewsModelProtocol.sha256(raw).equals(rs.getString("responseSha256"))) {
                throw new IllegalStateException("Corrupt frozen model output digest");
            }
            return readTree(raw);
        }, callId);
    }

    private NewsModelProtocol.Identity readIdentity(String raw) {
        try {
            return mapper.readValue(raw, NewsModelProtocol.Identity.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt stored model identity", ex);
        }
    }

    private JsonNode readTree(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt stored model output", ex);
        }
    }

    private String dispatchReservedCall(String callId, LocalDate budgetDate, String budgetScope) {
        String circuitReason = lockCircuit(budgetScope);
        if (circuitReason != null) {
            lockBudget(budgetDate, budgetScope);
            ReceiptRow receipt = lockReceipt(callId);
            if (receipt.state == NewsCurationModels.CallState.RESERVED) {
                abortReservedCall(receipt, "CURATION_CIRCUIT_BROKEN");
            }
            return "CURATION_CIRCUIT_BROKEN:" + circuitReason;
        }
        int updated = jdbc.update("""
                UPDATE t_news_model_call_receipt
                SET state = 'DISPATCHED', dispatchedAt = UTC_TIMESTAMP(6)
                WHERE callId = ? AND state = 'RESERVED' AND deadlineAt > UTC_TIMESTAMP(6)
                """, callId);
        return updated == 1 ? null : "FAILED_TO_MARK_DISPATCHED";
    }

    public NewsCurationModels.StaleReclaimResult reclaimStaleCalls() {
        List<StaleCandidate> staleRows = jdbc.query("""
                SELECT callId, state, DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope
                FROM t_news_model_call_receipt
                WHERE state IN ('RESERVED', 'DISPATCHED')
                  AND deadlineAt <= UTC_TIMESTAMP(6)
                ORDER BY budgetDate ASC, budgetScope ASC, callId ASC
                """, (rs, rowNum) -> new StaleCandidate(
                rs.getString("callId"),
                NewsCurationModels.CallState.valueOf(rs.getString("state")),
                LocalDate.parse(rs.getString("budgetDate")),
                rs.getString("budgetScope")
        ));

        int aborted = 0;
        int unknown = 0;
        for (StaleCandidate stale : staleRows) {
            ensureBudgetRowExists(stale.budgetDate, stale.budgetScope);
            NewsCurationModels.CallState resolved = transactions.execute(status -> reclaimSingleStaleCall(stale.callId));
            if (resolved == NewsCurationModels.CallState.ABORTED_PRE_IO) {
                aborted++;
            } else if (resolved == NewsCurationModels.CallState.OUTCOME_UNKNOWN) {
                unknown++;
            }
        }
        return new NewsCurationModels.StaleReclaimResult(aborted, unknown);
    }

    public NewsCurationModels.CallReceiptSnapshot reconcileUnknownCall(
            String callId,
            NewsCurationModels.ReconcileResolution resolution,
            String reconciledBy,
            String note,
            String recoveredRawJson,
            Integer promptTokens,
            Integer completionTokens,
            Long actualCostMicros,
            String providerRequestId) {
        if (callId == null || callId.isBlank() || resolution == null || reconciledBy == null || reconciledBy.isBlank()) {
            throw new IllegalArgumentException("callId, resolution, and reconciledBy are required");
        }
        BudgetRef budgetRef = jdbc.query("""
                SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope
                FROM t_news_model_call_receipt
                WHERE callId = ?
                """, (rs, rowNum) -> new BudgetRef(LocalDate.parse(rs.getString("budgetDate")), rs.getString("budgetScope")), callId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_CALL_ID"));
        ensureBudgetRowExists(budgetRef.budgetDate, budgetRef.scope);

        return transactions.execute(status -> {
            lockBudget(budgetRef.budgetDate, budgetRef.scope);
            ReceiptRow receipt = lockReceipt(callId);
            if (receipt.state != NewsCurationModels.CallState.OUTCOME_UNKNOWN) {
                throw new IllegalStateException("RECEIPT_NOT_UNKNOWN:" + receipt.state);
            }

            switch (resolution) {
                case CONFIRMED_CHARGED_WITH_OUTPUT -> {
                    if (recoveredRawJson == null || recoveredRawJson.isBlank()
                            || promptTokens == null || completionTokens == null || actualCostMicros == null
                            || promptTokens < 0 || completionTokens < 0 || actualCostMicros < 0) {
                        throw new IllegalArgumentException("Charged reconciliation with output requires recovered JSON and usage metrics");
                    }
                    NewsIngestionService.VisibleRevision rev = loadVisibleRevision(receipt.materialRevisionId)
                            .orElseThrow(() -> new IllegalStateException("REVISION_MISSING"));
                    NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(rev);
                    String snapshotJson = promptBuilder.canonicalSnapshotJson(snapshot);
                    String snapshotSha256 = NewsMaterialCanonicalizer.sha256Hex(
                            snapshotJson.getBytes(StandardCharsets.UTF_8));
                    boolean staged = receipt.identityJson != null;
                    if (staged && modelProtocol.validatedOutput(recoveredRawJson, readIdentity(receipt.identityJson),
                            snapshot, properties.getMaxResponseChars()) == null) {
                        throw new IllegalArgumentException("Recovered output violates frozen model contract");
                    }
                    String responseSha = NewsMaterialCanonicalizer.sha256Hex(
                            recoveredRawJson.getBytes(StandardCharsets.UTF_8));

                    long actualTokens = (long) promptTokens + completionTokens;
                    settleChargedBudget(receipt.budgetDate, receipt.budgetScope,
                            receipt.reservedTokens, receipt.reservedCostMicros, actualTokens, actualCostMicros);

                    jdbc.update("""
                            UPDATE t_news_model_call_receipt
                            SET state = 'SUCCEEDED',
                                promptTokens = ?,
                                completionTokens = ?,
                                actualCostMicros = ?,
                                httpStatus = COALESCE(httpStatus, 200),
                                providerRequestId = COALESCE(?, providerRequestId),
                                responseSha256 = ?,
                                frozenResponseJson = ?,
                                errorCode = NULL,
                                reconciledBy = ?,
                                reconcileNote = ?,
                                reconciledAt = UTC_TIMESTAMP(6),
                                completedAt = UTC_TIMESTAMP(6)
                            WHERE callId = ?
                            """, promptTokens, completionTokens, actualCostMicros, providerRequestId,
                            responseSha, staged ? recoveredRawJson : null, reconciledBy.trim(), note, callId);

                    if (!staged) {
                        upsertVerifiedCandidate(snapshot, snapshotJson, snapshotSha256,
                                receipt.policyVersion, callId, verifier.parseAndVerify(recoveredRawJson, snapshot));
                    }
                }
                case CONFIRMED_CHARGED_NO_OUTPUT -> {
                    if (promptTokens == null || completionTokens == null || actualCostMicros == null
                            || promptTokens < 0 || completionTokens < 0 || actualCostMicros < 0) {
                        throw new IllegalArgumentException("Charged reconciliation requires usage metrics");
                    }
                    long actualTokens = (long) promptTokens + completionTokens;
                    settleChargedBudget(receipt.budgetDate, receipt.budgetScope,
                            receipt.reservedTokens, receipt.reservedCostMicros, actualTokens, actualCostMicros);

                    jdbc.update("""
                            UPDATE t_news_model_call_receipt
                            SET state = 'FAILED_CHARGED',
                                promptTokens = ?,
                                completionTokens = ?,
                                actualCostMicros = ?,
                                providerRequestId = COALESCE(?, providerRequestId),
                                reconciledBy = ?,
                                reconcileNote = ?,
                                reconciledAt = UTC_TIMESTAMP(6),
                                completedAt = UTC_TIMESTAMP(6)
                            WHERE callId = ?
                            """, promptTokens, completionTokens, actualCostMicros, providerRequestId,
                            reconciledBy.trim(), note, callId);

                    deletePendingReconcileCandidate(receipt.materialRevisionId, receipt.policyVersion, callId);
                }
                case CONFIRMED_NOT_CHARGED -> {
                    releaseReservedBudget(receipt.budgetDate, receipt.budgetScope,
                            receipt.reservedTokens, receipt.reservedCostMicros);

                    jdbc.update("""
                            UPDATE t_news_model_call_receipt
                            SET state = 'FAILED_UNCHARGED',
                                providerRequestId = COALESCE(?, providerRequestId),
                                reconciledBy = ?,
                                reconcileNote = ?,
                                reconciledAt = UTC_TIMESTAMP(6),
                                completedAt = UTC_TIMESTAMP(6)
                            WHERE callId = ?
                            """, providerRequestId, reconciledBy.trim(), note, callId);

                    deletePendingReconcileCandidate(receipt.materialRevisionId, receipt.policyVersion, callId);
                }
            }
            return getCallReceipt(callId).orElseThrow();
        });
    }

    public Optional<NewsCurationModels.CurationResult> getCandidate(long materialRevisionId, String policyVersion) {
        List<NewsCurationModels.CurationResult> rows = jdbc.query("""
                SELECT callId, status, decision, evidenceTier, relevanceScore,
                       suggestedTitle, suggestedSummary, category,
                       limitationsJson, claimsJson, violationsJson,
                       evidenceSnapshotSha256, evidenceSnapshotJson
                FROM t_news_curation_candidate
                WHERE materialRevisionId = ? AND policyVersion = ?
                """, (rs, rowNum) -> mapCandidateRow(rs, false), materialRevisionId, policyVersion);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<NewsCurationModels.CurationResult> listCandidatesByStatus(NewsCurationModels.CandidateStatus status) {
        return jdbc.query("""
                SELECT callId, status, decision, evidenceTier, relevanceScore,
                       suggestedTitle, suggestedSummary, category,
                       limitationsJson, claimsJson, violationsJson,
                       evidenceSnapshotSha256, evidenceSnapshotJson
                FROM t_news_curation_candidate
                WHERE status = ?
                ORDER BY id ASC
                """, (rs, rowNum) -> mapCandidateRow(rs, false), status.name());
    }

    public Optional<NewsCurationModels.CallReceiptSnapshot> getCallReceipt(String callId) {
        List<NewsCurationModels.CallReceiptSnapshot> rows = jdbc.query(RECEIPT_SELECT + " WHERE callId = ?",
                (rs, rowNum) -> mapReceiptSnapshot(rs), callId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<NewsCurationModels.CallReceiptSnapshot> listCallReceiptsForRevision(long materialRevisionId,
                                                                                   String policyVersion) {
        return jdbc.query(RECEIPT_SELECT
                        + " WHERE materialRevisionId = ? AND policyVersion = ? ORDER BY attemptNo ASC",
                (rs, rowNum) -> mapReceiptSnapshot(rs), materialRevisionId, policyVersion);
    }

    private ReservationOutcome reserveAttempt(NewsIngestionService.VisibleRevision revision,
                                              NewsCurationModels.EvidenceSnapshot snapshot,
                                              String snapshotJson,
                                              String snapshotSha256,
                                              String policyVersion,
                                              LocalDate budgetDate,
                                              String budgetScope,
                                              String callId, NewsModelProtocol.Prepared prepared, String retryCallId) {
        // Canonical lock order: circuit (when needed), global day guard, scope budget, lifetime ceiling,
        // receipts, candidate.
        String circuitReason = lockCircuit(budgetScope);
        NewsCurationModels.BudgetSnapshot budget = lockBudget(budgetDate, budgetScope);
        NewsCurationModels.LifetimeBudget lifetime = lockLifetimeBudget();
        String logical = prepared == null ? null : prepared.logicalCallSha256();

        List<ReceiptRow> existingReceipts = jdbc.query("""
                SELECT callId, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       state, reservedTokens, reservedCostMicros, callIdentityJson
                FROM t_news_model_call_receipt
                WHERE materialRevisionId = ? AND policyVersion = ?
                   AND ((? IS NULL AND logicalCallSha256 IS NULL) OR logicalCallSha256 = ?)
                ORDER BY attemptNo ASC
                FOR UPDATE
                """, (rs, rowNum) -> mapReceiptRow(rs), revision.revisionId(), policyVersion, logical, logical);

        Optional<NewsCurationModels.CurationResult> existingCandidate = prepared != null ? Optional.empty() : jdbc.query("""
                SELECT callId, status, decision, evidenceTier, relevanceScore,
                       suggestedTitle, suggestedSummary, category,
                       limitationsJson, claimsJson, violationsJson,
                       evidenceSnapshotSha256, evidenceSnapshotJson
                FROM t_news_curation_candidate
                WHERE materialRevisionId = ? AND policyVersion = ?
                FOR UPDATE
                """, (rs, rowNum) -> mapCandidateRow(rs, true), revision.revisionId(), policyVersion)
                .stream().findFirst();

        if (existingCandidate.isPresent()) {
            NewsCurationModels.CurationResult candidate = existingCandidate.get();
            if (candidate.status() == NewsCurationModels.CandidateStatus.NEEDS_RECONCILIATION) {
                throw new IllegalStateException("CURATION_AWAITING_RECONCILIATION");
            }
            return new ReservationOutcome(candidate.callId(), candidate, null, 0L, 0L);
        }

        int maxAttempt = 0;
        for (ReceiptRow r : existingReceipts) {
            maxAttempt = Math.max(maxAttempt, r.attemptNo);
            if (r.state == NewsCurationModels.CallState.RESERVED
                    || r.state == NewsCurationModels.CallState.DISPATCHED
                    || r.state == NewsCurationModels.CallState.OUTCOME_UNKNOWN) {
                throw new IllegalStateException("CURATION_AWAITING_RECONCILIATION");
            }
        }

        ReceiptRow last = existingReceipts.isEmpty() ? null : existingReceipts.getLast();
        if (retryCallId != null && (last == null || !retryCallId.equals(last.callId)
                || last.state != NewsCurationModels.CallState.FAILED_DEFINITIVE || !budgetScope.equals(last.budgetScope))) {
            throw new IllegalArgumentException("MODEL_RETRY_REQUIRES_LAST_DEFINITIVE_FAILURE");
        }
        if (prepared != null && retryCallId == null && last != null
                && (last.state == NewsCurationModels.CallState.SUCCEEDED
                || last.state == NewsCurationModels.CallState.FAILED_CHARGED
                || last.state == NewsCurationModels.CallState.FAILED_DEFINITIVE)) {
            return new ReservationOutcome(last.callId, null, null, 0L, 0L);
        }

        if (circuitReason != null) {
            throw new IllegalStateException("CURATION_CIRCUIT_BROKEN:" + circuitReason);
        }

        long estTokens = prepared == null ? Math.max(1L, properties.getEstimatedTokensPerCall()) : prepared.reservedTokens();
        long estCost = prepared == null ? Math.max(0L, properties.getEstimatedCostMicrosPerCall()) : prepared.reservedCost();

        if (budget.usedCalls() + budget.reservedCalls() + 1 > budget.maxCalls()
                || budget.usedTokens() + budget.reservedTokens() + estTokens > budget.maxTokens()
                || budget.usedCostMicros() + budget.reservedCostMicros() + estCost > budget.maxCostMicros()
                || !withinGlobalBudget(budgetDate, estTokens, estCost)) {
            throw new IllegalStateException("BUDGET_EXHAUSTED");
        }
        // The lifetime ceiling counts every receipt ever written, so it cannot be escaped by crossing a UTC
        // date, splitting the budget scope or restarting the process.
        if (lifetime.exhaustedBy(estTokens, estCost)) {
            throw new IllegalStateException("LIFETIME_BUDGET_EXHAUSTED");
        }

        int nextAttempt = maxAttempt + 1;
        String idempotencyKey = prepared == null ? revision.revisionId() + ":" + policyVersion + ":" + nextAttempt
                : logical + ":" + nextAttempt;
        NewsCurationModels.ModelCallRequest request = prepared == null
                ? promptBuilder.buildRequest(callId, idempotencyKey, policyVersion, snapshot)
                : prepared.request(callId, idempotencyKey, snapshot);

        jdbc.update("""
                UPDATE t_news_curation_budget
                SET reservedCalls = reservedCalls + 1,
                    reservedTokens = reservedTokens + ?,
                    reservedCostMicros = reservedCostMicros + ?
                WHERE budgetDate = ? AND scope = ?
                """, estTokens, estCost, budgetDate.toString(), budgetScope);

        long timeoutMicros = Math.max(1_000L, request.timeout().toNanos() / 1_000L);
        jdbc.update("""
                INSERT INTO t_news_model_call_receipt
                    (callId, idempotencyKey, materialRevisionId, policyVersion, attemptNo,
                     budgetDate, budgetScope, provider, modelName, promptSha256, state,
                     reservedTokens, reservedCostMicros, logicalCallSha256, callIdentityJson, reservedAt, deadlineAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?, UTC_TIMESTAMP(6),
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? MICROSECOND))
                """,
                callId, idempotencyKey, revision.revisionId(), policyVersion, nextAttempt,
                budgetDate.toString(), budgetScope, request.provider(), request.modelName(),
                request.promptSha256(), estTokens, estCost, logical,
                prepared == null ? null : prepared.identityJson(), timeoutMicros);

        return new ReservationOutcome(null, null, request, estTokens, estCost);
    }

    private SettlementResult settleOutcome(NewsCurationModels.ModelCallRequest request,
                                           NewsCurationModels.EvidenceSnapshot snapshot,
                                           String snapshotJson,
                                           String snapshotSha256,
                                           long reservedTokens,
                                           long reservedCostMicros,
                                           LocalDate budgetDate,
                                           String budgetScope,
                                           NewsCurationModels.ModelCallOutcome outcome,
                                           NewsModelProtocol.Prepared prepared) {
        if (outcome instanceof NewsCurationModels.DefinitiveFailure failure && failure.authFailure()) {
            lockCircuit(budgetScope);
        }
        lockBudget(budgetDate, budgetScope);
        ReceiptRow receipt = lockReceipt(request.callId());
        if (receipt.state != NewsCurationModels.CallState.DISPATCHED) {
            throw new IllegalStateException("CALL_ALREADY_TRANSITIONED:" + receipt.state);
        }

        if (prepared != null) {
            NewsCurationModels.ChargedFailure failure = outcome instanceof NewsCurationModels.ChargedFailure f ? f : null;
            NewsCurationModels.Succeeded usage = outcome instanceof NewsCurationModels.Succeeded s
                    ? s : failure == null ? null : failure.usage();
            if (usage != null) {
                if (usage.promptTokens() < 0 || usage.completionTokens() < 0 || usage.actualCostMicros() < 0
                        || (long) usage.promptTokens() + usage.completionTokens() > reservedTokens
                        || usage.actualCostMicros() > reservedCostMicros) {
                    outcome = new NewsCurationModels.UnknownOutcome("USAGE_OUTSIDE_RESERVED_ENVELOPE", usage.httpStatus(),
                            usage.providerRequestId(), usage.latencyMs());
                } else {
                    String output = failure == null && modelProtocol.validatedOutput(usage.rawJson(),
                            prepared.identity(), snapshot, properties.getMaxResponseChars()) != null ? usage.rawJson() : null;
                    String error = failure != null ? failure.errorCode() : output == null ? "INVALID_STRUCTURED_OUTPUT" : null;
                    settleStagedCharge(receipt, usage, output, error);
                    return new SettlementResult(null, null);
                }
            }
        }

        if (outcome instanceof NewsCurationModels.Succeeded s) {
            long actualTokens = Math.max(0L, (long) s.promptTokens() + s.completionTokens());
            long actualCost = Math.max(0L, s.actualCostMicros());
            settleChargedBudget(budgetDate, budgetScope, reservedTokens, reservedCostMicros, actualTokens, actualCost);

            String responseSha = NewsMaterialCanonicalizer.sha256Hex(
                    Objects.toString(s.rawJson(), "").getBytes(StandardCharsets.UTF_8));
            NewsCurationModels.VerificationResult verified = verifier.parseAndVerify(s.rawJson(), snapshot);

            jdbc.update("""
                    UPDATE t_news_model_call_receipt
                    SET state = 'SUCCEEDED',
                        promptTokens = ?,
                        completionTokens = ?,
                        actualCostMicros = ?,
                        httpStatus = ?,
                        providerRequestId = ?,
                        responseSha256 = ?,
                        errorCode = NULL,
                        latencyMs = ?,
                        completedAt = UTC_TIMESTAMP(6)
                    WHERE callId = ?
                    """, s.promptTokens(), s.completionTokens(), actualCost, s.httpStatus(),
                    s.providerRequestId(), responseSha, s.latencyMs(), request.callId());

            upsertVerifiedCandidate(snapshot, snapshotJson, snapshotSha256,
                    request.policyVersion(), request.callId(), verified);

            NewsCurationModels.ModelCurationOutput out = verified.parsedOutput();
            return new SettlementResult(new NewsCurationModels.CurationResult(
                    false,
                    verified.effectiveStatus(),
                    request.callId(),
                    out == null ? null : out.decision(),
                    snapshot.evidenceTier(),
                    out == null ? null : out.relevanceScore(),
                    out == null ? null : out.suggestedTitle(),
                    out == null ? null : out.suggestedSummary(),
                    out == null ? null : out.category(),
                    out == null ? List.of() : out.limitations(),
                    out == null ? List.of() : out.claims(),
                    verified.violations(),
                    snapshotSha256,
                    snapshotJson
            ), null);
        }

        if (outcome instanceof NewsCurationModels.DefinitiveFailure f) {
            releaseReservedBudget(budgetDate, budgetScope, reservedTokens, reservedCostMicros);
            if (f.authFailure()) {
                jdbc.update("""
                        UPDATE t_news_curation_circuit SET circuitReason = ? WHERE scope = ?
                        """, f.errorCode(), budgetScope);
            }
            jdbc.update("""
                    UPDATE t_news_model_call_receipt
                    SET state = 'FAILED_DEFINITIVE',
                        httpStatus = ?,
                        providerRequestId = ?,
                        errorCode = ?,
                        latencyMs = ?,
                        completedAt = UTC_TIMESTAMP(6)
                    WHERE callId = ?
                    """, f.httpStatus(), f.providerRequestId(), f.errorCode(), f.latencyMs(), request.callId());
            return new SettlementResult(null, prepared == null ? "MODEL_CALL_FAILED:" + f.errorCode() : null);
        }

        NewsCurationModels.UnknownOutcome unknown = (NewsCurationModels.UnknownOutcome) outcome;
        // Conservative hold: reserved budget stays held until operator or audit reconciles the paid outcome.
        jdbc.update("""
                UPDATE t_news_model_call_receipt
                SET state = 'OUTCOME_UNKNOWN',
                    httpStatus = ?,
                    providerRequestId = ?,
                    errorCode = ?,
                    latencyMs = ?
                WHERE callId = ?
                """, unknown.httpStatus(), unknown.providerRequestId(), unknown.errorCode(),
                unknown.latencyMs(), request.callId());

        if (prepared != null) {
            return new SettlementResult(null, null);
        }

        upsertNeedsReconciliationCandidate(snapshot, snapshotJson, snapshotSha256,
                request.policyVersion(), request.callId());

        return new SettlementResult(new NewsCurationModels.CurationResult(
                false,
                NewsCurationModels.CandidateStatus.NEEDS_RECONCILIATION,
                request.callId(),
                null,
                snapshot.evidenceTier(),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                snapshotSha256,
                snapshotJson
        ), null);
    }

    private void settleStagedCharge(ReceiptRow receipt, NewsCurationModels.Succeeded usage, String output, String error) {
        settleChargedBudget(receipt.budgetDate, receipt.budgetScope, receipt.reservedTokens, receipt.reservedCostMicros,
                (long) usage.promptTokens() + usage.completionTokens(), usage.actualCostMicros());
        jdbc.update("""
                UPDATE t_news_model_call_receipt
                SET state = ?, promptTokens = ?, completionTokens = ?, cachedPromptTokens = ?,
                    actualCostMicros = ?, providerRequestId = COALESCE(?, providerRequestId), httpStatus = ?,
                    latencyMs = ?, responseSha256 = ?, frozenResponseJson = ?, errorCode = ?, completedAt = UTC_TIMESTAMP(6)
                WHERE callId = ?
                """, output == null ? "FAILED_CHARGED" : "SUCCEEDED", usage.promptTokens(), usage.completionTokens(),
                usage.cachedPromptTokens(), usage.actualCostMicros(), usage.providerRequestId(), usage.httpStatus(),
                usage.latencyMs(), output == null ? null : NewsModelProtocol.sha256(output), output, error, receipt.callId);
    }

    private NewsCurationModels.CallState reclaimSingleStaleCall(String callId) {
        ReceiptRow header = jdbc.query("""
                SELECT callId, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       state, reservedTokens, reservedCostMicros, callIdentityJson
                FROM t_news_model_call_receipt
                WHERE callId = ?
                """, (rs, rowNum) -> mapReceiptRow(rs), callId).stream().findFirst().orElse(null);
        if (header == null) {
            return null;
        }
        lockBudget(header.budgetDate, header.budgetScope);
        ReceiptRow locked = lockReceipt(callId);
        Boolean expired = jdbc.queryForObject("""
                SELECT (deadlineAt <= UTC_TIMESTAMP(6))
                FROM t_news_model_call_receipt
                WHERE callId = ?
                """, Boolean.class, callId);
        if (!Boolean.TRUE.equals(expired)) {
            return null;
        }
        if (locked.state == NewsCurationModels.CallState.RESERVED) {
            abortReservedCall(locked, "PRE_IO_DEADLINE_EXCEEDED");
            return NewsCurationModels.CallState.ABORTED_PRE_IO;
        }
        if (locked.state == NewsCurationModels.CallState.DISPATCHED) {
            jdbc.update("""
                    UPDATE t_news_model_call_receipt
                    SET state = 'OUTCOME_UNKNOWN',
                        errorCode = COALESCE(errorCode, 'DISPATCH_DEADLINE_EXCEEDED')
                    WHERE callId = ?
                    """, callId);
            NewsIngestionService.VisibleRevision rev = loadVisibleRevision(locked.materialRevisionId).orElse(null);
            if (rev != null && locked.identityJson == null) {
                NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(rev);
                String snapshotJson = promptBuilder.canonicalSnapshotJson(snapshot);
                String snapshotSha = NewsMaterialCanonicalizer.sha256Hex(
                        snapshotJson.getBytes(StandardCharsets.UTF_8));
                upsertNeedsReconciliationCandidate(snapshot, snapshotJson, snapshotSha,
                        locked.policyVersion, callId);
            }
            return NewsCurationModels.CallState.OUTCOME_UNKNOWN;
        }
        return null;
    }

    private void abortReservedCall(ReceiptRow receipt, String errorCode) {
        releaseReservedBudget(receipt.budgetDate, receipt.budgetScope,
                receipt.reservedTokens, receipt.reservedCostMicros);
        jdbc.update("""
                UPDATE t_news_model_call_receipt
                SET state = 'ABORTED_PRE_IO', errorCode = ?, completedAt = UTC_TIMESTAMP(6)
                WHERE callId = ?
                """, errorCode, receipt.callId);
    }

    private void settleChargedBudget(LocalDate budgetDate,
                                     String budgetScope,
                                     long reservedTokens,
                                     long reservedCostMicros,
                                     long actualTokens,
                                     long actualCostMicros) {
        jdbc.update("""
                UPDATE t_news_curation_budget
                SET reservedCalls = GREATEST(0, reservedCalls - 1),
                    usedCalls = usedCalls + 1,
                    reservedTokens = GREATEST(0, reservedTokens - ?),
                    usedTokens = usedTokens + ?,
                    reservedCostMicros = GREATEST(0, reservedCostMicros - ?),
                    usedCostMicros = usedCostMicros + ?
                WHERE budgetDate = ? AND scope = ?
                """, reservedTokens, actualTokens, reservedCostMicros, actualCostMicros,
                budgetDate.toString(), budgetScope);
    }

    private void releaseReservedBudget(LocalDate budgetDate,
                                       String budgetScope,
                                       long reservedTokens,
                                       long reservedCostMicros) {
        jdbc.update("""
                UPDATE t_news_curation_budget
                SET reservedCalls = GREATEST(0, reservedCalls - 1),
                    reservedTokens = GREATEST(0, reservedTokens - ?),
                    reservedCostMicros = GREATEST(0, reservedCostMicros - ?)
                WHERE budgetDate = ? AND scope = ?
                """, reservedTokens, reservedCostMicros, budgetDate.toString(), budgetScope);
    }

    private void upsertVerifiedCandidate(NewsCurationModels.EvidenceSnapshot snapshot,
                                         String snapshotJson,
                                         String snapshotSha256,
                                         String policyVersion,
                                         String callId,
                                         NewsCurationModels.VerificationResult verified) {
        NewsCurationModels.ModelCurationOutput out = verified.parsedOutput();
        String decision = out == null ? null : out.decision().name();
        Integer relevance = out == null ? null : out.relevanceScore();
        String suggestedTitle = out == null ? null : out.suggestedTitle();
        String suggestedSummary = out == null ? null : out.suggestedSummary();
        String category = out == null ? null : out.category();
        String limitationsJson = out == null ? null : writeJson(out.limitations());
        String claimsJson = out == null ? null : writeJson(out.claims());
        String violationsJson = verified.violations().isEmpty() ? null : writeJson(verified.violations());

        jdbc.update("""
                INSERT INTO t_news_curation_candidate
                    (materialId, materialRevisionId, source, sourceRecordId, contentDigest,
                     policyVersion, callId, status, decision, evidenceTier, relevanceScore,
                     suggestedTitle, suggestedSummary, category, limitationsJson, claimsJson,
                     violationsJson, evidenceSnapshotJson, evidenceSnapshotSha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    callId = VALUES(callId),
                    status = VALUES(status),
                    decision = VALUES(decision),
                    evidenceTier = VALUES(evidenceTier),
                    relevanceScore = VALUES(relevanceScore),
                    suggestedTitle = VALUES(suggestedTitle),
                    suggestedSummary = VALUES(suggestedSummary),
                    category = VALUES(category),
                    limitationsJson = VALUES(limitationsJson),
                    claimsJson = VALUES(claimsJson),
                    violationsJson = VALUES(violationsJson),
                    evidenceSnapshotJson = VALUES(evidenceSnapshotJson),
                    evidenceSnapshotSha256 = VALUES(evidenceSnapshotSha256)
                """,
                snapshot.materialId(), snapshot.materialRevisionId(), snapshot.source(),
                snapshot.sourceRecordId(), snapshot.contentDigest(), policyVersion, callId,
                verified.effectiveStatus().name(), decision, snapshot.evidenceTier().name(),
                relevance, suggestedTitle, suggestedSummary, category,
                limitationsJson, claimsJson, violationsJson, snapshotJson, snapshotSha256);
    }

    private void upsertNeedsReconciliationCandidate(NewsCurationModels.EvidenceSnapshot snapshot,
                                                    String snapshotJson,
                                                    String snapshotSha256,
                                                    String policyVersion,
                                                    String callId) {
        jdbc.update("""
                INSERT INTO t_news_curation_candidate
                    (materialId, materialRevisionId, source, sourceRecordId, contentDigest,
                     policyVersion, callId, status, evidenceTier, evidenceSnapshotJson, evidenceSnapshotSha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NEEDS_RECONCILIATION', ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    callId = VALUES(callId),
                    status = 'NEEDS_RECONCILIATION',
                    evidenceTier = VALUES(evidenceTier),
                    evidenceSnapshotJson = VALUES(evidenceSnapshotJson),
                    evidenceSnapshotSha256 = VALUES(evidenceSnapshotSha256)
                """,
                snapshot.materialId(), snapshot.materialRevisionId(), snapshot.source(),
                snapshot.sourceRecordId(), snapshot.contentDigest(), policyVersion, callId,
                snapshot.evidenceTier().name(), snapshotJson, snapshotSha256);
    }

    private void deletePendingReconcileCandidate(long materialRevisionId, String policyVersion, String callId) {
        jdbc.update("""
                DELETE FROM t_news_curation_candidate
                WHERE materialRevisionId = ?
                  AND policyVersion = ?
                  AND callId = ?
                  AND status = 'NEEDS_RECONCILIATION'
                """, materialRevisionId, policyVersion, callId);
    }

    private void ensureBudgetRowExists(LocalDate budgetDate, String scope) {
        // Persist a stricter deployment ceiling even if the next reservation is rejected and rolled back.
        jdbc.update("""
                INSERT INTO t_news_curation_budget_guard (budgetDate, maxCalls, maxTokens, maxCostMicros)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE maxCalls = LEAST(maxCalls, VALUES(maxCalls)),
                    maxTokens = LEAST(maxTokens, VALUES(maxTokens)),
                    maxCostMicros = LEAST(maxCostMicros, VALUES(maxCostMicros))
                """, budgetDate, Math.max(0, properties.getDailyMaxCalls()), Math.max(0L, properties.getDailyMaxTokens()),
                Math.max(0L, properties.getDailyMaxCostMicros()));
        jdbc.update("""
                INSERT IGNORE INTO t_news_curation_budget
                    (budgetDate, scope, maxCalls, maxTokens, maxCostMicros)
                VALUES (?, ?, ?, ?, ?)
                """,
                budgetDate.toString(),
                scope,
                Math.max(1, properties.getDailyMaxCalls()),
                Math.max(1L, properties.getDailyMaxTokens()),
                Math.max(0L, properties.getDailyMaxCostMicros()));
    }

    private void ensureCircuitRowExists(String scope) {
        jdbc.update("INSERT IGNORE INTO t_news_curation_circuit (scope) VALUES (?)", scope);
    }

    /**
     * Seeds the singleton whole-experiment ceiling. A ceiling may only ever be tightened: {@code 0} means
     * "unset" on either side, and when both sides carry a value the smaller one wins. This runs outside the
     * reserve transaction on purpose, so a reservation that is later rejected still persists the stricter
     * ceiling it tried to apply.
     */
    private void ensureLifetimeBudgetRowExists() {
        jdbc.update("""
                INSERT INTO t_news_curation_lifetime_budget (id, maxCalls, maxTokens, maxCostMicros, note)
                VALUES (1, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    maxCalls = CASE WHEN maxCalls = 0 THEN VALUES(maxCalls)
                                    WHEN VALUES(maxCalls) = 0 THEN maxCalls
                                    ELSE LEAST(maxCalls, VALUES(maxCalls)) END,
                    maxTokens = CASE WHEN maxTokens = 0 THEN VALUES(maxTokens)
                                     WHEN VALUES(maxTokens) = 0 THEN maxTokens
                                     ELSE LEAST(maxTokens, VALUES(maxTokens)) END,
                    maxCostMicros = CASE WHEN maxCostMicros = 0 THEN VALUES(maxCostMicros)
                                         WHEN VALUES(maxCostMicros) = 0 THEN maxCostMicros
                                         ELSE LEAST(maxCostMicros, VALUES(maxCostMicros)) END
                """,
                Math.max(0, properties.getLifetimeMaxCalls()),
                Math.max(0L, properties.getLifetimeMaxTokens()),
                Math.max(0L, properties.getLifetimeMaxCostMicros()),
                "seeded from eye-diagnosis.news-curation.lifetime-* configuration");
    }

    /** Whole-experiment ceiling as recorded, or {@code null} before the first reservation seeds it. */
    public NewsCurationModels.LifetimeBudget lifetimeBudget() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT maxCalls, maxTokens, maxCostMicros, note
                FROM t_news_curation_lifetime_budget WHERE id = 1
                """);
        return rows.isEmpty() ? null : lifetimeSnapshot(rows.getFirst());
    }

    /** Locks the lifetime row for the rest of the reservation transaction. */
    private NewsCurationModels.LifetimeBudget lockLifetimeBudget() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT maxCalls, maxTokens, maxCostMicros, note
                FROM t_news_curation_lifetime_budget WHERE id = 1 FOR UPDATE
                """);
        return lifetimeSnapshot(row);
    }

    /**
     * Limits plus real usage. Usage is derived from every receipt ever written: settled receipts count their
     * recorded tokens and cost, receipts still in flight count their full reservation (fail-safe), so a
     * crash mid-call can only over-count against the ceiling, never under-count.
     */
    private NewsCurationModels.LifetimeBudget lifetimeSnapshot(Map<String, Object> row) {
        Map<String, Object> used = jdbc.queryForMap("""
                SELECT COUNT(*) AS usedCalls,
                       COALESCE(SUM(CASE WHEN state IN ('RESERVED', 'DISPATCHED', 'OUTCOME_UNKNOWN')
                                         THEN reservedTokens
                                         ELSE COALESCE(promptTokens, 0) + COALESCE(completionTokens, 0) END), 0)
                           AS usedTokens,
                       COALESCE(SUM(CASE WHEN state IN ('RESERVED', 'DISPATCHED', 'OUTCOME_UNKNOWN')
                                         THEN reservedCostMicros
                                         ELSE COALESCE(actualCostMicros, 0) END), 0) AS usedCostMicros
                FROM t_news_model_call_receipt
                """);
        return new NewsCurationModels.LifetimeBudget(
                ((Number) row.get("maxCalls")).intValue(),
                ((Number) row.get("maxTokens")).longValue(),
                ((Number) row.get("maxCostMicros")).longValue(),
                ((Number) used.get("usedCalls")).intValue(),
                ((Number) used.get("usedTokens")).longValue(),
                ((Number) used.get("usedCostMicros")).longValue(),
                Objects.toString(row.get("note"), null));
    }

    private String lockCircuit(String scope) {
        return jdbc.queryForObject("""
                SELECT circuitReason FROM t_news_curation_circuit WHERE scope = ? FOR UPDATE
                """, String.class, scope);
    }

    private NewsCurationModels.BudgetSnapshot lockBudget(LocalDate budgetDate, String scope) {
        jdbc.queryForObject("SELECT maxCalls FROM t_news_curation_budget_guard WHERE budgetDate = ? FOR UPDATE",
                Integer.class, budgetDate);
        return jdbc.queryForObject("""
                SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, scope,
                       maxCalls, usedCalls, reservedCalls,
                       maxTokens, usedTokens, reservedTokens,
                       maxCostMicros, usedCostMicros, reservedCostMicros,
                       0 AS circuitBroken, NULL AS circuitReason
                FROM t_news_curation_budget
                WHERE budgetDate = ? AND scope = ?
                FOR UPDATE
                """, (rs, rowNum) -> mapBudgetRow(rs), budgetDate.toString(), scope);
    }

    private boolean withinGlobalBudget(LocalDate date, long tokens, long cost) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT (1 + COALESCE(SUM(b.usedCalls + b.reservedCalls), 0) <= g.maxCalls
                    AND ? + COALESCE(SUM(b.usedTokens + b.reservedTokens), 0) <= g.maxTokens
                    AND ? + COALESCE(SUM(b.usedCostMicros + b.reservedCostMicros), 0) <= g.maxCostMicros)
                FROM t_news_curation_budget_guard g LEFT JOIN t_news_curation_budget b ON b.budgetDate = g.budgetDate
                WHERE g.budgetDate = ? GROUP BY g.budgetDate, g.maxCalls, g.maxTokens, g.maxCostMicros
                """, Boolean.class, tokens, cost, date));
    }

    private ReceiptRow lockReceipt(String callId) {
        List<ReceiptRow> rows = jdbc.query("""
                SELECT callId, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       state, reservedTokens, reservedCostMicros, callIdentityJson
                FROM t_news_model_call_receipt
                WHERE callId = ?
                FOR UPDATE
                """, (rs, rowNum) -> mapReceiptRow(rs), callId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN_CALL_ID");
        }
        return rows.getFirst();
    }

    private Optional<NewsIngestionService.VisibleRevision> loadVisibleRevision(long revisionId) {
        List<NewsIngestionService.VisibleRevision> rows = jdbc.query("""
                SELECT id, materialId, source, sourceRecordId, contentDigest, url, title,
                       language, publishedOn,
                       DATE_FORMAT(updatedOn, '%Y-%m-%d') AS updatedOn,
                       pmid, doi, abstractText, abstractLicense, trialJson, canonicalJson,
                       firstTaskId, firstGeneration,
                       DATE_FORMAT(visibleAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS visibleAtUtc
                FROM t_news_material_revision
                WHERE id = ?
                """, (rs, rowNum) -> new NewsIngestionService.VisibleRevision(
                rs.getLong("id"),
                rs.getLong("materialId"),
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                rs.getString("contentDigest"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("language"),
                rs.getString("publishedOn"),
                parseOptionalDate(rs.getString("updatedOn")),
                rs.getString("pmid"),
                rs.getString("doi"),
                rs.getString("abstractText"),
                rs.getString("abstractLicense"),
                rs.getString("trialJson"),
                rs.getString("canonicalJson"),
                rs.getString("firstTaskId"),
                rs.getLong("firstGeneration"),
                parseUtcInstant(rs.getString("visibleAtUtc"))
        ), revisionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private LocalDate dbUtcDate() {
        String dateText = jdbc.queryForObject("SELECT DATE_FORMAT(UTC_DATE(), '%Y-%m-%d')", String.class);
        return LocalDate.parse(Objects.requireNonNull(dateText));
    }

    private NewsCurationModels.CurationResult mapCandidateRow(ResultSet rs, boolean replayed) throws SQLException {
        String decisionStr = rs.getString("decision");
        String tierStr = rs.getString("evidenceTier");
        return new NewsCurationModels.CurationResult(
                replayed,
                NewsCurationModels.CandidateStatus.valueOf(rs.getString("status")),
                rs.getString("callId"),
                decisionStr == null ? null : NewsCurationModels.Decision.valueOf(decisionStr),
                tierStr == null ? null : NewsCurationModels.EvidenceTier.valueOf(tierStr),
                (Integer) rs.getObject("relevanceScore"),
                rs.getString("suggestedTitle"),
                rs.getString("suggestedSummary"),
                rs.getString("category"),
                readJsonList(rs.getString("limitationsJson"), STRING_LIST_TYPE),
                readJsonList(rs.getString("claimsJson"), CLAIM_LIST_TYPE),
                readJsonList(rs.getString("violationsJson"), VIOLATION_LIST_TYPE),
                rs.getString("evidenceSnapshotSha256"),
                rs.getString("evidenceSnapshotJson")
        );
    }

    private static NewsCurationModels.BudgetSnapshot mapBudgetRow(ResultSet rs) throws SQLException {
        return new NewsCurationModels.BudgetSnapshot(
                LocalDate.parse(rs.getString("budgetDate")),
                rs.getString("scope"),
                rs.getInt("maxCalls"),
                rs.getInt("usedCalls"),
                rs.getInt("reservedCalls"),
                rs.getLong("maxTokens"),
                rs.getLong("usedTokens"),
                rs.getLong("reservedTokens"),
                rs.getLong("maxCostMicros"),
                rs.getLong("usedCostMicros"),
                rs.getLong("reservedCostMicros"),
                rs.getInt("circuitBroken") == 1,
                rs.getString("circuitReason")
        );
    }

    private static ReceiptRow mapReceiptRow(ResultSet rs) throws SQLException {
        return new ReceiptRow(
                rs.getString("callId"),
                rs.getLong("materialRevisionId"),
                rs.getString("policyVersion"),
                rs.getInt("attemptNo"),
                LocalDate.parse(rs.getString("budgetDate")),
                rs.getString("budgetScope"),
                NewsCurationModels.CallState.valueOf(rs.getString("state")),
                rs.getLong("reservedTokens"),
                rs.getLong("reservedCostMicros"),
                rs.getString("callIdentityJson")
        );
    }

    private static NewsCurationModels.CallReceiptSnapshot mapReceiptSnapshot(ResultSet rs) throws SQLException {
        return new NewsCurationModels.CallReceiptSnapshot(
                rs.getString("callId"),
                rs.getString("idempotencyKey"),
                rs.getLong("materialRevisionId"),
                rs.getString("policyVersion"),
                rs.getInt("attemptNo"),
                LocalDate.parse(rs.getString("budgetDate")),
                rs.getString("budgetScope"),
                rs.getString("provider"),
                rs.getString("modelName"),
                rs.getString("promptSha256"),
                NewsCurationModels.CallState.valueOf(rs.getString("state")),
                rs.getLong("reservedTokens"),
                rs.getLong("reservedCostMicros"),
                (Integer) rs.getObject("promptTokens"),
                (Integer) rs.getObject("completionTokens"),
                (Long) rs.getObject("actualCostMicros"),
                (Integer) rs.getObject("httpStatus"),
                rs.getString("providerRequestId"),
                rs.getString("responseSha256"),
                rs.getString("errorCode"),
                (Integer) rs.getObject("latencyMs"),
                rs.getString("reconciledBy"),
                rs.getString("reconcileNote"),
                parseUtcInstant(rs.getString("reconciledAtUtc")),
                parseUtcInstant(rs.getString("reservedAtUtc")),
                parseUtcInstant(rs.getString("dispatchedAtUtc")),
                parseUtcInstant(rs.getString("deadlineAtUtc")),
                parseUtcInstant(rs.getString("completedAtUtc")),
                rs.getString("logicalCallSha256"),
                (Integer) rs.getObject("cachedPromptTokens")
        );
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private <T> List<T> readJsonList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored curation JSON column", e);
        }
    }

    private static String normalizeScope(String scope) {
        return (scope == null || scope.isBlank()) ? DEFAULT_SCOPE : scope.trim();
    }

    private static LocalDate parseOptionalDate(String text) {
        return text == null ? null : LocalDate.parse(text);
    }

    private static Instant parseUtcInstant(String text) {
        return text == null ? null : Instant.from(DB_UTC_FORMAT.parse(text));
    }

    private static final String RECEIPT_SELECT = """
            SELECT callId, idempotencyKey, materialRevisionId, policyVersion, attemptNo,
                   DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                   provider, modelName, promptSha256, state, reservedTokens, reservedCostMicros,
                   promptTokens, completionTokens, actualCostMicros, httpStatus, providerRequestId,
                   responseSha256, errorCode, latencyMs, reconciledBy, reconcileNote, logicalCallSha256, cachedPromptTokens,
                   DATE_FORMAT(reconciledAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS reconciledAtUtc,
                   DATE_FORMAT(reservedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS reservedAtUtc,
                   DATE_FORMAT(dispatchedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS dispatchedAtUtc,
                   DATE_FORMAT(deadlineAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS deadlineAtUtc,
                   DATE_FORMAT(completedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS completedAtUtc
            FROM t_news_model_call_receipt
            """;

    private record ReservationOutcome(
            String replayedCallId,
            NewsCurationModels.CurationResult replayedResult,
            NewsCurationModels.ModelCallRequest request,
            long reservedTokens,
            long reservedCostMicros
    ) {
    }

    private record Execution(String callId, NewsCurationModels.CurationResult candidate, boolean replayed) {
    }

    private record ReceiptRow(
            String callId,
            long materialRevisionId,
            String policyVersion,
            int attemptNo,
            LocalDate budgetDate,
            String budgetScope,
            NewsCurationModels.CallState state,
            long reservedTokens,
            long reservedCostMicros,
            String identityJson
    ) {
    }

    private record StaleCandidate(
            String callId,
            NewsCurationModels.CallState state,
            LocalDate budgetDate,
            String budgetScope
    ) {
    }

    private record SettlementResult(NewsCurationModels.CurationResult result, String definitiveFailureMessage) {
    }

    private record BudgetRef(LocalDate budgetDate, String scope) {
    }

    /** Result of converting one frozen production call into a review candidate. */
    public record MaterializedCandidate(long candidateId, String status, String callId, boolean created,
                                        String evidenceVerificationSha256, Long supersededCandidateId) {
    }

    /**
     * WP-08B.2：把一条**生产命名空间**的联合精选（P05）冻结结果转成待人工审核的候选。
     *
     * <p>只做转换，不发明判断：结果必须通过 {@link NewsModelProtocol#validatedOutput} 的同一套契约校验；
     * 候选一律落在 {@code CURATED_NEEDS_REVIEW}（有内容）或 {@code CURATED_REJECTED}（无内容），
     * 永不自动 {@code CURATED_ACCEPTED}。若该调用冻结的证据核验此后被撤回或变更，转换直接拒绝，
     * 要求重新核验——已付费的冻结结果不会因为账本变动而自动继续可信。实验命名空间的回执一律拒绝。</p>
     *
     * <p>幂等：同一调用重复转换返回同一候选（{@code created=false}）；同一资料修订与策略版本已被
     * 另一条调用占用时，默认拒绝（{@code CANDIDATE_ALREADY_MATERIALIZED}）——绝不覆盖。</p>
     */
    public MaterializedCandidate materializeCandidate(String callId, String actor) {
        return materializeCandidate(callId, actor, null, null);
    }

    /**
     * 带显式替代的转换。证据核验被撤回、重新核验并重新付费调用后，旧候选按规则不能再建稿或发布；
     * 新的成功结果只能在**点名**旧候选并给出理由时接管：
     *
     * <ul>
     *   <li>旧候选行的内容列一个都不改，只写 {@code status=SUPERSEDED} 与三个替代指针；</li>
     *   <li>新结果另起一行，两边各写一条 {@code t_news_curation_candidate_event}；</li>
     *   <li>旧候选已关联未删除的资讯行时拒绝（{@code CANDIDATE_ALREADY_DRAFTED}），先人工处置草稿；</li>
     *   <li>同一资料修订上出现不同 {@code policyVersion} 的候选一律拒绝（{@code CANDIDATE_POLICY_CONFLICT}），
     *       改策略版本绕不过候选冲突。</li>
     * </ul>
     */
    public MaterializedCandidate materializeCandidate(String callId, String actor, Long supersedesCandidateId,
                                                      String supersedeReason) {
        if (!NewsModelProfile.identifier(callId, 64) || actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Invalid materialize request");
        }
        String reviewer = actor.trim();
        if (reviewer.length() > 128) {
            throw new IllegalArgumentException("Invalid materialize request");
        }
        String reason = supersedeReason == null ? null : supersedeReason.trim();
        boolean supersede = supersedesCandidateId != null;
        if (supersede && (reason == null || reason.isEmpty())) {
            throw new IllegalArgumentException("SUPERSEDE_REASON_REQUIRED");
        }
        if (!supersede && reason != null && !reason.isEmpty()) {
            throw new IllegalArgumentException("SUPERSEDE_REASON_WITHOUT_TARGET");
        }
        if (reason != null && (reason.length() > 255 || reason.indexOf('<') >= 0)) {
            throw new IllegalArgumentException("SUPERSEDE_REASON_INVALID");
        }
        return transactions.execute(status -> materializeInTransaction(callId, reviewer,
                supersedesCandidateId, supersede ? reason : null));
    }

    private MaterializedCandidate materializeInTransaction(String callId, String reviewer,
                                                           Long supersedesCandidateId, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT state, materialRevisionId, policyVersion, callIdentityJson, frozenResponseJson,
                       logicalCallSha256
                FROM t_news_model_call_receipt
                WHERE callId = ?
                FOR UPDATE
                """, callId);
        if (rows.size() != 1) {
            throw new IllegalStateException("MODEL_CALL_NOT_FOUND");
        }
        Map<String, Object> row = rows.getFirst();
        if (!"SUCCEEDED".equals(row.get("state")) || row.get("logicalCallSha256") == null
                || row.get("frozenResponseJson") == null) {
            throw new IllegalStateException("MODEL_CALL_NOT_MATERIALIZABLE");
        }
        long revisionId = ((Number) row.get("materialRevisionId")).longValue();
        String policy = Objects.toString(row.get("policyVersion"));
        NewsModelProtocol.Identity identity;
        try {
            identity = mapper.readValue(Objects.toString(row.get("callIdentityJson")),
                    NewsModelProtocol.Identity.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("MODEL_CALL_IDENTITY_INVALID", ex);
        }
        if (identity == null || !"production".equals(identity.namespace())
                || identity.prompt() != NewsModelProtocol.Prompt.P05
                || identity.stage() != NewsModelProtocol.Stage.JOINT) {
            throw new IllegalStateException("MODEL_CALL_NOT_PRODUCTION_JOINT");
        }
        if (identity.materialRevisionId() != revisionId) {
            throw new IllegalStateException("MODEL_CALL_IDENTITY_INVALID");
        }
        NewsEvidenceLedger.Overlay overlay = ledger.overlay(revisionId);
        if (!overlay.sha256().equals(identity.evidenceOverlaySha256())) {
            throw new IllegalStateException("EVIDENCE_VERIFICATION_STALE");
        }
        NewsIngestionService.VisibleRevision revision = requireVisibleRevision(revisionId);
        NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(revision);
        JsonNode validated = modelProtocol.validatedOutput(Objects.toString(row.get("frozenResponseJson")),
                identity, snapshot, properties.getMaxResponseChars());
        if (validated == null) {
            throw new IllegalStateException("FROZEN_OUTPUT_INVALID");
        }
        JsonNode content = validated.path("content");
        boolean hasContent = content.isObject();
        JsonNode sections = content.path("sections");
        String contentType = hasContent ? content.path("contentType").asText(null) : null;
        String decision = hasContent ? NewsCurationModels.Decision.NEEDS_REVIEW.name()
                : NewsCurationModels.Decision.REJECT.name();
        String summary = hasContent ? switch (String.valueOf(contentType)) {
            case "RESEARCH_INSIGHT" -> sections.path("findings").asText(null);
            case "RESEARCH_RADAR" -> sections.path("reasonToWatch").asText(null);
            default -> sections.path("status").asText(null);
        } : null;
        List<String> limitations = hasContent && !sections.path("limitations").asText("").isBlank()
                ? List.of(sections.path("limitations").asText()) : List.of();
        String claimsJson = hasContent ? writeJson(claims(content.path("claims"))) : null;
        List<NewsCurationModels.EvidenceViolation> violations = safetyViolations(content);
        String candidateStatus = !violations.isEmpty()
                ? NewsCurationModels.CandidateStatus.REJECTED_EVIDENCE_VIOLATION.name()
                : hasContent ? NewsCurationModels.CandidateStatus.CURATED_NEEDS_REVIEW.name()
                : NewsCurationModels.CandidateStatus.CURATED_REJECTED.name();

        // Every candidate row of this revision is locked here, across all policy versions, so concurrent
        // materializations serialize and a changed policyVersion cannot sidestep the conflict check.
        List<Map<String, Object>> siblings = jdbc.queryForList("""
                SELECT id, policyVersion, callId, status, evidenceVerificationSha256, supersededAt
                FROM t_news_curation_candidate
                WHERE materialRevisionId = ?
                ORDER BY id ASC
                FOR UPDATE
                """, revisionId);
        Map<String, Object> active = null;
        for (Map<String, Object> sibling : siblings) {
            if (!policy.equals(Objects.toString(sibling.get("policyVersion"), ""))) {
                throw new IllegalStateException("CANDIDATE_POLICY_CONFLICT");
            }
            if (sibling.get("supersededAt") == null) {
                active = sibling;
            }
        }
        String supersededFromStatus = null;
        if (active != null) {
            if (callId.equals(Objects.toString(active.get("callId"), ""))) {
                return new MaterializedCandidate(((Number) active.get("id")).longValue(),
                        Objects.toString(active.get("status")), callId, false,
                        Objects.toString(active.get("evidenceVerificationSha256"), null), null);
            }
            if (supersedesCandidateId == null) {
                throw new IllegalStateException("CANDIDATE_ALREADY_MATERIALIZED");
            }
            long activeId = ((Number) active.get("id")).longValue();
            if (activeId != supersedesCandidateId) {
                throw new IllegalStateException("CANDIDATE_SUPERSEDE_TARGET_MISMATCH");
            }
            supersededFromStatus = Objects.toString(active.get("status"), "");
            if (!supersedable(supersededFromStatus)) {
                throw new IllegalStateException("CANDIDATE_NOT_SUPERSEDABLE:" + supersededFromStatus);
            }
            Integer bound = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_news WHERE curationCandidateId = ? AND isDelete = 0",
                    Integer.class, supersedesCandidateId);
            if (bound != null && bound > 0) {
                throw new IllegalStateException("CANDIDATE_ALREADY_DRAFTED");
            }
            // Retire the old row first: this clears its activeKey so the new active row can be inserted.
            // The forward pointer is filled in right after the insert; both writes share this transaction, so
            // no reader ever sees a superseded row without its successor.
            int retired = jdbc.update("""
                    UPDATE t_news_curation_candidate
                    SET status = 'SUPERSEDED', supersededAt = UTC_TIMESTAMP(6), supersedeReason = ?
                    WHERE id = ? AND supersededAt IS NULL AND status = ?
                    """, reason, supersedesCandidateId, supersededFromStatus);
            if (retired != 1) {
                throw new IllegalStateException("CANDIDATE_SUPERSEDE_FAILED");
            }
        } else if (supersedesCandidateId != null) {
            throw new IllegalStateException("CANDIDATE_SUPERSEDE_TARGET_MISSING");
        }

        String snapshotJson = promptBuilder.canonicalSnapshotJson(snapshot);
        String snapshotSha256 = NewsMaterialCanonicalizer.sha256Hex(
                snapshotJson.getBytes(StandardCharsets.UTF_8));
        String overlayJson = writeJson(overlay.attributes());
        jdbc.update("""
                        INSERT INTO t_news_curation_candidate
                            (materialId, materialRevisionId, source, sourceRecordId, contentDigest,
                             policyVersion, callId, status, decision, evidenceTier, relevanceScore,
                             suggestedTitle, suggestedSummary, category, contentType, limitationsJson, claimsJson,
                             violationsJson, evidenceSnapshotJson, evidenceSnapshotSha256,
                             evidenceVerificationJson, evidenceVerificationSha256, materializedAt, materializedBy,
                             draftTemplateVersion, structuredContentJson, researchValueScore)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?,?,?,?)
                        """,
                snapshot.materialId(), snapshot.materialRevisionId(), snapshot.source(),
                snapshot.sourceRecordId(), snapshot.contentDigest(), policy, callId, candidateStatus,
                decision, snapshot.evidenceTier().name(), validated.path("relevanceScore").isInt()
                        ? validated.path("relevanceScore").intValue() : null,
                null, summary, null, contentType, writeJson(limitations), claimsJson,
                violations.isEmpty() ? null : writeJson(violations), snapshotJson, snapshotSha256,
                overlayJson, overlay.sha256(), reviewer,
                NewsEditorialDraft.TEMPLATE_VERSION, hasContent ? writeJson(content) : null,
                validated.path("researchValueScore").isInt()
                        ? validated.path("researchValueScore").intValue() : null);
        long newId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (supersededFromStatus != null) {
            int linked = jdbc.update("""
                    UPDATE t_news_curation_candidate SET supersededByCandidateId = ?
                    WHERE id = ? AND supersededByCandidateId IS NULL
                    """, newId, supersedesCandidateId);
            if (linked != 1) {
                throw new IllegalStateException("CANDIDATE_SUPERSEDE_FAILED");
            }
            recordCandidateEvent(supersedesCandidateId, "superseded", supersededFromStatus,
                    NewsCurationModels.CandidateStatus.SUPERSEDED.name(), newId, callId, reviewer, reason);
        }
        recordCandidateEvent(newId, "materialized", null, candidateStatus, supersedesCandidateId, callId, reviewer,
                supersededFromStatus == null ? null : "supersedes candidate " + supersedesCandidateId);
        return new MaterializedCandidate(newId, candidateStatus, callId, true, overlay.sha256(),
                supersedesCandidateId);
    }

    /** Statuses a named replacement may retire. A candidate awaiting reconciliation must be reconciled first. */
    private static boolean supersedable(String status) {
        return switch (status) {
            case "CURATED_ACCEPTED", "CURATED_NEEDS_REVIEW", "CURATED_REJECTED",
                 "REJECTED_EVIDENCE_VIOLATION" -> true;
            default -> false;
        };
    }

    private void recordCandidateEvent(long candidateId, String eventType, String fromStatus, String toStatus,
                                      Long relatedCandidateId, String relatedCallId, String actor, String note) {
        jdbc.update("""
                INSERT INTO t_news_curation_candidate_event
                    (candidateId, eventType, fromStatus, toStatus, relatedCandidateId, relatedCallId, actor, note)
                VALUES (?,?,?,?,?,?,?,?)
                """, candidateId, eventType, fromStatus, toStatus, relatedCandidateId, relatedCallId, actor, note);
    }

    private static List<NewsCurationModels.CurationClaim> claims(JsonNode claims) {
        List<NewsCurationModels.CurationClaim> out = new java.util.ArrayList<>();
        int index = 0;
        for (JsonNode claim : claims) {
            index++;
            out.add(new NewsCurationModels.CurationClaim("c" + index, claim.path("evidenceId").asText(null),
                    claim.path("statement").asText(null), claim.path("quote").asText(null)));
        }
        return List.copyOf(out);
    }

    /** Generated text must clear the same clinical/injection boundaries as the publish path. */
    private static List<NewsCurationModels.EvidenceViolation> safetyViolations(JsonNode content) {
        List<NewsCurationModels.EvidenceViolation> violations = new java.util.ArrayList<>();
        if (!content.isObject()) {
            return List.of();
        }
        for (String text : texts(content)) {
            NewsEvidenceVerifier.findProhibitedClinicalMarker(text).ifPresent(marker ->
                    violations.add(new NewsCurationModels.EvidenceViolation(
                            "PROHIBITED_CLINICAL_CLAIM", "content", marker)));
            NewsEvidenceVerifier.findPromptInjectionMarker(text).ifPresent(marker ->
                    violations.add(new NewsCurationModels.EvidenceViolation("PROMPT_INJECTION", "content", marker)));
            NewsEvidenceVerifier.findUnsupportedTrialOutcome(text).ifPresent(marker ->
                    violations.add(new NewsCurationModels.EvidenceViolation(
                            "UNSUPPORTED_TRIAL_OUTCOME", "content", marker)));
        }
        return List.copyOf(violations);
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new java.util.ArrayList<>();
        collectTexts(node, values);
        return values;
    }

    private static void collectTexts(JsonNode node, List<String> values) {
        if (node.isTextual()) {
            values.add(node.textValue());
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectTexts(child, values));
        }
    }

    /** Active and revoked verifications for one material revision, newest last. */
    public List<NewsEvidenceLedger.Verification> listEvidenceVerifications(long materialRevisionId) {
        return ledger.list(materialRevisionId);
    }

    /**
     * Records one reviewer verification after validating it against the frozen snapshot, so a ledger row
     * can never describe an attribute the material does not actually support.
     */
    public NewsEvidenceLedger.Verification verifyEvidence(long materialRevisionId, NewsEvidenceLedger.Request request,
                                                          String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("Invalid evidence reviewer");
        }
        NewsIngestionService.VisibleRevision revision = requireVisibleRevision(materialRevisionId);
        NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(revision);
        NewsEvidenceLedger.Request validated = NewsEvidenceLedger.validate(request, snapshot);
        return ledger.append(materialRevisionId, validated, reviewer.trim());
    }

    /** Revokes one active verification; the row and its history are retained. */
    public NewsEvidenceLedger.Verification revokeEvidence(long verificationId, String reviewer, String note) {
        if (verificationId <= 0 || reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("Invalid evidence revocation");
        }
        return ledger.revoke(verificationId, reviewer.trim(), note);
    }

    /** Current active overlay digest; used by admin views to show what a candidate is bound to. */
    public String activeEvidenceOverlaySha256(long materialRevisionId) {
        return ledger.overlay(materialRevisionId).sha256();
    }
}
