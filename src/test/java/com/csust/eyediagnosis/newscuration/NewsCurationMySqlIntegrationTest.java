package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.newsingestion.NewsIngestionProperties;
import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsWireCodec;
import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.csust.eyediagnosis.newsingestion.NewsWorkerIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 real-MySQL integration suite for WP-03 News Engine curation, reserve-before-IO receipts,
 * daily budgets, unknown-paid-outcome reconciliation, auth circuit breaking, and NewsBench.
 *
 * <p>Keeps {@code Assumptions.assumeTrue} in {@code @BeforeEach} so Surefire records exact
 * case/skip counts matching {@code scripts/check_surefire_skips.py} (13 cases).
 */
class NewsCurationMySqlIntegrationTest {

    private static final String POLICY_V1 = "news-curation-v1";
    private static final String SCOPE_GLOBAL = "GLOBAL";
    private static final LocalDate BUDGET_DATE = LocalDate.of(2026, 10, 15);
    private static final NewsWorkerIdentity WORKER =
            new NewsWorkerIdentity("curation-it-worker", NewsIngestionProperties.ALL_SOURCES);

    private static String schema;
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;

    private ObjectMapper mapper;
    private NewsWireCodec wireCodec;
    private NewsIngestionService ingestionService;
    private NewsCurationProperties curationProperties;
    private NewsCurationPromptBuilder promptBuilder;
    private NewsEvidenceVerifier verifier;
    private ScriptedModelClient modelClient;
    private NewsCurationService curationService;

    @BeforeEach
    void setUp() {
        String url = System.getenv("FLYWAY_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set FLYWAY_TEST_URL to run the real MySQL News Curation gate");
        ensureDatabase();

        jdbc.execute("DELETE FROM t_news_bench_run");
        jdbc.execute("DELETE FROM t_news_curation_candidate");
        jdbc.execute("DELETE FROM t_news_model_call_receipt");
        jdbc.execute("DELETE FROM t_news_curation_budget");
        jdbc.execute("DELETE FROM t_news_curation_budget_guard");
        jdbc.execute("DELETE FROM t_news_curation_circuit");
        jdbc.execute("DELETE FROM t_news_material_revision");
        jdbc.execute("DELETE FROM t_news_material");
        jdbc.execute("DELETE FROM t_news_worker_receipt");
        jdbc.execute("DELETE FROM t_news_batch_item");
        jdbc.execute("DELETE FROM t_news_collection_batch");
        jdbc.execute("DELETE FROM t_news_collection_lease");
        jdbc.execute("DELETE FROM t_news_collection_task");
        jdbc.execute("DELETE FROM t_news_source_cursor");
        jdbc.execute("DELETE FROM t_news_source");
        jdbc.execute("""
                INSERT INTO t_news_source (source, enabled)
                VALUES ('pubmed', 1), ('europe-pmc', 1), ('clinicaltrials-gov', 1)
                """);

        mapper = JsonConfig.createNewsProtocolObjectMapper();
        wireCodec = new NewsWireCodec(mapper);
        NewsIngestionProperties ingestionProps = new NewsIngestionProperties();
        ingestionProps.setEnabled(true);
        ingestionService = new NewsIngestionService(jdbc, ingestionProps, wireCodec);

        curationProperties = new NewsCurationProperties();
        curationProperties.setEnabled(true);
        curationProperties.setPolicyVersion(POLICY_V1);
        curationProperties.setProvider("dashscope");
        curationProperties.setModelName("qwen3.5-plus");
        curationProperties.setCallTimeout(Duration.ofSeconds(20));
        curationProperties.setDailyMaxCalls(50);
        curationProperties.setDailyMaxTokens(200_000L);
        curationProperties.setDailyMaxCostMicros(2_000_000L);
        curationProperties.setEstimatedTokensPerCall(4_000L);
        curationProperties.setEstimatedCostMicrosPerCall(50_000L);

        promptBuilder = new NewsCurationPromptBuilder(curationProperties, mapper);
        verifier = new NewsEvidenceVerifier(curationProperties, mapper);
        modelClient = new ScriptedModelClient();
        curationService = new NewsCurationService(
                jdbc, curationProperties, modelClient, verifier, promptBuilder, mapper);
    }

    private static synchronized void ensureDatabase() {
        if (jdbc != null) {
            return;
        }
        String url = System.getenv("FLYWAY_TEST_URL");
        String username = System.getenv("FLYWAY_TEST_USERNAME");
        String password = System.getenv("FLYWAY_TEST_PASSWORD");
        schema = "diagnosis_ci_curation_" + UUID.randomUUID().toString().replace("-", "");

        admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        admin.execute("CREATE DATABASE `" + schema + "`");

        dataSource = new DriverManagerDataSource(withSchema(url, schema), username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/callback")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void tearDownAll() {
        if (schema == null || admin == null) {
            return;
        }
        admin.execute("DROP DATABASE IF EXISTS `" + schema + "`");
    }

    // Case 1
    @Test
    void v40MigrationCreatesCurationAndReceiptTables() {
        for (String table : List.of(
                "t_news_curation_budget",
                "t_news_model_call_receipt",
                "t_news_curation_candidate",
                "t_news_bench_run")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class, schema, table);
            assertEquals(1, count, "Missing V41 table: " + table);
        }
    }

