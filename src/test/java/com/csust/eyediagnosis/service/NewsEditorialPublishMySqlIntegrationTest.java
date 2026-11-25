package com.csust.eyediagnosis.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.csust.eyediagnosis.common.BatchResultVO;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.constant.NewsStatus;
import com.csust.eyediagnosis.controller.NewsController;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.manager.FileManager;
import com.csust.eyediagnosis.mapper.NewsAuditMapper;
import com.csust.eyediagnosis.mapper.NewsMapper;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.model.dto.file.UploadPictureDetail;
import com.csust.eyediagnosis.model.dto.news.NewsBatchStatusDTO;
import com.csust.eyediagnosis.model.dto.news.NewsQueryDTO;
import com.csust.eyediagnosis.model.dto.news.NewsUpdateDTO;
import com.csust.eyediagnosis.model.dto.news.NewsUploadDTO;
import com.csust.eyediagnosis.model.entity.News;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.vo.news.NewsRevisionVO;
import com.csust.eyediagnosis.model.vo.news.NewsVO;
import com.csust.eyediagnosis.newscuration.NewsCurationModelClient;
import com.csust.eyediagnosis.newscuration.NewsCurationModels;
import com.csust.eyediagnosis.newscuration.NewsCurationPromptBuilder;
import com.csust.eyediagnosis.newscuration.NewsCurationProperties;
import com.csust.eyediagnosis.newscuration.NewsCurationService;
import com.csust.eyediagnosis.newscuration.NewsEvidenceVerifier;
import com.csust.eyediagnosis.newsingestion.NewsIngestionProperties;
import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.csust.eyediagnosis.newsingestion.NewsWireCodec;
import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.csust.eyediagnosis.newsingestion.NewsWorkerIdentity;
import com.csust.eyediagnosis.service.impl.NewsServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2 real-MySQL + real-Redis integration suite for WP-04 editorial review, candidate evidence
 * binding, centralized service-layer publish gate, immutable content revisions, and post-commit
 * Redis cache invalidation / withdrawal visibility guarantees.
 *
 * <p>Keeps {@code Assumptions.assumeTrue} in {@code @BeforeEach} so Surefire records exact
 * case/skip counts matching {@code scripts/check_surefire_skips.py} (8 cases).
 */
class NewsEditorialPublishMySqlIntegrationTest {

    private static final String POLICY_V1 = "news-curation-v1";
    private static final String SCOPE_GLOBAL = "GLOBAL";
    private static final LocalDate BUDGET_DATE = LocalDate.of(2026, 10, 20);
    private static final NewsWorkerIdentity WORKER =
            new NewsWorkerIdentity("publish-it-worker", NewsIngestionProperties.ALL_SOURCES);
    private static final String SAFE_BODY_PREFIX =
            "这是一段用于核验眼科临床资讯发布生命周期的完整正文内容，明确说明仅做科普展示而不构成诊疗依据：";

    private static String schema;
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate rawRedis;
    private static FaultInjectableReactiveRedisTemplate faultRedis;
    private static AnnotationConfigApplicationContext appContext;
    private static final ThreadLocal<User> ACTIVE_ACTOR = new ThreadLocal<>();

    private NewsController controller;
    private NewsService newsService;
    private NewsLifecycleService lifecycleService;
    private NewsListCacheService cacheService;
    private FileManager fileManager;
    private NewsIngestionService ingestionService;
    private NewsCurationService curationService;
    private ScriptedModelClient modelClient;
    private ObjectMapper protocolMapper;
    private User adminActor;

