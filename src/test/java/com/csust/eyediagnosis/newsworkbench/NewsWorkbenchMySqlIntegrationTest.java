package com.csust.eyediagnosis.newsworkbench;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.constant.NewsStatus;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.NewsAuditMapper;
import com.csust.eyediagnosis.mapper.NewsMapper;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newscuration.NewsBenchRunner;
import com.csust.eyediagnosis.newscuration.NewsCurationModelClient;
import com.csust.eyediagnosis.newscuration.NewsCurationModels;
import com.csust.eyediagnosis.newscuration.NewsCurationPromptBuilder;
import com.csust.eyediagnosis.newscuration.NewsCurationProperties;
import com.csust.eyediagnosis.newscuration.NewsCurationService;
import com.csust.eyediagnosis.newscuration.NewsEvidenceVerifier;
import com.csust.eyediagnosis.newsingestion.NewsIngestionProperties;
import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsWireCodec;
import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.csust.eyediagnosis.newsingestion.NewsWorkerIdentity;
import com.csust.eyediagnosis.service.NewsLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 real-MySQL integration suite for WP-05 News Engine Workbench backend services.
 *
 * <p>Keeps {@code Assumptions.assumeTrue} in {@code @BeforeEach} so Surefire records exact
 * case/skip counts matching {@code scripts/check_surefire_skips.py}.
 */
class NewsWorkbenchMySqlIntegrationTest {

    private static final String POLICY_V1 = "news-curation-v1";
    private static final String SCOPE_GLOBAL = "GLOBAL";
    private static final LocalDate BUDGET_DATE = LocalDate.of(2026, 10, 22);
    private static final NewsWorkerIdentity WORKER =
            new NewsWorkerIdentity("workbench-it-worker", NewsIngestionProperties.ALL_SOURCES);

    private static String schema;
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static SqlSessionTemplate sqlSessionTemplate;

    private ObjectMapper mapper;
    private NewsWireCodec wireCodec;
    private NewsIngestionService ingestionService;
    private NewsCurationProperties curationProperties;
    private NewsCurationPromptBuilder promptBuilder;
    private NewsEvidenceVerifier verifier;
    private ScriptedModelClient modelClient;
    private NewsCurationService curationService;
    private NewsLifecycleService lifecycleService;
    private NewsWorkbenchService workbenchService;
    private User adminActor;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("FLYWAY_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set FLYWAY_TEST_URL to run the real MySQL News Workbench gate");
        ensureDatabase();

        jdbc.execute("DELETE FROM t_news_collection_task_event");
        jdbc.execute("DELETE FROM t_news_revision");
        jdbc.execute("DELETE FROM t_news_audit");
        jdbc.execute("DELETE FROM t_news");
        jdbc.execute("DELETE FROM t_news_cache_epoch");
        jdbc.execute("INSERT INTO t_news_cache_epoch (scope, epoch) VALUES ('news_list', 0)");
        jdbc.execute("DELETE FROM t_news_bench_run");
        jdbc.execute("DELETE FROM t_news_curation_candidate");
        jdbc.execute("DELETE FROM t_news_model_call_receipt");
        jdbc.execute("DELETE FROM t_news_curation_budget");
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
        jdbc.execute("DELETE FROM t_user");
        jdbc.execute("""
                INSERT INTO t_user (id, userAccount, userPassword, userName, userRole)
                VALUES (1001, 'admin_wb', 'hash', 'Workbench Admin', 'admin')
                """);