    // Case 2
    @Test
    void uncommittedStagedBatchCannotBeCuratedOnlyVisibleRevisionCan() throws Exception {
        ingestionService.enqueueWindow("task-vis-1", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        NewsWireModels.Lease lease = ingestionService.claim(WORKER).orElseThrow();
        ingestionService.submitBatch(WORKER, lease.taskId(), "1:1", batchPayload(lease.generation(), lease.leaseToken(),
                1, List.of(openPubmedMaterial("PMID-VIS-1", "Glaucoma OCT screening validation"))));

        // Revision is still only in t_news_batch_item -> curation MUST reject without touching model client.
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
                curationService.curateRevision(1L, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));
        assertTrue(missing.getMessage().contains("REVISION_NOT_VISIBLE"));
        assertEquals(0, modelClient.invocations.get());

        ingestionService.complete(WORKER, lease.taskId(), "1:complete",
                completePayload(lease.generation(), lease.leaseToken(), 1, 1, LocalDate.of(2026, 10, 8)));

        NewsIngestionService.VisibleRevision rev =
                ingestionService.listVisibleRevisions("pubmed", "PMID-VIS-1").getFirst();
        modelClient.enqueue(req -> success(validAcceptJson("Glaucoma OCT screening validation", "abstract"),
                500, 180, 3_500L, "req-vis-1"));

        NewsCurationModels.CurationResult result = curationService.curateRevision(
                rev.revisionId(), POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        assertEquals(NewsCurationModels.CandidateStatus.CURATED_ACCEPTED, result.status());
        assertEquals(1, modelClient.invocations.get());
    }

    // Case 3
    @Test
    void reserveBeforeIoPersistsReceiptBeforeModelCallAndEnforcesConcurrentBudgetCeiling() throws Exception {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 2, 200_000L, 500_000L);

        List<Long> revisionIds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            revisionIds.add(ingestAndCompleteSingle("pubmed", "PMID-CONC-" + i,
                    "Ophthalmic fundus AI screening study " + i, 100L + i));
        }

        for (int i = 0; i < 6; i++) {
            final int idx = i + 1;
            modelClient.enqueue(req -> {
                // While inside invoke(), DISPATCHED receipt AND reserved budget MUST already be committed in MySQL!
                NewsCurationModels.CallReceiptSnapshot inFlight = curationService.getCallReceipt(req.callId()).orElseThrow();
                assertEquals(NewsCurationModels.CallState.DISPATCHED, inFlight.state());
                assertNotNull(inFlight.dispatchedAt());
                NewsCurationModels.BudgetSnapshot b = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
                assertTrue(b.reservedCalls() >= 1);
                return success(validAcceptJson("Ophthalmic fundus AI screening study " + idx, "abstract"),
                        400, 150, 2_500L, "req-conc-" + idx);
            });
        }

        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        int succeededCount = 0;
        int exhaustedCount = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (Long revId : revisionIds) {
                futures.add(pool.submit(race(ready, start, () -> {
                    try {
                        curationService.curateRevision(revId, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
                        return "OK";
                    } catch (IllegalStateException ex) {
                        return ex.getMessage();
                    }
                })));
            }
            ready.await();
            start.countDown();
            for (Future<String> f : futures) {
                String res = f.get();
                if ("OK".equals(res)) {
                    succeededCount++;
                } else {
                    assertTrue(res.contains("BUDGET_EXHAUSTED"), "Unexpected error: " + res);
                    exhaustedCount++;
                }
            }
        }

