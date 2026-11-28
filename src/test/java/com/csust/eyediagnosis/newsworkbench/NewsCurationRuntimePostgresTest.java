package com.csust.eyediagnosis.newsworkbench;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.csust.eyediagnosis.common.BatchResultVO;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.constant.NewsStatus;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import com.csust.eyediagnosis.mapper.NewsAuditMapper;
import com.csust.eyediagnosis.mapper.NewsMapper;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newscuration.NewsCurationModelClient;
import com.csust.eyediagnosis.newscuration.NewsCurationModels;
import com.csust.eyediagnosis.newscuration.NewsCurationPromptBuilder;
import com.csust.eyediagnosis.newscuration.NewsCurationProperties;
import com.csust.eyediagnosis.newscuration.NewsCurationService;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger;
import com.csust.eyediagnosis.newscuration.NewsEvidenceVerifier;
import com.csust.eyediagnosis.newscuration.NewsModelProfile;
import com.csust.eyediagnosis.newsingestion.NewsIngestionProperties;
import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsWireCodec;
import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.csust.eyediagnosis.newsingestion.NewsWorkerIdentity;
import com.csust.eyediagnosis.service.NewsLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1/N3 runtime-closure regression: the admin-triggered production P05 entry, its evidence gate,
 * reserve-before-IO receipts, budget/circuit/unknown semantics and the chain into a review candidate
 * and an unreviewed draft. Runs on the real PostgreSQL business_test schema.
 *
 * <p>Collection → evidence verification → admin trigger → receipt → candidate → draft is exercised end
 * to end with a scripted model client (never a real provider). Every error class asserted here maps to a
 * distinct receipt/budget state; the auth circuit is proven not to trip on a free-quota exhaustion.
 */
class NewsCurationRuntimePostgresTest {

    private static final String POLICY_V1 = "news-curation-v1";
    private static final String SCOPE = "GLOBAL";
    private static final NewsWorkerIdentity WORKER =
            new NewsWorkerIdentity("runtime-it-worker", NewsIngestionProperties.ALL_SOURCES);

    private static JdbcTemplate jdbc;
    private static javax.sql.DataSource dataSource;
    private static SqlSessionTemplate sqlSessionTemplate;

    private ObjectMapper mapper;
    private NewsWireCodec wireCodec;
    private NewsIngestionService ingestionService;
    private NewsCurationProperties curationProperties;
    private NewsCurationPromptBuilder promptBuilder;
    private NewsEvidenceVerifier verifier;
    private ScriptedModelClient modelClient;
    private NewsCurationService curationService;
    private NewsWorkbenchService workbenchService;
    private NewsLifecycleService lifecycleService;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        String url = LegacyGateSupport.assumeIsolated(System.getenv("BUSINESS_TEST_URL"), "business_test");
        ensureDatabase(url);

        jdbc.execute("DELETE FROM t_news_collection_task_event");
        jdbc.execute("DELETE FROM t_news_collection_schedule");
        jdbc.execute("DELETE FROM t_news_revision");
        jdbc.execute("DELETE FROM t_news_audit");
        jdbc.execute("DELETE FROM t_news");
        jdbc.execute("DELETE FROM t_news_cache_epoch");
        jdbc.execute("INSERT INTO t_news_cache_epoch (scope, epoch) VALUES ('news_list', 0)");
        jdbc.execute("DELETE FROM t_news_bench_run");
        jdbc.execute("DELETE FROM t_news_curation_candidate_event");
        jdbc.execute("DELETE FROM t_news_curation_lifetime_budget");
        jdbc.execute("DELETE FROM t_news_curation_candidate");
        jdbc.execute("DELETE FROM t_news_evidence_verification");
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
        jdbc.execute("DELETE FROM t_user");
        jdbc.execute("""
                INSERT INTO t_user (id, userAccount, userPassword, userName, userRole)
                VALUES (1001, 'admin_runtime', 'hash', 'Runtime Admin', 'admin')
                """);

        mapper = JsonConfig.createNewsProtocolObjectMapper();
        wireCodec = new NewsWireCodec(mapper);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        NewsIngestionProperties ingestionProps = new NewsIngestionProperties();
        ingestionProps.setEnabled(true);
        ingestionService = new NewsIngestionService(jdbc, txManager, ingestionProps, wireCodec);

