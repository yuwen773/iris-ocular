package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.controlcenter.model.dto.DraftSaveRequest;
import com.csust.eyediagnosis.controlcenter.model.vo.ActiveConfigVO;
import com.csust.eyediagnosis.controlcenter.model.vo.DraftVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ValidationVO;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterAuditService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterDraftService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterIdempotencyService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterReadService;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置中心持久化与事务边界的真 MySQL 门禁（WP-01B）。
 *
 * <p>跑在 ci profile 的 MySQL 8 上，走真实 Flyway（含 V35）、真实 Spring 事务
 * 与真实 AOP 代理。覆盖：迁移结构、空库回落、草稿生命周期、revision 冲突、
 * 校验失效、审计同事务与失败审计独立事务、幂等重放、并发唯一赢家。
 *
 * <p>不覆盖：发布/回滚（WP-01C 范围）。
 */
@SpringBootTest(properties = "eye-diagnosis.control-center.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterPersistenceIntegrationTest {

    private static final long ACTOR_ID = 9_100_001L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControlCenterDraftService draftService;

    @Autowired
    private ControlCenterReadService readService;

    @Autowired
    private ControlCenterIdempotencyService idempotency;

    @Autowired
    private ControlCenterAuditService auditService;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM t_config_publish_request");
        jdbc.update("DELETE FROM t_config_audit_event");
        jdbc.update("DELETE FROM t_config_request");
        jdbc.update("DELETE FROM t_config_release");
        jdbc.update("DELETE FROM t_config_draft");
        jdbc.update("DELETE FROM t_config_active_pointer");
        jdbc.update("INSERT INTO t_config_active_pointer (id, releaseId) VALUES (1, NULL)");
    }

    private User actor() {
        User user = new User();
        user.setId(ACTOR_ID);
        user.setUserRole("admin");
        return user;
    }

    private DraftSaveRequest saveRequest(Map<String, Object> values, Long expectedRevision) {
        DraftSaveRequest request = new DraftSaveRequest();
        request.setSchemaVersion(1);
        request.setValues(values);
        request.setExpectedRevision(expectedRevision);
        return request;
    }

    private DraftVO createDraft(Map<String, Object> values, String requestId) {
        DraftSaveRequest request = saveRequest(values, null);
        request.setRequestId(requestId);
        return draftService.create(request, actor(), requestDigest("DRAFT_CREATE", requestId, values));
    }

    private DraftVO updateDraft(Map<String, Object> values, Long expectedRevision, String requestId) {
        DraftSaveRequest request = saveRequest(values, expectedRevision);
        request.setRequestId(requestId);
        return draftService.update(1, request, actor(),
                requestDigest("DRAFT_UPDATE", requestId, values + "|" + expectedRevision));
    }

    private ValidationVO validateDraft(long expectedRevision, String requestId) {
        return draftService.validate(1, expectedRevision, requestId, actor(),
                requestDigest("DRAFT_VALIDATE", requestId, String.valueOf(expectedRevision)));
    }

    /**
     * 测试用请求摘要。
     *
     * <p>真实摘要由控制器按「方法 + 路径 + 业务字段」计算；这里只要求
     * 「同一次操作给出同一个值」——幂等服务比较的是相等性，不是来源。
     */
    private static String requestDigest(String action, String requestId, Object body) {
        String raw = action + "|" + requestId + "|" + body;
        return String.format("%064d", Math.abs((long) raw.hashCode()));
    }

    private int auditCount(String action, String result) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_audit_event WHERE action = ? AND result = ?",
                Integer.class, action, result);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ 迁移

    @Test
    void migrationCreatesEveryConfigTableAndSeedsThePointer() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name LIKE 't_config_%' ORDER BY table_name", String.class);
        assertEquals(List.of("t_config_active_pointer", "t_config_audit_event", "t_config_draft",
                "t_config_publish_request", "t_config_release", "t_config_request"), tables);

        Integer pointerRows = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_active_pointer", Integer.class);
        assertEquals(1, pointerRows, "活跃指针必须预置单例行");
        assertEquals(null, jdbc.queryForObject(
                "SELECT releaseId FROM t_config_active_pointer WHERE id = 1", Long.class));
    }

    // ------------------------------------------------------------------ 读取

    @Test
    void emptyDatabaseReturnsCompleteRegistryDefaults() {
        ActiveConfigVO active = readService.active();

        assertEquals("DEFAULT", active.source());
        assertEquals(null, active.releaseId());
        assertEquals("Iris", active.values().get("brand.siteName"));
        assertEquals("Iris", active.values().get("brand.shortName"));
        assertEquals(6, active.values().size());
        assertTrue(active.values().containsKey("brand.favicon.dark"));
    }

    @Test
    void registryExposesSixKeysWithLimits() {
        var registry = readService.registry();
        assertEquals(1, registry.schemaVersion());
        assertEquals(6, registry.keys().size());
        assertTrue(registry.keys().stream().anyMatch(k -> k.key().equals("brand.siteName")
                && k.minLength() == 2 && k.maxLength() == 40));
    }

    // ------------------------------------------------------------------ 草稿

    @Test
    void creatingDraftStoresRevisionOneAndWritesAuditInTheSameTransaction() {
        DraftVO draft = createDraft(Map.of("brand.siteName", "Iris 眼科"), null);

        assertEquals(1, draft.revision());
        assertEquals("DRAFT", draft.status());
        assertEquals("Iris 眼科", draft.values().get("brand.siteName"));
        assertEquals(1, auditCount("DRAFT_CREATE", "SUCCESS"));
    }

    @Test
    void secondCreateIsRejectedAndKeepsTheExistingDraft() {
        createDraft(Map.of("brand.siteName", "First"), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> createDraft(Map.of("brand.siteName", "Second"), null));

        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), error.getCode());
        assertEquals("First", draftService.get().values().get("brand.siteName"));
        assertEquals(1, auditCount("DRAFT_CREATE", "FAILED"), "失败的创建也要留痕");
    }

    @Test
    void updateAdvancesRevisionAndRecordsChangedKeysOnly() {
        createDraft(Map.of("brand.siteName", "Iris"), null);

        DraftVO updated = updateDraft(
                Map.of("brand.siteName", "Iris 2", "brand.shortName", "IR"), 1L, null);

        assertEquals(2, updated.revision());
        assertEquals("Iris 2", updated.values().get("brand.siteName"));

        String payload = jdbc.queryForObject(
                "SELECT payload FROM t_config_audit_event WHERE action = 'DRAFT_UPDATE' AND result = 'SUCCESS' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertTrue(payload.contains("brand.siteName"), "审计必须记下变更的键: " + payload);
        assertFalse(payload.contains("Iris 2"), "审计不复制配置值: " + payload);
    }

    @Test
    void staleRevisionIsRejectedAndDoesNotOverwrite() {
        createDraft(Map.of("brand.siteName", "Iris"), null);
        updateDraft(Map.of("brand.siteName", "Second"), 1L, null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> updateDraft(Map.of("brand.siteName", "Stale"), 1L, null));

        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), error.getCode());
        assertEquals("Second", draftService.get().values().get("brand.siteName"));
        assertEquals(2, draftService.get().revision());
    }

    @Test
    void failedUpdateAuditSurvivesTheRollback() {
        createDraft(Map.of("brand.siteName", "Iris"), null);

        assertThrows(BusinessException.class,
                () -> updateDraft(Map.of("brand.siteName", "Stale"), 99L, null));

        // 业务事务回滚了，但失败审计走独立事务，必须留下来。
        assertEquals(1, auditCount("DRAFT_UPDATE", "FAILED"));
        assertEquals(0, auditCount("DRAFT_UPDATE", "SUCCESS"));
    }

    @Test
    void editingAfterValidationInvalidatesTheValidation() {
        createDraft(Map.of("brand.siteName", "Iris"), null);
        ValidationVO validated = validateDraft(1L, null);
        assertTrue(validated.valid());
        assertEquals("VALIDATED", draftService.get().status());

        DraftVO edited = updateDraft(Map.of("brand.siteName", "Iris 2"), 1L, null);

        assertEquals("DRAFT", edited.status());
        assertEquals(null, edited.validationRevision());
        assertEquals(null, jdbc.queryForObject(
                "SELECT validationResult FROM t_config_draft WHERE id = 1", String.class));
    }

    @Test
    void validationAgainstStaleRevisionIsRejected() {
        createDraft(Map.of("brand.siteName", "Iris"), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> validateDraft(5L, null));

        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), error.getCode());
        assertEquals("DRAFT", draftService.get().status());
    }

    @Test
    void invalidStoredPayloadIsReportedWithoutMarkingValidated() {
        // 直接写入一份不合法的载荷（模拟历史数据 / 手工改库），验证校验端点如实报告。
        jdbc.update("INSERT INTO t_config_draft (id, revision, status, payload) VALUES (1, 3, 'DRAFT', ?)",
                "{\"brand.siteName\":\"A\"}");

        ValidationVO result = validateDraft(3L, null);

        assertFalse(result.valid());
        assertEquals(List.of("LENGTH_OUT_OF_RANGE"), result.fieldErrors().get("brand.siteName"));
        assertEquals("DRAFT", draftService.get().status());
        assertEquals(1, auditCount("DRAFT_VALIDATE", "FAILED"));
    }

    @Test
    void unknownAndProtectedKeysAreRejectedBeforeAnyWrite() {
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> createDraft(Map.of("brand.nope", "x"), null));
        assertEquals(ErrorCode.CONFIG_UNKNOWN_KEY.getCode(), unknown.getCode());

        BusinessException protectedKey = assertThrows(BusinessException.class,
                () -> createDraft(Map.of("eye-diagnosis.control-center.enabled", "true"), null));
        assertEquals(ErrorCode.CONFIG_DEPLOY_PROTECTED.getCode(), protectedKey.getCode());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_config_draft", Integer.class));
    }

    @Test
    void unsupportedSchemaVersionIsRejected() {
        DraftSaveRequest request = saveRequest(Map.of("brand.siteName", "Iris"), null);
        request.setSchemaVersion(99);

        BusinessException error = assertThrows(BusinessException.class,
                () -> draftService.create(request, actor(), requestDigest("DRAFT_CREATE", null, request.getValues())));
        assertEquals(ErrorCode.CONFIG_SCHEMA_UNSUPPORTED.getCode(), error.getCode());
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    void idempotencyReplaysTheSameRequestAndRejectsADifferentBody() {
        DraftVO first = createDraft(Map.of("brand.siteName", "Iris"), "req-1");

        // 同号同内容：不重复执行，拿回首次结果（草稿仍只有 1 行）。
        DraftVO replayed = createDraft(Map.of("brand.siteName", "Iris"), "req-1");
        assertEquals(first.revision(), replayed.revision());
        assertEquals("Iris", replayed.values().get("brand.siteName"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_config_draft", Integer.class));

        // 同号不同内容：40908，且不产生第二份草稿。
        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> createDraft(Map.of("brand.siteName", "Other"), "req-1"));
        assertEquals(ErrorCode.CONFIG_IDEMPOTENCY_MISMATCH.getCode(), mismatch.getCode());
        assertEquals("Iris", draftService.get().values().get("brand.siteName"));
    }

    @Test
    void idempotencyIsScopedPerActor() {
        assertTrue(idempotency.reserve(ACTOR_ID, "shared", "c".repeat(64), "DRAFT_CREATE",
                Map.class).isEmpty(), "首次占位不应命中重放");
        idempotency.complete(ACTOR_ID, "shared", Map.of("ok", true));

        assertTrue(idempotency.reserve(ACTOR_ID + 1, "shared", "c".repeat(64), "DRAFT_CREATE",
                Map.class).isEmpty(), "别人的 requestId 不能被当成自己的重放");
    }

    // ------------------------------------------------------------------ 并发

    @Test
    void concurrentUpdatesOnTheSameRevisionHaveExactlyOneWinner() throws Exception {
        createDraft(Map.of("brand.siteName", "Iris"), null);

        for (int round = 0; round < 10; round++) {
            long revision = draftService.get().revision();
            final int roundNo = round;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = pool.submit(race(start, () -> tryUpdate(revision, "A" + roundNo)));
                Future<Boolean> second = pool.submit(race(start, () -> tryUpdate(revision, "B" + roundNo)));
                start.countDown();

                boolean a = first.get();
                boolean b = second.get();

                assertTrue(a ^ b, "同一 revision 必须恰好一个赢：a=" + a + " b=" + b);
                assertEquals(revision + 1, draftService.get().revision());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private boolean tryUpdate(long revision, String value) {
        try {
            updateDraft(Map.of("brand.siteName", value), revision, null);
            return true;
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.CONFIG_VERSION_CONFLICT.getCode()) {
                return false;
            }
            throw e;
        }
    }

    private static <T> Callable<T> race(CountDownLatch start, Callable<T> body) {
        return () -> {
            start.await();
            return body.call();
        };
    }

    @Test
    void auditRedactsSensitiveFieldValues() {
        // 审计服务对名字里带 secret/key/token/password 的字段一律脱敏，
        // 即便今天注册的 6 个品牌键都不是敏感值——这是给未来加键留的防线。
        assertEquals("***", redactedValue(Map.of("cos.client.secretKey", "super-secret")));
        assertEquals("visible", redactedValue(Map.of("brand.siteName", "visible")));
    }

    private Object redactedValue(Map<String, Object> payload) {
        auditService.record("REDACTION_PROBE", ACTOR_ID, null, null, null, null, "SUCCESS", null, payload);
        String stored = jdbc.queryForObject(
                "SELECT payload FROM t_config_audit_event WHERE action = 'REDACTION_PROBE' ORDER BY id DESC LIMIT 1",
                String.class);
        String key = payload.keySet().iterator().next();
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stored);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return node.path(key).asText();
    }
}