        assertEquals(2, succeededCount);
        assertEquals(4, exhaustedCount);
        assertEquals(2, modelClient.invocations.get(), "Must never execute more model calls than dailyMaxCalls");
        NewsCurationModels.BudgetSnapshot finalBudget = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
        assertEquals(2, finalBudget.usedCalls());
        assertEquals(0, finalBudget.reservedCalls());
    }

    // Case 4
    @Test
    void succeededCallSettlesActualCostAndIdempotentlyReplaysWithoutSecondModelCall() throws Exception {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 1, 20_000L, 100_000L);
        long revId = ingestAndCompleteSingle("pubmed", "PMID-IDEM-1",
                "Retinal vessel segmentation clinical validation", 1L);

        modelClient.enqueue(req -> success(
                validAcceptJson("Retinal vessel segmentation clinical validation", "abstract"),
                610, 240, 4_200L, "req-idem-1"));

        NewsCurationModels.CurationResult first = curationService.curateRevision(
                revId, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        assertFalse(first.replayed());
        assertEquals(NewsCurationModels.CandidateStatus.CURATED_ACCEPTED, first.status());

        NewsCurationModels.BudgetSnapshot afterFirst = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
        assertEquals(1, afterFirst.usedCalls());
        assertEquals(0, afterFirst.reservedCalls());
        assertEquals(850L, afterFirst.usedTokens());
        assertEquals(0L, afterFirst.reservedTokens());
        assertEquals(4_200L, afterFirst.usedCostMicros());
        assertEquals(0L, afterFirst.reservedCostMicros());

        // Even though dailyMaxCalls=1 is now full, replay of the same (revisionId, policyVersion) succeeds without I/O.
        NewsCurationModels.CurationResult replay = curationService.curateRevision(
                revId, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        assertTrue(replay.replayed());
        assertEquals(first.callId(), replay.callId());
        assertEquals(first.evidenceSnapshotSha256(), replay.evidenceSnapshotSha256());
        assertEquals(1, modelClient.invocations.get());
    }

    // Case 5
    @Test
    void evidenceViolationMarksCandidateRejectedAndDoesNotPromoteToAccepted() throws Exception {
        long revId = ingestAndCompleteSingle("pubmed", "PMID-EV-1",
                "Choroidal neovascularization imaging biomarkers", 1L);

        String fabricatedOutput = """
                {
                  "decision": "ACCEPT",
                  "relevanceScore": 90,
                  "suggestedTitle": "脉冲脉络膜新生血管突破",
                  "suggestedSummary": "引用不存在的原文文本。",
                  "category": "眼底影像学",
                  "limitations": ["仅基于摘要。"],
                  "claims": [
                    {
                      "id": "c1",
                      "field": "abstract",
                      "statement": "100% 患者完全恢复",
                      "quote": "completely hallucinated substring not present in source"
                    }
                  ]
                }
                """;
        modelClient.enqueue(req -> success(fabricatedOutput, 300, 120, 2_000L, "req-ev-1"));

        NewsCurationModels.CurationResult result = curationService.curateRevision(
                revId, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        assertEquals(NewsCurationModels.CandidateStatus.REJECTED_EVIDENCE_VIOLATION, result.status());
        assertTrue(result.violations().stream().anyMatch(v -> "QUOTE_NOT_FOUND".equals(v.code())));
        assertTrue(curationService.listCandidatesByStatus(NewsCurationModels.CandidateStatus.CURATED_ACCEPTED).isEmpty());

        NewsCurationModels.CallReceiptSnapshot receipt = curationService.getCallReceipt(result.callId()).orElseThrow();
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, receipt.state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_news", Integer.class));
    }

    // Case 6
    @Test
    void unknownPaidOutcomeHoldsConservativeBudgetAndBlocksDuplicatePaidCalls() throws Exception {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 1, 20_000L, 100_000L);
        long rev1 = ingestAndCompleteSingle("pubmed", "PMID-UNK-1", "Glaucoma drainage surgery follow-up", 1L);
        long rev2 = ingestAndCompleteSingle("pubmed", "PMID-UNK-2", "Another ophthalmology study", 2L);

        modelClient.enqueue(req -> new NewsCurationModels.UnknownOutcome("READ_TIMEOUT", null, "req-unk-1", 20_000));

        NewsCurationModels.CurationResult res = curationService.curateRevision(
                rev1, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        assertEquals(NewsCurationModels.CandidateStatus.NEEDS_RECONCILIATION, res.status());

        NewsCurationModels.CallReceiptSnapshot receipt = curationService.getCallReceipt(res.callId()).orElseThrow();
        assertEquals(NewsCurationModels.CallState.OUTCOME_UNKNOWN, receipt.state());

        // Conservative hold: reserved call/tokens/cost MUST remain reserved until reconciled!
        NewsCurationModels.BudgetSnapshot held = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
        assertEquals(0, held.usedCalls());
        assertEquals(1, held.reservedCalls());
        assertEquals(4_000L, held.reservedTokens());
        assertEquals(50_000L, held.reservedCostMicros());

        // Duplicate call to rev1 is blocked by awaiting reconciliation.
        IllegalStateException dup = assertThrows(IllegalStateException.class, () ->
                curationService.curateRevision(rev1, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));
        assertTrue(dup.getMessage().contains("CURATION_AWAITING_RECONCILIATION"));

        // Different rev2 is blocked because the conservative hold consumes the 1-call daily ceiling.
        IllegalStateException cap = assertThrows(IllegalStateException.class, () ->
                curationService.curateRevision(rev2, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));
        assertTrue(cap.getMessage().contains("BUDGET_EXHAUSTED"));
        assertEquals(1, modelClient.invocations.get());
    }

    // Case 7
    @Test
    void reconcileUnknownCallHandlesAllThreeResolutionsAccurately() throws Exception {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 10, 100_000L, 500_000L);
        long revA = ingestAndCompleteSingle("pubmed", "PMID-REC-A", "Glaucoma aqueous humor proteomics", 1L);
        long revB = ingestAndCompleteSingle("pubmed", "PMID-REC-B", "Diabetic retinopathy neurovascular coupling", 2L);
        long revC = ingestAndCompleteSingle("pubmed", "PMID-REC-C", "Age-related macular degeneration complement trial", 3L);

        for (int i = 0; i < 3; i++) {
            final int n = i + 1;
            modelClient.enqueue(req -> new NewsCurationModels.UnknownOutcome("SOCKET_RESET", null, "req-r-" + n, 5_000));
        }
        String callA = curationService.curateRevision(revA, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL).callId();
        String callB = curationService.curateRevision(revB, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL).callId();
        String callC = curationService.curateRevision(revC, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL).callId();

        // 1) Confirmed charged with recovered output -> settles actual usage and accepts candidate.
        NewsCurationModels.CallReceiptSnapshot recA = curationService.reconcileUnknownCall(
                callA,
                NewsCurationModels.ReconcileResolution.CONFIRMED_CHARGED_WITH_OUTPUT,
                "ops-admin-1",
                "Recovered from DashScope billing log",
                validAcceptJson("Glaucoma aqueous humor proteomics", "abstract"),
                500, 200, 3_000L, "dash-req-A"
        );
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, recA.state());
        assertEquals("ops-admin-1", recA.reconciledBy());
        assertEquals(NewsCurationModels.CandidateStatus.CURATED_ACCEPTED,
                curationService.getCandidate(revA, POLICY_V1).orElseThrow().status());

        // 2) Confirmed charged without usable output -> settles actual usage as FAILED_CHARGED, clears placeholder.
        NewsCurationModels.CallReceiptSnapshot recB = curationService.reconcileUnknownCall(
                callB,
                NewsCurationModels.ReconcileResolution.CONFIRMED_CHARGED_NO_OUTPUT,
                "ops-admin-1",
                "Provider billed empty stream",
                null, 500, 0, 2_000L, "dash-req-B"
        );
        assertEquals(NewsCurationModels.CallState.FAILED_CHARGED, recB.state());
        assertTrue(curationService.getCandidate(revB, POLICY_V1).isEmpty());

        // 3) Confirmed NOT charged -> releases reservation cleanly, clears placeholder so retry runs attempt 2.
        NewsCurationModels.CallReceiptSnapshot recC = curationService.reconcileUnknownCall(
                callC,
                NewsCurationModels.ReconcileResolution.CONFIRMED_NOT_CHARGED,
                "ops-admin-1",
                "Request never reached provider billing",
                null, null, null, null, null
        );
        assertEquals(NewsCurationModels.CallState.FAILED_UNCHARGED, recC.state());
        assertTrue(curationService.getCandidate(revC, POLICY_V1).isEmpty());

        NewsCurationModels.BudgetSnapshot mid = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
        assertEquals(2, mid.usedCalls());
        assertEquals(0, mid.reservedCalls());
        assertEquals(1_200L, mid.usedTokens());
        assertEquals(0L, mid.reservedTokens());
        assertEquals(5_000L, mid.usedCostMicros());
        assertEquals(0L, mid.reservedCostMicros());

        // Retry revC safely as attempt 2.
        modelClient.enqueue(req -> success(
                validAcceptJson("Age-related macular degeneration complement trial", "abstract"),
                450, 150, 2_800L, "req-r-c-2"));
        NewsCurationModels.CurationResult retriedC = curationService.curateRevision(
                revC, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        assertEquals(NewsCurationModels.CandidateStatus.CURATED_ACCEPTED, retriedC.status());
        List<NewsCurationModels.CallReceiptSnapshot> receiptsC =
                curationService.listCallReceiptsForRevision(revC, POLICY_V1);
        assertEquals(2, receiptsC.size());
        assertEquals(1, receiptsC.get(0).attemptNo());
        assertEquals(NewsCurationModels.CallState.FAILED_UNCHARGED, receiptsC.get(0).state());
        assertEquals(2, receiptsC.get(1).attemptNo());
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, receiptsC.get(1).state());
    }

    // Case 8
    @Test
    void staleCallRecoveryAbortsPreIoAndMarksDispatchedAsUnknownUsingDbClock() throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        try {
            curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 10, 100_000L, 500_000L);
            long revPre = ingestAndCompleteSingle("pubmed", "PMID-STALE-1", "Pre-IO crash title", 1L);
            long revDisp = ingestAndCompleteSingle("pubmed", "PMID-STALE-2", "Dispatched timeout title", 2L);
            long revFresh = ingestAndCompleteSingle("pubmed", "PMID-STALE-3", "Fresh non-expired call", 3L);

            jdbc.update("""
                    INSERT INTO t_news_model_call_receipt
                        (callId, idempotencyKey, materialRevisionId, policyVersion, attemptNo,
                         budgetDate, budgetScope, provider, modelName, promptSha256, state,
                         reservedTokens, reservedCostMicros, reservedAt, deadlineAt)
                    VALUES ('stale-pre', '1:p:1', ?, ?, 1, ?, ?, 'dashscope', 'qwen3.5-plus', ?, 'RESERVED',
                            4000, 50000, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 60 SECOND), DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 SECOND)),
                           ('stale-disp', '2:p:1', ?, ?, 1, ?, ?, 'dashscope', 'qwen3.5-plus', ?, 'DISPATCHED',
                            4000, 50000, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 60 SECOND), DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 SECOND)),
                           ('fresh-disp', '3:p:1', ?, ?, 1, ?, ?, 'dashscope', 'qwen3.5-plus', ?, 'DISPATCHED',
                            4000, 50000, UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 60 SECOND))
                    """,
                    revPre, POLICY_V1, BUDGET_DATE.toString(), SCOPE_GLOBAL, "a".repeat(64),
                    revDisp, POLICY_V1, BUDGET_DATE.toString(), SCOPE_GLOBAL, "b".repeat(64),
                    revFresh, POLICY_V1, BUDGET_DATE.toString(), SCOPE_GLOBAL, "c".repeat(64));

            jdbc.update("""
                    UPDATE t_news_curation_budget
                    SET reservedCalls = 3, reservedTokens = 12000, reservedCostMicros = 150000
                    WHERE budgetDate = ? AND scope = ?
                    """, BUDGET_DATE.toString(), SCOPE_GLOBAL);

            NewsCurationModels.StaleReclaimResult reclaimed = curationService.reclaimStaleCalls();
            assertEquals(1, reclaimed.abortedPreIoCount());
            assertEquals(1, reclaimed.markedUnknownCount());

            assertEquals(NewsCurationModels.CallState.ABORTED_PRE_IO,
                    curationService.getCallReceipt("stale-pre").orElseThrow().state());
            assertEquals(NewsCurationModels.CallState.OUTCOME_UNKNOWN,
                    curationService.getCallReceipt("stale-disp").orElseThrow().state());
            assertEquals(NewsCurationModels.CallState.DISPATCHED,
                    curationService.getCallReceipt("fresh-disp").orElseThrow().state());
            assertEquals(NewsCurationModels.CandidateStatus.NEEDS_RECONCILIATION,
                    curationService.getCandidate(revDisp, POLICY_V1).orElseThrow().status());

            NewsCurationModels.BudgetSnapshot after = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
            // stale-pre released (1 slot), stale-disp and fresh-disp remain reserved (2 slots).
            assertEquals(2, after.reservedCalls());
            assertEquals(8_000L, after.reservedTokens());
            assertEquals(100_000L, after.reservedCostMicros());

            long revExpired = ingestAndCompleteSingle("pubmed", "PMID-STALE-4", "Dispatch deadline", 4L);
            JdbcTemplate expireOnReserve = new JdbcTemplate(dataSource) {
                @Override
                public int update(String sql, Object... args) {
                    int updated = super.update(sql, args);
                    if (sql.contains("INSERT INTO t_news_model_call_receipt")) {
                        super.update("""
                                UPDATE t_news_model_call_receipt
                                SET deadlineAt = UTC_TIMESTAMP(6) WHERE callId = ?
                                """, args[0]);
                    }
                    return updated;
                }
            };
            NewsCurationService deadlineService = new NewsCurationService(expireOnReserve,
                    curationProperties, modelClient, verifier, promptBuilder, mapper);
            assertThrows(IllegalStateException.class, () ->
                    deadlineService.curateRevision(revExpired, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));
            assertEquals(0, modelClient.invocations.get());
            assertEquals("ABORTED_PRE_IO", jdbc.queryForObject("""
                    SELECT state FROM t_news_model_call_receipt WHERE materialRevisionId = ?
                    """, String.class, revExpired));
            assertEquals(after.reservedCalls(), curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL)
                    .orElseThrow().reservedCalls());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // Case 9
    @Test
    void authFailureTripsCircuitBreakerWithoutRetryingSubsequentCalls() throws Exception {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 5, 100_000L, 500_000L);
        long rev1 = ingestAndCompleteSingle("pubmed", "PMID-AUTH-1", "Glaucoma trabecular meshwork study", 1L);
        long rev2 = ingestAndCompleteSingle("pubmed", "PMID-AUTH-2", "Optic nerve head biomechanics", 2L);

        modelClient.enqueue(req -> new NewsCurationModels.DefinitiveFailure(
                "PROVIDER_AUTH_401", 401, "req-auth-401", true, 90));

        IllegalStateException authEx = assertThrows(IllegalStateException.class, () ->
                curationService.curateRevision(rev1, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));
        assertTrue(authEx.getMessage().contains("PROVIDER_AUTH_401"));

        NewsCurationModels.BudgetSnapshot broken = curationService.getBudget(BUDGET_DATE, SCOPE_GLOBAL).orElseThrow();
        assertTrue(broken.circuitBroken());
        assertEquals("PROVIDER_AUTH_401", broken.circuitReason());
        assertEquals(0, broken.reservedCalls());
        assertEquals(0, broken.usedCalls());

        // Subsequent call must trip immediately without hitting the external model client.
        IllegalStateException breakerEx = assertThrows(IllegalStateException.class, () ->
                curationService.curateRevision(rev2, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));
        assertTrue(breakerEx.getMessage().contains("CURATION_CIRCUIT_BROKEN"));
        LocalDate nextDay = BUDGET_DATE.plusDays(1);
        IllegalStateException nextDayBlocked = assertThrows(IllegalStateException.class, () ->
                curationService.curateRevision(rev2, POLICY_V1, nextDay, SCOPE_GLOBAL));
        assertTrue(nextDayBlocked.getMessage().contains("CURATION_CIRCUIT_BROKEN"));
        assertEquals(1, modelClient.invocations.get());

        // After operator resets breaker, curation succeeds.
        curationService.resetCircuitBreaker(BUDGET_DATE, SCOPE_GLOBAL, "ops-lead");
        modelClient.enqueue(req -> success(
                validAcceptJson("Optic nerve head biomechanics", "abstract"),
                400, 150, 2_200L, "req-auth-recovered"));
        NewsCurationModels.CurationResult ok = curationService.curateRevision(
                rev2, POLICY_V1, nextDay, SCOPE_GLOBAL);
        assertEquals(NewsCurationModels.CandidateStatus.CURATED_ACCEPTED, ok.status());

        long revToBlock = ingestAndCompleteSingle("pubmed", "PMID-AUTH-3", "Reserved before circuit trip", 3L);
        long revToTrip = ingestAndCompleteSingle("pubmed", "PMID-AUTH-4", "Concurrent provider auth failure", 4L);
        CountDownLatch dispatchReady = new CountDownLatch(1);
        CountDownLatch circuitTripped = new CountDownLatch(1);
        AtomicInteger circuitReads = new AtomicInteger();
        JdbcTemplate beforeDispatch = new JdbcTemplate(dataSource) {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                if (sql.contains("SELECT circuitReason FROM t_news_curation_circuit")
                        && circuitReads.incrementAndGet() == 2) {
                    dispatchReady.countDown();
                    try {
                        assertTrue(circuitTripped.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }
                return super.queryForObject(sql, requiredType, args);
            }
        };
        NewsCurationService blockedService = new NewsCurationService(beforeDispatch, curationProperties,
                modelClient, verifier, promptBuilder, mapper);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<IllegalStateException> blocked = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> blockedService.curateRevision(revToBlock, POLICY_V1, nextDay, SCOPE_GLOBAL)));
            try {
                assertTrue(dispatchReady.await(10, TimeUnit.SECONDS));
                modelClient.enqueue(req -> new NewsCurationModels.DefinitiveFailure(
                        "PROVIDER_AUTH_401", 401, "req-auth-401-again", true, 90));
                assertThrows(IllegalStateException.class, () ->
                        curationService.curateRevision(revToTrip, POLICY_V1, nextDay, SCOPE_GLOBAL));
            } finally {
                circuitTripped.countDown();
            }
            assertTrue(blocked.get(10, TimeUnit.SECONDS).getMessage().contains("CURATION_CIRCUIT_BROKEN"));
        }
        assertEquals(3, modelClient.invocations.get());
        assertEquals("ABORTED_PRE_IO", jdbc.queryForObject("""
                SELECT state FROM t_news_model_call_receipt WHERE materialRevisionId = ?
                """, String.class, revToBlock));
        assertEquals(0, curationService.getBudget(nextDay, SCOPE_GLOBAL).orElseThrow().reservedCalls());
    }

    // Case 10
    @Test
    void newsBenchPersistsDeterministicRunReportAgainstRealMySqlMaterials() {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 100, 500_000L, 5_000_000L);
        NewsBenchRunner runner = new NewsBenchRunner(jdbc, wireCodec, verifier, promptBuilder, mapper);
        NewsBenchRunner.FixtureModelClientRegistry registry = (rawJson, promptTokens, completionTokens, cost) ->
                modelClient.enqueue(req -> success(rawJson, promptTokens, completionTokens, cost,
                        "bench-req-" + req.callId()));

        NewsCurationModels.NewsBenchReport report = runner.runEndToEndWithMySql(
                Path.of("contracts/news/newsbench/v1.dataset.json"),
                POLICY_V1,
                ingestionService,
                curationService,
                registry,
                BUDGET_DATE,
                SCOPE_GLOBAL
        );

        assertTrue(report.gatePassed(), () -> "NewsBench failed cases: " + report.caseResults().stream()
                .filter(c -> !c.passed()).toList());
        assertEquals(18, report.totalCases());
        assertEquals(18, report.passedCases());
        assertEquals(10_000, report.evidenceCatchBps());
        assertEquals(10_000, report.licenseComplianceBps());
        assertEquals(10_000, report.injectionCatchBps());
        assertEquals(18, report.totalCalls());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_bench_run WHERE gatePassed = 1", Integer.class));
        assertEquals(18, jdbc.queryForObject("SELECT COUNT(*) FROM t_news_curation_candidate", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_news", Integer.class));
    }

    private static final String SCREENING_JSON = """
            {"relevanceScore":90,"researchValueScore":null,"recommendedType":"RESEARCH_INSIGHT",
             "rationale":"题录与青光眼研究相关，研究价值待核实。"}
            """;

    @Test
    void modelIdentitySeparatesNamespacesModelsAndStagesWithoutWritingCandidates() throws Exception {
        long revision = ingestAndCompleteSingle("pubmed", "PMID-STAGE-1", "Glaucoma outcomes metadata", 1L);
        curationProperties.setModelProfiles(Map.of("a", stagedProfile("fixture-a"), "b", stagedProfile("fixture-b")));
        var screening = new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P01, "a", null);
        modelClient.enqueue(request -> new NewsCurationModels.Succeeded(SCREENING_JSON, 100, 20, 140, 200, "fixture-a", 1));
        var first = curationService.runModel(revision, POLICY_V1, "screen-a", screening);
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, first.receipt().state());
        assertEquals(NewsEditorialEvidence.ContentType.RESEARCH_RADAR, first.identity().eligibleType());
        assertEquals(140, first.receipt().actualCostMicros());
        assertEquals(NewsModelProtocol.Stage.SCREENING, first.identity().stage());
        var replay = curationService.runModel(revision, POLICY_V1, "another-scope", screening);
        assertTrue(replay.replayed());
        assertEquals(first.receipt().idempotencyKey(), replay.receipt().idempotencyKey());
        assertEquals(first.frozenOutput(), replay.frozenOutput());

        modelClient.enqueue(request -> new NewsCurationModels.Succeeded(SCREENING_JSON, 100, 20, 140, 200, "fixture-b", 1));
        var second = curationService.runModel(revision, POLICY_V1, "screen-b",
                new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P01, "b", null));
        assertFalse(first.receipt().logicalCallSha256().equals(second.receipt().logicalCallSha256()));
        assertThrows(IllegalArgumentException.class, () -> curationService.runModel(revision, POLICY_V1, "foreign",
                new NewsModelProtocol.Selection("production", NewsModelProtocol.Prompt.P03, "a", first.receipt().callId())));

        modelClient.enqueue(request -> new NewsCurationModels.Succeeded("""
                {"contentType":"RESEARCH_RADAR","sections":{"introduction":"青光眼研究题录","reasonToWatch":"用于后续追踪",
                 "limitations":"仅题录可用"},"claims":[{"evidenceId":"title","statement":"题录涉及青光眼","quote":"Glaucoma"}]}
                """, 100, 20, 140, 200, "fixture-gen", 1));
        var generation = curationService.runModel(revision, POLICY_V1, "generation",
                new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P03, "a", first.receipt().callId()));
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, generation.receipt().state());
        assertEquals(first.receipt().responseSha256(), generation.identity().upstreamResponseSha256());
        assertEquals(NewsModelProtocol.Stage.GENERATION, generation.identity().stage());
        assertEquals(3, modelClient.invocations.get());
        assertTrue(curationService.getCandidate(revision, POLICY_V1).isEmpty());
    }

    @Test
    void globalCapHoldsAcrossConcurrentScopesAndUnknownOutcomeUntilReconciliation() throws Exception {
        long revision = ingestAndCompleteSingle("pubmed", "PMID-STAGE-2", "Glaucoma registry metadata", 1L);
        curationProperties.setModelProfiles(Map.of("a", stagedProfile("fixture-a")));
        curationProperties.setDailyMaxCalls(1);
        var first = new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P01, "a", null);
        var second = new NewsModelProtocol.Selection("experiment.B", NewsModelProtocol.Prompt.P01, "a", null);
        CountDownLatch dispatched = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        modelClient.enqueue(request -> {
            dispatched.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return new NewsCurationModels.UnknownOutcome("MODEL_TRANSPORT_OUTCOME_UNKNOWN", null, null, 1);
        });
        NewsCurationService.ModelRun unknown;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<NewsCurationService.ModelRun> pending = executor.submit(
                    () -> curationService.runModel(revision, POLICY_V1, "scope-a", first));
            try {
                assertTrue(dispatched.await(10, TimeUnit.SECONDS));
                var blocked = assertThrows(IllegalStateException.class,
                        () -> curationService.runModel(revision, POLICY_V1, "scope-b", second));
                assertEquals("BUDGET_EXHAUSTED", blocked.getMessage());
            } finally {
                release.countDown();
            }
            unknown = pending.get(10, TimeUnit.SECONDS);
        }
        assertEquals(NewsCurationModels.CallState.OUTCOME_UNKNOWN, unknown.receipt().state());
        assertThrows(IllegalStateException.class, () -> curationService.runModel(revision, POLICY_V1, "scope-b", second));
        assertTrue(curationService.getCandidate(revision, POLICY_V1).isEmpty());
        curationService.reconcileUnknownCall(unknown.receipt().callId(),
                NewsCurationModels.ReconcileResolution.CONFIRMED_NOT_CHARGED, "reviewer", "no charge",
                null, null, null, null, null);
        modelClient.enqueue(request -> new NewsCurationModels.Succeeded(SCREENING_JSON, 100, 20, 140, 200, "fixture-b", 1));
        var completed = curationService.runModel(revision, POLICY_V1, "scope-b", second);
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, completed.receipt().state());
        assertFalse(unknown.receipt().logicalCallSha256().equals(completed.receipt().logicalCallSha256()));
        assertEquals(2, modelClient.invocations.get());
    }

    private static NewsModelProfile stagedProfile(String model) {
        return new NewsModelProfile(NewsModelProfile.Provider.QWEN, NewsModelProfile.Platform.BAILIAN,
                URI.create("https://dashscope.aliyuncs.com"), "/compatible-mode/v1/chat/completions",
                model, "FIXTURE_KEY", Duration.ofSeconds(2), Duration.ofSeconds(5), 8192, 512, 0,
                NewsModelProfile.Format.JSON_OBJECT, new NewsModelProfile.Capabilities(
                        true, false, NewsModelProfile.UsageFormat.STANDARD),
                new NewsModelProfile.PricingVersion("fixture-v1", 1_000_000, 1_000_000, 2_000_000));
    }

    @Test
    void aConfirmedFreeFailureRequiresAnExplicitRetryAfterTheCircuitIsReset() throws Exception {
        long revision = ingestAndCompleteSingle("pubmed", "PMID-STAGE-3", "Glaucoma screening metadata", 1L);
        curationProperties.setModelProfiles(Map.of("a", stagedProfile("fixture-a")));
        var selection = new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P01, "a", null);
        modelClient.enqueue(request -> new NewsCurationModels.DefinitiveFailure("MODEL_AUTH_REJECTED", 401, null, true, 1));
        var failed = curationService.runModel(revision, POLICY_V1, "scope-a", selection);
        assertEquals(NewsCurationModels.CallState.FAILED_DEFINITIVE, failed.receipt().state());
        assertTrue(curationService.runModel(revision, POLICY_V1, "scope-a", selection).replayed());
        assertThrows(IllegalStateException.class, () -> curationService.retryDefinitiveModel(revision, POLICY_V1,
                "scope-a", selection, failed.receipt().callId()));

        curationService.resetCircuitBreaker(failed.receipt().budgetDate(), "scope-a", "reviewer");
        modelClient.enqueue(request -> new NewsCurationModels.Succeeded(SCREENING_JSON, 100, 20, 140, 200, "fixture-a", 1));
        var retry = curationService.retryDefinitiveModel(revision, POLICY_V1, "scope-a", selection, failed.receipt().callId());
        assertEquals(2, retry.receipt().attemptNo());
        assertEquals(NewsCurationModels.CallState.SUCCEEDED, retry.receipt().state());
        assertEquals(failed.receipt().logicalCallSha256(), retry.receipt().logicalCallSha256());
        assertEquals(retry.receipt().callId(), curationService.runModel(revision, POLICY_V1, "scope-a", selection).receipt().callId());
        assertThrows(IllegalArgumentException.class, () -> curationService.retryDefinitiveModel(revision, POLICY_V1,
                "scope-a", selection, failed.receipt().callId()));
        assertEquals(2, modelClient.invocations.get());
    }

    private long ingestAndCompleteSingle(String source, String recordId, String title, long configVersion)
            throws Exception {
        String taskId = "task-" + recordId + "-" + configVersion;
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);
        ingestionService.enqueueWindow(taskId, source, configVersion, "ophthalmology", start, end, 3);
        NewsWireModels.Lease lease = ingestionService.claim(WORKER).orElseThrow();
        ingestionService.submitBatch(WORKER, lease.taskId(), lease.generation() + ":1",
                batchPayload(lease.generation(), lease.leaseToken(), 1, List.of(openPubmedMaterial(recordId, title))));
        ingestionService.complete(WORKER, lease.taskId(), lease.generation() + ":complete",
                completePayload(lease.generation(), lease.leaseToken(), 1, 1, end));
        return ingestionService.listVisibleRevisions(source, recordId).getLast().revisionId();
    }

    private byte[] batchPayload(long generation, String token, int seq, List<Map<String, Object>> records)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generation", generation);
        body.put("leaseToken", token);
        body.put("sequence", seq);
        body.put("records", records);
        return mapper.writeValueAsBytes(body);
    }

    private byte[] completePayload(long generation, String token, int batches, int records, LocalDate nextCursor)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generation", generation);
        body.put("leaseToken", token);
        body.put("batchCount", batches);
        body.put("recordCount", records);
        body.put("nextCursor", nextCursor.toString());
        return mapper.writeValueAsBytes(body);
    }

    private static Map<String, Object> openPubmedMaterial(String recordId, String title) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", "pubmed");
        record.put("sourceRecordId", recordId);
        record.put("url", "https://pubmed.ncbi.nlm.nih.gov/42000001/");
        record.put("title", title);
        record.put("publishedOn", "2026-10");
        record.put("abstract", "Open-access multicenter ophthalmic abstract verifying " + title + " in clinical screening.");
        record.put("abstractLicense", "CC-BY");
        return record;
    }

    private static String validAcceptJson(String title, String field) {
        return """
                {
                  "decision": "ACCEPT",
                  "relevanceScore": 88,
                  "suggestedTitle": "%s",
                  "suggestedSummary": "客观报道 %s 相关眼科研究成果，不构成临床诊疗依据。",
                  "category": "眼科临床进展",
                  "limitations": ["仅基于公开摘要数据，未评估长期结局。"],
                  "claims": [
                    {
                      "id": "c1",
                      "field": "%s",
                      "statement": "原文明确记录研究主题 %s。",
                      "quote": "%s"
                    }
                  ]
                }
                """.formatted(title, title, field, title, title);
    }

    private static NewsCurationModels.Succeeded success(String rawJson,
                                                        int promptTokens,
                                                        int completionTokens,
                                                        long actualCostMicros,
                                                        String providerRequestId) {
        return new NewsCurationModels.Succeeded(
                rawJson, promptTokens, completionTokens, actualCostMicros, 200, providerRequestId, 320);
    }

    private static String withSchema(String url, String targetSchema) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String params = query < 0 ? "" : url.substring(query);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + targetSchema + params;
    }

    private static <T> Callable<T> race(CountDownLatch ready, CountDownLatch start, Callable<T> body) {
        return () -> {
            ready.countDown();
            start.await();
            return body.call();
        };
    }

    private static final class ScriptedModelClient implements NewsCurationModelClient {
        private final Deque<NewsCurationModelClient> handlers = new ArrayDeque<>();
        private final AtomicInteger invocations = new AtomicInteger();

        synchronized void enqueue(NewsCurationModelClient handler) {
            handlers.addLast(handler);
        }

        @Override
        public NewsCurationModels.ModelCallOutcome invoke(NewsCurationModels.ModelCallRequest request) {
            invocations.incrementAndGet();
            NewsCurationModelClient handler;
            synchronized (this) {
                handler = handlers.pollFirst();
            }
            if (handler == null) {
                throw new IllegalStateException("Unexpected model invocation without scripted handler");
            }
            return handler.invoke(request);
        }
    }
}
