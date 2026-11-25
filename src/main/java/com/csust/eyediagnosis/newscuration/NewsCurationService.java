package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
        if (!properties.isEnabled()) {
            throw new IllegalStateException("NEWS_CURATION_DISABLED");
        }
        String effectivePolicy = (policyVersion == null || policyVersion.isBlank())
                ? properties.getPolicyVersion() : policyVersion.trim();
        LocalDate effectiveDate = budgetDate != null ? budgetDate : dbUtcDate();
        String effectiveScope = normalizeScope(budgetScope);

        NewsIngestionService.VisibleRevision revision = loadVisibleRevision(materialRevisionId)
                .orElseThrow(() -> new IllegalArgumentException("REVISION_NOT_VISIBLE"));

        NewsCurationModels.EvidenceSnapshot snapshot = promptBuilder.buildSnapshot(revision);
        String snapshotJson = promptBuilder.canonicalSnapshotJson(snapshot);
        String snapshotSha256 = NewsMaterialCanonicalizer.sha256Hex(snapshotJson.getBytes(StandardCharsets.UTF_8));
        String pendingCallId = UUID.randomUUID().toString();

        // Ensure budget row exists outside reserve transaction so SELECT ... FOR UPDATE hits a real row without gap locks.
        ensureBudgetRowExists(effectiveDate, effectiveScope);

        ReservationOutcome reserved = transactions.execute(status -> reserveAttempt(
                revision, snapshot, snapshotJson, snapshotSha256,
                effectivePolicy, effectiveDate, effectiveScope, pendingCallId));

        if (reserved.replayedResult != null) {
            return reserved.replayedResult;
        }

        boolean dispatched = Boolean.TRUE.equals(transactions.execute(status -> jdbc.update("""
                    UPDATE t_news_model_call_receipt
                    SET state = 'DISPATCHED',
                        dispatchedAt = UTC_TIMESTAMP(6)
                    WHERE callId = ? AND state = 'RESERVED' AND deadlineAt > UTC_TIMESTAMP(6)
                    """, reserved.request.callId()) == 1));
        if (!dispatched) {
            transactions.execute(status -> reclaimSingleStaleCall(reserved.request.callId()));
            throw new IllegalStateException("FAILED_TO_MARK_DISPATCHED");
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
                effectiveDate, effectiveScope, finalOutcome));
        if (settled == null) {
            throw new IllegalStateException("MODEL_CALL_SETTLEMENT_MISSING");
        }
        if (settled.definitiveFailureMessage() != null) {
            throw new IllegalStateException(settled.definitiveFailureMessage());
        }
        return settled.result();
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
                    NewsCurationModels.VerificationResult verified = verifier.parseAndVerify(recoveredRawJson, snapshot);
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
                                errorCode = NULL,
                                reconciledBy = ?,
                                reconcileNote = ?,
                                reconciledAt = UTC_TIMESTAMP(6),
                                completedAt = UTC_TIMESTAMP(6)
                            WHERE callId = ?
                            """, promptTokens, completionTokens, actualCostMicros, providerRequestId,
                            responseSha, reconciledBy.trim(), note, callId);

                    upsertVerifiedCandidate(snapshot, snapshotJson, snapshotSha256,
                            receipt.policyVersion, callId, verified);
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
                                              String callId) {
        // Canonical lock order: circuit (when needed) -> budget -> receipts -> candidate
        String circuitReason = lockCircuit(budgetScope);
        NewsCurationModels.BudgetSnapshot budget = lockBudget(budgetDate, budgetScope);

        List<ReceiptRow> existingReceipts = jdbc.query("""
                SELECT callId, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       state, reservedTokens, reservedCostMicros
                FROM t_news_model_call_receipt
                WHERE materialRevisionId = ? AND policyVersion = ?
                ORDER BY attemptNo ASC
                FOR UPDATE
                """, (rs, rowNum) -> mapReceiptRow(rs), revision.revisionId(), policyVersion);

        Optional<NewsCurationModels.CurationResult> existingCandidate = jdbc.query("""
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
            return new ReservationOutcome(candidate, null, 0L, 0L);
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

        if (circuitReason != null) {
            throw new IllegalStateException("CURATION_CIRCUIT_BROKEN:" + circuitReason);
        }

        long estTokens = Math.max(1L, properties.getEstimatedTokensPerCall());
        long estCost = Math.max(0L, properties.getEstimatedCostMicrosPerCall());

        if (budget.usedCalls() + budget.reservedCalls() + 1 > budget.maxCalls()
                || budget.usedTokens() + budget.reservedTokens() + estTokens > budget.maxTokens()
                || budget.usedCostMicros() + budget.reservedCostMicros() + estCost > budget.maxCostMicros()) {
            throw new IllegalStateException("BUDGET_EXHAUSTED");
        }

        int nextAttempt = maxAttempt + 1;
        String idempotencyKey = revision.revisionId() + ":" + policyVersion + ":" + nextAttempt;
        NewsCurationModels.ModelCallRequest request = promptBuilder.buildRequest(
                callId, idempotencyKey, policyVersion, snapshot);

        jdbc.update("""
                UPDATE t_news_curation_budget
                SET reservedCalls = reservedCalls + 1,
                    reservedTokens = reservedTokens + ?,
                    reservedCostMicros = reservedCostMicros + ?
                WHERE budgetDate = ? AND scope = ?
                """, estTokens, estCost, budgetDate.toString(), budgetScope);

        long timeoutMicros = Math.max(1_000L, properties.getCallTimeout().toNanos() / 1_000L);
        jdbc.update("""
                INSERT INTO t_news_model_call_receipt
                    (callId, idempotencyKey, materialRevisionId, policyVersion, attemptNo,
                     budgetDate, budgetScope, provider, modelName, promptSha256, state,
                     reservedTokens, reservedCostMicros, reservedAt, deadlineAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, UTC_TIMESTAMP(6),
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? MICROSECOND))
                """,
                callId, idempotencyKey, revision.revisionId(), policyVersion, nextAttempt,
                budgetDate.toString(), budgetScope, request.provider(), request.modelName(),
                request.promptSha256(), estTokens, estCost, timeoutMicros);

        return new ReservationOutcome(null, request, estTokens, estCost);
    }

    private SettlementResult settleOutcome(NewsCurationModels.ModelCallRequest request,
                                           NewsCurationModels.EvidenceSnapshot snapshot,
                                           String snapshotJson,
                                           String snapshotSha256,
                                           long reservedTokens,
                                           long reservedCostMicros,
                                           LocalDate budgetDate,
                                           String budgetScope,
                                           NewsCurationModels.ModelCallOutcome outcome) {
        if (outcome instanceof NewsCurationModels.DefinitiveFailure failure && failure.authFailure()) {
            lockCircuit(budgetScope);
        }
        lockBudget(budgetDate, budgetScope);
        ReceiptRow receipt = lockReceipt(request.callId());
        if (receipt.state != NewsCurationModels.CallState.DISPATCHED) {
            throw new IllegalStateException("CALL_ALREADY_TRANSITIONED:" + receipt.state);
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
            return new SettlementResult(null, "MODEL_CALL_FAILED:" + f.errorCode());
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

    private NewsCurationModels.CallState reclaimSingleStaleCall(String callId) {
        ReceiptRow header = jdbc.query("""
                SELECT callId, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       state, reservedTokens, reservedCostMicros
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
            releaseReservedBudget(locked.budgetDate, locked.budgetScope,
                    locked.reservedTokens, locked.reservedCostMicros);
            jdbc.update("""
                    UPDATE t_news_model_call_receipt
                    SET state = 'ABORTED_PRE_IO',
                        errorCode = 'PRE_IO_DEADLINE_EXCEEDED',
                        completedAt = UTC_TIMESTAMP(6)
                    WHERE callId = ?
                    """, callId);
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
            if (rev != null) {
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

    private String lockCircuit(String scope) {
        jdbc.update("INSERT IGNORE INTO t_news_curation_circuit (scope) VALUES (?)", scope);
        return jdbc.queryForObject("""
                SELECT circuitReason FROM t_news_curation_circuit WHERE scope = ? FOR UPDATE
                """, String.class, scope);
    }

    private NewsCurationModels.BudgetSnapshot lockBudget(LocalDate budgetDate, String scope) {
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

    private ReceiptRow lockReceipt(String callId) {
        List<ReceiptRow> rows = jdbc.query("""
                SELECT callId, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       state, reservedTokens, reservedCostMicros
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
                rs.getLong("reservedCostMicros")
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
                parseUtcInstant(rs.getString("completedAtUtc"))
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
                   responseSha256, errorCode, latencyMs, reconciledBy, reconcileNote,
                   DATE_FORMAT(reconciledAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS reconciledAtUtc,
                   DATE_FORMAT(reservedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS reservedAtUtc,
                   DATE_FORMAT(dispatchedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS dispatchedAtUtc,
                   DATE_FORMAT(deadlineAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS deadlineAtUtc,
                   DATE_FORMAT(completedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS completedAtUtc
            FROM t_news_model_call_receipt
            """;

    private record ReservationOutcome(
            NewsCurationModels.CurationResult replayedResult,
            NewsCurationModels.ModelCallRequest request,
            long reservedTokens,
            long reservedCostMicros
    ) {
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
            long reservedCostMicros
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
}
