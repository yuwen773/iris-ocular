package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newsworkbench.NewsWorkbenchModels.RetryTaskRequest;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import com.csust.eyediagnosis.newsworkbench.NewsWorkbenchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动采集调度的真实 PostgreSQL 回归：开关与初始游标闸门、双实例竞争只落一窗一审、
 * 审计写失败回滚整个入队而不影响另一个信源。
 *
 * <p>原套件跑在一次性 MySQL 库上（原类名 {@code NewsCollectionScheduleMySqlIntegrationTest}）。
 * 迁移只改方言：日期算术换成 PG 的 {@code CURRENT_DATE - 5}、时钟换成 {@code now()}、
 * {@code SIGNAL SQLSTATE} 触发器换成 {@link LegacyGateSupport#createFailTriggerLike}（条件仍是
 * 「只让 auto-pubmed- 前缀的任务审计失败」）。断言一条未改。
 *
 * <p>库由 {@code BUSINESS_TEST_URL} 指定（库名必须叫 {@code business_test}）。
 */
class NewsCollectionSchedulePostgresTest {

    private static final String SOURCE = "pubmed";
    private static final NewsWorkerIdentity WORKER = new NewsWorkerIdentity("worker-a", Set.of(SOURCE));
    private static final NewsWireCodec CODEC = new NewsWireCodec(JsonConfig.createNewsProtocolObjectMapper());
    private static JdbcTemplate jdbc;
    private static javax.sql.DataSource dataSource;

    private NewsIngestionProperties ingestionProperties;
    private NewsCollectionScheduleProperties scheduleProperties;
    private NewsIngestionService ingestion;
    private DataSourceTransactionManager manager;
    private NewsCollectionScheduler scheduler;
    private LocalDate initial;

    @BeforeEach
    void setUp() {
        String url = LegacyGateSupport.assumeIsolated(System.getenv("BUSINESS_TEST_URL"), "business_test");
        dataSource = LegacyGateSupport.dataSource(url);
        LegacyGateSupport.ensureSchema(url, dataSource, List.of(), () -> {
            throw new IllegalStateException("BUSINESS_TEST_URL must point at PostgreSQL");
        });
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("t_news_worker_poll", "t_news_collection_task_event", "t_news_worker_receipt",
                "t_news_collection_lease", "t_news_collection_task", "t_news_collection_schedule",
                "t_news_source_cursor")) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.update("UPDATE t_news_source SET enabled = 1");
        initial = jdbc.queryForObject("SELECT CURRENT_DATE - 5",
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
        var secondSource = LegacyGateSupport.dataSource(System.getenv("BUSINESS_TEST_URL"));
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
        LegacyGateSupport.createFailTriggerLike(jdbc, "reject_pubmed_audit", "INSERT",
                "t_news_collection_task_event", "taskId", "auto-pubmed-%", "audit rejected by test");
        try {
            scheduler.tick();
            assertEquals(1, automaticTasks());
            assertEquals("europe-pmc", jdbc.queryForObject(
                    "SELECT source FROM t_news_collection_task", String.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_news_collection_schedule WHERE source = 'pubmed'", Integer.class));
            assertEquals(initial, cursor(1));
        } finally {
            LegacyGateSupport.dropFailTrigger(jdbc, "reject_pubmed_audit", "t_news_collection_task_event");
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
        jdbc.update("UPDATE t_news_collection_schedule SET nextRunAt = now()");
    }

    private String decision() {
        return jdbc.queryForObject("SELECT status FROM t_news_collection_schedule WHERE source = ?", String.class, SOURCE);
    }
}