        curationProperties = new NewsCurationProperties();
        curationProperties.setEnabled(true);
        curationProperties.setPolicyVersion(POLICY_V1);
        curationProperties.setProvider("dashscope");
        curationProperties.setModelName("qwen3.8-max");
        curationProperties.setCallTimeout(Duration.ofSeconds(20));
        curationProperties.setDailyMaxCalls(50);
        curationProperties.setDailyMaxTokens(200_000L);
        curationProperties.setDailyMaxCostMicros(2_000_000L);
        curationProperties.setEstimatedTokensPerCall(4_000L);
        curationProperties.setEstimatedCostMicrosPerCall(50_000L);
        curationProperties.setModelProfiles(Map.of("prod", stagedProfile("qwen3.8-max-0902")));
        curationProperties.setRunProfileKey("prod");

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

        admin = new User();
        admin.setId(1001L);
        admin.setUserRole(AuthConstant.ADMIN_ROLE);
        admin.setUserName("Runtime Admin");
    }

    @Test
    void profileIsArbitratedByServerConfigurationOnly() {
        curationProperties.setModelProfiles(Map.of());
        assertEquals("MODEL_PROFILE_NOT_CONFIGURED",
                assertThrows(IllegalStateException.class, () -> curationService.resolveRunProfileKey()).getMessage());

        curationProperties.setRunProfileKey(null);
        curationProperties.setModelProfiles(Map.of("a", stagedProfile("m-a")));
        assertEquals("a", curationService.resolveRunProfileKey());

        curationProperties.setModelProfiles(Map.of("a", stagedProfile("m-a"), "b", stagedProfile("m-b")));
        assertEquals("MODEL_PROFILE_NOT_CONFIGURED",
                assertThrows(IllegalStateException.class, () -> curationService.resolveRunProfileKey()).getMessage());

        curationProperties.setRunProfileKey("b");
        assertEquals("b", curationService.resolveRunProfileKey());
        curationProperties.setRunProfileKey("missing");
        assertEquals("MODEL_PROFILE_NOT_CONFIGURED",
                assertThrows(IllegalStateException.class, () -> curationService.resolveRunProfileKey()).getMessage());
    }

    @Test
    void retractedMaterialIsRefusedBeforeAnyReservationOrModelCall() throws Exception {
        long revision = ingest("pubmed", "PMID-RETRACT-1", "A retracted ophthalmic trial");
        curationService.verifyEvidence(revision,
                evidence(NewsEvidenceLedger.Attribute.INTEGRITY, "RETRACTED", NewsEvidenceLedger.Method.RETRACTION_NOTICE),
                "admin:1001");

        NewsCurationService.RevisionCuration view = curationService.describeRevision(revision);
        assertEquals("SKIP", view.eligibleType());
        assertFalse(view.evidenceGateOpen());
        assertFalse(view.curatable());
        assertEquals("INTEGRITY_RETRACTED", view.gateReason());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> curationService.runAdminCuration(revision, "admin:1001"));
        assertTrue(refused.getMessage().startsWith("EVIDENCE_GATE_SKIP"));
        assertEquals(0, modelClient.invocations.get(), "SKIP 证据路由不得发出付费请求");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_news_model_call_receipt", Integer.class));
    }

    @Test
    void fullChainFromCollectionToUnreviewedDraft() throws Exception {
        long revision = ingest("pubmed", "PMID-CHAIN-1", "Deep learning diabetic retinopathy screening");
        fullyVerify(revision);

        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.MaterialCurationVO> page =
                workbenchService.listCurationMaterials("pubmed", 1, 10);
        assertEquals(1, page.total());
        NewsWorkbenchModels.MaterialCurationVO row = page.records().getFirst();
        assertEquals(revision, row.materialRevisionId());
        assertEquals("RESEARCH_INSIGHT", row.eligibleType());
        assertTrue(row.curatable());
        assertEquals("prod", row.profileKey());
        assertNull(row.candidateId());
        assertNull(row.latestReceiptState());

        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-chain-1"));
        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        assertEquals("SUCCEEDED", run.state());
        assertEquals("RESEARCH_INSIGHT", run.eligibleType());
        assertFalse(run.replayed());
        assertTrue(run.materializable());
        assertTrue(run.frozenOutputAvailable());
        assertEquals(1, modelClient.invocations.get());
        assertNotNull(run.callId());

        List<NewsWorkbenchModels.CallReceiptVO> receipts = workbenchService.listMaterialReceipts(revision);
        assertEquals(1, receipts.size());
        assertEquals("SUCCEEDED", receipts.getFirst().state());

        NewsWorkbenchModels.MaterializedCandidateVO candidate =
                workbenchService.materializeCandidate(run.callId(), admin);
        assertTrue(candidate.created());
        assertEquals("CURATED_NEEDS_REVIEW", candidate.status());
        assertNotNull(candidate.evidenceVerificationSha256());

        NewsWorkbenchModels.MaterialCurationVO after = workbenchService.listCurationMaterials("pubmed", 1, 10)
                .records().getFirst();
        assertEquals(candidate.candidateId(), after.candidateId());
        assertEquals("CURATED_NEEDS_REVIEW", after.candidateStatus());
        assertEquals("SUCCEEDED", after.latestReceiptState());

        // 候选可以进入未发布草稿，但仍绑定候选/修订/证据摘要，且停在待审核而非已发布。
        Long newsId = lifecycleService.createDraftFromCandidate(candidate.candidateId(), admin, true);
        Map<String, Object> news = jdbc.queryForMap(
                "SELECT status, curationCandidateId, materialRevisionId FROM t_news WHERE id = ?", newsId);
        assertEquals(NewsStatus.PENDING_REVIEW, ((Number) news.get("status")).intValue());
        assertEquals(candidate.candidateId(), ((Number) news.get("curationCandidateId")).longValue());
        assertEquals(revision, ((Number) news.get("materialRevisionId")).longValue());

        // 人工审核通过后发布：公开列表口径（isDelete=0 且 PUBLISHED）能读到这一行，并留下发布修订。
        BatchResultVO published = lifecycleService.changeStatusBatch(
                List.of(newsId), NewsStatus.PUBLISHED, "证据、引文与医学表述已复核，同意发布", admin);
        assertEquals(1, published.getSucceeded());
        Map<String, Object> publishedRow = jdbc.queryForMap(
                "SELECT status, publishedRevisionNo FROM t_news WHERE id = ?", newsId);
        assertEquals(NewsStatus.PUBLISHED, ((Number) publishedRow.get("status")).intValue());
        assertNotNull(publishedRow.get("publishedRevisionNo"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news WHERE id = ? AND isDelete = 0 AND status = ?",
                Integer.class, newsId, NewsStatus.PUBLISHED));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_revision WHERE newsId = ? AND status = ?",
                Integer.class, newsId, NewsStatus.PUBLISHED));
    }

    @Test
    void unreviewedDraftCannotBePublishedDirectly() throws Exception {
        long revision = ingest("pubmed", "PMID-NOPUB-1", "Unreviewed draft must not publish");
        fullyVerify(revision);
        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-nopub-1"));
        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        NewsWorkbenchModels.MaterializedCandidateVO candidate =
                workbenchService.materializeCandidate(run.callId(), admin);

        Long draftId = lifecycleService.createDraftFromCandidate(candidate.candidateId(), admin, false);
        BatchResultVO refused = lifecycleService.changeStatusBatch(
                List.of(draftId), NewsStatus.PUBLISHED, "尝试跳过审核", admin);
        assertEquals(0, refused.getSucceeded());
        assertEquals(1, refused.getFailed());
        assertTrue(refused.getMessage().contains("待复核候选必须"));
        assertEquals(NewsStatus.DRAFT, jdbc.queryForObject(
                "SELECT status FROM t_news WHERE id = ?", Integer.class, draftId));
    }

    @Test
    void duplicateTriggerReplaysTheSameReceiptWithoutASecondModelCall() throws Exception {
        long revision = ingest("pubmed", "PMID-DUP-1", "Repeatable ophthalmic curation run");
        fullyVerify(revision);
        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-dup-1"));

        NewsWorkbenchModels.CurationRunVO first = workbenchService.runCuration(revision, admin);
        NewsWorkbenchModels.CurationRunVO second = workbenchService.runCuration(revision, admin);
        assertEquals(first.callId(), second.callId());
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(1, modelClient.invocations.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_news_model_call_receipt", Integer.class));
    }

    @Test
    void freeQuotaExhaustionIsDefinitiveAndDoesNotTripTheCircuit() throws Exception {
        long revision = ingest("pubmed", "PMID-QUOTA-1", "Free tier quota exhaustion trial");
        fullyVerify(revision);
        modelClient.enqueue(req -> new NewsCurationModels.DefinitiveFailure(
                "MODEL_FREE_QUOTA_EXHAUSTED", 403, null, false, 210));

        // 额度耗尽是一次确定的失败：回执落 FAILED_DEFINITIVE 并返回给管理员，不抛异常、不重试、不换模型。
        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        assertEquals("FAILED_DEFINITIVE", run.state());
        assertEquals("MODEL_FREE_QUOTA_EXHAUSTED", run.errorCode());
        assertFalse(run.materializable());
        assertFalse(run.frozenOutputAvailable());
        assertEquals(1, modelClient.invocations.get());

        Map<String, Object> receipt = jdbc.queryForMap(
                "SELECT state, errorCode FROM t_news_model_call_receipt");
        assertEquals("FAILED_DEFINITIVE", receipt.get("state"));
        assertEquals("MODEL_FREE_QUOTA_EXHAUSTED", receipt.get("errorCode"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_curation_circuit WHERE circuitReason IS NOT NULL", Integer.class),
                "额度耗尽不是鉴权失败，不得熔断");

        Map<String, Object> budget = jdbc.queryForMap(
                "SELECT usedCalls, reservedCalls, usedTokens, reservedTokens FROM t_news_curation_budget");
        assertEquals(0, ((Number) budget.get("usedCalls")).intValue());
        assertEquals(0, ((Number) budget.get("reservedCalls")).intValue());
        assertEquals(0L, ((Number) budget.get("reservedTokens")).longValue());

        // 失败回执是确定结果：再次触发会重放失败回执，不再发出第二次请求。
        NewsWorkbenchModels.CurationRunVO replay = workbenchService.runCuration(revision, admin);
        assertEquals("FAILED_DEFINITIVE", replay.state());
        assertTrue(replay.replayed());
        assertFalse(replay.materializable());
        assertEquals(1, modelClient.invocations.get());

        // 列表对确定性失败不再宣称「可运行」：按钮层不会承诺一个只会重放的进度。
        NewsCurationService.RevisionCuration after = curationService.describeRevision(revision);
        assertFalse(after.curatable());
        assertEquals("TERMINAL_FAILURE_REPLAY_ONLY", after.gateReason());
    }

    @Test
    void authFailureOpensCircuitAndBlocksFurtherRuns() throws Exception {
        long revision = ingest("pubmed", "PMID-AUTH-1", "Authentication failure trial");
        fullyVerify(revision);
        modelClient.enqueue(req -> new NewsCurationModels.DefinitiveFailure(
                "MODEL_AUTH_REJECTED", 401, null, true, 180));

        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        assertEquals("FAILED_DEFINITIVE", run.state());
        assertEquals("MODEL_AUTH_REJECTED", run.errorCode());
        assertFalse(run.materializable());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_curation_circuit WHERE circuitReason = 'MODEL_AUTH_REJECTED'",
                Integer.class));

        // 同一修订再次触发只重放那次失败回执，不会发第二次请求。
        NewsWorkbenchModels.CurationRunVO replay = workbenchService.runCuration(revision, admin);
        assertEquals("FAILED_DEFINITIVE", replay.state());
        assertTrue(replay.replayed());

        // 熔断对新的付费调用生效：换一条修订也必须先重置熔断。
        long other = ingest("pubmed", "PMID-AUTH-2", "Second study after auth failure");
        fullyVerify(other);
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> workbenchService.runCuration(other, admin));
        assertTrue(blocked.getMessage().contains("熔断"));
        assertEquals(1, modelClient.invocations.get(), "熔断后不得再发请求");
    }

    @Test
    void rateLimitBecomesUnknownAndMustBeReconciledBeforeAnotherRun() throws Exception {
        long revision = ingest("pubmed", "PMID-RATE-1", "Rate limited trial");
        fullyVerify(revision);
        modelClient.enqueue(req -> new NewsCurationModels.UnknownOutcome("MODEL_RATE_LIMITED", 429, null, 150));

        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        assertEquals("OUTCOME_UNKNOWN", run.state());
        assertFalse(run.materializable());
        assertEquals("MODEL_RATE_LIMITED", run.errorCode());

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> workbenchService.runCuration(revision, admin));
        assertTrue(blocked.getMessage().contains("待对账"));
        assertEquals(1, modelClient.invocations.get());

        // 列表不得宣称「可运行」：同一个 logical 调用在 reserveAttempt 里必然被待对账拒绝。
        NewsCurationService.RevisionCuration after = curationService.describeRevision(revision);
        assertFalse(after.curatable());
        assertEquals("AWAITING_RECONCILIATION", after.gateReason());
        assertEquals("OUTCOME_UNKNOWN", after.latestReceiptState());
    }

    @Test
    void dailyBudgetExhaustionRefusesTheSecondRunBeforeCallingTheModel() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        curationService.configureDailyBudget(today, SCOPE, 1, 200_000L, 2_000_000L);
        long first = ingest("pubmed", "PMID-BUDGET-1", "Budget bound study one");
        long second = ingest("pubmed", "PMID-BUDGET-2", "Budget bound study two");
        fullyVerify(first);
        fullyVerify(second);

        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-budget-1"));
        assertEquals("SUCCEEDED", workbenchService.runCuration(first, admin).state());

        BusinessException exhausted = assertThrows(BusinessException.class,
                () -> workbenchService.runCuration(second, admin));
        assertTrue(exhausted.getMessage().contains("预算已耗尽"));
        assertEquals(1, modelClient.invocations.get(), "预算耗尽必须在校验阶段拒绝，不发请求");
    }

    @Test
    void invalidStructuredOutputIsChargedFailedAndProducesNoCandidate() throws Exception {
        long revision = ingest("pubmed", "PMID-BADJSON-1", "Malformed output study");
        fullyVerify(revision);
        modelClient.enqueue(req -> success("{\"decision\":\"ACCEPT\"}", 420, 60, 700L, "req-bad-1"));

        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        assertEquals("FAILED_CHARGED", run.state());
        assertFalse(run.materializable());
        assertFalse(run.frozenOutputAvailable());
        assertEquals("INVALID_STRUCTURED_OUTPUT", run.errorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_news_curation_candidate", Integer.class));
    }

    @Test
    void evidenceRevocationAfterTheRunBlocksMaterialization() throws Exception {
        long revision = ingest("pubmed", "PMID-REVOKE-1", "Evidence revocation study");
        fullyVerify(revision);
        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-revoke-1"));
        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(revision, admin);
        assertEquals("SUCCEEDED", run.state());

        long verificationId = jdbc.queryForObject("""
                SELECT id FROM t_news_evidence_verification
                WHERE materialRevisionId = ? AND attribute = 'PEER_REVIEW' AND revokedAt IS NULL
                """, Long.class, revision);
        curationService.revokeEvidence(verificationId, "admin:1001", "复核撤回");

        BusinessException stale = assertThrows(BusinessException.class,
                () -> workbenchService.materializeCandidate(run.callId(), admin));
        assertTrue(stale.getMessage().contains("证据核验已撤回或变更"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_curation_candidate WHERE materialRevisionId = ?",
                Integer.class, revision));
    }

    @Test
    void supersededRevisionIsRefusedByCurateAndMaterializeAfterANewerIngest() throws Exception {
        long oldRevision = ingest("pubmed", "PMID-STALE-1", "First version of the screening study");
        fullyVerify(oldRevision);
        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-stale-1"));
        NewsWorkbenchModels.CurationRunVO run = workbenchService.runCuration(oldRevision, admin);
        assertEquals("SUCCEEDED", run.state());

        // 同一资料被重新采集为新修订：旧修订仍然可见，但已不是 t_news_material.latestRevisionId。
        long latest = ingest("pubmed", "PMID-STALE-1", "Second version of the screening study", "PMID-STALE-1-v2");
        assertTrue(latest != oldRevision);
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_news_material_revision WHERE source = 'pubmed' AND sourceRecordId = 'PMID-STALE-1'
                """, Integer.class));

        BusinessException staleMaterialize = assertThrows(BusinessException.class,
                () -> workbenchService.materializeCandidate(run.callId(), admin));
        assertTrue(staleMaterialize.getMessage().contains("更新版本"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_curation_candidate WHERE materialRevisionId = ?",
                Integer.class, oldRevision));

        int callsBefore = modelClient.invocations.get();
        int receiptsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM t_news_model_call_receipt", Integer.class);
        BusinessException staleRun = assertThrows(BusinessException.class,
                () -> workbenchService.runCuration(oldRevision, admin));
        assertTrue(staleRun.getMessage().contains("更新版本"));
        assertEquals(callsBefore, modelClient.invocations.get(), "过期修订不得再发起付费调用");
        assertEquals(receiptsBefore,
                jdbc.queryForObject("SELECT COUNT(*) FROM t_news_model_call_receipt", Integer.class));

        // 列表只列当前修订：过期的那一条不会从工作台再被点开。
        NewsWorkbenchModels.PageVO<NewsWorkbenchModels.MaterialCurationVO> page =
                workbenchService.listCurationMaterials("pubmed", 1, 10);
        assertEquals(1, page.total());
        assertEquals(latest, page.records().getFirst().materialRevisionId());
    }

    @Test
    void aNewProfileAfterADefinitiveFailureIsACuratableNewCall() throws Exception {
        long revision = ingest("pubmed", "PMID-RECOVER-1", "Quota recovery study");
        fullyVerify(revision);
        modelClient.enqueue(req -> new NewsCurationModels.DefinitiveFailure(
                "MODEL_FREE_QUOTA_EXHAUSTED", 403, null, false, 210));
        assertEquals("FAILED_DEFINITIVE", workbenchService.runCuration(revision, admin).state());
        assertFalse(curationService.describeRevision(revision).curatable());

        // 运维换到另一个已配置的 profile（新的 modelId ⇒ 新的 logical 调用），这是确定失败后的恢复路径：
        // 服务端会真正发起一次新的调用，列表必须随之恢复「可运行」，而不是继续显示只重放。
        curationProperties.setModelProfiles(Map.of(
                "prod", stagedProfile("qwen3.8-max-0902"),
                "prod2", stagedProfile("qwen3.8-max-1001")));
        curationProperties.setRunProfileKey("prod2");
        assertTrue(curationService.describeRevision(revision).curatable());
        modelClient.enqueue(req -> success(JOINT_INSIGHT_JSON, 420, 180, 960L, "req-recover-1"));

        NewsWorkbenchModels.CurationRunVO second = workbenchService.runCuration(revision, admin);
        assertEquals("SUCCEEDED", second.state());
        assertFalse(second.replayed());
        assertTrue(second.materializable());
        assertEquals(2, modelClient.invocations.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_news_model_call_receipt", Integer.class));

        NewsCurationService.RevisionCuration after = curationService.describeRevision(revision);
        assertEquals("SUCCEEDED", after.latestReceiptState());
        assertTrue(after.curatable());
        assertNull(after.gateReason());
    }

    // ---- helpers ----

    private void fullyVerify(long revision) {
        curationService.verifyEvidence(revision,
                evidence(NewsEvidenceLedger.Attribute.PEER_REVIEW, "VERIFIED", NewsEvidenceLedger.Method.PUBMED_RECORD),
                "admin:1001");
        curationService.verifyEvidence(revision,
                evidence(NewsEvidenceLedger.Attribute.INTEGRITY, "VERIFIED", NewsEvidenceLedger.Method.PUBMED_RECORD),
                "admin:1001");
        curationService.verifyEvidence(revision,
                evidence(NewsEvidenceLedger.Attribute.STUDY_DESIGN, "OBSERVATIONAL",
                        NewsEvidenceLedger.Method.PUBMED_RECORD),
                "admin:1001");
        curationService.verifyEvidence(revision,
                evidence(NewsEvidenceLedger.Attribute.CONTENT_SCOPE, "LICENSED_RESULTS",
                        NewsEvidenceLedger.Method.DOI_LANDING_PAGE),
                "admin:1001");
    }

    private static NewsEvidenceLedger.Request evidence(NewsEvidenceLedger.Attribute attribute, String value,
                                                       NewsEvidenceLedger.Method method) {
        String url = method == NewsEvidenceLedger.Method.DOI_LANDING_PAGE
                ? "https://doi.org/10.1000/iris.runtime.01"
                : "https://pubmed.ncbi.nlm.nih.gov/42000009/";
        return new NewsEvidenceLedger.Request(attribute, value, method, url, "pubmed-2026", null);
    }

    private long ingest(String source, String recordId, String title) throws Exception {
        return ingest(source, recordId, title, recordId);
    }

    private long ingest(String source, String recordId, String title, String taskKey) throws Exception {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);
        String taskId = "task-" + taskKey;
        ingestionService.enqueueWindow(taskId, source, 1L, "ophthalmology", start, end, 3);
        NewsWireModels.Lease lease = ingestionService.claim(WORKER).orElseThrow();
        ingestionService.submitBatch(WORKER, lease.taskId(), lease.generation() + ":1",
                batchPayload(lease.generation(), lease.leaseToken(), 1, List.of(openMaterial(recordId, title))));
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

    private static Map<String, Object> openMaterial(String recordId, String title) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", "pubmed");
        record.put("sourceRecordId", recordId);
        record.put("url", "https://pubmed.ncbi.nlm.nih.gov/42000009/");
        record.put("title", title);
        record.put("publishedOn", "2026-10");
        record.put("abstract", "Open-access multicenter ophthalmic abstract verifying " + title
                + " in clinical screening.");
        record.put("abstractLicense", "CC-BY");
        return record;
    }

    private static NewsCurationModels.Succeeded success(String rawJson, int promptTokens, int completionTokens,
                                                        long actualCostMicros, String providerRequestId) {
        return new NewsCurationModels.Succeeded(
                rawJson, promptTokens, completionTokens, actualCostMicros, 200, providerRequestId, 320);
    }

    private static NewsModelProfile stagedProfile(String model) {
        return new NewsModelProfile(NewsModelProfile.Provider.QWEN, NewsModelProfile.Platform.BAILIAN,
                URI.create("https://dashscope.aliyuncs.com"), "/compatible-mode/v1/chat/completions",
                model, "FIXTURE_KEY", Duration.ofSeconds(2), Duration.ofSeconds(5), 8192, 1024, 0,
                NewsModelProfile.Format.JSON_OBJECT, new NewsModelProfile.Capabilities(
                        true, false, NewsModelProfile.UsageFormat.STANDARD),
                new NewsModelProfile.PricingVersion("fixture-v1", 12_000_000, 1_500_000, 36_000_000));
    }

    private static final String JOINT_INSIGHT_JSON = """
            {"relevanceScore":84,"researchValueScore":71,"recommendedType":"RESEARCH_INSIGHT",
             "rationale":"同行评审、完整性、研究设计与授权全文范围均已人工核验。",
             "content":{"contentType":"RESEARCH_INSIGHT","sections":{
               "question":"深度学习筛查在真实世界队列中能否稳定识别需转诊的视网膜病变？",
               "studyDesign":"多中心前瞻性观察研究。",
               "findings":"研究报告了较高的筛查敏感度，仍需外部验证。",
               "meaning":"为基层筛查评估提供了可复用的路径。",
               "limitations":"未评估长期结局。"},
              "claims":[{"evidenceId":"abstract","statement":"公开摘要记录了筛查评估结果。",
                         "quote":"Open-access multicenter ophthalmic abstract"}]}}
            """;

    private static synchronized void ensureDatabase(String url) throws Exception {
        if (jdbc != null) {
            return;
        }
        var source = LegacyGateSupport.dataSource(url);
        LegacyGateSupport.ensureSchema(url, source, List.of(), () -> {
            throw new IllegalStateException("BUSINESS_TEST_URL must point at PostgreSQL");
        });
        dataSource = source;
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
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setPlugins(interceptor);
        sqlSessionTemplate = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
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
