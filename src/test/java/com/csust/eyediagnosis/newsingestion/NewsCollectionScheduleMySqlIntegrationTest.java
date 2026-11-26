package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newsworkbench.NewsWorkbenchModels.RetryTaskRequest;
import com.csust.eyediagnosis.newsworkbench.NewsWorkbenchService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsCollectionScheduleMySqlIntegrationTest {

    private static final String SOURCE = "pubmed";
    private static final NewsWorkerIdentity WORKER = new NewsWorkerIdentity("worker-a", Set.of(SOURCE));
    private static final NewsWireCodec CODEC = new NewsWireCodec(JsonConfig.createNewsProtocolObjectMapper());
    private static String schema;
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;

    private NewsIngestionProperties ingestionProperties;
    private NewsCollectionScheduleProperties scheduleProperties;
    private NewsIngestionService ingestion;
    private DataSourceTransactionManager manager;
    private NewsCollectionScheduler scheduler;
    private LocalDate initial;

    @BeforeEach
    void setUp() {
        String url = System.getenv("FLYWAY_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Requires the MySQL L2 harness");
        if (jdbc == null) {
            String username = System.getenv("FLYWAY_TEST_USERNAME");
            String password = System.getenv("FLYWAY_TEST_PASSWORD");
            schema = "diagnosis_ci_news_" + UUID.randomUUID().toString().replace("-", "");
            admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
            admin.execute("CREATE DATABASE `" + schema + "`");
            int params = url.indexOf('?');
            String base = params < 0 ? url : url.substring(0, params);
            String testUrl = base.substring(0, base.lastIndexOf('/') + 1) + schema
                    + (params < 0 ? "" : url.substring(params));
            dataSource = new DriverManagerDataSource(testUrl, username, password);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration", "classpath:db/callback")
                    .load().migrate();
            jdbc = new JdbcTemplate(dataSource);
        }
        for (String table : List.of("t_news_worker_poll", "t_news_collection_task_event", "t_news_worker_receipt",
                "t_news_collection_lease", "t_news_collection_task", "t_news_collection_schedule",
                "t_news_source_cursor")) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.update("UPDATE t_news_source SET enabled = 1");
        initial = jdbc.queryForObject("SELECT DATE_SUB(UTC_DATE(), INTERVAL 5 DAY)",
                (rs, row) -> rs.getObject(1, LocalDate.class));
        ingestionProperties = new NewsIngestionProperties();
        ingestionProperties.setEnabled(true);
        scheduleProperties = new NewsCollectionScheduleProperties();
        scheduleProperties.setEnabled(true);
        setPolicy(1);
        manager = new DataSourceTransactionManager(dataSource);
        ingestion = new NewsIngestionService(jdbc, manager, ingestionProperties, CODEC);
        scheduler = new NewsCollectionScheduler(jdbc, manager, ingestionProperties, scheduleProperties, ingestion);
    }

    @AfterAll
    static void dropSchema() {
        if (admin != null && schema != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    @Test
    void switchesAndMissingInitialCursorGateEnqueueIncludingAfterReEnable() {
        ingestion.registerSourceConfig(SOURCE, 1, "glaucoma", null);
        scheduler.tick();
        assertEquals("INITIAL_CURSOR_REQUIRED", decision());
        assertEquals(0, automaticTasks());
        initialize();
        ingestionProperties.setEnabled(false);
        makeDue();
        scheduler.tick();
        ingestionProperties.setEnabled(true);
        scheduleProperties.setEnabled(false);
        scheduler.tick();
        scheduleProperties.setEnabled(true);
        ingestion.setSourceEnabled(SOURCE, false);
        scheduler.tick();
        assertEquals(0, automaticTasks());
        ingestion.setSourceEnabled(SOURCE, true);
        scheduleProperties.setSources(List.of(new NewsCollectionScheduleProperties.SourcePolicy(
                SOURCE, false, 1, 1, 2, 1, 1, 30)));
        scheduler.tick();
        assertEquals(0, automaticTasks());
        setPolicy(1);
        scheduler.tick();
        assertEquals(1, automaticTasks());
        assertEquals(initial, cursor(1));
    }

    @Test
    void twoInstancesAndRestartCreateOneWindowAndOneAudit() throws Exception {
        initialize();
        DriverManagerDataSource secondSource = new DriverManagerDataSource(
                dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
        var secondJdbc = new JdbcTemplate(secondSource);
        var secondManager = new DataSourceTransactionManager(secondSource);
        var secondIngestion = new NewsIngestionService(secondJdbc, secondManager, ingestionProperties, CODEC);
        var second = new NewsCollectionScheduler(secondJdbc, secondManager,
                ingestionProperties, scheduleProperties, secondIngestion);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); scheduler.tick(); return null; });
            var other = pool.submit(() -> { start.await(); second.tick(); return null; });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            other.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, automaticTasks());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_collection_task_event WHERE action = 'AUTO_ENQUEUE'", Integer.class));
        makeDue();
        second.tick();
        assertEquals("WAITING_FOR_TASK", decision());
        assertEquals(1, automaticTasks());
        assertEquals(initial, cursor(1));
    }

    @Test
    void completeIsTheOnlyProgressEventAndNextWindowUsesOverlap() {
        initialize();
        scheduler.tick();
        var lease = ingestion.claim(WORKER).orElseThrow();
        assertTrue(ingestion.claim(new NewsWorkerIdentity("worker-b", Set.of(SOURCE))).isEmpty());
        assertEquals(initial, cursor(1));
        assertEquals(lease.taskId(), jdbc.queryForObject(
                "SELECT lastTaskId FROM t_news_worker_poll WHERE workerId = 'worker-a'", String.class));
        assertEquals(initial, lease.window().startOn());
        assertEquals(initial.plusDays(2), lease.window().endBefore());
        complete(lease);
        assertEquals(initial.plusDays(2), cursor(1));
        makeDue();
        scheduler.tick();
        var next = ingestion.claim(WORKER).orElseThrow();
        assertEquals(initial.plusDays(1), next.window().startOn());
        assertEquals(initial.plusDays(4), next.window().endBefore());
        assertFalse(lease.taskId().equals(next.taskId()));
        complete(next);
        makeDue();
        scheduler.tick();
        assertEquals("CAUGHT_UP", decision());
        assertEquals(2, automaticTasks());
        var status = new NewsCollectionScheduleStatusService(jdbc, ingestionProperties, scheduleProperties).status();
        var pubmed = status.sources().stream().filter(source -> SOURCE.equals(source.source())).findFirst().orElseThrow();
        assertEquals(initial.plusDays(4).toString(), pubmed.committedCursor());
        assertEquals(initial.plusDays(1).toString(), pubmed.lastCompletedStartOn());
        assertEquals(0, pubmed.lastCompletedRecordCount());
        assertEquals(0, pubmed.backlog());
        assertEquals(next.taskId(), status.workers().getFirst().lastTaskId());
    }

    @Test
    void failedWindowBlocksPlanningAcrossVersionsUntilSameIdentityIsRetried() throws Exception {
        initialize();
        scheduler.tick();
        var lease = ingestion.claim(WORKER).orElseThrow();
        ingestion.fail(WORKER, lease.taskId(), "1:fail", """
                {"generation":1,"leaseToken":"%s","code":"WINDOW_TOO_LARGE","retryable":false}
                """.formatted(lease.leaseToken()).getBytes(StandardCharsets.UTF_8));
        makeDue();
        scheduler.tick();
        assertEquals("FAILED_WINDOW", decision());
        assertEquals(initial, cursor(1));
        ingestion.registerSourceConfig(SOURCE, 2, "glaucoma", initial.plusDays(2));
        setPolicy(2);
        scheduler.tick();
        assertEquals("FAILED_WINDOW", decision());
        makeDue();
        var workbench = new NewsWorkbenchService(jdbc, manager, ingestion, null, null,
                JsonConfig.createNewsProtocolObjectMapper());
        User adminUser = new User();
        adminUser.setId(17L);
        adminUser.setUserRole(AuthConstant.ADMIN_ROLE);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var plan = pool.submit(() -> { start.await(); scheduler.tick(); return null; });
            var reopen = pool.submit(() -> {
                start.await();
                return workbench.retryFailedTask(lease.taskId(), new RetryTaskRequest(1, "operator retry"), adminUser);
            });
            start.countDown();
            plan.get(15, TimeUnit.SECONDS);
            assertEquals("PENDING", reopen.get(15, TimeUnit.SECONDS).status());
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_collection_task_event WHERE action = 'RETRY'", Integer.class));
        assertEquals(1, automaticTasks());
        var retry = ingestion.claim(WORKER).orElseThrow();
        assertEquals(lease.taskId(), retry.taskId());
        assertEquals(2, retry.generation());
        assertEquals(initial, cursor(1));
        complete(retry);
        makeDue();
        scheduler.tick();
        assertEquals("QUEUED", decision());
        assertEquals(2, automaticTasks());
        setPolicy(1);
        makeDue();
        scheduler.tick();
        assertEquals(2, automaticTasks());
    }

    @Test
    void onlyHistoricalManualReplayCanCoexistWithAnAdoptedVersion() {
        initialize();
        scheduler.tick();
        var automatic = ingestion.claim(WORKER).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> ingestion.enqueueWindow(
                "human-future", SOURCE, 1, "glaucoma", initial, initial.plusDays(1), 1));
        assertThrows(IllegalArgumentException.class, () -> ingestion.enqueueWindow(
                "auto-reserved", SOURCE, 1, "glaucoma", initial.minusDays(1), initial, 1));
        ingestion.enqueueWindow("human-history", SOURCE, 1, "glaucoma", initial.minusDays(1), initial, 1);
        complete(automatic);
        assertEquals(initial.plusDays(2), cursor(1));
        var history = ingestion.claim(WORKER).orElseThrow();
        assertEquals("human-history", history.taskId());
        complete(history);
        assertEquals(initial.plusDays(2), cursor(1));
        assertEquals(1, automaticTasks());
    }

    @Test
    void aFailedAuditWriteRollsBackEnqueueWhileAnotherSourceStillPlans() {
        initialize();
        ingestion.registerSourceConfig("europe-pmc", 1, "glaucoma", initial);
        scheduleProperties.setSources(List.of(policy(SOURCE, 1), policy("europe-pmc", 1)));
        jdbc.execute("""
                CREATE TRIGGER reject_pubmed_audit BEFORE INSERT ON t_news_collection_task_event
                FOR EACH ROW BEGIN
                    IF NEW.taskId LIKE 'auto-pubmed-%' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit rejected by test';
                    END IF;
                END
                """);
        try {
            scheduler.tick();
            assertEquals(1, automaticTasks());
            assertEquals("europe-pmc", jdbc.queryForObject(
                    "SELECT source FROM t_news_collection_task", String.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_news_collection_schedule WHERE source = 'pubmed'", Integer.class));
            assertEquals(initial, cursor(1));
        } finally {
            jdbc.execute("DROP TRIGGER reject_pubmed_audit");
        }
        scheduler.tick();
        assertEquals(2, automaticTasks());
    }

    private void complete(NewsWireModels.Lease lease) {
        byte[] body = """
                {"generation":%d,"leaseToken":"%s","batchCount":0,"recordCount":0,"nextCursor":"%s"}
                """.formatted(lease.generation(), lease.leaseToken(), lease.window().endBefore())
                .getBytes(StandardCharsets.UTF_8);
        ingestion.complete(WORKER, lease.taskId(), lease.generation() + ":complete", body);
    }

    private void initialize() {
        ingestion.registerSourceConfig(SOURCE, 1, "glaucoma", initial);
    }

    private void setPolicy(long version) {
        scheduleProperties.setSources(List.of(policy(SOURCE, version)));
    }

    private NewsCollectionScheduleProperties.SourcePolicy policy(String source, long version) {
        return new NewsCollectionScheduleProperties.SourcePolicy(source, true, version, 1, 2, 1, 1, 30);
    }

    private LocalDate cursor(long version) {
        return jdbc.queryForObject("SELECT committedCursor FROM t_news_source_cursor WHERE source = ? AND sourceConfigVersion = ?",
                (rs, row) -> rs.getObject(1, LocalDate.class), SOURCE, version);
    }

    private int automaticTasks() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_news_collection_task WHERE taskType = 'AUTO'", Integer.class);
    }

    private void makeDue() {
        jdbc.update("UPDATE t_news_collection_schedule SET nextRunAt = UTC_TIMESTAMP(6)");
    }

    private String decision() {
        return jdbc.queryForObject("SELECT status FROM t_news_collection_schedule WHERE source = ?", String.class, SOURCE);
    }
}