    @BeforeEach
    void setUp() {
        String url = System.getenv("FLYWAY_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set FLYWAY_TEST_URL to run the real MySQL + Redis News Publish gate");
        ensureInfra();

        faultRedis.failAllOps.set(false);
        faultRedis.failIncrement.set(false);
        faultRedis.incrementCalls.set(0);
        Set<String> cachedKeys = rawRedis.keys(NewsListCacheService.NEWS_LIST_CACHE_PREFIX + "*");
        if (cachedKeys != null && !cachedKeys.isEmpty()) {
            rawRedis.delete(cachedKeys);
        }
        rawRedis.delete(NewsListCacheService.NEWS_LIST_CACHE_GENERATION);

        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_news_revision_insert");
        jdbc.execute("DELETE FROM t_news_revision");
        jdbc.execute("DELETE FROM t_news_audit");
        jdbc.execute("DELETE FROM t_news");
        jdbc.execute("DELETE FROM t_news_cache_epoch");
        jdbc.execute("INSERT INTO t_news_cache_epoch (scope, epoch) VALUES ('news_list', 0)");
        jdbc.execute("DELETE FROM t_news_curation_candidate");
        jdbc.execute("DELETE FROM t_news_model_call_receipt");
        jdbc.execute("DELETE FROM t_news_curation_budget");
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
                VALUES (1001, 'admin_pub', 'hash', 'Chief Editor', 'admin'),
                       (1002, 'user_pub', 'hash', 'Patient Reader', 'user')
                """);

        controller = appContext.getBean(NewsController.class);
        newsService = appContext.getBean(NewsService.class);
        lifecycleService = appContext.getBean(NewsLifecycleService.class);
        cacheService = appContext.getBean(NewsListCacheService.class);
        cacheService.resetLocalStateForTests();
        fileManager = appContext.getBean(FileManager.class);
        protocolMapper = JsonConfig.createNewsProtocolObjectMapper();

        NewsWireCodec wireCodec = new NewsWireCodec(protocolMapper);
        NewsIngestionProperties ingProps = new NewsIngestionProperties();
        ingProps.setEnabled(true);
        PlatformTransactionManager txManager = appContext.getBean(PlatformTransactionManager.class);
        ingestionService = new NewsIngestionService(jdbc, txManager, ingProps, wireCodec);

        NewsCurationProperties curProps = new NewsCurationProperties();
        curProps.setEnabled(true);
        curProps.setPolicyVersion(POLICY_V1);
        curProps.setProvider("dashscope");
        curProps.setModelName("qwen3.5-plus");
        curProps.setCallTimeout(Duration.ofSeconds(20));
        curProps.setDailyMaxCalls(50);
        curProps.setDailyMaxTokens(200_000L);
        curProps.setDailyMaxCostMicros(2_000_000L);
        curProps.setEstimatedTokensPerCall(4_000L);
        curProps.setEstimatedCostMicrosPerCall(50_000L);
        modelClient = new ScriptedModelClient();
        curationService = new NewsCurationService(
                jdbc, txManager, curProps, modelClient,
                new NewsEvidenceVerifier(curProps, protocolMapper),
                new NewsCurationPromptBuilder(curProps, protocolMapper),
                protocolMapper);

        adminActor = new User();
        adminActor.setId(1001L);
        adminActor.setUserRole(AuthConstant.ADMIN_ROLE);
        adminActor.setUserName("Chief Editor");
        ACTIVE_ACTOR.set(adminActor);
    }

    private static synchronized void ensureInfra() {
        if (appContext != null) {
            return;
        }
        String url = System.getenv("FLYWAY_TEST_URL");
        String username = System.getenv("FLYWAY_TEST_USERNAME");
        String password = System.getenv("FLYWAY_TEST_PASSWORD");
        schema = "diagnosis_ci_publish_" + UUID.randomUUID().toString().replace("-", "");

        admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        admin.execute("CREATE DATABASE `" + schema + "`");

        dataSource = new DriverManagerDataSource(withSchema(url, schema), username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/callback")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);

        String redisHost = envOrDefault("SPRING_DATA_REDIS_HOST", "127.0.0.1");
        int redisPort = Integer.parseInt(envOrDefault("SPRING_DATA_REDIS_PORT", "6379"));
        redisFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort));
        redisFactory.afterPropertiesSet();
        rawRedis = new StringRedisTemplate(redisFactory);
        faultRedis = new FaultInjectableReactiveRedisTemplate(redisFactory);

        UserService mockUserService = mock(UserService.class);
        when(mockUserService.getLoginUser(any())).thenAnswer(inv -> {
            User actor = ACTIVE_ACTOR.get();
            if (actor == null) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
            }
            return actor;
        });
        when(mockUserService.isAdmin(any())).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            return user != null && AuthConstant.ADMIN_ROLE.equals(user.getUserRole());
        });

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // Register external service mocks as pre-built singletons so Spring skips @Resource field injection on FileManager.
        ctx.getBeanFactory().registerSingleton("userService", mockUserService);
        ctx.getBeanFactory().registerSingleton("fileManager", mock(FileManager.class));
        ctx.register(PublishTestSpringConfig.class);
        ctx.refresh();
        appContext = ctx;
    }

    @AfterAll
    static void tearDownAll() {
        ACTIVE_ACTOR.remove();
        if (appContext != null) {
            appContext.close();
            appContext = null;
        }
        if (redisFactory != null) {
            redisFactory.destroy();
            redisFactory = null;
        }
        if (schema != null && admin != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            schema = null;
        }
    }

    // Case 1
    @Test
    void v41MigrationAddsEvidenceBindingColumnsRevisionTableAndCacheEpoch() {
        for (String col : List.of(
                "curationCandidateId", "materialRevisionId", "evidenceSnapshotSha256",
                "currentRevisionNo", "publishedRevisionNo")) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 't_news' AND column_name = ?
                    """, Integer.class, schema, col);
            assertEquals(1, count, "Missing V42 column on t_news: " + col);
        }
        for (String table : List.of("t_news_revision", "t_news_cache_epoch")) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = ?
                    """, Integer.class, schema, table);
            assertEquals(1, count, "Missing V42 table: " + table);
        }
        assertEquals(0L, cacheService.readAuthoritativeDbEpoch());
    }

    // Case 2
    @Test
    void candidateDraftCreationBindsFrozenEvidenceAndCreatesImmutableRevision() throws Exception {
        CuratedCandidateFixture accepted = createCuratedCandidate(
                "PMID-PUB-1", "Glaucoma OCT screening cohort accuracy", "ACCEPT", true);

        MockHttpServletRequest req = new MockHttpServletRequest();
        Long newsId = controller.createFromCandidate(accepted.candidateId, false, req).getData();
        assertNotNull(newsId);

        News saved = newsService.getById(newsId);
        assertEquals(Integer.valueOf(NewsStatus.DRAFT), saved.getStatus());
        assertEquals(accepted.candidateId, saved.getCurationCandidateId());
        assertEquals(accepted.materialRevisionId, saved.getMaterialRevisionId());
        assertEquals(accepted.snapshotSha256, saved.getEvidenceSnapshotSha256());
        assertEquals(Integer.valueOf(1), saved.getCurrentRevisionNo());
        assertNull(saved.getPublishedRevisionNo());

        List<NewsRevisionVO> revisions = controller.listNewsRevisions(newsId).getData();
        assertEquals(1, revisions.size());
        assertEquals("CREATE_FROM_CANDIDATE", revisions.getFirst().getAction());
        assertEquals(accepted.snapshotSha256, revisions.getFirst().getEvidenceSnapshotSha256());
        assertTrue(revisions.getFirst().getEvidenceSnapshotJson().contains("Glaucoma OCT screening cohort accuracy"));

        // Duplicate candidate draft is blocked.
        BusinessException dup = assertThrows(BusinessException.class, () ->
                controller.createFromCandidate(accepted.candidateId, false, req));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), dup.getCode());

        // Passing raw materialRevisionId without curationCandidateId on /news/edit is rejected.
        News orphan = new News();
        orphan.setTitle("Fake bound article");
        orphan.setContent(SAFE_BODY_PREFIX + "伪造证据关联字段。");
        orphan.setMaterialRevisionId(accepted.materialRevisionId);
        assertThrows(BusinessException.class, () -> lifecycleService.applyEdit(orphan, adminActor, null));

        // Direct create-and-publish of a candidate-bound article is rejected at the service layer.
        NewsUpdateDTO directPublishDto = new NewsUpdateDTO();
        directPublishDto.setCurationCandidateId(accepted.candidateId);
        directPublishDto.setTitle("Direct publish bypass attempt");
        directPublishDto.setContent(SAFE_BODY_PREFIX + "企图直接跳过草稿发布精选候选。");
        directPublishDto.setStatus(NewsStatus.PUBLISHED);
        assertThrows(BusinessException.class, () -> controller.updateNews(directPublishDto, req));
    }

    // Case 3
    @Test
    void serviceLayerPublishGateBlocksInvalidCandidatesTamperedSnapshotsAndClinicalViolationsAcrossAllEntries()
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();

        // 1) REJECTED_EVIDENCE_VIOLATION and NEEDS_RECONCILIATION candidates cannot create drafts.
        long rejectedCand = createCuratedCandidate(
                "PMID-GATE-REJ", "Macular edema OCT biomarker study", "BAD_QUOTE", false).candidateId;
        long reconcileCand = createCuratedCandidate(
                "PMID-GATE-UNK", "Uveitis biologic immunomodulator study", "UNKNOWN_OUTCOME", false).candidateId;
        assertThrows(BusinessException.class, () -> controller.createFromCandidate(rejectedCand, false, req));
        assertThrows(BusinessException.class, () -> controller.createFromCandidate(reconcileCand, false, req));

        // 2) CURATED_NEEDS_REVIEW requires PENDING_REVIEW -> PUBLISHED + non-blank review note.
        CuratedCandidateFixture needsReview = createCuratedCandidate(
                "PMID-GATE-REV", "Early corneal cross-linking pilot trial", "NEEDS_REVIEW", false);
        Long reviewNewsId = controller.createFromCandidate(needsReview.candidateId, false, req).getData();

        // Shortcut DRAFT -> PUBLISHED blocked on both single edit and batch status endpoints.
        NewsUpdateDTO shortcutDto = new NewsUpdateDTO();
        shortcutDto.setId(reviewNewsId);
        shortcutDto.setContent(newsService.getById(reviewNewsId).getContent());
        shortcutDto.setStatus(NewsStatus.PUBLISHED);
        shortcutDto.setNote("跳过待审核");
        assertThrows(BusinessException.class, () -> controller.updateNews(shortcutDto, req));

        NewsBatchStatusDTO batchShortcut = new NewsBatchStatusDTO();
        batchShortcut.setIds(List.of(reviewNewsId));
        batchShortcut.setStatus(NewsStatus.PUBLISHED);
        batchShortcut.setNote("批量跳过待审核");
        BatchResultVO batchShortcutRes = controller.changeNewsStatusBatch(batchShortcut, req).getData();
        assertEquals(0, batchShortcutRes.getSucceeded());
        assertEquals(List.of(String.valueOf(reviewNewsId)), batchShortcutRes.getFailedIds());

        // Move to PENDING_REVIEW; publishing with blank note still fails.
        NewsUpdateDTO submitDto = new NewsUpdateDTO();
        submitDto.setId(reviewNewsId);
        submitDto.setContent(newsService.getById(reviewNewsId).getContent());
        submitDto.setStatus(NewsStatus.PENDING_REVIEW);
        controller.updateNews(submitDto, req);

        NewsUpdateDTO noNotePublish = new NewsUpdateDTO();
        noNotePublish.setId(reviewNewsId);
        noNotePublish.setContent(newsService.getById(reviewNewsId).getContent());
        noNotePublish.setStatus(NewsStatus.PUBLISHED);
        noNotePublish.setNote("   ");
        assertThrows(BusinessException.class, () -> controller.updateNews(noNotePublish, req));

        // Valid PENDING_REVIEW -> PUBLISHED with review note succeeds!
        noNotePublish.setNote("临床编辑核对早期试点研究局限性后准予发布");
        controller.updateNews(noNotePublish, req);
        assertEquals(Integer.valueOf(NewsStatus.PUBLISHED), newsService.getById(reviewNewsId).getStatus());

        // 3) Tampering with snapshot JSON in DB blocks subsequent publish of an accepted candidate draft.
        CuratedCandidateFixture tampered = createCuratedCandidate(
                "PMID-GATE-TAMP", "Diabetic retinopathy screening neural model", "ACCEPT", false);
        Long tamperedNewsId = controller.createFromCandidate(tampered.candidateId, true, req).getData();
        jdbc.update("UPDATE t_news_curation_candidate SET evidenceSnapshotJson = '{\"tampered\":true}' WHERE id = ?",
                tampered.candidateId);

        NewsUpdateDTO publishTampered = new NewsUpdateDTO();
        publishTampered.setId(tamperedNewsId);
        publishTampered.setContent(newsService.getById(tamperedNewsId).getContent());
        publishTampered.setStatus(NewsStatus.PUBLISHED);
        assertThrows(BusinessException.class, () -> controller.updateNews(publishTampered, req));

        NewsBatchStatusDTO batchTampered = new NewsBatchStatusDTO();
        batchTampered.setIds(List.of(tamperedNewsId));
        batchTampered.setStatus(NewsStatus.PUBLISHED);
        BatchResultVO batchTamperedRes = controller.changeNewsStatusBatch(batchTampered, req).getData();
        assertEquals(0, batchTamperedRes.getSucceeded());
        assertEquals(Integer.valueOf(NewsStatus.PENDING_REVIEW), newsService.getById(tamperedNewsId).getStatus());

        // 4) Prohibited clinical claim & prompt injection blocked on manual publish and post-publish edit.
        NewsUpdateDTO manualUnsafe = new NewsUpdateDTO();
        manualUnsafe.setTitle("新技术保证治愈所有眼底病变");
        manualUnsafe.setContent(SAFE_BODY_PREFIX + "患者无需就诊即可开具处方并彻底根治病变。");
        manualUnsafe.setStatus(NewsStatus.PUBLISHED);
        assertThrows(BusinessException.class, () -> controller.updateNews(manualUnsafe, req));
        manualUnsafe.setTitle("眼科研究资料");
        manualUnsafe.setContent(SAFE_BODY_PREFIX + "研究团队宣称保**证治愈**该病。");
        assertThrows(BusinessException.class, () -> controller.updateNews(manualUnsafe, req));
        manualUnsafe.setContent("<p>" + SAFE_BODY_PREFIX + "研究团队宣称保<strong>证治愈</strong>该病。</p>");
        assertThrows(BusinessException.class, () -> controller.updateNews(manualUnsafe, req));
        manualUnsafe.setContent("<p>" + SAFE_BODY_PREFIX + "Guaranteed<br>cure</p>");
        assertThrows(BusinessException.class, () -> controller.updateNews(manualUnsafe, req));

        NewsUpdateDTO editSafePublishedUnsafe = new NewsUpdateDTO();
        editSafePublishedUnsafe.setId(reviewNewsId);
        editSafePublishedUnsafe.setTitle("IGNORE PREVIOUS 篡改标题");
        editSafePublishedUnsafe.setContent(newsService.getById(reviewNewsId).getContent());
        assertThrows(BusinessException.class, () -> controller.updateNews(editSafePublishedUnsafe, req));

        // 5) Trying to mutate candidate binding on an existing draft fails.
        News tamperBind = new News();
        tamperBind.setId(tamperedNewsId);
        tamperBind.setCurationCandidateId(needsReview.candidateId);
        assertThrows(BusinessException.class, () -> lifecycleService.applyEdit(tamperBind, adminActor, null));

        CuratedCandidateFixture wrongReceipt = createCuratedCandidate(
                "PMID-GATE-RCT", "Retinal image quality repeatability", "ACCEPT", false);
        Long wrongReceiptNews = controller.createFromCandidate(wrongReceipt.candidateId, true, req).getData();
        Long invalidatedNews = reviewNewsId;
        String originalCallId = jdbc.queryForObject("SELECT callId FROM t_news_curation_candidate WHERE id = ?",
                String.class, wrongReceipt.candidateId);
        String otherCallId = jdbc.queryForObject("SELECT callId FROM t_news_curation_candidate WHERE id = ?",
                String.class, tampered.candidateId);
        jdbc.update("UPDATE t_news_curation_candidate SET callId = ? WHERE id IN (?, ?)",
                otherCallId, wrongReceipt.candidateId, needsReview.candidateId);
        assertThrows(BusinessException.class, () -> controller.createFromCandidate(wrongReceipt.candidateId, false, req));
        long beforeReject = cacheService.readAuthoritativeDbEpoch();
        Integer publishedRevision = newsService.getById(invalidatedNews).getPublishedRevisionNo();
        assertThrows(BusinessException.class, () -> lifecycleService.recordCoverUpdate(
                invalidatedNews, "https://example.org/cover.jpg", null, adminActor));
        assertEquals(publishedRevision, newsService.getById(invalidatedNews).getPublishedRevisionNo());
        publishTampered.setId(wrongReceiptNews);
        publishTampered.setContent(newsService.getById(wrongReceiptNews).getContent());
        assertThrows(BusinessException.class, () -> controller.updateNews(publishTampered, req));
        batchTampered.setIds(List.of(wrongReceiptNews));
        assertEquals(0, controller.changeNewsStatusBatch(batchTampered, req).getData().getSucceeded());
        assertEquals(beforeReject, cacheService.readAuthoritativeDbEpoch());

        jdbc.update("""
                UPDATE t_news_curation_candidate target JOIN t_news_curation_candidate other ON other.id = ?
                SET target.callId = ?, target.evidenceSnapshotJson = other.evidenceSnapshotJson,
                    target.evidenceSnapshotSha256 = other.evidenceSnapshotSha256
                WHERE target.id = ?
                """, needsReview.candidateId, originalCallId, wrongReceipt.candidateId);
        BusinessException wrongSnapshot = assertThrows(BusinessException.class,
                () -> controller.createFromCandidate(wrongReceipt.candidateId, false, req));
        assertTrue(wrongSnapshot.getMessage().contains("快照与候选身份不一致"));

        NewsBatchStatusDTO withdraw = new NewsBatchStatusDTO();
        withdraw.setIds(List.of(invalidatedNews));
        withdraw.setStatus(NewsStatus.OFFLINE);
        withdraw.setNote("发现证据绑定错误并撤回");
        assertEquals(1, controller.changeNewsStatusBatch(withdraw, req).getData().getSucceeded());
        assertEquals(Integer.valueOf(NewsStatus.OFFLINE), newsService.getById(invalidatedNews).getStatus());
        assertEquals(needsReview.snapshotSha256, controller.listNewsRevisions(invalidatedNews).getData()
                .getLast().getEvidenceSnapshotSha256());
    }

    // Case 4
    @Test
    void legacyManualCreateEditBatchPublishAndWithdrawFlowWorksEndToEndWithRevisions() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();

        NewsUpdateDTO createDto = new NewsUpdateDTO();
        createDto.setTitle("眼科光学相干断层扫描临床应用进展");
        createDto.setContent(SAFE_BODY_PREFIX + "探讨 OCT 在眼底随访中的量化监测价值。");
        createDto.setCategory("眼科影像");
        createDto.setSource("人工采编");
        Long newsId = controller.updateNews(createDto, req).getData();
        assertEquals(Integer.valueOf(NewsStatus.DRAFT), newsService.getById(newsId).getStatus());

        // Cover upload preserves title/content/status and records COVER_UPDATE revision + audit.
        UploadPictureDetail uploadDetail = new UploadPictureDetail();
        uploadDetail.setUrl("https://cdn.iris.test/news/cover-1.png");
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadDetail);
        NewsUploadDTO uploadDto = new NewsUploadDTO();
        uploadDto.setId(newsId);
        String coverUrl = controller.uploadCoverImage(
                new MockMultipartFile("file", "cover.png", "image/png", new byte[]{1, 2, 3}),
                uploadDto, req).getData();
        assertEquals("https://cdn.iris.test/news/cover-1.png", coverUrl);
        News afterCover = newsService.getById(newsId);
        assertEquals("眼科光学相干断层扫描临床应用进展", afterCover.getTitle());
        assertEquals(Integer.valueOf(NewsStatus.DRAFT), afterCover.getStatus());

        // Publish via batch status endpoint.
        NewsBatchStatusDTO pubBatch = new NewsBatchStatusDTO();
        pubBatch.setIds(List.of(newsId));
        pubBatch.setStatus(NewsStatus.PUBLISHED);
        pubBatch.setNote("人工采编初审通过");
        assertEquals(1, controller.changeNewsStatusBatch(pubBatch, req).getData().getSucceeded());

        // Anonymous visitor sees published article.
        ACTIVE_ACTOR.remove();
        NewsQueryDTO publicQuery = new NewsQueryDTO();
        publicQuery.setCurrent(1);
        publicQuery.setPageSize(10);
        assertEquals(1L, controller.listNewsVOByPage(publicQuery, req).getData().getTotal());
        assertEquals("眼科光学相干断层扫描临床应用进展", controller.getNewsVO(newsId, req).getData().getTitle());

        // Admin withdraws article via batch offline -> anonymous visitor gets 404.
        ACTIVE_ACTOR.set(adminActor);
        NewsBatchStatusDTO offBatch = new NewsBatchStatusDTO();
        offBatch.setIds(List.of(newsId));
        offBatch.setStatus(NewsStatus.OFFLINE);
        offBatch.setNote("修订版本待更新");
        assertEquals(1, controller.changeNewsStatusBatch(offBatch, req).getData().getSucceeded());

        ACTIVE_ACTOR.remove();
        assertEquals(0L, controller.listNewsVOByPage(publicQuery, req).getData().getTotal());
        BusinessException hiddenEx = assertThrows(BusinessException.class, () -> controller.getNewsVO(newsId, req));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), hiddenEx.getCode());

        // Admin soft-deletes article and inspects full immutable revision trail.
        ACTIVE_ACTOR.set(adminActor);
        assertTrue(controller.deleteNews(newsId, req).getData());
        assertNull(newsService.getById(newsId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news WHERE id = ? AND isDelete = 1", Integer.class, newsId));

        List<NewsRevisionVO> revs = controller.listNewsRevisions(newsId).getData();
        assertEquals(List.of("CREATE", "COVER_UPDATE", "PUBLISH", "OFFLINE", "DELETE"),
                revs.stream().map(NewsRevisionVO::getAction).toList());
    }

    // Case 5
    @Test
    void cacheInvalidationRunsStrictlyAfterCommitAndDoesNotFireOnRollback() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Long id = publishManualArticle("糖尿病视网膜病变随访规范解读", "探讨定期眼底筛查窗口与健康教育意义。", req);

        // Prime Redis cache as anonymous visitor.
        ACTIVE_ACTOR.remove();
        NewsQueryDTO q = new NewsQueryDTO();
        q.setCurrent(1);
        q.setPageSize(10);
        assertEquals(1L, controller.listNewsVOByPageWithCache(q, req).getData().getTotal());
        String genBefore = rawRedis.opsForValue().get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION);
        int incrementsBefore = faultRedis.incrementCalls.get();
        long epochBefore = cacheService.readAuthoritativeDbEpoch();

        // Inject DB trigger failure on t_news_revision to force transaction rollback during withdrawal.
        jdbc.execute("""
                CREATE TRIGGER trg_fail_news_revision_insert
                BEFORE INSERT ON t_news_revision
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected revision failure'
                """);
        ACTIVE_ACTOR.set(adminActor);
        NewsBatchStatusDTO withdraw = new NewsBatchStatusDTO();
        withdraw.setIds(List.of(id));
        withdraw.setStatus(NewsStatus.OFFLINE);
        withdraw.setNote("事务回滚校验");
        try {
            assertThrows(DataAccessException.class, () -> controller.changeNewsStatusBatch(withdraw, req));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_news_revision_insert");
        }

        // Verify rollback left t_news, t_news_cache_epoch, and Redis generation completely untouched!
        assertEquals(Integer.valueOf(NewsStatus.PUBLISHED), newsService.getById(id).getStatus());
        assertEquals(epochBefore, cacheService.readAuthoritativeDbEpoch());
        assertEquals(genBefore, rawRedis.opsForValue().get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION));
        assertEquals(incrementsBefore, faultRedis.incrementCalls.get(),
                "Rolled-back write transaction MUST NEVER trigger afterCommit Redis invalidation");

        // Now commit succeeds and advances Redis strictly after commit.
        assertEquals(1, controller.changeNewsStatusBatch(withdraw, req).getData().getSucceeded());
        assertEquals(epochBefore + 1, cacheService.readAuthoritativeDbEpoch());
        assertEquals(incrementsBefore + 1, faultRedis.incrementCalls.get());
        assertEquals(String.valueOf(epochBefore + 1),
                rawRedis.opsForValue().get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION));
    }

    // Case 6
    @Test
    void withdrawalRemainsImmediatelyInvisibleEvenWhenRedisEvictionFailsAndAfterRedisRecovers() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Long idA = publishManualArticle("青光眼眼压昼夜波动监测价值", "总结眼压基线记录在慢病管理中的作用。", req);
        Long idB = publishManualArticle("高度近视定期眼底检查指南", "说明超广角眼底照相对周边视网膜观察的意义。", req);

        // Visitor populates Redis 7 cache with both articles.
        ACTIVE_ACTOR.remove();
        NewsQueryDTO q = new NewsQueryDTO();
        q.setCurrent(1);
        q.setPageSize(10);
        Page<NewsVO> firstRead = controller.listNewsVOByPageWithCache(q, req).getData();
        assertEquals(2L, firstRead.getTotal());
        long cachedEpoch = cacheService.readAuthoritativeDbEpoch();
        String staleRedisKey = buildExpectedCacheKey(cachedEpoch, q);
        assertNotNull(rawRedis.opsForValue().get(staleRedisKey), "Cached list must exist in real Redis 7");
        long legacyEpoch = cachedEpoch + 50;
        String aheadKey = buildExpectedCacheKey(legacyEpoch, q);
        rawRedis.opsForValue().set(NewsListCacheService.NEWS_LIST_CACHE_GENERATION, String.valueOf(legacyEpoch));
        rawRedis.opsForValue().set(aheadKey, rawRedis.opsForValue().get(staleRedisKey));

        // Simulate Redis eviction failure RIGHT WHEN admin withdraws article A!
        ACTIVE_ACTOR.set(adminActor);
        faultRedis.failIncrement.set(true);
        NewsBatchStatusDTO withdrawA = new NewsBatchStatusDTO();
        withdrawA.setIds(List.of(idA));
        withdrawA.setStatus(NewsStatus.OFFLINE);
        withdrawA.setNote("内容撤回且模拟 Redis 失效异常");
        BatchResultVO withdrawRes = controller.changeNewsStatusBatch(withdrawA, req).getData();
        assertEquals(1, withdrawRes.getSucceeded());
        assertEquals(1L, cacheService.getEvictionFailureCount());

        // Real Redis still holds stale generation and stale cached [A, B] payload!
        assertNotEquals(String.valueOf(cacheService.readAuthoritativeDbEpoch()),
                rawRedis.opsForValue().get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION));
        assertNotNull(rawRedis.opsForValue().get(staleRedisKey));

        // Visitor reads immediately using the DB epoch despite the higher legacy Redis generation.
        faultRedis.failIncrement.set(false);
        ACTIVE_ACTOR.remove();
        Page<NewsVO> afterWithdrawRead = controller.listNewsVOByPageWithCache(q, req).getData();
        assertEquals(1L, afterWithdrawRead.getTotal());
        String afterWithdrawJson = JSONUtil.toJsonStr(afterWithdrawRead.getRecords());
        assertFalse(afterWithdrawJson.contains("青光眼眼压昼夜波动监测价值"),
                "Withdrawn article MUST NOT leak from stale Redis cache");
        assertTrue(afterWithdrawJson.contains("高度近视定期眼底检查指南"));
        assertEquals(String.valueOf(legacyEpoch),
                rawRedis.opsForValue().get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION));

        // Total Redis outage while deleting article B: withdrawal still succeeds and reads fall back cleanly to MySQL!
        faultRedis.failAllOps.set(true);
        ACTIVE_ACTOR.set(adminActor);
        assertTrue(controller.deleteNews(idB, req).getData());

        ACTIVE_ACTOR.remove();
        Page<NewsVO> duringOutage = controller.listNewsVOByPageWithCache(q, req).getData();
        assertEquals(0L, duringOutage.getTotal());

        // After Redis comes back online, stale keys stay bypassed and 0 articles remain visible.
        faultRedis.failAllOps.set(false);
        Page<NewsVO> afterRecovery = controller.listNewsVOByPageWithCache(q, req).getData();
        assertEquals(0L, afterRecovery.getTotal());
    }

    // Case 7
    @Test
    void batchPublishPartialFailureCommitsValidItemsAndInvalidatesCacheOnce() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Long validDraftId = createManualDraft("早产儿视网膜病变随访标准", "阐述早产儿眼底筛查时间窗与随访计划。", req);
        CuratedCandidateFixture needsReview = createCuratedCandidate(
                "PMID-BATCH-REV", "Retinal vein occlusion anti-VEGF regimen", "NEEDS_REVIEW", false);
        Long unreviewedCandNewsId = controller.createFromCandidate(needsReview.candidateId, false, req).getData();

        // Prime Redis generation at current epoch.
        ACTIVE_ACTOR.remove();
        NewsQueryDTO q = new NewsQueryDTO();
        q.setCurrent(1);
        q.setPageSize(10);
        controller.listNewsVOByPageWithCache(q, req);
        int redisIncBefore = faultRedis.incrementCalls.get();

        // Batch publish: validDraftId + unreviewedCandNewsId + validDraft2 (coalesced in 1 TX).
        ACTIVE_ACTOR.set(adminActor);
        Long validDraftId2 = createManualDraft("年龄相关性白内障术后随访说明", "介绍白内障术后复查周期与用眼卫生建议。", req);
        int redisIncAfterDrafts = faultRedis.incrementCalls.get();
        long epochBeforeBatch = cacheService.readAuthoritativeDbEpoch();

        NewsBatchStatusDTO batch = new NewsBatchStatusDTO();
        batch.setIds(List.of(validDraftId, unreviewedCandNewsId, validDraftId2));
        batch.setStatus(NewsStatus.PUBLISHED);
        batch.setNote("批量发布含一条未走复核流转的候选");
        BatchResultVO res = controller.changeNewsStatusBatch(batch, req).getData();

        assertEquals(3, res.getRequested());
        assertEquals(2, res.getSucceeded());
        assertEquals(List.of(String.valueOf(unreviewedCandNewsId)), res.getFailedIds());
        assertEquals(Integer.valueOf(NewsStatus.PUBLISHED), newsService.getById(validDraftId).getStatus());
        assertEquals(Integer.valueOf(NewsStatus.PUBLISHED), newsService.getById(validDraftId2).getStatus());
        assertEquals(Integer.valueOf(NewsStatus.DRAFT), newsService.getById(unreviewedCandNewsId).getStatus());

        assertTrue(redisIncAfterDrafts > redisIncBefore);
        assertEquals(redisIncAfterDrafts + 1, faultRedis.incrementCalls.get(),
                "Multiple valid items in one batch transaction must coalesce into ONE afterCommit Redis invalidation");
        assertEquals(epochBeforeBatch + 2, cacheService.readAuthoritativeDbEpoch());
    }

    // Case 8
    @Test
    void evidenceSnapshotImmutabilityPreservesPublishedRevisionHistoryAcrossSubsequentEditsAndOffline()
            throws Exception {
        CuratedCandidateFixture accepted = createCuratedCandidate(
                "PMID-IMMUT-1", "Optic nerve head blood flow OCTA repeatability", "ACCEPT", false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        Long newsId = controller.createFromCandidate(accepted.candidateId, false, req).getData();

        // Rev 2: submit review
        NewsUpdateDTO submit = new NewsUpdateDTO();
        submit.setId(newsId);
        submit.setContent(newsService.getById(newsId).getContent());
        submit.setStatus(NewsStatus.PENDING_REVIEW);
        controller.updateNews(submit, req);

        // Rev 3: publish
        NewsUpdateDTO publish = new NewsUpdateDTO();
        publish.setId(newsId);
        publish.setContent(newsService.getById(newsId).getContent());
        publish.setStatus(NewsStatus.PUBLISHED);
        publish.setNote("首次审校发布");
        controller.updateNews(publish, req);

        // Rev 4: edit content while published
        NewsUpdateDTO editPublished = new NewsUpdateDTO();
        editPublished.setId(newsId);
        editPublished.setTitle("视盘血流 OCTA 重复性评估（编辑修订版）");
        editPublished.setContent(SAFE_BODY_PREFIX + "补充视盘血流重复性研究方法学限制与适用条件说明。");
        controller.updateNews(editPublished, req);

        // Rev 5: take offline
        NewsBatchStatusDTO offline = new NewsBatchStatusDTO();
        offline.setIds(List.of(newsId));
        offline.setStatus(NewsStatus.OFFLINE);
        offline.setNote("专题归档下架");
        controller.changeNewsStatusBatch(offline, req);

        News latest = newsService.getById(newsId);
        assertEquals(Integer.valueOf(5), latest.getCurrentRevisionNo());
        assertEquals(Integer.valueOf(4), latest.getPublishedRevisionNo());
        assertEquals(accepted.candidateId, latest.getCurationCandidateId());
        assertEquals(accepted.materialRevisionId, latest.getMaterialRevisionId());
        assertEquals(accepted.snapshotSha256, latest.getEvidenceSnapshotSha256());

        List<NewsRevisionVO> revisions = controller.listNewsRevisions(newsId).getData();
        assertEquals(5, revisions.size());
        assertEquals(List.of("CREATE_FROM_CANDIDATE", "SUBMIT_REVIEW", "PUBLISH", "EDIT", "OFFLINE"),
                revisions.stream().map(NewsRevisionVO::getAction).toList());
        assertNotEquals(revisions.get(2).getContentSha256(), revisions.get(3).getContentSha256());
        for (NewsRevisionVO rev : revisions) {
            assertEquals(accepted.snapshotSha256, rev.getEvidenceSnapshotSha256());
            assertTrue(rev.getEvidenceSnapshotJson().contains("Optic nerve head blood flow OCTA repeatability"));
        }
    }

    private Long createManualDraft(String title, String suffix, HttpServletRequest req) {
        NewsUpdateDTO dto = new NewsUpdateDTO();
        dto.setTitle(title);
        dto.setContent(SAFE_BODY_PREFIX + suffix);
        dto.setCategory("临床前沿");
        dto.setSource("人工编辑");
        return controller.updateNews(dto, req).getData();
    }

    private Long publishManualArticle(String title, String suffix, HttpServletRequest req) {
        NewsUpdateDTO dto = new NewsUpdateDTO();
        dto.setTitle(title);
        dto.setContent(SAFE_BODY_PREFIX + suffix);
        dto.setCategory("临床前沿");
        dto.setSource("人工编辑");
        dto.setStatus(NewsStatus.PUBLISHED);
        return controller.updateNews(dto, req).getData();
    }

    private CuratedCandidateFixture createCuratedCandidate(String recordId,
                                                           String title,
                                                           String behavior,
                                                           boolean allowSecondCall) throws Exception {
        long revId = ingestAndCompleteSingle("pubmed", recordId, title);
        modelClient.enqueue(req -> switch (behavior) {
            case "ACCEPT" -> new NewsCurationModels.Succeeded(
                    validCurationJson("ACCEPT", title), 400, 140, 2_500L, 200, "req-" + recordId, 200);
            case "NEEDS_REVIEW" -> new NewsCurationModels.Succeeded(
                    validCurationJson("NEEDS_REVIEW", title), 420, 150, 2_700L, 200, "req-" + recordId, 210);
            case "BAD_QUOTE" -> new NewsCurationModels.Succeeded(
                    validCurationJson("ACCEPT", "completely nonexistent quote phrase"),
                    300, 100, 1_800L, 200, "req-" + recordId, 180);
            case "UNKNOWN_OUTCOME" -> new NewsCurationModels.UnknownOutcome(
                    "READ_TIMEOUT", null, "req-" + recordId, 5_000);
            default -> throw new IllegalArgumentException("Unknown behavior: " + behavior);
        });
        if (!allowSecondCall) {
            curationService.configureDailyBudget(BUDGET_DATE, SCOPE_GLOBAL, 50, 200_000L, 2_000_000L);
        }
        NewsCurationModels.CurationResult result = curationService.curateRevision(
                revId, POLICY_V1, BUDGET_DATE, SCOPE_GLOBAL);
        Long candidateId = jdbc.queryForObject(
                "SELECT id FROM t_news_curation_candidate WHERE materialRevisionId = ? AND policyVersion = ?",
                Long.class, revId, POLICY_V1);
        return new CuratedCandidateFixture(candidateId, revId, result.evidenceSnapshotSha256());
    }

    private long ingestAndCompleteSingle(String source, String recordId, String title) throws Exception {
        String taskId = "pub-task-" + recordId + "-" + UUID.randomUUID().toString().substring(0, 8);
        long configVersion = Math.abs(recordId.hashCode()) + 1L;
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);
        ingestionService.enqueueWindow(taskId, source, configVersion, "ophthalmology", start, end, 3);
        NewsWireModels.Lease lease = ingestionService.claim(WORKER).orElseThrow();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", source);
        record.put("sourceRecordId", recordId);
        record.put("url", "https://pubmed.ncbi.nlm.nih.gov/49000001/");
        record.put("title", title);
        record.put("publishedOn", "2026-10-05");
        record.put("abstract", "Peer-reviewed ophthalmic abstract evaluating " + title + " in clinical screening.");
        record.put("abstractLicense", "CC-BY");

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("generation", lease.generation());
        batch.put("leaseToken", lease.leaseToken());
        batch.put("sequence", 1);
        batch.put("records", List.of(record));
        ingestionService.submitBatch(WORKER, lease.taskId(), lease.generation() + ":1",
                protocolMapper.writeValueAsBytes(batch));

        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("generation", lease.generation());
        complete.put("leaseToken", lease.leaseToken());
        complete.put("batchCount", 1);
        complete.put("recordCount", 1);
        complete.put("nextCursor", end.toString());
        ingestionService.complete(WORKER, lease.taskId(), lease.generation() + ":complete",
                protocolMapper.writeValueAsBytes(complete));
        return ingestionService.listVisibleRevisions(source, recordId).getLast().revisionId();
    }

    private static String validCurationJson(String decision, String quoteText) {
        return """
                {
                  "decision": "%s",
                  "relevanceScore": 86,
                  "suggestedTitle": "眼科临床研究进展：%s",
                  "suggestedSummary": "客观介绍 %s 相关的眼科研究背景与主要指标观察结论，仅供参考。",
                  "category": "眼底疾病研究",
                  "limitations": ["仅基于公开摘要数据总结，实际诊疗仍需结合专科医师评估。"],
                  "claims": [
                    {
                      "id": "c1",
                      "field": "abstract",
                      "statement": "原文摘要明确评估主题 %s。",
                      "quote": "%s"
                    }
                  ]
                }
                """.formatted(decision, quoteText, quoteText, quoteText, quoteText);
    }

    private static String buildExpectedCacheKey(long epoch, NewsQueryDTO dto) {
        NewsQueryDTO copy = new NewsQueryDTO();
        copy.setCurrent(dto.getCurrent());
        copy.setPageSize(dto.getPageSize());
        copy.setVisibleOnly(true);
        String cond = JSONUtil.toJsonStr(copy);
        String md5 = org.springframework.util.DigestUtils.md5DigestAsHex(
                (epoch + ":" + cond).getBytes(StandardCharsets.UTF_8));
        return NewsListCacheService.NEWS_LIST_CACHE_PREFIX + md5;
    }

    private static String envOrDefault(String key, String fallback) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? fallback : val;
    }

    private static String withSchema(String url, String targetSchema) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String params = query < 0 ? "" : url.substring(query);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + targetSchema + params;
    }

    @Configuration
    @EnableTransactionManagement
    static class PublishTestSpringConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return jdbc;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return rawRedis;
        }

        @Bean(name = "newsProtocolObjectMapper")
        ObjectMapper newsProtocolObjectMapper() {
            return JsonConfig.createNewsProtocolObjectMapper();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate() throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(false);
            configuration.addMapper(NewsMapper.class);
            configuration.addMapper(NewsAuditMapper.class);
            configuration.addMapper(UserMapper.class);

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
            factory.setMapperLocations(new ClassPathResource("mapper/UserMapper.xml"));
            return new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        }

        @Bean
        NewsMapper newsMapper(SqlSessionTemplate sqlSession) {
            return sqlSession.getMapper(NewsMapper.class);
        }

        @Bean
        NewsAuditMapper newsAuditMapper(SqlSessionTemplate sqlSession) {
            return sqlSession.getMapper(NewsAuditMapper.class);
        }

        @Bean
        UserMapper userMapper(SqlSessionTemplate sqlSession) {
            return sqlSession.getMapper(UserMapper.class);
        }

        @Bean
        NewsListCacheService newsListCacheService(JdbcTemplate jdbcTemplate) {
            return new NewsListCacheService(faultRedis, jdbcTemplate);
        }

        @Bean
        NewsLifecycleService newsLifecycleService(NewsMapper newsMapper,
                                                  NewsAuditMapper newsAuditMapper,
                                                  JdbcTemplate jdbcTemplate,
                                                  NewsListCacheService cacheService,
                                                  ObjectMapper newsProtocolObjectMapper) {
            return new NewsLifecycleService(
                    newsMapper, newsAuditMapper, jdbcTemplate, cacheService, newsProtocolObjectMapper);
        }

        @Bean
        NewsServiceImpl newsService() {
            return new NewsServiceImpl();
        }

        @Bean
        NewsController newsController(NewsService newsService,
                                      NewsLifecycleService lifecycleService,
                                      UserService userService,
                                      FileManager fileManager,
                                      StringRedisTemplate redisTemplate,
                                      NewsListCacheService cacheService) {
            NewsController controller = new NewsController();
            ReflectionTestUtils.setField(controller, "newsService", newsService);
            ReflectionTestUtils.setField(controller, "newsLifecycleService", lifecycleService);
            ReflectionTestUtils.setField(controller, "userService", userService);
            ReflectionTestUtils.setField(controller, "fileManager", fileManager);
            ReflectionTestUtils.setField(controller, "stringRedisTemplate", redisTemplate);
            ReflectionTestUtils.setField(controller, "newsListCacheService", cacheService);
            return controller;
        }
    }

    private record CuratedCandidateFixture(long candidateId, long materialRevisionId, String snapshotSha256) {
    }

    private static final class FaultInjectableReactiveRedisTemplate extends ReactiveStringRedisTemplate {
        private final AtomicBoolean failIncrement = new AtomicBoolean(false);
        private final AtomicBoolean failAllOps = new AtomicBoolean(false);
        private final AtomicInteger incrementCalls = new AtomicInteger(0);

        private FaultInjectableReactiveRedisTemplate(LettuceConnectionFactory factory) {
            super(factory);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ReactiveValueOperations<String, String> opsForValue() {
            ReactiveValueOperations<String, String> delegate = super.opsForValue();
            return (ReactiveValueOperations<String, String>) Proxy.newProxyInstance(
                    ReactiveValueOperations.class.getClassLoader(),
                    new Class<?>[]{ReactiveValueOperations.class},
                    (proxy, method, args) -> {
                        if ("increment".equals(method.getName())
                                && args != null && args.length >= 1
                                && NewsListCacheService.NEWS_LIST_CACHE_GENERATION.equals(args[0])) {
                            incrementCalls.incrementAndGet();
                            if (failIncrement.get() || failAllOps.get()) {
                                throw new RedisConnectionFailureException("Injected Redis increment failure");
                            }
                        } else if (failAllOps.get()) {
                            throw new RedisConnectionFailureException("Injected Redis outage");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    }
            );
        }
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
