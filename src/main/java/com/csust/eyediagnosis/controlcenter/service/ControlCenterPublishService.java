package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.controlcenter.model.dto.ApprovalDecisionRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.PublishRequestBody;
import com.csust.eyediagnosis.controlcenter.model.dto.RollbackRequestBody;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigPublishRequest;
import com.csust.eyediagnosis.controlcenter.model.enums.ConfigErrorReason;
import com.csust.eyediagnosis.controlcenter.model.vo.PageVO;
import com.csust.eyediagnosis.controlcenter.model.vo.PublishRequestVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.ConfigPublishRequestMapper;
import com.csust.eyediagnosis.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 发布审批的编排层（WP-01C）。
 *
 * <p>本类<b>不带事务</b>：它负责算请求摘要、翻译内部异常、在事务回滚之后补写
 * STALE 与失败审计。所有事务语义都在 {@link ControlCenterReleaseTransactions}。
 *
 * <p>为什么要在这里翻译而不是在事务里：STALE 标记必须发生在发布事务回滚之后
 * （否则 REQUIRES_NEW 会在同一行上和外层行锁互等）。异常从事务方法里抛出，
 * Spring 完成回滚、释放行锁，本类再开新事务写状态——顺序是语义的一部分。
 */
@Slf4j
@Service
public class ControlCenterPublishService {

    private final ControlCenterReleaseTransactions transactions;
    private final ControlCenterAuditService audit;
    private final ConfigPayloadCodec codec;
    private final ConfigPublishRequestMapper approvalMapper;

    public ControlCenterPublishService(ControlCenterReleaseTransactions transactions,
                                       ControlCenterAuditService audit,
                                       ConfigPayloadCodec codec,
                                       ConfigPublishRequestMapper approvalMapper) {
        this.transactions = transactions;
        this.audit = audit;
        this.codec = codec;
        this.approvalMapper = approvalMapper;
    }

    public PublishRequestVO requestPublish(long draftId, PublishRequestBody body, User actor) {
        ControlCenterIdempotencyService.requireRequestId(body.getRequestId());
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("revision", body.getRevision());
        canonical.put("expectedActiveReleaseId", body.getExpectedActiveReleaseId());
        canonical.put("note", body.getNote());
        String digest = codec.requestDigest("POST", path("/drafts/" + draftId + "/publish-requests"),
                canonical);
        return transactions.requestPublish(draftId, body, digest, actor);
    }

    public ReleaseVO approve(long approvalId, ApprovalDecisionRequest body, User actor) {
        ControlCenterIdempotencyService.requireRequestId(body.getRequestId());
        String digest = codec.requestDigest("POST", path("/publish-requests/" + approvalId + "/approve"),
                Map.of("requestId", body.getRequestId()));
        try {
            return transactions.approve(approvalId, body.getRequestId(), digest, actor);
        } catch (StaleApprovalException stale) {
            // 事务已回滚、行锁已释放：现在才把审批单钉成 STALE。
            transactions.markStale(approvalId, actor.getId(), body.getRequestId(), stale.getReasonCode());
            ErrorCode code = codeForStale(stale.getReasonCode());
            throw new BusinessException(code, staleMessage(code));
        } catch (DecisionNotPendingException decided) {
            audit.recordFailure(ControlCenterReleaseTransactions.ACTION_PUBLISH_APPROVE,
                    actor.getId(), String.valueOf(approvalId), body.getRequestId(), null,
                    ConfigErrorReason.APPROVAL_NOT_PENDING.name(), null);
            throw new BusinessException(ErrorCode.CONFIG_APPROVAL_NOT_PENDING,
                    "该发布申请当前状态为 " + decided.getCurrentStatus() + "，不能再次决定");
        }
    }

