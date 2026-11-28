package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.common.InsertRaceGuard;
import com.csust.eyediagnosis.controlcenter.model.dto.ApprovalDecisionRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.PublishRequestBody;
import com.csust.eyediagnosis.controlcenter.model.dto.RollbackRequestBody;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigActivePointer;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigAuditEvent;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigDraft;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigPublishRequest;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigRelease;
import com.csust.eyediagnosis.controlcenter.model.enums.ConfigErrorReason;
import com.csust.eyediagnosis.controlcenter.model.vo.PublishRequestVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.controlcenter.registry.ConfigRegistry;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.ConfigActivePointerMapper;
import com.csust.eyediagnosis.mapper.ConfigDraftMapper;
import com.csust.eyediagnosis.mapper.ConfigPublishRequestMapper;
import com.csust.eyediagnosis.mapper.ConfigReleaseMapper;
import com.csust.eyediagnosis.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 发布/审批/回滚的事务体（WP-01C）。
 *
 * <p>为什么单独成类：{@link ControlCenterPublishService} 需要在<b>事务之外</b>
 * 捕获 {@link StaleApprovalException}，然后开新事务写 STALE 状态。若两者在同一
 * 个 Bean 里用 {@code this.} 调用，Spring 代理会被绕过，REQUIRES_NEW 与回滚语义
 * 都会失效（CGLIB 不拦截自调用）。
 *
 * <p><b>事务顺序（批准，也是本类最重要的一条）</b>：
 * <ol>
 *   <li>幂等占位（第一条写，撞唯一键即重放）；</li>
 *   <li>锁审批行 {@code FOR UPDATE}，非 PENDING 直接放弃；</li>
 *   <li>逐条重验绑定：revision / payloadDigest / validationDigest / 校验状态；不符就抛
 *       {@link StaleApprovalException} 让本事务整体回滚（含占位），由外层标记 STALE；</li>
 *   <li>锁活跃指针行 {@code FOR UPDATE}——从这里开始，发布与回滚全局串行；</li>
 *   <li>期望活跃版本比较（{@code Objects.equals}，NULL 安全），不符 40900；</li>
 *   <li>在锁内取 {@code MAX(releaseVersion)+1} 并插入不可变 release；</li>
 *   <li>条件切换指针（{@code releaseId <=> 期望值}），0 行即 40900；</li>
 *   <li>审批单条件更新为 APPROVED（{@code status='PENDING'}），0 行说明被别人抢先；</li>
 *   <li>回填幂等结果；</li>
 *   <li>写成功审计——与以上所有写同事务，不存在「发布成功但没有审计」。</li>
 * </ol>
 * 任何一步抛出 RuntimeException，整条链（含幂等占位与成功审计）一起回滚，
 * 线上指针、release、审批状态三者不会出现半发布。
 */
@Slf4j
@Service
public class ControlCenterReleaseTransactions {

    static final String ACTION_PUBLISH_REQUEST = "PUBLISH_REQUEST";
    static final String ACTION_PUBLISH_APPROVE = "PUBLISH_APPROVE";
    static final String ACTION_PUBLISH_REJECT = "PUBLISH_REJECT";
    static final String ACTION_ROLLBACK = "ROLLBACK";

    private static final int NOTE_MAX = 255;

    private final ConfigDraftMapper draftMapper;
    private final ConfigReleaseMapper releaseMapper;
    private final ConfigActivePointerMapper pointerMapper;
    private final ConfigPublishRequestMapper approvalMapper;
    private final ConfigRegistry registry;
    private final ConfigPayloadCodec codec;
    private final ControlCenterAuditService audit;
    private final ControlCenterIdempotencyService idempotency;
    private final InsertRaceGuard insertRaceGuard;

    public ControlCenterReleaseTransactions(ConfigDraftMapper draftMapper,
                                            ConfigReleaseMapper releaseMapper,
                                            ConfigActivePointerMapper pointerMapper,
                                            ConfigPublishRequestMapper approvalMapper,
                                            ConfigRegistry registry,
                                            ConfigPayloadCodec codec,
                                            ControlCenterAuditService audit,
                                            ControlCenterIdempotencyService idempotency,
                                            InsertRaceGuard insertRaceGuard) {
        this.draftMapper = draftMapper;
        this.releaseMapper = releaseMapper;
        this.pointerMapper = pointerMapper;
        this.approvalMapper = approvalMapper;
        this.registry = registry;
        this.codec = codec;
        this.audit = audit;
        this.idempotency = idempotency;
        this.insertRaceGuard = insertRaceGuard;
    }

