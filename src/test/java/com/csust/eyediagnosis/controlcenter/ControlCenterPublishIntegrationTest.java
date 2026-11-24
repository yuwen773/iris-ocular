package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.controlcenter.model.dto.ApprovalDecisionRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.DraftSaveRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.PublishRequestBody;
import com.csust.eyediagnosis.controlcenter.model.dto.RollbackRequestBody;

import com.csust.eyediagnosis.controlcenter.model.vo.ActiveConfigVO;
import com.csust.eyediagnosis.controlcenter.model.vo.DraftVO;
import com.csust.eyediagnosis.controlcenter.model.vo.PublishRequestVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterAuditService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterDraftService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterPublishService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterReadService;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;

import com.csust.eyediagnosis.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 发布审批 / 原子发布 / 回滚 / 一致性的真 MySQL 门禁（WP-01C）。
 *
 * <p>跑在 ci profile 的 MySQL 8 上：真实 Flyway（含 V36）、真实 Spring 事务、
 * 真实行锁。这里验证的不是「代码看起来对」，而是：
 * <ul>
 *   <li>重复发布不重复生效（幂等重放 + 唯一键）；</li>
 *   <li>同号不同内容被拒（40908）；</li>
 *   <li>旧审批不能发布新草稿（40910 + STALE）；</li>
 *   <li>并发时只有合法结果（同一审批、不同审批、发布 vs 回滚）；</li>
 *   <li>失败不产生半发布（审计写失败 → 全部回滚；重试可成功）；</li>
 *   <li>回滚产生新 Release，历史不可变；</li>
 *   <li>成功审计与发布同事务，失败审计不丢失。</li>
 * </ul>
 */