        mapper = JsonConfig.createNewsProtocolObjectMapper();
        wireCodec = new NewsWireCodec(mapper);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        NewsIngestionProperties ingProps = new NewsIngestionProperties();
        ingProps.setEnabled(true);
        ingestionService = new NewsIngestionService(jdbc, txManager, ingProps, wireCodec);

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
                jdbc, txManager, curationProperties, modelClient, verifier, promptBuilder, mapper);

        NewsMapper newsMapper = sqlSessionTemplate.getMapper(NewsMapper.class);
        NewsAuditMapper auditMapper = sqlSessionTemplate.getMapper(NewsAuditMapper.class);
        lifecycleService = new NewsLifecycleService(newsMapper, auditMapper, jdbc, null, mapper);

        workbenchService = new NewsWorkbenchService(
                jdbc, txManager, ingestionService, curationService, curationProperties, mapper);

        adminActor = new User();
        adminActor.setId(1001L);
        adminActor.setUserRole(AuthConstant.ADMIN_ROLE);
        adminActor.setUserName("Workbench Admin");
    }

    @Test
    void overviewAndSourcesReflectRealIngestionCurationAndEditorialState() throws Exception {
        long revId = ingestSingleMaterial(
                "wb-overview-1",
                "pubmed",
                "PMID-WB-1",
                "Myopia Orthokeratology Randomized Trial",
                "CC-BY",
                "A twelve month randomized trial reported reduced axial elongation with orthokeratology."
        );

        String validJson = acceptJson(
                "PMID-WB-1 临床研究：角膜塑形镜控制近视进展随访结果",
                "基于公开摘要，角膜塑形镜组眼轴增长较对照组减缓，仍需长期大样本随访。",
                "A twelve month randomized trial reported reduced axial elongation",
                "abstract"
        );
        modelClient.enqueue(req -> new NewsCurationModels.Succeeded(
                validJson, 500, 180, 3_500L, 200, "req-wb-1", 210));
        curationService.curateRevision(revId, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);

        Long candidateId = jdbc.queryForObject(
                "SELECT id FROM t_news_curation_candidate WHERE materialRevisionId = ?", Long.class, revId);
        assertNotNull(candidateId);
        lifecycleService.createDraftFromCandidate(candidateId, adminActor, true);

        curationProperties.setEnabled(false);
        NewsWorkbenchModels.OverviewVO overview = workbenchService.getOverview();
        assertFalse(overview.curationEnabled());
        assertEquals(3, overview.sources().totalSources());
        assertEquals(3, overview.sources().enabledSources());
        assertEquals(1, overview.tasks().completedCount());
        assertEquals(1, overview.materials().visibleMaterialCount());
        assertEquals(1, overview.materials().visibleRevisionCount());
        assertEquals(1, overview.candidates().acceptedCount());
        assertEquals(1, overview.candidates().draftedNewsCount());
        assertEquals(1, overview.receipts().succeededCount());

        List<NewsWorkbenchModels.SourceDetailVO> sources = workbenchService.listSources();
        assertEquals(3, sources.size());
        NewsWorkbenchModels.SourceDetailVO pubmed = sources.stream()
                .filter(s -> "pubmed".equals(s.source()))
                .findFirst()
                .orElseThrow();
        assertTrue(pubmed.enabled());
        assertEquals(1L, pubmed.visibleMaterialCount());
        assertEquals(1L, pubmed.completedTaskCount());
        assertFalse(pubmed.cursors().isEmpty());
    }

    @Test
    void sourceToggleConfigAndManualEnqueueRespectClaimFencingAndHideTokenHash() throws Exception {
        NewsWorkbenchModels.SourceDetailVO disabled = workbenchService.toggleSource("pubmed", false);
        assertFalse(disabled.enabled());

        NewsWorkbenchModels.SourceDetailVO configured = workbenchService.registerSourceConfig(
                "pubmed",
                new NewsWorkbenchModels.RegisterSourceConfigRequest(2L, "glaucoma[Title/Abstract]", "2026-10-01")
        );
        assertEquals(2L, configured.cursors().getFirst().sourceConfigVersion());
        assertEquals("2026-10-01", configured.cursors().getFirst().committedCursor());

        NewsWorkbenchModels.CollectionTaskVO created = workbenchService.enqueueTask(
                new NewsWorkbenchModels.EnqueueTaskRequest(
                        "wb-manual-task-1",
                        "pubmed",
                        2L,
                        "glaucoma[Title/Abstract]",
                        "2026-10-01",
                        "2026-10-02",
                        3
                ),
                adminActor
        );
        assertEquals("PENDING", created.status());
        assertEquals("wb-manual-task-1", created.taskId());
        var audit = workbenchService.listTaskEvents(created.taskId());
        assertEquals(1, audit.size());
        assertEquals("ENQUEUE", audit.getFirst().action());
        assertEquals("PENDING", audit.getFirst().toStatus());
        assertEquals(1001L, audit.getFirst().actorId());

        BusinessException dupEx = assertThrows(BusinessException.class, () -> workbenchService.enqueueTask(
                new NewsWorkbenchModels.EnqueueTaskRequest(
                        "wb-manual-task-1", "pubmed", 2L, "glaucoma[Title/Abstract]",
                        "2026-10-01", "2026-10-02", 3), null));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), dupEx.getCode());

        NewsWorkerIdentity pubmedWorker = new NewsWorkerIdentity("w-pubmed", java.util.Set.of("pubmed"));
        assertTrue(ingestionService.claim(pubmedWorker).isEmpty());

        workbenchService.toggleSource("pubmed", true);
        NewsWireModels.Lease lease = ingestionService.claim(pubmedWorker).orElseThrow();
        assertEquals("wb-manual-task-1", lease.taskId());

        byte[] completeBody = mapper.writeValueAsBytes(Map.of(
                "generation", lease.generation(),
                "leaseToken", lease.leaseToken(),
                "batchCount", 0,
                "recordCount", 0,
                "nextCursor", "2026-10-02"
        ));
        ingestionService.complete(pubmedWorker, lease.taskId(), lease.generation() + ":complete", completeBody);

        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CollectionTaskVO> completedPage =
                workbenchService.listTasks("pubmed", "COMPLETED", 1, 10);
        assertEquals(1L, completedPage.total());
        assertFalse(completedPage.records().getFirst().leaseExpired());

        List<NewsWorkbenchModels.LeaseRecordVO> leases = workbenchService.listTaskLeases("wb-manual-task-1");
        assertEquals(1, leases.size());
        assertEquals("w-pubmed", leases.getFirst().workerId());
        assertEquals(1L, leases.getFirst().generation());
    }

    @Test
    void auditInsertFailureRollsBackNewTaskAndCursorRegistration() {
        assertTaskAuditInsertFails(() -> workbenchService.enqueueTask(
                new NewsWorkbenchModels.EnqueueTaskRequest(
                        "wb-enqueue-rollback", "pubmed", 51L, "ophthalmology", "2026-10-01", "2026-10-02", 2),
                adminActor));

        assertTrue(ingestionService.getTask("wb-enqueue-rollback").isEmpty());
        assertTrue(workbenchService.listTaskEvents("wb-enqueue-rollback").isEmpty());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_news_source_cursor WHERE source = 'pubmed' AND sourceConfigVersion = 51
                """, Integer.class));
        assertTrue(ingestionService.claim(WORKER).isEmpty());
    }

    @Test
    void auditInsertFailureRollsBackReopenAndPreservesLeaseAndCursor() throws Exception {
        String taskId = "wb-retry-rollback";
        LocalDate start = LocalDate.of(2026, 10, 1);
        workbenchService.registerSourceConfig("pubmed",
                new NewsWorkbenchModels.RegisterSourceConfigRequest(52L, "ophthalmology", start.toString()));
        workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                taskId, "pubmed", 52L, "ophthalmology", start.toString(), start.plusDays(1).toString(), 1), adminActor);
        NewsWireModels.Lease lease = ingestionService.claim(WORKER).orElseThrow();
        byte[] failure = mapper.writeValueAsBytes(Map.of(
                "generation", lease.generation(), "leaseToken", lease.leaseToken(),
                "code", "SOURCE_UNAVAILABLE", "retryable", false));
        ingestionService.fail(WORKER, taskId, lease.generation() + ":fail", failure);

        var previousTask = ingestionService.getTask(taskId).orElseThrow();
        assertEquals("FAILED", previousTask.status());
        var previousLeases = workbenchService.listTaskLeases(taskId);
        var previousEvents = workbenchService.listTaskEvents(taskId);
        assertThrows(DuplicateKeyException.class, () -> ingestionService.enqueueWindow(
                taskId, "pubmed", 52L, "ophthalmology", start, start.plusDays(1), 3));
        assertThrows(IllegalTransactionStateException.class,
                () -> ingestionService.reopenFailedWindow(previousTask, 3));
        assertTaskAuditInsertFails(() -> workbenchService.retryFailedTask(
                taskId, new NewsWorkbenchModels.RetryTaskRequest(3, "审核重试"), adminActor));

        assertEquals(previousTask, ingestionService.getTask(taskId).orElseThrow());
        assertEquals(previousEvents, workbenchService.listTaskEvents(taskId));
        assertEquals(previousLeases, workbenchService.listTaskLeases(taskId));
        assertEquals(Optional.of(start), ingestionService.getCommittedCursor("pubmed", 52L));
        assertTrue(ingestionService.claim(WORKER).isEmpty());
    }

    private static void assertTaskAuditInsertFails(Runnable operation) {
        jdbc.execute("""
                CREATE TRIGGER fail_task_audit_insert BEFORE INSERT ON t_news_collection_task_event
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected task audit failure'
                """);
        try {
            DataAccessException failure = assertThrows(DataAccessException.class, operation::run);
            assertTrue(failure.getMostSpecificCause().getMessage().contains("injected task audit failure"));
        } finally {
            jdbc.execute("DROP TRIGGER fail_task_audit_insert");
        }
    }

    /**
     * WP-07G：失败窗口恢复。
     *
     * <p>覆盖：失败窗口可被重新打开、重试不推进游标、
     * 非 FAILED 状态拒绝重试、并发重试只有一路生效、重复入队的错误语义按状态区分。
     */
    @Test
    void failedWindowCanBeReopenedWithoutAdvancingCursorAndRejectsBadRetries() throws Exception {
        workbenchService.registerSourceConfig(
                "pubmed",
                new NewsWorkbenchModels.RegisterSourceConfigRequest(7L, "ophthalmology", "2026-10-01"));
        NewsWorkbenchModels.CollectionTaskVO created = workbenchService.enqueueTask(
                new NewsWorkbenchModels.EnqueueTaskRequest(
                        "wb-retry-1", "pubmed", 7L, "ophthalmology", "2026-10-01", "2026-10-02", 2), null);
        assertEquals("PENDING", created.status());

        // 人为把任务置为 FAILED（模拟 Worker 报告不可重试失败）
        jdbc.update("""
                UPDATE t_news_collection_task
                SET status = 'FAILED', attemptCount = 2, lastFailureCode = 'SOURCE_TIMEOUT'
                WHERE taskId = 'wb-retry-1'
                """);

        // note 超过列宽时返回参数错误，状态和审计都保持原样。
        BusinessException noteTooLong = assertThrows(BusinessException.class,
                () -> workbenchService.retryFailedTask("wb-retry-1",
                        new NewsWorkbenchModels.RetryTaskRequest(2, "x".repeat(513)), adminActor));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), noteTooLong.getCode());
        assertEquals("FAILED", ingestionService.getTask("wb-retry-1").orElseThrow().status(),
                "被拒绝的重试不得改动任务状态");
        assertEquals(1, workbenchService.listTaskEvents("wb-retry-1").size(),
                "被拒绝的重试不得写审计");

        NewsWorkbenchModels.CollectionTaskVO reopened = workbenchService.retryFailedTask(
                "wb-retry-1", new NewsWorkbenchModels.RetryTaskRequest(2, "重跑失败窗口"), adminActor);
        assertEquals("PENDING", reopened.status());
        assertEquals(2, reopened.attemptCount(), "重试不应清空已消耗的尝试次数");
        assertEquals(4, reopened.maxAttempts(), "重试应按追加次数抬高尝试上限");
        assertTrue(reopened.retryable() == false);
        assertNull(reopened.blockingReason());

        // 游标不得因重试而推进：初始游标是登记时的 2026-10-01，重开窗口后必须仍是它，
        // 不能被窗口结束日（2026-10-02）顶替。
        assertEquals(Optional.of(LocalDate.of(2026, 10, 1)),
                ingestionService.getCommittedCursor("pubmed", 7L),
                "失败窗口重试不得静默推进信源游标");

        // 操作审计
        List<NewsWorkbenchModels.TaskEventVO> events = workbenchService.listTaskEvents("wb-retry-1");
        assertEquals(2, events.size());
        assertEquals("ENQUEUE", events.get(0).action());
        assertEquals("RETRY", events.get(1).action());
        assertEquals("FAILED", events.get(1).fromStatus());
        assertEquals("PENDING", events.get(1).toStatus());
        assertEquals("重跑失败窗口", events.get(1).note());
        assertEquals(1001L, events.get(1).actorId());

        // 再次重试同一任务必须被明确拒绝（幂等负例）
        BusinessException duplicateRetry = assertThrows(BusinessException.class,
                () -> workbenchService.retryFailedTask(
                        "wb-retry-1", new NewsWorkbenchModels.RetryTaskRequest(2, null), adminActor));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), duplicateRetry.getCode());
        assertTrue(duplicateRetry.getMessage().contains("PENDING"), duplicateRetry.getMessage());

        // 并发重试：只有一路生效，另一路必须失败而不是静默覆盖
        jdbc.update("""
                UPDATE t_news_collection_task
                SET status = 'FAILED', lastFailureCode = 'SOURCE_TIMEOUT'
                WHERE taskId = 'wb-retry-1'
                """);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> successes = Collections.synchronizedList(new ArrayList<>());
        Runnable retry = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                workbenchService.retryFailedTask(
                        "wb-retry-1", new NewsWorkbenchModels.RetryTaskRequest(1, "并发重试"), adminActor);
                successes.add(Boolean.TRUE);
            } catch (Throwable ex) {
                failures.add(ex);
            }
        };
        Thread first = new Thread(retry);
        Thread second = new Thread(retry);
        first.start();
        second.start();
        first.join(20_000);
        second.join(20_000);
        assertEquals(1, successes.size(), "并发重试必须只有一路生效");
        assertEquals(1, failures.size(), "并发重试的落败方必须收到明确错误");
        assertTrue(failures.getFirst() instanceof BusinessException, String.valueOf(failures.getFirst()));
        assertEquals(List.of("ENQUEUE", "RETRY", "RETRY"), workbenchService.listTaskEvents("wb-retry-1")
                .stream().map(NewsWorkbenchModels.TaskEventVO::action).toList());

        // 重复入队：已完成的窗口与失败窗口要给出不同的、指向正确入口的错误
        workbenchService.registerSourceConfig(
                "europe-pmc",
                new NewsWorkbenchModels.RegisterSourceConfigRequest(3L, "ophthalmology", "2026-10-01"));
        workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                "wb-dup-completed", "europe-pmc", 3L, "ophthalmology", "2026-10-01", "2026-10-02", 1), null);
        jdbc.update("UPDATE t_news_collection_task SET status = 'COMPLETED' WHERE taskId = 'wb-dup-completed'");
        BusinessException completedDup = assertThrows(BusinessException.class,
                () -> workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                        "wb-dup-completed", "europe-pmc", 3L, "ophthalmology", "2026-10-01", "2026-10-02", 1), null));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), completedDup.getCode());
        assertTrue(completedDup.getMessage().contains("COMPLETED"), completedDup.getMessage());
        // 语义必须真实：同窗口重复采集只会让 complete() 跳过推进游标，不会被任何规则拒绝。
        assertTrue(completedDup.getMessage().contains("不会推进游标"), completedDup.getMessage());
        assertTrue(!completedDup.getMessage().contains("会被游标推进规则拒绝"), completedDup.getMessage());

        // 失败窗口的重复入队必须指向「失败窗口重试入口」，而不是含糊的「任务 ID 已存在」
        workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                "wb-dup-failed", "pubmed", 7L, "ophthalmology", "2026-10-05", "2026-10-06", 1), null);
        jdbc.update("UPDATE t_news_collection_task SET status = 'FAILED' WHERE taskId = 'wb-dup-failed'");
        BusinessException failedDup = assertThrows(BusinessException.class,
                () -> workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                        "wb-dup-failed", "pubmed", 7L, "ophthalmology", "2026-10-05", "2026-10-06", 1), null));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), failedDup.getCode());
        assertTrue(failedDup.getMessage().contains("失败窗口"), failedDup.getMessage());
        assertTrue(failedDup.getMessage().contains("重试入口"), failedDup.getMessage());
    }

    /**
     * WP-07G：更早窗口失败时，后面的窗口要被说清「卡在谁身上」——系统不自动跳过失败窗口。
     */
    @Test
    void laterWindowReportsTheEarlierFailedWindowThatBlocksIt() throws Exception {
        workbenchService.registerSourceConfig(
                "pubmed",
                new NewsWorkbenchModels.RegisterSourceConfigRequest(9L, "ophthalmology", "2026-10-01"));
        workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                "wb-block-early", "pubmed", 9L, "ophthalmology", "2026-10-01", "2026-10-02", 1), null);
        workbenchService.enqueueTask(new NewsWorkbenchModels.EnqueueTaskRequest(
                "wb-block-late", "pubmed", 9L, "ophthalmology", "2026-10-02", "2026-10-03", 1), null);
        jdbc.update("""
                UPDATE t_news_collection_task
                SET status = 'FAILED', attemptCount = 1, lastFailureCode = 'SOURCE_TIMEOUT'
                WHERE taskId = 'wb-block-early'
                """);

        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CollectionTaskVO> page =
                workbenchService.listTasks("pubmed", null, 1, 10);
        NewsWorkbenchModels.CollectionTaskVO late = page.records().stream()
                .filter(row -> "wb-block-late".equals(row.taskId()))
                .findFirst()
                .orElseThrow();
        assertEquals("wb-block-early", late.blockedByTaskId());
        assertNotNull(late.blockingReason());
        assertTrue(late.blockingReason().contains("wb-block-early"), late.blockingReason());
        assertTrue(late.blockingReason().contains("FAILED"), late.blockingReason());

        NewsWorkbenchModels.CollectionTaskVO early = page.records().stream()
                .filter(row -> "wb-block-early".equals(row.taskId()))
                .findFirst()
                .orElseThrow();
        assertTrue(early.retryable());
        assertTrue(early.blockingReason().contains("SOURCE_TIMEOUT"), early.blockingReason());
        assertTrue(early.blockingReason().contains("不会自动跳过"), early.blockingReason());
    }

    @Test
    void candidateQueueDetailAndLinkedNewsStatus() throws Exception {
        long revAccepted = ingestSingleMaterial(
                "wb-cand-1", "pubmed", "PMID-C1",
                "Diabetic Retinopathy Screening Diagnostic Accuracy", "CC0",
                "Deep learning screening reached high sensitivity for referable diabetic retinopathy in multi-center evaluation."
        );
        long revReview = ingestSingleMaterial(
                "wb-cand-2", "europe-pmc", "EPMC-C2",
                "Age-Related Macular Degeneration Anti-VEGF Follow-up", "CC-BY",
                "Extended dosing intervals maintained visual acuity outcomes over twenty-four months in responders."
        );
        long revViolation = ingestSingleMaterial(
                "wb-cand-3", "pubmed", "PMID-C3",
                "Proprietary Retina Abstract Without Open License", null,
                null
        );

        String acceptedJson = acceptJson(
                "糖尿病视网膜病变多中心筛查研究",
                "多中心临床研究评估了糖网转诊病变检出灵敏度，仍需基层真实世界验证。",
                "Deep learning screening reached high sensitivity for referable diabetic retinopathy",
                "abstract"
        );
        modelClient.enqueue(req -> new NewsCurationModels.Succeeded(
                acceptedJson, 400, 150, 2_500L, 200, "req-c1", 120));
        curationService.curateRevision(revAccepted, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);

        String reviewJson = """
                {
                  "decision": "NEEDS_REVIEW",
                  "relevanceScore": 79,
                  "suggestedTitle": "抗 VEGF 延长给药间隔随访观察",
                  "suggestedSummary": "应答人群 24 个月随访视力结局维持稳定，个体化方案需临床进一步确认。",
                  "category": "眼底病",
                  "limitations": ["仅针对初始应答者分析"],
                  "claims": [{
                    "id": "claim-1",
                    "field": "abstract",
                    "statement": "Extended dosing maintained acuity over 24 months",
                    "quote": "Extended dosing intervals maintained visual acuity outcomes"
                  }]
                }
                """;
        modelClient.enqueue(req -> new NewsCurationModels.Succeeded(
                reviewJson, 420, 160, 2_700L, 200, "req-c2", 140));
        curationService.curateRevision(revReview, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);

        String violationJson = acceptJson(
                "擅自引用未授权摘要并保证治愈",
                "本方法保证治愈所有眼病，替代眼科医生。",
                "Proprietary text that must be stripped",
                "abstract"
        );
        modelClient.enqueue(req -> new NewsCurationModels.Succeeded(
                violationJson, 300, 120, 1_900L, 200, "req-c3", 100));
        curationService.curateRevision(revViolation, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);

        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CandidateSummaryVO> acceptedPage =
                workbenchService.listCandidates("CURATED_ACCEPTED", "pubmed", "LICENSED_ABSTRACT", 1, 10);
        assertEquals(1L, acceptedPage.total());
        long acceptedId = acceptedPage.records().getFirst().id();
        assertNull(acceptedPage.records().getFirst().linkedNewsId());

        Long newsId = lifecycleService.createDraftFromCandidate(acceptedId, adminActor, true);

        NewsWorkbenchModels.CandidateDetailVO detail = workbenchService.getCandidateDetail(acceptedId);
        assertEquals(newsId, detail.linkedNewsId());
        assertEquals(NewsStatus.PENDING_REVIEW, detail.linkedNewsStatus());
        assertEquals(1, detail.claims().size());
        assertEquals(1, detail.limitations().size());
        assertNotNull(detail.callReceipt());
        assertEquals("SUCCEEDED", detail.callReceipt().state());

        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CandidateSummaryVO> violationsPage =
                workbenchService.listCandidates("REJECTED_EVIDENCE_VIOLATION", null, null, 1, 10);
        assertEquals(1L, violationsPage.total());
        assertTrue(violationsPage.records().getFirst().violationsCount() > 0);
    }

    @Test
    void budgetCircuitBreakerReclaimAndUnknownReceiptReconciliation() throws Exception {
        NewsWorkbenchModels.BudgetSummaryVO configured = workbenchService.configureBudget(
                new NewsWorkbenchModels.ConfigureBudgetRequest(
                        BUDGET_DATE.toString(), SCOPE_GLOBAL, 20, 100_000L, 1_000_000L));
        assertEquals(20, configured.maxCalls());

        long revCircuit = ingestSingleMaterial(
                "wb-rec-1", "pubmed", "PMID-R1",
                "Glaucoma Progression Visual Field Monitoring", "CC-BY",
                "Visual field progression monitoring interval analysis across primary open angle glaucoma."
        );
        modelClient.enqueue(req -> new NewsCurationModels.DefinitiveFailure(
                "DASHSCOPE_UNAUTHORIZED", 401, "req-auth-fail", true, 55));
        assertThrows(IllegalStateException.class,
                () -> curationService.curateRevision(revCircuit, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL));

        List<NewsWorkbenchModels.BudgetSummaryVO> brokenBudgets = workbenchService.listBudgets(SCOPE_GLOBAL, 10);
        assertTrue(brokenBudgets.getFirst().circuitBroken());

        NewsWorkbenchModels.BudgetSummaryVO reset = workbenchService.resetCircuitBreaker(
                new NewsWorkbenchModels.ResetCircuitRequest(BUDGET_DATE.toString(), SCOPE_GLOBAL), adminActor);
        assertFalse(reset.circuitBroken());
        assertNull(reset.circuitReason());

        long revUnknown = ingestSingleMaterial(
                "wb-rec-2", "pubmed", "PMID-R2",
                "Pediatric Amblyopia Patching Adherence Study", "CC-BY",
                "Electronic occlusion dose monitors quantified daily patching adherence in pediatric amblyopia."
        );
        modelClient.enqueue(req -> new NewsCurationModels.UnknownOutcome(
                "READ_TIMEOUT", 504, "req-unk-2", 20_000));
        curationService.curateRevision(revUnknown, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);

        // Insert a stale RESERVED receipt directly to exercise stale pre-IO reclaim through the workbench.
        jdbc.update("""
                UPDATE t_news_curation_budget
                SET reservedCalls = reservedCalls + 1,
                    reservedTokens = reservedTokens + 4000,
                    reservedCostMicros = reservedCostMicros + 50000
                WHERE budgetDate = ? AND scope = ?
                """, BUDGET_DATE.toString(), SCOPE_GLOBAL);
        jdbc.update("""
                INSERT INTO t_news_model_call_receipt
                    (callId, idempotencyKey, materialRevisionId, policyVersion, attemptNo,
                     budgetDate, budgetScope, provider, modelName, promptSha256, state,
                     reservedTokens, reservedCostMicros, reservedAt, deadlineAt)
                VALUES ('stale-pre-io-call', '999999:news-curation-v1:1', 999999, ?, 1,
                        ?, ?, 'dashscope', 'qwen3.5-plus', ?, 'RESERVED',
                        4000, 50000, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE),
                        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE))
                """, POLICY_V1, BUDGET_DATE.toString(), SCOPE_GLOBAL, "a".repeat(64));

        NewsCurationModels.StaleReclaimResult reclaim = workbenchService.reclaimStaleCalls();
        assertEquals(1, reclaim.abortedPreIoCount());

        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CallReceiptVO> unknownReceipts =
                workbenchService.listReceipts("OUTCOME_UNKNOWN", SCOPE_GLOBAL, 1, 10);
        assertEquals(1L, unknownReceipts.total());
        String unknownCallId = unknownReceipts.records().getFirst().callId();

        String recoveredJson = acceptJson(
                "儿童弱视电子遮盖依从性监测研究",
                "基于客观电子监测仪统计的每日遮盖时长分布，为依从性评估提供量化依据。",
                "Electronic occlusion dose monitors quantified daily patching adherence",
                "abstract"
        );
        NewsWorkbenchModels.CallReceiptVO reconciled = workbenchService.reconcileReceipt(
                unknownCallId,
                new NewsWorkbenchModels.ReconcileReceiptRequest(
                        "CONFIRMED_CHARGED_WITH_OUTPUT",
                        "Verified billing invoice #42",
                        recoveredJson,
                        510,
                        190,
                        4_200L,
                        "req-unk-2"
                ),
                adminActor
        );
        assertEquals("SUCCEEDED", reconciled.state());
        assertEquals("admin:1001", reconciled.reconciledBy());
        assertEquals(4_200L, reconciled.actualCostMicros());

        NewsWorkbenchModels.CandidateSummaryVO recoveredCandidate = workbenchService
                .listCandidates("CURATED_ACCEPTED", "pubmed", null, 1, 10)
                .records()
                .getFirst();
        assertEquals(revUnknown, recoveredCandidate.materialRevisionId());
    }

    @Test
    void benchRunsReturnPersistedNewsBenchMetrics() {
        curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 100, 1_000_000L, 10_000_000L);
        NewsBenchRunner runner = new NewsBenchRunner(jdbc, wireCodec, verifier, promptBuilder, mapper);
        NewsCurationModels.NewsBenchReport report = runner.runEndToEndWithMySql(
                Path.of("contracts/news/newsbench/v1.dataset.json"),
                POLICY_V1,
                ingestionService,
                curationService,
                (rawJson, promptTokens, completionTokens, actualCostMicros) -> modelClient.enqueue(req ->
                        new NewsCurationModels.Succeeded(
                                rawJson, promptTokens, completionTokens, actualCostMicros, 200, "bench-req", 30)),
                BUDGET_DATE,
                SCOPE_GLOBAL
        );
        assertTrue(report.gatePassed());

        List<NewsWorkbenchModels.BenchRunSummaryVO> runs = workbenchService.listBenchRuns(10);
        assertEquals(1, runs.size());
        NewsWorkbenchModels.BenchRunSummaryVO latest = runs.getFirst();
        assertEquals(report.runId(), latest.runId());
        assertTrue(latest.gatePassed());
        assertEquals(18, latest.totalCases());
        assertEquals(18, latest.passedCases());
        assertEquals(10_000, latest.precisionBps());
        assertEquals(10_000, latest.recallBps());
        assertEquals(10_000, latest.evidenceCatchBps());
        assertEquals(10_000, latest.licenseComplianceBps());
        assertEquals(10_000, latest.injectionCatchBps());
    }

    @Test
    void issuePreviewGroupsPublishedRevisionsAndExcludesWithdrawnOrUnboundArticles() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        LocalDateTime start = day.atStartOfDay();
        putIssueArticle(1, "pubmed", "123", null, start);
        putIssueArticle(2, "europe-pmc", "123", "https://doi.org/10.1234/STUDY", start.plusHours(1));
        putIssueArticle(3, "europe-pmc", null, "doi:10.1234/study", start.plusHours(2));
        putIssueArticle(4, "pubmed", "900", "10.1234/conflict", start.plusHours(3));
        putIssueArticle(5, "europe-pmc", "901", "10.1234/conflict", start.plusHours(4));
        putIssueArticle(6, "clinicaltrials-gov", null, null, start.plusHours(5));
        putIssueArticle(7, "pubmed", "123", null, start.plusDays(1));
        putIssueArticle(8, "pubmed", "123", null, start.plusHours(6));
        putIssueArticle(9, "pubmed", "123", null, start.plusHours(7));
        putIssueArticle(10, "pubmed", "123", null, start.plusHours(8));
        jdbc.update("UPDATE t_news SET status = ? WHERE id = 8", NewsStatus.OFFLINE);
        jdbc.update("UPDATE t_news SET isDelete = 1 WHERE id = 9");
        jdbc.update("UPDATE t_news SET publishedRevisionNo = NULL WHERE id = 10");

        NewsIssueService issue = new NewsIssueService(jdbc);
        NewsIssueService.Preview report = issue.preview(day, day);
        assertEquals(6, report.articleCount());
        assertEquals(List.of(6L, 5L, 4L, 3L),
                report.entries().stream().map(entry -> entry.articles().getFirst().newsId()).toList());
        assertFalse(report.entries().getFirst().identifierConflict());
        assertTrue(report.entries().get(1).identifierConflict());
        assertTrue(report.entries().get(2).identifierConflict());
        NewsIssueService.Entry joined = report.entries().getLast();
        assertFalse(joined.identifierConflict());
        assertEquals(List.of(3L, 2L, 1L), joined.articles().stream().map(NewsIssueService.Article::newsId).toList());
        assertEquals("10.1234/study", joined.articles().getFirst().doi());
        assertEquals("Published 3", joined.articles().getFirst().title());
        assertEquals(1, joined.articles().getFirst().revisionNo());
        assertEquals(3L, joined.articles().getFirst().materialRevisionId());

        jdbc.update("UPDATE t_news SET status = ? WHERE id = 3", NewsStatus.OFFLINE);
        assertEquals(5, issue.preview(day, day).articleCount());
        assertTrue(issue.preview(day.minusDays(1), day.minusDays(1)).entries().isEmpty());
        assertThrows(BusinessException.class, () -> issue.preview(day, day.plusDays(31)));
        assertThrows(BusinessException.class, () -> issue.preview(day.plusDays(1), day));
    }

    private void putIssueArticle(long id, String source, String pmid, String doi, LocalDateTime publishTime) {
        String digest = "a".repeat(64);
        jdbc.update("""
                INSERT INTO t_news_material_revision
                    (id, materialId, source, sourceRecordId, contentDigest, url, title, pmid, doi,
                     canonicalJson, firstTaskId, firstGeneration, visibleAt)
                VALUES (?, ?, ?, ?, ?, 'https://example.test/study', 'Material', ?, ?,
                        '{}', 'fixture', 1, UTC_TIMESTAMP(6))
                """, id, id, source, "record-" + id, digest, pmid, doi);
        jdbc.update("""
                INSERT INTO t_news (id, title, userId, content, coverImg, status, publishTime,
                                    publishedRevisionNo, currentRevisionNo)
                VALUES (?, 'Mutable working title', 1001, '', '', ?, ?, 1, 2)
                """, id, NewsStatus.PUBLISHED, publishTime);
        jdbc.update("""
                INSERT INTO t_news_revision
                    (newsId, revisionNo, action, status, title, content, coverImg,
                     materialRevisionId, evidenceSnapshotSha256, contentSha256)
                VALUES (?, 1, 'PUBLISH', ?, ?, '', '', ?, ?, ?)
                """, id, NewsStatus.PUBLISHED, "Published " + id, id, digest, digest);
    }

    private long ingestSingleMaterial(String taskId,
                                      String source,
                                      String sourceRecordId,
                                      String title,
                                      String abstractLicense,
                                      String abstractText) throws Exception {
        long configVersion = 100L + jdbc.queryForObject("SELECT COUNT(*) FROM t_news_collection_task", Long.class);
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);
        ingestionService.enqueueWindow(taskId, source, configVersion, "ophthalmology", start, end, 3);
        NewsWireModels.Lease lease = ingestionService.claim(WORKER).orElseThrow();

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("source", source);
        material.put("sourceRecordId", sourceRecordId);
        material.put("url", "https://example.org/news/" + sourceRecordId);
        material.put("title", title);
        material.put("language", "eng");
        material.put("publishedOn", "2026-10-01");
        material.put("updatedOn", "2026-10-01");
        if ("pubmed".equals(source)) {
            material.put("pmid", String.valueOf(39000000L + configVersion));
        }
        if (abstractText != null) {
            material.put("abstract", abstractText);
        }
        if (abstractLicense != null) {
            material.put("abstractLicense", abstractLicense);
        }

        byte[] batchBody = mapper.writeValueAsBytes(Map.of(
                "generation", lease.generation(),
                "leaseToken", lease.leaseToken(),
                "sequence", 1,
                "records", List.of(material)
        ));
        ingestionService.submitBatch(WORKER, lease.taskId(), lease.generation() + ":1", batchBody);

        byte[] completeBody = mapper.writeValueAsBytes(Map.of(
                "generation", lease.generation(),
                "leaseToken", lease.leaseToken(),
                "batchCount", 1,
                "recordCount", 1,
                "nextCursor", end.toString()
        ));
        ingestionService.complete(WORKER, lease.taskId(), lease.generation() + ":complete", completeBody);

        return ingestionService.listVisibleRevisions(source, sourceRecordId).getLast().revisionId();
    }

    private String acceptJson(String title, String summary, String quote, String field) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "decision", "ACCEPT",
                "relevanceScore", 91,
                "suggestedTitle", title,
                "suggestedSummary", summary,
                "category", "临床进展",
                "limitations", List.of("样本随访周期需进一步延长验证"),
                "claims", List.of(Map.of(
                        "id", "claim-1",
                        "field", field,
                        "statement", summary,
                        "quote", quote
                ))
        ));
    }

    private static synchronized void ensureDatabase() throws Exception {
        if (jdbc != null) {
            return;
        }
        String url = System.getenv("FLYWAY_TEST_URL");
        String username = System.getenv("FLYWAY_TEST_USERNAME");
        String password = System.getenv("FLYWAY_TEST_PASSWORD");
        schema = "diagnosis_ci_workbench_" + UUID.randomUUID().toString().replace("-", "");

        admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        admin.execute("CREATE DATABASE `" + schema + "`");

        dataSource = new DriverManagerDataSource(withSchema(url, schema), username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/callback")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(NewsMapper.class);
        configuration.addMapper(NewsAuditMapper.class);

        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setLogicDeleteField("isDelete");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setPlugins(interceptor);
        sqlSessionTemplate = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    }

    @AfterAll
    static void tearDown() {
        if (admin != null && schema != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    private static String withSchema(String url, String targetSchema) {
        int queryStart = url.indexOf('?');
        String base = queryStart >= 0 ? url.substring(0, queryStart) : url;
        String query = queryStart >= 0 ? url.substring(queryStart) : "";
        int lastSlash = base.lastIndexOf('/');
        String rewrittenBase = (lastSlash > base.indexOf("//") + 1 ? base.substring(0, lastSlash) : base)
                + "/" + targetSchema;
        return rewrittenBase + query;
    }

    private static final class ScriptedModelClient implements NewsCurationModelClient {
        private final Deque<NewsCurationModelClient> queue = new ArrayDeque<>();

        synchronized void enqueue(NewsCurationModelClient handler) {
            queue.addLast(handler);
        }

        @Override
        public NewsCurationModels.ModelCallOutcome invoke(NewsCurationModels.ModelCallRequest request) {
            NewsCurationModelClient handler;
            synchronized (this) {
                handler = queue.pollFirst();
            }
            if (handler == null) {
                throw new IllegalStateException("Missing scripted model response");
            }
            return handler.invoke(request);
        }
    }
}