    // ------------------------------------------------------------------ 申请

    @Transactional
    public PublishRequestVO requestPublish(long draftId, PublishRequestBody body,
                                           String requestDigest, User actor) {
        Optional<PublishRequestVO> replay = idempotency.reserve(actor.getId(), body.getRequestId(),
                requestDigest, ACTION_PUBLISH_REQUEST, PublishRequestVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (draftId != ConfigDraft.SINGLETON_ID) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "草稿 id 必须为 1");
        }
        if (body.getRevision() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "申请发布必须携带 revision");
        }
        String note = trimToNull(body.getNote(), "备注", NOTE_MAX);

        ConfigDraft draft = draftMapper.selectById(ConfigDraft.SINGLETON_ID);
        if (draft == null) {
            throw failure(ACTION_PUBLISH_REQUEST, actor, null, body.getRequestId(), null,
                    ConfigErrorReason.NOT_VALIDATED, "草稿不存在");
        }
        if (!Objects.equals(draft.getRevision(), body.getRevision())) {
            throw failure(ACTION_PUBLISH_REQUEST, actor, null, body.getRequestId(),
                    String.valueOf(body.getRevision()), ConfigErrorReason.VERSION_CONFLICT,
                    "revision 与当前草稿不一致");
        }
        String problem = validationAnchorProblem(draft);
        if (problem != null) {
            throw failure(ACTION_PUBLISH_REQUEST, actor, null, body.getRequestId(),
                    "rev:" + draft.getRevision(), problem, "草稿未处于可发布状态");
        }

        ConfigActivePointer pointer = pointerMapper.selectById(ConfigActivePointer.SINGLETON_ID);
        Long active = pointer == null ? null : pointer.getReleaseId();
        if (!Objects.equals(active, body.getExpectedActiveReleaseId())) {
            throw failure(ACTION_PUBLISH_REQUEST, actor, null, body.getRequestId(),
                    String.valueOf(active), ConfigErrorReason.VERSION_CONFLICT,
                    "期望的活跃版本与当前不一致");
        }

        ConfigPublishRequest approval = new ConfigPublishRequest();
        approval.setActorId(actor.getId());
        approval.setRequestId(body.getRequestId());
        approval.setDraftRevision(draft.getRevision());
        approval.setPayloadDigest(payloadDigest(draft));
        approval.setValidationDigest(validationDigest(draft));
        approval.setExpectedActiveReleaseId(active);
        approval.setNote(note);
        approval.setStatus(ConfigPublishRequest.STATUS_PENDING);
        // savepoint：PostgreSQL 撞键后事务即中止，(actorId, requestId) 的复核查询和
        // 后面的审计/回填都必须在回滚到 savepoint 之后才能继续。
        if (!insertRaceGuard.tryInsert(() -> approvalMapper.insert(approval))) {
            // (actorId, requestId) 的唯一键在 t_config_request 上已经判过一次；能走到这里
            // 说明数据被外部写坏，按冲突处理而不是静默接受。
            log.error("发布申请唯一键冲突但幂等记录未命中, actorId={}, requestId={}",
                    actor.getId(), body.getRequestId());
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT, "审批单已存在");
        }

        audit.record(ACTION_PUBLISH_REQUEST, actor.getId(), String.valueOf(approval.getId()),
                body.getRequestId(), "rev:" + draft.getRevision(),
                ConfigPublishRequest.STATUS_PENDING, ConfigAuditEvent.RESULT_SUCCESS, null,
                auditPayload("draftRevision", draft.getRevision(),
                        "payloadDigest", approval.getPayloadDigest(),
                        "expectedActiveReleaseId", active));
        PublishRequestVO vo = toVO(approvalMapper.selectById(approval.getId()));
        idempotency.complete(actor.getId(), body.getRequestId(), vo);
        return vo;
    }

    // ------------------------------------------------------------------ 批准

    @Transactional
    public ReleaseVO approve(long approvalId, String requestId, String requestDigest, User actor) {
        Optional<ReleaseVO> replay = idempotency.reserve(actor.getId(), requestId,
                requestDigest, ACTION_PUBLISH_APPROVE, ReleaseVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        ConfigPublishRequest approval = approvalMapper.selectForUpdate(approvalId);
        if (approval == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "发布申请不存在");
        }
        if (!ConfigPublishRequest.STATUS_PENDING.equals(approval.getStatus())) {
            throw new DecisionNotPendingException(approval.getStatus());
        }

        ConfigDraft draft = draftMapper.selectById(ConfigDraft.SINGLETON_ID);
        String problem = bindingProblem(approval, draft);
        if (problem != null) {
            throw new StaleApprovalException(problem);
        }

        ConfigActivePointer pointer = pointerMapper.selectForUpdate();
        if (pointer == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "配置活跃指针缺失");
        }
        Long active = pointer.getReleaseId();
        if (!Objects.equals(active, approval.getExpectedActiveReleaseId())) {
            throw failure(ACTION_PUBLISH_APPROVE, actor, String.valueOf(approvalId), requestId,
                    versionLabel(active), ConfigErrorReason.VERSION_CONFLICT,
                    "活跃版本在申请之后已经变化");
        }

        long nextVersion = releaseMapper.selectMaxVersion() + 1;
        ConfigRelease release = new ConfigRelease();
        release.setReleaseVersion(nextVersion);
        release.setSchemaVersion(ConfigRegistry.SCHEMA_VERSION);
        release.setPayload(codec.canonical(codec.parse(draft.getPayload())));
        release.setDigest(approval.getPayloadDigest());
        release.setCreatedBy(actor.getId());
        release.setCreatedTime(new Date());
        releaseMapper.insert(release);

        int switched = pointerMapper.switchPointer(release.getId(), active, actor.getId());
        if (switched == 0) {
            throw failure(ACTION_PUBLISH_APPROVE, actor, String.valueOf(approvalId), requestId,
                    versionLabel(active), ConfigErrorReason.VERSION_CONFLICT,
                    "指针切换失败：活跃版本已被并发修改");
        }

        int decided = approvalMapper.decide(approvalId, ConfigPublishRequest.STATUS_APPROVED,
                actor.getId(), null, release.getId());
        if (decided == 0) {
            throw new DecisionNotPendingException(ConfigPublishRequest.STATUS_APPROVED);
        }

        ReleaseVO vo = toReleaseVO(release);
        idempotency.complete(actor.getId(), requestId, vo);
        audit.record(ACTION_PUBLISH_APPROVE, actor.getId(), String.valueOf(approvalId), requestId,
                versionLabel(active), String.valueOf(nextVersion),
                ConfigAuditEvent.RESULT_SUCCESS, null,
                auditPayload("releaseId", release.getId(),
                        "draftRevision", approval.getDraftRevision(),
                        "payloadDigest", approval.getPayloadDigest()));
        return vo;
    }

    // ------------------------------------------------------------------ 拒绝

    @Transactional
    public PublishRequestVO reject(long approvalId, ApprovalDecisionRequest body,
                                   String requestDigest, User actor) {
        Optional<PublishRequestVO> replay = idempotency.reserve(actor.getId(), body.getRequestId(),
                requestDigest, ACTION_PUBLISH_REJECT, PublishRequestVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        String reason = trimToNull(body.getReason(), "拒绝理由", NOTE_MAX);

        ConfigPublishRequest approval = approvalMapper.selectForUpdate(approvalId);
        if (approval == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "发布申请不存在");
        }
        if (!ConfigPublishRequest.STATUS_PENDING.equals(approval.getStatus())) {
            throw new DecisionNotPendingException(approval.getStatus());
        }

        int decided = approvalMapper.decide(approvalId, ConfigPublishRequest.STATUS_REJECTED,
                actor.getId(), reason, null);
        if (decided == 0) {
            throw new DecisionNotPendingException(ConfigPublishRequest.STATUS_REJECTED);
        }

        audit.record(ACTION_PUBLISH_REJECT, actor.getId(), String.valueOf(approvalId),
                body.getRequestId(), ConfigPublishRequest.STATUS_PENDING,
                ConfigPublishRequest.STATUS_REJECTED, ConfigAuditEvent.RESULT_SUCCESS, null,
                auditPayload("reason", reason, "draftRevision", approval.getDraftRevision()));
        PublishRequestVO vo = toVO(approvalMapper.selectById(approvalId));
        idempotency.complete(actor.getId(), body.getRequestId(), vo);
        return vo;
    }

    // ------------------------------------------------------------------ 回滚

    @Transactional
    public ReleaseVO rollback(long releaseId, RollbackRequestBody body,
                              String requestDigest, User actor) {
        Optional<ReleaseVO> replay = idempotency.reserve(actor.getId(), body.getRequestId(),
                requestDigest, ACTION_ROLLBACK, ReleaseVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        String reason = trimToNull(body.getReason(), "回滚理由", NOTE_MAX);

        ConfigActivePointer pointer = pointerMapper.selectForUpdate();
        if (pointer == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "配置活跃指针缺失");
        }
        Long active = pointer.getReleaseId();
        if (!Objects.equals(active, body.getExpectedActiveReleaseId())) {
            throw failure(ACTION_ROLLBACK, actor, String.valueOf(releaseId), body.getRequestId(),
                    versionLabel(active), ConfigErrorReason.VERSION_CONFLICT,
                    "期望的活跃版本与当前不一致");
        }

        ConfigRelease target = releaseMapper.selectById(releaseId);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "目标发布版本不存在");
        }
        if (Objects.equals(active, releaseId)) {
            throw failure(ACTION_ROLLBACK, actor, String.valueOf(releaseId), body.getRequestId(),
                    versionLabel(active), ConfigErrorReason.VERSION_CONFLICT,
                    "目标版本已是当前活跃版本");
        }
        if (!Objects.equals(target.getSchemaVersion(), ConfigRegistry.SCHEMA_VERSION)) {
            throw failure(ACTION_ROLLBACK, actor, String.valueOf(releaseId), body.getRequestId(),
                    "schema:" + target.getSchemaVersion(), ConfigErrorReason.SCHEMA_UNSUPPORTED,
                    "目标版本载荷结构与当前代码不一致");
        }

        long nextVersion = releaseMapper.selectMaxVersion() + 1;
        ConfigRelease snapshot = new ConfigRelease();
        snapshot.setReleaseVersion(nextVersion);
        snapshot.setSchemaVersion(target.getSchemaVersion());
        snapshot.setPayload(target.getPayload());
        snapshot.setDigest(target.getDigest());
        snapshot.setRollbackFromReleaseId(target.getId());
        snapshot.setCreatedBy(actor.getId());
        snapshot.setCreatedTime(new Date());
        releaseMapper.insert(snapshot);

        int switched = pointerMapper.switchPointer(snapshot.getId(), active, actor.getId());
        if (switched == 0) {
            throw failure(ACTION_ROLLBACK, actor, String.valueOf(releaseId), body.getRequestId(),
                    versionLabel(active), ConfigErrorReason.VERSION_CONFLICT,
                    "指针切换失败：活跃版本已被并发修改");
        }

        ReleaseVO vo = toReleaseVO(snapshot);
        idempotency.complete(actor.getId(), body.getRequestId(), vo);
        audit.record(ACTION_ROLLBACK, actor.getId(), String.valueOf(releaseId), body.getRequestId(),
                versionLabel(active), String.valueOf(nextVersion),
                ConfigAuditEvent.RESULT_SUCCESS, null,
                auditPayload("releaseId", snapshot.getId(),
                        "rollbackFromReleaseId", target.getId(),
                        "reason", reason));
        return vo;
    }

    // ------------------------------------------------------------------ STALE

    /**
     * 在发布事务<b>回滚之后</b>把审批单标记为 STALE 并写失败审计。
     *
     * <p>独立事务：发布事务已经回滚，这里必须单独提交——否则「审批单被静默留在
     * PENDING，而绑定的内容早已变化」会一直误导后续审批人。
     * 条件更新只在仍是 PENDING 时生效：并发批准已经成功的场景下，不覆盖它的终态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStale(long approvalId, Long actorId, String requestId, String reasonCode) {
        int rows = approvalMapper.decide(approvalId, ConfigPublishRequest.STATUS_STALE, actorId,
                truncate("STALE: " + reasonCode), null);
        if (rows == 0) {
            log.info("审批单已被并发决定，跳过 STALE 标记, approvalId={}", approvalId);
            return;
        }
        audit.recordFailure(ACTION_PUBLISH_APPROVE, actorId, String.valueOf(approvalId),
                requestId, null, reasonCode, null);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 草稿是否处于「可被申请发布」的状态。
     *
     * <p>返回原因码而不是抛异常：申请路径要在同一个地方写失败审计并返回对应错误码。
     */
    private String validationAnchorProblem(ConfigDraft draft) {
        if (!ConfigDraft.STATUS_VALIDATED.equals(draft.getStatus())) {
            return ConfigErrorReason.NOT_VALIDATED.name();
        }
        if (draft.getValidationRevision() == null
                || !Objects.equals(draft.getValidationRevision(), draft.getRevision())) {
            return ConfigErrorReason.STALE_VALIDATION.name();
        }
        return null;
    }

    /**
     * 审批绑定复核：审批单钉住的四件事都必须与当前草稿一致。
     *
     * <p>顺序是语义的一部分：先判「还是不是被批准的那一份」——revision 一旦推进，
     * 审批单唯一正确的归宿就是 STALE（40910，重新申请）；再判「那一份还能不能发布」
     * （同 revision 但状态被改回未校验 → 40901/40902，属于外部改库或异常恢复场景）。
     * 反过来先判校验状态，会把「内容已经变了」伪装成「未校验」，让审批人查错方向。
     */
    private String bindingProblem(ConfigPublishRequest approval, ConfigDraft draft) {
        if (draft == null) {
            return ConfigErrorReason.NOT_VALIDATED.name();
        }
        if (!Objects.equals(draft.getRevision(), approval.getDraftRevision())
                || !Objects.equals(payloadDigest(draft), approval.getPayloadDigest())
                || !Objects.equals(validationDigest(draft), approval.getValidationDigest())) {
            return ConfigErrorReason.APPROVAL_STALE.name();
        }
        return validationAnchorProblem(draft);
    }

    private String payloadDigest(ConfigDraft draft) {
        return codec.digest(codec.canonical(codec.parse(draft.getPayload())));
    }

    /**
     * 校验摘要 = 校验 revision + 校验结论的规范化 JSON 的 SHA-256。
     *
     * <p>同时覆盖 revision 与结论：只比 revision 会漏掉「同 revision 但结论被改写」，
     * 只比结论则会漏掉「同结论但对应的内容不同」。
     */
    private String validationDigest(ConfigDraft draft) {
        String result = draft.getValidationResult() == null
                ? "null"
                : codec.canonical(codec.parse(draft.getValidationResult()));
        return codec.digest("{\"validationRevision\":" + draft.getValidationRevision()
                + ",\"result\":" + result + "}");
    }

    private BusinessException failure(String action, User actor, String targetId, String requestId,
                                      String beforeVersion, ConfigErrorReason reason, String message) {
        return failure(action, actor, targetId, requestId, beforeVersion, reason.name(), message);
    }

    /** 原因码为字符串的版本：用于 {@link #validationAnchorProblem} 这类返回原因码名的方法。 */
    private BusinessException failure(String action, User actor, String targetId, String requestId,
                                      String beforeVersion, String reasonCode, String message) {
        audit.recordFailure(action, actor.getId(), targetId, requestId, beforeVersion,
                reasonCode, null);
        ConfigErrorReason reason = ConfigErrorReason.fromName(reasonCode);
        ErrorCode code = reason == null ? ErrorCode.SYSTEM_ERROR : reason.errorCode();
        return new BusinessException(code, message);
    }

    /** 审计里的「版本」用 releaseVersion 表达；指针为空时写 NONE。 */
    private String versionLabel(Long releaseId) {
        if (releaseId == null) {
            return "NONE";
        }
        ConfigRelease release = releaseMapper.selectById(releaseId);
        return release == null ? "id:" + releaseId : String.valueOf(release.getReleaseVersion());
    }

    private PublishRequestVO toVO(ConfigPublishRequest row) {
        return new PublishRequestVO(row.getId(), row.getStatus(), row.getDraftRevision(),
                row.getPayloadDigest(), row.getValidationDigest(), row.getExpectedActiveReleaseId(),
                row.getNote(), row.getActorId(), row.getRequestedTime(), row.getDecidedBy(),
                row.getDecidedTime(), row.getDecisionReason(), row.getReleaseId());
    }

    private ReleaseVO toReleaseVO(ConfigRelease release) {
        Map<String, Object> values = codec.overlay(registry.defaults(), codec.parse(release.getPayload()));
        return new ReleaseVO(release.getId(), release.getReleaseVersion(), release.getSchemaVersion(),
                release.getDigest(), release.getRollbackFromReleaseId(), release.getCreatedBy(),
                release.getCreatedTime(), values);
    }

    private static Map<String, Object> auditPayload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                payload.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        }
        return payload.isEmpty() ? null : payload;
    }

    private static String trimToNull(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + "长度不能超过 " + max);
        }
        return trimmed;
    }

    private static String truncate(String value) {
        return value.length() <= NOTE_MAX ? value : value.substring(0, NOTE_MAX);
    }
}