    public PublishRequestVO reject(long approvalId, ApprovalDecisionRequest body, User actor) {
        ControlCenterIdempotencyService.requireRequestId(body.getRequestId());
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("requestId", body.getRequestId());
        canonical.put("reason", body.getReason());
        String digest = codec.requestDigest("POST", path("/publish-requests/" + approvalId + "/reject"),
                canonical);
        try {
            return transactions.reject(approvalId, body, digest, actor);
        } catch (DecisionNotPendingException decided) {
            audit.recordFailure(ControlCenterReleaseTransactions.ACTION_PUBLISH_REJECT,
                    actor.getId(), String.valueOf(approvalId), body.getRequestId(), null,
                    ConfigErrorReason.APPROVAL_NOT_PENDING.name(), null);
            throw new BusinessException(ErrorCode.CONFIG_APPROVAL_NOT_PENDING,
                    "该发布申请当前状态为 " + decided.getCurrentStatus() + "，不能再次决定");
        }
    }

    public ReleaseVO rollback(long releaseId, RollbackRequestBody body, User actor) {
        ControlCenterIdempotencyService.requireRequestId(body.getRequestId());
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("expectedActiveReleaseId", body.getExpectedActiveReleaseId());
        canonical.put("requestId", body.getRequestId());
        canonical.put("reason", body.getReason());
        String digest = codec.requestDigest("POST", path("/releases/" + releaseId + "/rollback"),
                canonical);
        return transactions.rollback(releaseId, body, digest, actor);
    }

    /** 审批单列表；{@code status} 为空表示全部。 */
    public PageVO<PublishRequestVO> listPublishRequests(String status, int page, int pageSize) {
        String normalized = normalizeStatus(status);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long total = approvalMapper.countByStatus(normalized);
        List<PublishRequestVO> records = approvalMapper
                .page(normalized, safeSize, (safePage - 1) * safeSize)
                .stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(records, total, safePage, safeSize);
    }

    public PublishRequestVO getPublishRequest(long id) {
        ConfigPublishRequest row = approvalMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "发布申请不存在");
        }
        return toVO(row);
    }

    // ------------------------------------------------------------------

    /** STALE 的三种成因对外保持各自的错误码，诊断信息不丢。 */
    private ErrorCode codeForStale(String reasonCode) {
        ConfigErrorReason reason = ConfigErrorReason.fromName(reasonCode);
        if (reason == ConfigErrorReason.NOT_VALIDATED) {
            return ErrorCode.CONFIG_NOT_VALIDATED;
        }
        if (reason == ConfigErrorReason.STALE_VALIDATION) {
            return ErrorCode.CONFIG_STALE_VALIDATION;
        }
        return ErrorCode.CONFIG_APPROVAL_STALE;
    }

    /**
     * 失效提示按成因分开写。
     *
     * <p>三种成因的**下一步动作不一样**：内容变了要重新申请，未校验要重新校验，
     * 校验过期要重新校验再申请。统一成一句「请重新申请」会让申请人先跑一趟
     * 发现还得校验。错误码与提示必须说同一件事。
     */
    private String staleMessage(ErrorCode code) {
        return switch (code) {
            case CONFIG_NOT_VALIDATED -> "发布申请绑定的草稿已不再处于已校验状态，请重新校验后再申请";
            case CONFIG_STALE_VALIDATION -> "发布申请绑定的校验结论已过期，请重新校验后再申请";
            default -> "发布申请绑定的草稿内容已变化，请重新申请";
        };
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String upper = status.trim().toUpperCase();
        if (!Objects.equals(upper, ConfigPublishRequest.STATUS_PENDING)
                && !Objects.equals(upper, ConfigPublishRequest.STATUS_APPROVED)
                && !Objects.equals(upper, ConfigPublishRequest.STATUS_REJECTED)
                && !Objects.equals(upper, ConfigPublishRequest.STATUS_STALE)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的审批状态：" + status);
        }
        return upper;
    }

    private static String path(String suffix) {
        return "/admin/control-center" + suffix;
    }

    private PublishRequestVO toVO(ConfigPublishRequest row) {
        return new PublishRequestVO(row.getId(), row.getStatus(), row.getDraftRevision(),
                row.getPayloadDigest(), row.getValidationDigest(), row.getExpectedActiveReleaseId(),
                row.getNote(), row.getActorId(), row.getRequestedTime(), row.getDecidedBy(),
                row.getDecidedTime(), row.getDecisionReason(), row.getReleaseId());
    }
}