@SpringBootTest(properties = "eye-diagnosis.control-center.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterPublishIntegrationTest {

    private static final long ACTOR_ID = 9_200_001L;
    private static final long APPROVER_ID = 9_200_002L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControlCenterDraftService draftService;

    @Autowired
    private ControlCenterPublishService publishService;

    @Autowired
    private ControlCenterReadService readService;

    /**
     * 用于失败注入：默认透传真实实现，只有需要时抛出。
     *
     * <p>spy 的是审计<b>服务</b>而不是 Mapper：Mapper 是接口代理，Mockito 无法在
     * 抽象方法上 callRealMethod；服务是具体类，可以在同一个 Answer 里先抛后委托。
     */
    @MockitoSpyBean
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

    // ------------------------------------------------------------------ 正常发布

    @Test
    void approvedRequestPublishesExactlyOneImmutableRelease() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "pub-1");
        assertEquals("PENDING", approval.status());
        assertEquals(null, approval.expectedActiveReleaseId());

        ReleaseVO release = approve(approval.id(), "appr-1");

        assertEquals(1, release.releaseVersion());
        assertEquals("Iris A", release.values().get("brand.siteName"));
        assertEquals(null, release.rollbackFromReleaseId());
        assertEquals(release.id(), activeReleaseId(), "活跃指针必须切到新 release");
        assertEquals("APPROVED", approvalStatus(approval.id()));
        assertEquals(release.id(), approvalReleaseId(approval.id()));

        ActiveConfigVO active = readService.active();
        assertEquals("RELEASE", active.source());
        assertEquals("Iris A", active.values().get("brand.siteName"));
        assertEquals(1, auditCount("PUBLISH_APPROVE", "SUCCESS"));

        // 首次结果的幂等回放：同一 requestId 重放，不新增 release、不再动指针。
        ReleaseVO replayed = approve(approval.id(), "appr-1");
        assertEquals(release.id(), replayed.id());
        assertEquals(1, releaseCount());
    }

    @Test
    void secondApprovalOfTheSameTicketIsRejectedWith40909() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "pub-2");
        approve(approval.id(), "appr-2");

        BusinessException error = assertThrows(BusinessException.class,
                () -> approve(approval.id(), "appr-2b"));

        assertEquals(ErrorCode.CONFIG_APPROVAL_NOT_PENDING.getCode(), error.getCode());
        assertEquals("APPROVED", approvalStatus(approval.id()));
        assertEquals(1, releaseCount(), "重复决定不得产生第二个 release");
    }

    @Test
    void reusingARequestIdWithDifferentContentIsRejected() {
        long revision = publishableDraft("Iris A");
        requestPublish(revision, null, "dup-1", "第一次");

        BusinessException error = assertThrows(BusinessException.class,
                () -> requestPublish(revision, null, "dup-1", "内容不同了"));

        assertEquals(ErrorCode.CONFIG_IDEMPOTENCY_MISMATCH.getCode(), error.getCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_publish_request", Integer.class),
                "同号不同内容不得插入第二条审批");
    }

    @Test
    void replayingTheSamePublishRequestReturnsTheFirstApproval() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO first = requestPublish(revision, null, "pub-3", "同一条");

        PublishRequestVO replayed = requestPublish(revision, null, "pub-3", "同一条");

        assertEquals(first.id(), replayed.id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_publish_request", Integer.class));
    }

    // ------------------------------------------------------------------ 绑定复核

    @Test
    void oldApprovalCannotPublishADraftEditedAfterwards() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "pub-4");

        editDraft(Map.of("brand.siteName", "Iris B"), revision);

        BusinessException error = assertThrows(BusinessException.class,
                () -> approve(approval.id(), "appr-4"));

        assertEquals(ErrorCode.CONFIG_APPROVAL_STALE.getCode(), error.getCode());
        assertEquals("STALE", approvalStatus(approval.id()));
        assertEquals(null, activeReleaseId(), "失效的审批不能动指针");
        assertEquals(0, releaseCount());
        assertEquals(1, auditCount("PUBLISH_APPROVE", "FAILED"), "失效必须留痕");
    }

    @Test
    void approvalWhoseDraftLostItsValidationIsRejectedWith40901() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "pub-5");

        // 模拟外部改库：revision 不变，但状态被打回 DRAFT（正常流程做不到）。
        jdbc.update("UPDATE t_config_draft SET status = 'DRAFT' WHERE id = 1");

        BusinessException error = assertThrows(BusinessException.class,
                () -> approve(approval.id(), "appr-5"));

        assertEquals(ErrorCode.CONFIG_NOT_VALIDATED.getCode(), error.getCode());
        assertEquals("STALE", approvalStatus(approval.id()));
        assertEquals(0, releaseCount());
    }

    @Test
    void approvalWhoseActiveVersionMovedIsRejectedWith40900AndStaysPending() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO first = requestPublish(revision, null, "pub-6a");
        PublishRequestVO second = requestPublish(revision, null, "pub-6b");
        approve(first.id(), "appr-6a");

        BusinessException error = assertThrows(BusinessException.class,
                () -> approve(second.id(), "appr-6b"));

        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), error.getCode());
        assertEquals("PENDING", approvalStatus(second.id()),
                "版本冲突不是失效：审批单保持 PENDING，等待申请人重新申请或重新决定");
        assertEquals(1, releaseCount());
        assertEquals("Iris A", readService.active().values().get("brand.siteName"));
    }

    @Test
    void requestPublishRequiresAValidatedDraft() {
        createDraft("Iris A");

        BusinessException notValidated = assertThrows(BusinessException.class,
                () -> requestPublish(draftService.get().revision(), null, "pub-7a"));
        assertEquals(ErrorCode.CONFIG_NOT_VALIDATED.getCode(), notValidated.getCode());

        // 校验后又改动：状态被打回 DRAFT，同样不能申请。
        long revision = publishableDraft("Iris B");
        editDraft(Map.of("brand.siteName", "Iris C"), revision);
        BusinessException afterEdit = assertThrows(BusinessException.class,
                () -> requestPublish(draftService.get().revision(), null, "pub-7b"));
        assertEquals(ErrorCode.CONFIG_NOT_VALIDATED.getCode(), afterEdit.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_publish_request", Integer.class));
    }

    @Test
    void requestPublishRejectsAStaleRevisionAndAStaleActivePointer() {
        long revision = publishableDraft("Iris A");

        BusinessException staleRevision = assertThrows(BusinessException.class,
                () -> requestPublish(revision + 5, null, "pub-8a"));
        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), staleRevision.getCode());

        BusinessException stalePointer = assertThrows(BusinessException.class,
                () -> requestPublish(revision, 4242L, "pub-8b"));
        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), stalePointer.getCode());
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    void rejectedTicketCannotBeApprovedAfterwards() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "pub-9");

        PublishRequestVO rejected = reject(approval.id(), "rej-9", "内容不合规");
        assertEquals("REJECTED", rejected.status());
        assertEquals("内容不合规", rejected.decisionReason());

        BusinessException error = assertThrows(BusinessException.class,
                () -> approve(approval.id(), "appr-9"));
        assertEquals(ErrorCode.CONFIG_APPROVAL_NOT_PENDING.getCode(), error.getCode());
        assertEquals(0, releaseCount());
    }

    // ------------------------------------------------------------------ 回滚

    @Test
    void rollbackCreatesANewReleaseInsteadOfRewritingHistory() {
        ReleaseVO first = publish("Iris A", "rb-1", "rb-a1");
        ReleaseVO second = publish("Iris B", "rb-2", "rb-a2");
        assertEquals(2, second.releaseVersion());

        ReleaseVO rolledBack = rollback(first.id(), second.id(), "rb-3");

        assertEquals(3, rolledBack.releaseVersion(), "回滚必须占用新的发布序号");
        assertEquals(first.id(), rolledBack.rollbackFromReleaseId());
        assertEquals("Iris A", rolledBack.values().get("brand.siteName"));
        assertEquals(rolledBack.id(), activeReleaseId());
        assertEquals(3, releaseCount());

        // 历史快照不可变：v1 仍然是原来的内容。
        assertEquals("Iris A", readService.release(first.id()).values().get("brand.siteName"));
        assertEquals("Iris B", readService.release(second.id()).values().get("brand.siteName"));

        // 幂等重放。
        ReleaseVO replayed = rollback(first.id(), second.id(), "rb-3");
        assertEquals(rolledBack.id(), replayed.id());
        assertEquals(3, releaseCount());
    }

    @Test
    void rollbackRejectsStalePointerAndTheAlreadyActiveRelease() {
        ReleaseVO first = publish("Iris A", "rb-4", "rb-a4");
        ReleaseVO second = publish("Iris B", "rb-5", "rb-a5");

        BusinessException stalePointer = assertThrows(BusinessException.class,
                () -> rollback(first.id(), first.id(), "rb-6"));
        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), stalePointer.getCode());

        BusinessException alreadyActive = assertThrows(BusinessException.class,
                () -> rollback(second.id(), second.id(), "rb-7"));
        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), alreadyActive.getCode());
        assertEquals(2, releaseCount());
    }

    @Test
    void rollbackRejectsAReleaseFromAnotherSchemaVersion() {
        ReleaseVO first = publish("Iris A", "rb-8", "rb-a8");

        // 模拟历史遗留：一条 schemaVersion 与当前代码不一致的 release（正常写入路径造不出来）。
        jdbc.update("INSERT INTO t_config_release "
                        + "(releaseVersion, schemaVersion, payload, digest, createdBy) VALUES (?,?,?,?,?)",
                2L, 0, "{\"brand.siteName\":\"legacy\"}", "f".repeat(64), ACTOR_ID);
        Long legacyId = jdbc.queryForObject(
                "SELECT id FROM t_config_release WHERE releaseVersion = 2", Long.class);

        BusinessException error = assertThrows(BusinessException.class,
                () -> rollback(legacyId, first.id(), "rb-9"));

        assertEquals(ErrorCode.CONFIG_SCHEMA_UNSUPPORTED.getCode(), error.getCode());
        assertEquals(2, releaseCount(), "被拒绝的回滚不得插入新 release");
        assertEquals(first.id(), activeReleaseId(), "被拒绝的回滚不得动指针");
    }

    // ------------------------------------------------------------------ Provider 拒绝

    @Test
    void providerNamespaceKeysAreRejectedWith40911() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> createDraft("Iris A", Map.of("provider.qwen.model", "qwen-vl-plus")));

        assertEquals(ErrorCode.CONFIG_PROVIDER_NOT_SUPPORTED.getCode(), error.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_draft", Integer.class));
    }

    // ------------------------------------------------------------------ 并发

    @Test
    void twoInstancesDecidingTheSameTicketProduceExactlyOneRelease() throws Exception {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "cc-1");

        List<Object> outcomes = raceTwo(
                () -> approve(approval.id(), "cc-1a"),
                () -> approve(approval.id(), "cc-1b"));

        assertExactlyOne(outcomes);
        assertEquals(1, releaseCount());
        assertEquals("APPROVED", approvalStatus(approval.id()));
        assertEquals(1, auditCount("PUBLISH_APPROVE", "SUCCESS"));
    }

    @Test
    void twoApprovalsBoundToTheSameActiveVersionCannotBothPublish() throws Exception {
        ReleaseVO base = publish("Iris A", "cc-2", "cc-2a");
        long revision = publishableDraft("Iris B");
        PublishRequestVO left = requestPublish(revision, base.id(), "cc-2b");
        PublishRequestVO right = requestPublish(revision, base.id(), "cc-2c");

        List<Object> outcomes = raceTwo(
                () -> approve(left.id(), "cc-2d"),
                () -> approve(right.id(), "cc-2e"));

        assertExactlyOne(outcomes);
        assertEquals(2, releaseCount(), "只能多出 1 个 release");
        // 本用例里一共有 3 张审批单（基础发布 1 张 + 竞争 2 张），所以只能按 id 判。
        long approvedAmongRace = java.util.stream.Stream.of(left.id(), right.id())
                .filter(id -> "APPROVED".equals(approvalStatus(id)))
                .count();
        assertEquals(1, approvedAmongRace, "输掉的那张审批单必须仍是 PENDING，不能被写成 APPROVED");
    }

    @Test
    void concurrentPublishAndRollbackCannotBothWin() throws Exception {
        ReleaseVO first = publish("Iris A", "cc-3", "cc-3a");
        long revision = publishableDraft("Iris B");
        PublishRequestVO approval = requestPublish(revision, first.id(), "cc-3b");

        List<Object> outcomes = raceTwo(
                () -> approve(approval.id(), "cc-3c"),
                () -> rollback(first.id(), first.id(), "cc-3d"));

        assertExactlyOne(outcomes);
        assertEquals(2, releaseCount());
        assertNotNull(activeReleaseId());
    }

    // ------------------------------------------------------------------ 失败恢复

    @Test
    void publishAuditFailureRollsBackEverythingAndStaysRetryable() {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "fail-1");

        // 注入：只让**第一次**成功审计写入失败。ADR D4 要求「没有审计就没有发布」，
        // 所以整条链必须回滚，而不是留下一个静默生效的版本。第二次起 callRealMethod
        // 交还原实现——这也正是「失败不消耗 requestId」的复验路径。
        java.util.concurrent.atomic.AtomicInteger budget =
                new java.util.concurrent.atomic.AtomicInteger(1);
        doAnswer(invocation -> {
            if (budget.getAndDecrement() > 0) {
                throw new DataIntegrityViolationException("injected audit failure");
            }
            return invocation.callRealMethod();
        }).when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(Exception.class, () -> approve(approval.id(), "fail-1a"));

        assertEquals(0, releaseCount(), "失败的发布不得留下 release");
        assertEquals(null, activeReleaseId(), "失败的发布不得切指针");
        assertEquals("PENDING", approvalStatus(approval.id()), "审批单保持 PENDING");

        // 恢复之后用同一个 requestId 重试：占位记录随事务回滚，号没有被消耗。
        ReleaseVO release = approve(approval.id(), "fail-1a");

        assertEquals(1, release.releaseVersion());
        assertEquals(1, releaseCount());
        assertEquals(release.id(), activeReleaseId());
        assertEquals("APPROVED", approvalStatus(approval.id()));
        assertEquals(1, auditCount("PUBLISH_APPROVE", "SUCCESS"));
    }

    // ------------------------------------------------------------------ 辅助

    private User actor(long id) {
        User user = new User();
        user.setId(id);
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

    private DraftVO createDraft(String siteName) {
        return createDraft(siteName, Map.of());
    }

    private DraftVO createDraft(String siteName, Map<String, Object> extra) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("brand.siteName", siteName);
        values.putAll(extra);
        return draftService.create(saveRequest(values, null), actor(ACTOR_ID), null);
    }

    private void editDraft(Map<String, Object> values, long expectedRevision) {
        draftService.update(1, saveRequest(values, expectedRevision), actor(ACTOR_ID), null);
    }

    /**
     * 建一份「已校验」草稿并返回其 revision。
     *
     * <p>草稿是单例（id=1）：同一个用例里第二次调用必须走 update，否则会撞 40900。
     */
    private long publishableDraft(String siteName) {
        if ("NONE".equals(draftService.get().status())) {
            createDraft(siteName);
        } else {
            editDraft(Map.of("brand.siteName", siteName), draftService.get().revision());
        }
        long revision = draftService.get().revision();
        assertTrue(draftService.validate(1, revision, null, actor(ACTOR_ID), null).valid());
        return revision;
    }

    private PublishRequestVO requestPublish(long revision, Long expectedActive, String requestId) {
        return requestPublish(revision, expectedActive, requestId, null);
    }

    private PublishRequestVO requestPublish(long revision, Long expectedActive, String requestId,
                                            String note) {
        PublishRequestBody body = new PublishRequestBody();
        body.setRevision(revision);
        body.setExpectedActiveReleaseId(expectedActive);
        body.setRequestId(requestId);
        body.setNote(note);
        return publishService.requestPublish(1, body, actor(ACTOR_ID));
    }

    private ReleaseVO approve(long approvalId, String requestId) {
        ApprovalDecisionRequest body = new ApprovalDecisionRequest();
        body.setRequestId(requestId);
        return publishService.approve(approvalId, body, actor(APPROVER_ID));
    }

    private PublishRequestVO reject(long approvalId, String requestId, String reason) {
        ApprovalDecisionRequest body = new ApprovalDecisionRequest();
        body.setRequestId(requestId);
        body.setReason(reason);
        return publishService.reject(approvalId, body, actor(APPROVER_ID));
    }

    private ReleaseVO rollback(long targetReleaseId, Long expectedActive, String requestId) {
        RollbackRequestBody body = new RollbackRequestBody();
        body.setExpectedActiveReleaseId(expectedActive);
        body.setRequestId(requestId);
        body.setReason("回滚验收");
        return publishService.rollback(targetReleaseId, body, actor(APPROVER_ID));
    }

    /** 走完整链路发布一版，返回新 release。 */
    private ReleaseVO publish(String siteName, String requestId, String approveRequestId) {
        long revision = publishableDraft(siteName);
        PublishRequestVO approval = requestPublish(revision, activeReleaseId(), requestId);
        return approve(approval.id(), approveRequestId);
    }

    private Long activeReleaseId() {
        return jdbc.queryForObject(
                "SELECT releaseId FROM t_config_active_pointer WHERE id = 1", Long.class);
    }

    private String approvalStatus(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM t_config_publish_request WHERE id = ?", String.class, id);
    }

    private Long approvalReleaseId(long id) {
        return jdbc.queryForObject(
                "SELECT releaseId FROM t_config_publish_request WHERE id = ?", Long.class, id);
    }

    private int releaseCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_release", Integer.class);
        return count == null ? 0 : count;
    }

    private int auditCount(String action, String result) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_audit_event WHERE action = ? AND result = ?",
                Integer.class, action, result);
        return count == null ? 0 : count;
    }

    private List<Object> raceTwo(Callable<Object> left, Callable<Object> right) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> {
                start.await();
                return attempt(left);
            });
            Future<Object> second = pool.submit(() -> {
                start.await();
                return attempt(right);
            });
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 把成功映射成结果对象、把冲突映射成错误码，其余异常原样抛出。 */
    private Object attempt(Callable<Object> body) {
        try {
            return body.call();
        } catch (BusinessException e) {
            return e.getCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertExactlyOne(List<Object> outcomes) {
        long successes = outcomes.stream().filter(o -> !(o instanceof Integer)).count();
        assertEquals(1, successes, "必须恰好一个成功，实际: " + outcomes);
    }
}
