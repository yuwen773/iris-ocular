package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.config.SameOriginFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L2 real-MySQL integration suite for WP-02 News Engine scheduling, lease fencing,
 * idempotent receipts, cursor arbitration, atomic rollback, and material pool promotion.
 *
 * <p>Requires {@code FLYWAY_TEST_URL} / {@code FLYWAY_TEST_USERNAME} / {@code FLYWAY_TEST_PASSWORD}.
 * Keeps {@code Assumptions.assumeTrue} inside {@code @BeforeEach} so Surefire records exact
 * case/skip counts matching {@code scripts/check_surefire_skips.py} (13 cases).
 */
class NewsIngestionMySqlIntegrationTest {

    private static final DateTimeFormatter DB_UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static final NewsWorkerIdentity WORKER_A =
            new NewsWorkerIdentity("worker-a", NewsIngestionProperties.ALL_SOURCES);
    private static final NewsWorkerIdentity WORKER_B =
            new NewsWorkerIdentity("worker-b", NewsIngestionProperties.ALL_SOURCES);

    private static String schema;
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;

    private ObjectMapper mapper;
    private NewsWireCodec codec;
    private NewsIngestionProperties properties;
    private NewsIngestionService service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("FLYWAY_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set FLYWAY_TEST_URL to run the real MySQL News Engine gate");
        ensureDatabase();

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
        codec = new NewsWireCodec(mapper);
        properties = new NewsIngestionProperties();
        properties.setEnabled(true);
        properties.setLeaseDuration(Duration.ofMinutes(5));
        properties.setRetryBaseDelay(Duration.ofSeconds(30));
        properties.setRetryMaxDelay(Duration.ofMinutes(15));
        service = new NewsIngestionService(jdbc, properties, codec);
    }

    private static synchronized void ensureDatabase() {
        if (jdbc != null) {
            return;
        }
        String url = System.getenv("FLYWAY_TEST_URL");
        String username = System.getenv("FLYWAY_TEST_USERNAME");
        String password = System.getenv("FLYWAY_TEST_PASSWORD");
        schema = "diagnosis_ci_news_" + UUID.randomUUID().toString().replace("-", "");

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
    void migrationCreatesPoolTablesAndSeedsSources() {
        List<String> expectedTables = List.of(
                "t_news_source", "t_news_source_cursor", "t_news_collection_task",
                "t_news_collection_lease", "t_news_collection_batch", "t_news_batch_item",
                "t_news_worker_receipt", "t_news_material", "t_news_material_revision"
        );
        for (String table : expectedTables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class, schema, table);
            assertEquals(1, count, "Missing V39 table: " + table);
        }
        List<String> sources = jdbc.queryForList(
                "SELECT source FROM t_news_source WHERE enabled = 1 ORDER BY source ASC", String.class);
        assertEquals(List.of("clinicaltrials-gov", "europe-pmc", "pubmed"), sources);
    }

    // Case 2
    @Test
    void concurrentClaimGrantsLeaseToExactlyOneWorker() throws Exception {
        service.enqueueWindow("task-race-1", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Optional<NewsWireModels.Lease>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Optional<NewsWireModels.Lease>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                NewsWorkerIdentity worker = new NewsWorkerIdentity("race-worker-" + i, Set.of("pubmed"));
                futures.add(pool.submit(race(ready, start, () -> service.claim(worker))));
            }
            ready.await();
            start.countDown();
            for (Future<Optional<NewsWireModels.Lease>> f : futures) {
                results.add(f.get());
            }
        }

        List<NewsWireModels.Lease> granted = results.stream().flatMap(Optional::stream).toList();
        assertEquals(1, granted.size(), "Exactly one worker must win the lease");
        assertEquals("task-race-1", granted.getFirst().taskId());
        assertEquals(1L, granted.getFirst().generation());

        NewsIngestionService.TaskSnapshot task = service.getTask("task-race-1").orElseThrow();
        assertEquals("LEASED", task.status());
        assertEquals(1L, task.generation());
        assertEquals(1, task.attemptCount());
    }

    // Case 3
    @Test
    void concurrentClaimAcrossDistinctSourcesRunsInParallel() throws Exception {
        service.enqueueWindow("task-pm-1", "pubmed", 1L, "retinal",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        service.enqueueWindow("task-epmc-1", "europe-pmc", 1L, "retinal",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);

        NewsWorkerIdentity pmWorker = new NewsWorkerIdentity("w-pm", Set.of("pubmed"));
        NewsWorkerIdentity epmcWorker = new NewsWorkerIdentity("w-epmc", Set.of("europe-pmc"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Optional<NewsWireModels.Lease> pmLease;
        Optional<NewsWireModels.Lease> epmcLease;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<NewsWireModels.Lease>> f1 = pool.submit(race(ready, start, () -> service.claim(pmWorker)));
            Future<Optional<NewsWireModels.Lease>> f2 = pool.submit(race(ready, start, () -> service.claim(epmcWorker)));
            ready.await();
            start.countDown();
            pmLease = f1.get();
            epmcLease = f2.get();
        }

        assertTrue(pmLease.isPresent());
        assertTrue(epmcLease.isPresent());
        assertEquals("pubmed", pmLease.get().window().source());
        assertEquals("europe-pmc", epmcLease.get().window().source());
    }

    // Case 4
    @Test
    void singleSourceParallelWindowsSerializeClaimAndPreventCursorLeapfrog() throws Exception {
        service.enqueueWindow("task-w1", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        service.enqueueWindow("task-w2", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), 3);

        NewsWireModels.Lease w1Gen1 = service.claim(WORKER_A).orElseThrow();
        assertEquals("task-w1", w1Gen1.taskId());
        assertEquals(1L, w1Gen1.generation());

        // W2 cannot be claimed while W1 is leased.
        assertTrue(service.claim(WORKER_B).isEmpty());

        // Retryable failure puts W1 into future backoff; W2 still MUST NOT leapfrog W1!
        String failReceipt = service.fail(WORKER_A, "task-w1", "1:fail",
                failJson(w1Gen1.generation(), w1Gen1.leaseToken(), "SOURCE_UNAVAILABLE", true));
        assertEquals("RETRY_SCHEDULED", mapper.readTree(failReceipt).get("status").textValue());
        assertTrue(service.claim(WORKER_B).isEmpty(), "Later window W2 must not leapfrog retry-scheduled W1");

        // Make W1 eligible again; Worker B claims W1 gen 2 and completes empty window.
        jdbc.update("UPDATE t_news_collection_task SET nextAttemptAt = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE taskId = 'task-w1'");
        NewsWireModels.Lease w1Gen2 = service.claim(WORKER_B).orElseThrow();
        assertEquals("task-w1", w1Gen2.taskId());
        assertEquals(2L, w1Gen2.generation());
        service.complete(WORKER_B, "task-w1", "2:complete",
                completeJson(2L, w1Gen2.leaseToken(), 0, 0, LocalDate.of(2026, 10, 8)));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 8)), service.getCommittedCursor("pubmed", 1L));

        // Now W2 is unlocked and advances cursor to 2026-10-15.
        NewsWireModels.Lease w2Gen1 = service.claim(WORKER_B).orElseThrow();
        assertEquals("task-w2", w2Gen1.taskId());
        service.complete(WORKER_B, "task-w2", "1:complete",
                completeJson(1L, w2Gen1.leaseToken(), 0, 0, LocalDate.of(2026, 10, 15)));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("pubmed", 1L));
    }

    // Case 5
    @Test
    void outOfOrderWindowCannotAdvanceCursorAcrossGapAndBackfillNeverRegressesCursor() throws Exception {
        service.enqueueWindow("task-v1-base", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 3);
        NewsWireModels.Lease base = service.claim(WORKER_A).orElseThrow();
        service.complete(WORKER_A, base.taskId(), "1:complete",
                completeJson(1L, base.leaseToken(), 0, 0, LocalDate.of(2026, 10, 15)));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("pubmed", 1L));

        // Gap window [2026-10-20, 2026-10-30) skips [2026-10-15, 2026-10-20) -> complete must fail.
        service.enqueueWindow("task-v1-gap", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 30), 3);
        NewsWireModels.Lease gap = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, gap.taskId(), "1:1",
                batchJson(1L, gap.leaseToken(), 1, List.of(pubmedMaterial("PMID-GAP", "Gap article"))));
        NewsControlException gapEx = assertThrows(NewsControlException.class, () ->
                service.complete(WORKER_A, gap.taskId(), "1:complete",
                        completeJson(1L, gap.leaseToken(), 1, 1, LocalDate.of(2026, 10, 30))));
        assertEquals(HttpStatus.CONFLICT, gapEx.getStatus());
        assertEquals("STALE_LEASE", gapEx.getCode());
        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("pubmed", 1L));
        assertTrue(service.listVisibleMaterials("pubmed").isEmpty());

        // Mark gap window non-retryable failed to free source slot.
        service.fail(WORKER_A, gap.taskId(), "1:fail",
                failJson(1L, gap.leaseToken(), "CONTROL_REJECTED", false));

        // Backfill window [2026-09-01, 2026-09-15) must promote records WITHOUT regressing committedCursor.
        service.enqueueWindow("task-v1-backfill", "pubmed", 1L, "glaucoma",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), 3);
        NewsWireModels.Lease backfill = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, backfill.taskId(), "1:1",
                batchJson(1L, backfill.leaseToken(), 1, List.of(pubmedMaterial("PMID-BF", "Backfill article"))));
        String bfReceipt = service.complete(WORKER_A, backfill.taskId(), "1:complete",
                completeJson(1L, backfill.leaseToken(), 1, 1, LocalDate.of(2026, 9, 15)));
        assertEquals("2026-09-15", mapper.readTree(bfReceipt).get("committedCursor").textValue());
        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("pubmed", 1L));
        assertEquals(1, service.listVisibleMaterials("pubmed").size());

        // Config version isolation: (pubmed, v2) has its own independent cursor starting from null.
        service.enqueueWindow("task-v2-first", "pubmed", 2L, "glaucoma AND ai",
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 30), 3);
        NewsWireModels.Lease v2 = service.claim(WORKER_A).orElseThrow();
        assertEquals("task-v2-first", v2.taskId());
        assertEquals(2L, v2.window().sourceConfigVersion());
        service.complete(WORKER_A, v2.taskId(), "1:complete",
                completeJson(1L, v2.leaseToken(), 0, 0, LocalDate.of(2026, 10, 30)));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 30)), service.getCommittedCursor("pubmed", 2L));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("pubmed", 1L));
    }

    // Case 6
    @Test
    void partialBatchesStayInvisibleUntilAtomicCompleteAndCrashRecoveryReclaims() throws Exception {
        service.enqueueWindow("task-crash-1", "clinicaltrials-gov", 1L, "glaucoma",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 3);

        NewsWireModels.Lease gen1 = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, gen1.taskId(), "1:1",
                batchJson(1L, gen1.leaseToken(), 1, List.of(trialMaterial("NCT00000001", "Gen 1 staged only"))));

        // Staged batch must remain completely invisible to editorial queries.
        assertTrue(service.listVisibleMaterials("clinicaltrials-gov").isEmpty());
        assertEquals(0L, service.countVisibleRevisions());

        // Simulate worker A crash via lease expiration in DB time.
        jdbc.update("UPDATE t_news_collection_task SET leaseExpiresAt = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE taskId = 'task-crash-1'");

        NewsWireModels.Lease gen2 = service.claim(WORKER_B).orElseThrow();
        assertEquals(2L, gen2.generation());
        assertNotEquals(gen1.leaseToken(), gen2.leaseToken());

        // Stale gen 1 writes and completion must fail with 409 STALE_LEASE.
        NewsControlException staleBatch = assertThrows(NewsControlException.class, () ->
                service.submitBatch(WORKER_A, gen1.taskId(), "1:2",
                        batchJson(1L, gen1.leaseToken(), 2, List.of(trialMaterial("NCT00000099", "Late gen 1")))));
        assertEquals("STALE_LEASE", staleBatch.getCode());

        NewsControlException staleComplete = assertThrows(NewsControlException.class, () ->
                service.complete(WORKER_A, gen1.taskId(), "1:complete",
                        completeJson(1L, gen1.leaseToken(), 1, 1, LocalDate.of(2026, 10, 15))));
        assertEquals("STALE_LEASE", staleComplete.getCode());

        // Worker B completes gen 2 -> ONLY gen 2 staged records are promoted.
        service.submitBatch(WORKER_B, gen2.taskId(), "2:1",
                batchJson(2L, gen2.leaseToken(), 1, List.of(trialMaterial("NCT00000002", "Gen 2 committed"))));
        service.complete(WORKER_B, gen2.taskId(), "2:complete",
                completeJson(2L, gen2.leaseToken(), 1, 1, LocalDate.of(2026, 10, 15)));

        List<NewsIngestionService.VisibleMaterial> visible = service.listVisibleMaterials("clinicaltrials-gov");
        assertEquals(1, visible.size());
        assertEquals("NCT00000002", visible.getFirst().sourceRecordId());
        assertTrue(service.listVisibleRevisions("clinicaltrials-gov", "NCT00000001").isEmpty());
    }

    // Case 7
    @Test
    void idempotentReplayWorksAfterCompletionAndExpiryWhileConflictAndForeignWorkerFail() throws Exception {
        service.enqueueWindow("task-idem-1", "pubmed", 1L, "macular",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        NewsWireModels.Lease lease = service.claim(WORKER_A).orElseThrow();
        byte[] batchBody = batchJson(1L, lease.leaseToken(), 1, List.of(pubmedMaterial("PMID-1", "Macular OCT")));

        // 4 concurrent identical retries of batch 1:1 must all succeed with identical receipt and 1 DB row.
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(race(ready, start, () ->
                        service.submitBatch(WORKER_A, lease.taskId(), "1:1", batchBody))));
            }
            ready.await();
            start.countDown();
            for (Future<String> f : futures) {
                responses.add(f.get());
            }
        }
        assertEquals(4, responses.size());
        for (String resp : responses) {
            assertEquals("{\"sequence\":1,\"recordCount\":1}", resp);
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_collection_batch WHERE taskId = 'task-idem-1'", Integer.class));

        // Same key with mutated payload -> 409 IDEMPOTENCY_CONFLICT.
        byte[] mutatedBatch = batchJson(1L, lease.leaseToken(), 1, List.of(pubmedMaterial("PMID-1", "Mutated title")));
        NewsControlException conflict = assertThrows(NewsControlException.class, () ->
                service.submitBatch(WORKER_A, lease.taskId(), "1:1", mutatedBatch));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // Foreign worker replaying Worker A's idempotency key -> 403 FORBIDDEN.
        NewsControlException foreignBatch = assertThrows(NewsControlException.class, () ->
                service.submitBatch(WORKER_B, lease.taskId(), "1:1", batchBody));
        assertEquals(HttpStatus.FORBIDDEN, foreignBatch.getStatus());
        assertEquals("FORBIDDEN", foreignBatch.getCode());

        // Complete, then expire lease timestamp, and verify both batch and complete replay identically.
        byte[] completeBody = completeJson(1L, lease.leaseToken(), 1, 1, LocalDate.of(2026, 10, 8));
        String completeResp = service.complete(WORKER_A, lease.taskId(), "1:complete", completeBody);
        assertEquals("{\"committedCursor\":\"2026-10-08\"}", completeResp);

        jdbc.update("UPDATE t_news_collection_task SET leaseExpiresAt = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE) WHERE taskId = 'task-idem-1'");
        assertEquals("{\"sequence\":1,\"recordCount\":1}",
                service.submitBatch(WORKER_A, lease.taskId(), "1:1", batchBody));
        assertEquals(completeResp,
                service.complete(WORKER_A, lease.taskId(), "1:complete", completeBody));

        // Foreign worker replaying complete -> 403; mutated complete -> 409 IDEMPOTENCY_CONFLICT.
        NewsControlException foreignComplete = assertThrows(NewsControlException.class, () ->
                service.complete(WORKER_B, lease.taskId(), "1:complete", completeBody));
        assertEquals("FORBIDDEN", foreignComplete.getCode());

        byte[] mutatedComplete = completeJson(1L, lease.leaseToken(), 1, 2, LocalDate.of(2026, 10, 8));
        NewsControlException completeConflict = assertThrows(NewsControlException.class, () ->
                service.complete(WORKER_A, lease.taskId(), "1:complete", mutatedComplete));
        assertEquals("IDEMPOTENCY_CONFLICT", completeConflict.getCode());
    }

    // Case 8
    @Test
    void revisionUniquenessDeduplicatesIdenticalContentAndPreservesDistinctRevisions() throws Exception {
        Map<String, Object> v1Record = pubmedMaterial("PMID-1001", "Original DR study");

        // Window 1: identical record appears twice across two batches -> deduped to 1 revision.
        service.enqueueWindow("task-rev-1", "pubmed", 1L, "retina",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        NewsWireModels.Lease l1 = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, l1.taskId(), "1:1", batchJson(1L, l1.leaseToken(), 1, List.of(v1Record)));
        service.submitBatch(WORKER_A, l1.taskId(), "1:2", batchJson(1L, l1.leaseToken(), 2, List.of(v1Record)));
        service.complete(WORKER_A, l1.taskId(), "1:complete",
                completeJson(1L, l1.leaseToken(), 2, 2, LocalDate.of(2026, 10, 8)));

        assertEquals(1, service.listVisibleMaterials("pubmed").size());
        List<NewsIngestionService.VisibleRevision> afterW1 = service.listVisibleRevisions("pubmed", "PMID-1001");
        assertEquals(1, afterW1.size());
        assertEquals("task-rev-1", afterW1.getFirst().firstTaskId());

        // Window 2: exact same record again -> still 1 immutable revision.
        service.enqueueWindow("task-rev-2", "pubmed", 1L, "retina",
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), 3);
        NewsWireModels.Lease l2 = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, l2.taskId(), "1:1", batchJson(1L, l2.leaseToken(), 1, List.of(v1Record)));
        service.complete(WORKER_A, l2.taskId(), "1:complete",
                completeJson(1L, l2.leaseToken(), 1, 1, LocalDate.of(2026, 10, 15)));

        assertEquals(1, service.listVisibleRevisions("pubmed", "PMID-1001").size());

        // Window 3: updated title -> 2nd immutable revision under the SAME t_news_material row.
        Map<String, Object> v2Record = pubmedMaterial("PMID-1001", "Revised DR study (erratum)");
        service.enqueueWindow("task-rev-3", "pubmed", 1L, "retina",
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 22), 3);
        NewsWireModels.Lease l3 = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, l3.taskId(), "1:1", batchJson(1L, l3.leaseToken(), 1, List.of(v2Record)));
        service.complete(WORKER_A, l3.taskId(), "1:complete",
                completeJson(1L, l3.leaseToken(), 1, 1, LocalDate.of(2026, 10, 22)));

        List<NewsIngestionService.VisibleMaterial> materials = service.listVisibleMaterials("pubmed");
        assertEquals(1, materials.size());
        List<NewsIngestionService.VisibleRevision> revisions = service.listVisibleRevisions("pubmed", "PMID-1001");
        assertEquals(2, revisions.size());
        assertEquals("Original DR study", revisions.get(0).title());
        assertEquals("Revised DR study (erratum)", revisions.get(1).title());
        assertEquals(revisions.get(1).revisionId(), materials.getFirst().latestRevisionId());
        assertEquals(revisions.get(1).contentDigest(), materials.getFirst().latestContentDigest());
        assertFalse(materials.getFirst().lastSeenAt().isBefore(materials.getFirst().firstSeenAt()));
    }

    // Case 9
    @Test
    void incompleteBatchesAndCursorMismatchRejectWithoutPublishingOrAdvancingCursor() throws Exception {
        service.enqueueWindow("task-inc-1", "pubmed", 1L, "cataract",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        NewsWireModels.Lease l1 = service.claim(WORKER_A).orElseThrow();

        // Submit sequences 1 and 3 (missing sequence 2).
        service.submitBatch(WORKER_A, l1.taskId(), "1:1",
                batchJson(1L, l1.leaseToken(), 1, List.of(pubmedMaterial("PMID-A", "A"))));
        service.submitBatch(WORKER_A, l1.taskId(), "1:3",
                batchJson(1L, l1.leaseToken(), 3, List.of(pubmedMaterial("PMID-C", "C"))));

        // Sequence gap -> INCOMPLETE_BATCHES
        assertIncomplete(() -> service.complete(WORKER_A, l1.taskId(), "1:complete",
                completeJson(1L, l1.leaseToken(), 2, 2, LocalDate.of(2026, 10, 8))));
        // Batch count mismatch -> INCOMPLETE_BATCHES
        assertIncomplete(() -> service.complete(WORKER_A, l1.taskId(), "1:complete",
                completeJson(1L, l1.leaseToken(), 3, 3, LocalDate.of(2026, 10, 8))));

        // Fill sequence 2; recordCount mismatch and nextCursor != windowEndBefore must still reject.
        service.submitBatch(WORKER_A, l1.taskId(), "1:2",
                batchJson(1L, l1.leaseToken(), 2, List.of(pubmedMaterial("PMID-B", "B"))));
        assertIncomplete(() -> service.complete(WORKER_A, l1.taskId(), "1:complete",
                completeJson(1L, l1.leaseToken(), 3, 4, LocalDate.of(2026, 10, 8))));
        assertIncomplete(() -> service.complete(WORKER_A, l1.taskId(), "1:complete",
                completeJson(1L, l1.leaseToken(), 3, 3, LocalDate.of(2026, 10, 7))));

        assertTrue(service.listVisibleMaterials("pubmed").isEmpty());
        assertTrue(service.getCommittedCursor("pubmed", 1L).isEmpty());

        // Exact counts + exact cursor succeed.
        service.complete(WORKER_A, l1.taskId(), "1:complete",
                completeJson(1L, l1.leaseToken(), 3, 3, LocalDate.of(2026, 10, 8)));
        assertEquals(3, service.listVisibleMaterials("pubmed").size());
        assertEquals(Optional.of(LocalDate.of(2026, 10, 8)), service.getCommittedCursor("pubmed", 1L));

        // Empty window (0 batches, 0 records) is valid and advances cursor.
        service.enqueueWindow("task-inc-2", "pubmed", 1L, "cataract",
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15), 3);
        NewsWireModels.Lease l2 = service.claim(WORKER_A).orElseThrow();
        service.complete(WORKER_A, l2.taskId(), "1:complete",
                completeJson(1L, l2.leaseToken(), 0, 0, LocalDate.of(2026, 10, 15)));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("pubmed", 1L));
    }

    // Case 10
    @Test
    void completeTransactionRollsBackAtomicallyOnCursorWriteFailure() throws Exception {
        service.enqueueWindow("task-rb-1", "pubmed", 1L, "myopia",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        NewsWireModels.Lease lease = service.claim(WORKER_A).orElseThrow();
        service.submitBatch(WORKER_A, lease.taskId(), "1:1",
                batchJson(1L, lease.leaseToken(), 1, List.of(pubmedMaterial("PMID-RB1", "Rollback test"))));

        byte[] completeBody = completeJson(1L, lease.leaseToken(), 1, 1, LocalDate.of(2026, 10, 8));
        jdbc.execute("""
                CREATE TRIGGER trg_fail_news_cursor_update
                BEFORE UPDATE ON t_news_source_cursor
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected cursor failure'
                """);
        try {
            assertThrows(DataAccessException.class, () ->
                    service.complete(WORKER_A, lease.taskId(), "1:complete", completeBody));

            // Material promotion ran BEFORE cursor update and MUST have rolled back cleanly.
            assertTrue(service.listVisibleMaterials("pubmed").isEmpty());
            assertEquals(0L, service.countVisibleRevisions());
            assertTrue(service.getCommittedCursor("pubmed", 1L).isEmpty());
            NewsIngestionService.TaskSnapshot task = service.getTask("task-rb-1").orElseThrow();
            assertEquals("LEASED", task.status());
            assertNull(task.completedAt());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_news_worker_receipt WHERE taskId = 'task-rb-1' AND action = 'complete'",
                    Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_news_cursor_update");
        }

        // Retrying the exact same request after fault clears commits everything cleanly.
        String receipt = service.complete(WORKER_A, lease.taskId(), "1:complete", completeBody);
        assertEquals("{\"committedCursor\":\"2026-10-08\"}", receipt);
        assertEquals(1, service.listVisibleMaterials("pubmed").size());
        assertEquals(1L, service.countVisibleRevisions());
        assertEquals(Optional.of(LocalDate.of(2026, 10, 8)), service.getCommittedCursor("pubmed", 1L));
        assertEquals("COMPLETED", service.getTask("task-rb-1").orElseThrow().status());
    }

    // Case 11
    @Test
    void javaClockSkewAndJvmTimezoneDoNotBreakLeaseFencing() throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        try {
            properties.setLeaseDuration(Duration.ofSeconds(10));
            service.enqueueWindow("task-tz-1", "pubmed", 1L, "uveitis",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);

            Instant dbBefore = dbUtcNow();
            NewsWireModels.Lease lease = service.claim(WORKER_A).orElseThrow();
            Instant dbAfter = dbUtcNow();

            // Must be true UTC (within DB now + 10s), never shifted by +8h local JVM timezone.
            assertFalse(lease.leaseExpiresAt().isBefore(dbBefore.plusSeconds(9)),
                    "leaseExpiresAt earlier than expected UTC window: " + lease.leaseExpiresAt());
            assertFalse(lease.leaseExpiresAt().isAfter(dbAfter.plusSeconds(11)),
                    "leaseExpiresAt shifted past UTC window (timezone bug): " + lease.leaseExpiresAt());
            JsonNode serialized = mapper.readTree(codec.writeLease(lease));
            assertTrue(serialized.get("leaseExpiresAt").textValue().endsWith("Z"));

            // Force DB-clock lease expiry; fencing must reject based on MySQL UTC_TIMESTAMP(6).
            jdbc.update("UPDATE t_news_collection_task SET leaseExpiresAt = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE taskId = 'task-tz-1'");
            NewsControlException stale = assertThrows(NewsControlException.class, () ->
                    service.submitBatch(WORKER_A, lease.taskId(), "1:1",
                            batchJson(1L, lease.leaseToken(), 1, List.of(pubmedMaterial("PMID-TZ", "Expired")))));
            assertEquals("STALE_LEASE", stale.getCode());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // Case 12
    @Test
    void retryBudgetAndFailureSemanticsPreserveCursorAndEnforceMaxAttempts() throws Exception {
        // Task A: maxAttempts=2, retryable failure schedules retry once then exhausts to FAILED.
        service.enqueueWindow("task-fail-a", "pubmed", 1L, "cornea",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 2);
        NewsWireModels.Lease a1 = service.claim(WORKER_A).orElseThrow();
        String r1 = service.fail(WORKER_A, "task-fail-a", "1:fail",
                failJson(1L, a1.leaseToken(), "SOURCE_UNAVAILABLE", true));
        assertEquals("RETRY_SCHEDULED", mapper.readTree(r1).get("status").textValue());
        assertTrue(service.getCommittedCursor("pubmed", 1L).isEmpty());
        assertTrue(service.claim(WORKER_A).isEmpty(), "Backoff window must delay immediate re-claim");

        jdbc.update("UPDATE t_news_collection_task SET nextAttemptAt = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE taskId = 'task-fail-a'");
        NewsWireModels.Lease a2 = service.claim(WORKER_A).orElseThrow();
        assertEquals(2L, a2.generation());
        String r2 = service.fail(WORKER_A, "task-fail-a", "2:fail",
                failJson(2L, a2.leaseToken(), "SOURCE_UNAVAILABLE", true));
        assertEquals("FAILED", mapper.readTree(r2).get("status").textValue());
        assertEquals("FAILED", service.getTask("task-fail-a").orElseThrow().status());
        assertEquals("SOURCE_UNAVAILABLE", service.getTask("task-fail-a").orElseThrow().lastFailureCode());

        // Task B: maxAttempts=3, non-retryable failure immediately transitions to FAILED.
        service.enqueueWindow("task-fail-b", "europe-pmc", 1L, "cornea",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 3);
        NewsWireModels.Lease b1 = service.claim(WORKER_A).orElseThrow();
        String rb = service.fail(WORKER_A, "task-fail-b", "1:fail",
                failJson(1L, b1.leaseToken(), "BAD_SOURCE_DATA", false));
        assertEquals("FAILED", mapper.readTree(rb).get("status").textValue());
        assertEquals("FAILED", service.getTask("task-fail-b").orElseThrow().status());

        // Task C: maxAttempts=1, expired lease moves to FAILED (DEADLINE_EXCEEDED) on next claim sweep.
        service.enqueueWindow("task-fail-c", "clinicaltrials-gov", 1L, "cornea",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), 1);
        NewsWireModels.Lease c1 = service.claim(WORKER_A).orElseThrow();
        assertEquals("task-fail-c", c1.taskId());
        jdbc.update("UPDATE t_news_collection_task SET leaseExpiresAt = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE taskId = 'task-fail-c'");
        assertTrue(service.claim(WORKER_B).isEmpty());
        NewsIngestionService.TaskSnapshot cSnap = service.getTask("task-fail-c").orElseThrow();
        assertEquals("FAILED", cSnap.status());
        assertEquals("DEADLINE_EXCEEDED", cSnap.lastFailureCode());
    }

    // Case 13
    @Test
    void endToEndHttpWireRoundTripWithRealMySqlAndAuthBoundary() throws Exception {
        String keyA = "mysql-it-worker-a-key-111122223333";
        String keyB = "mysql-it-worker-b-key-444455556666";
        NewsIngestionProperties.WorkerConfig cfgA = new NewsIngestionProperties.WorkerConfig();
        cfgA.setKey(keyA);
        cfgA.setAllowedSources(List.of("clinicaltrials-gov"));
        NewsIngestionProperties.WorkerConfig cfgB = new NewsIngestionProperties.WorkerConfig();
        cfgB.setKey(keyB);
        cfgB.setAllowedSources(List.of("clinicaltrials-gov"));
        properties.setWorkers(Map.of("worker-a", cfgA, "worker-b", cfgB));

        SameOriginFilter sameOriginFilter = new SameOriginFilter();
        ReflectionTestUtils.setField(sameOriginFilter, "additionalHosts", List.of());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NewsWorkerLeaseController(service, codec))
                .setControllerAdvice(new NewsWorkerExceptionHandler(codec))
                .addFilters(new NewsWorkerAuthFilter(properties), sameOriginFilter)
                .build();

        // Auth boundary checks against real stack (no Origin header needed for worker channel).
        mockMvc.perform(post("/api/internal/news/v1/leases/claim").contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHENTICATED\"}", true));
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA)
                        .header("Cookie", "JSESSIONID=forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA)
                        .header("Authorization", "Bearer forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        // Empty queue -> 204
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA))
                .andExpect(status().isNoContent());

        // Enqueue, claim, submit batch, replay batch, foreign reject, complete, verify empty queue.
        service.enqueueWindow("task-http-1", "clinicaltrials-gov", 1L, "glaucoma",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 3);

        MvcResult claimResult = mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode leaseJson = mapper.readTree(claimResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("task-http-1", leaseJson.get("taskId").textValue());
        assertTrue(leaseJson.get("generation").isIntegralNumber());
        assertEquals(1L, leaseJson.get("generation").longValue());
        assertTrue(leaseJson.get("window").get("sourceConfigVersion").isIntegralNumber());
        assertTrue(leaseJson.get("leaseExpiresAt").textValue().endsWith("Z"));
        String token = leaseJson.get("leaseToken").textValue();

        byte[] batchBody = batchJson(1L, token, 1, List.of(trialMaterial("NCT00001234", "HTTP E2E trial")));
        mockMvc.perform(post("/api/internal/news/v1/leases/task-http-1/batches")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA)
                        .header("Idempotency-Key", "1:1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"sequence\":1,\"recordCount\":1}", true));

        // Idempotent replay
        mockMvc.perform(post("/api/internal/news/v1/leases/task-http-1/batches")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA)
                        .header("Idempotency-Key", "1:1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"sequence\":1,\"recordCount\":1}", true));

        // Foreign worker key rejected with 403 FORBIDDEN
        mockMvc.perform(post("/api/internal/news/v1/leases/task-http-1/batches")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyB)
                        .header("Idempotency-Key", "1:1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        // Complete -> 200 CompleteReceipt
        byte[] completeBody = completeJson(1L, token, 1, 1, LocalDate.of(2026, 10, 15));
        mockMvc.perform(post("/api/internal/news/v1/leases/task-http-1/complete")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA)
                        .header("Idempotency-Key", "1:complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"committedCursor\":\"2026-10-15\"}", true));

        assertEquals(Optional.of(LocalDate.of(2026, 10, 15)), service.getCommittedCursor("clinicaltrials-gov", 1L));
        assertEquals(1, service.listVisibleMaterials("clinicaltrials-gov").size());
        assertNotNull(service.listVisibleMaterials("clinicaltrials-gov").getFirst().latestRevisionId());

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, keyA))
                .andExpect(status().isNoContent());
    }

    private Instant dbUtcNow() {
        String text = jdbc.queryForObject(
                "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class);
        return Instant.from(DB_UTC_FORMAT.parse(text));
    }

    private byte[] batchJson(long generation, String leaseToken, int sequence, List<Map<String, Object>> records)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generation", generation);
        payload.put("leaseToken", leaseToken);
        payload.put("sequence", sequence);
        payload.put("records", records);
        return mapper.writeValueAsBytes(payload);
    }

    private byte[] completeJson(long generation, String leaseToken, int batchCount, int recordCount, LocalDate nextCursor)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generation", generation);
        payload.put("leaseToken", leaseToken);
        payload.put("batchCount", batchCount);
        payload.put("recordCount", recordCount);
        payload.put("nextCursor", nextCursor.toString());
        return mapper.writeValueAsBytes(payload);
    }

    private byte[] failJson(long generation, String leaseToken, String code, boolean retryable) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generation", generation);
        payload.put("leaseToken", leaseToken);
        payload.put("code", code);
        payload.put("retryable", retryable);
        return mapper.writeValueAsBytes(payload);
    }

    private static Map<String, Object> pubmedMaterial(String recordId, String title) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", "pubmed");
        record.put("sourceRecordId", recordId);
        record.put("url", "https://pubmed.ncbi.nlm.nih.gov/" + recordId.replaceAll("[^0-9]", "1") + "/");
        record.put("title", title);
        record.put("publishedOn", "2026-10");
        return record;
    }

    private static Map<String, Object> trialMaterial(String nctId, String title) {
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("studyType", "INTERVENTIONAL");
        trial.put("overallStatus", "RECRUITING");
        trial.put("phases", List.of("PHASE2"));
        trial.put("conditions", List.of("Glaucoma"));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", "clinicaltrials-gov");
        record.put("sourceRecordId", nctId);
        record.put("url", "https://clinicaltrials.gov/study/" + nctId);
        record.put("title", title);
        record.put("publishedOn", "2026-09");
        record.put("updatedOn", "2026-10-09");
        record.put("trial", trial);
        return record;
    }

    private static void assertIncomplete(Executable action) {
        NewsControlException ex = assertThrows(NewsControlException.class, action);
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INCOMPLETE_BATCHES", ex.getCode());
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
}
