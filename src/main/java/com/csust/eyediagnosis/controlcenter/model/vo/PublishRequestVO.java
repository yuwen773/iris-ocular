package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.Date;

/**
 * 发布审批请求视图。
 *
 * <p>三个摘要原样返回：前端/运维可以用它们核对「审批单钉住的是哪一份内容」，
 * 而不是只能相信状态字段。
 */
public record PublishRequestVO(long id,
                               String status,
                               long draftRevision,
                               String payloadDigest,
                               String validationDigest,
                               Long expectedActiveReleaseId,
                               String note,
                               Long requestedBy,
                               Date requestedTime,
                               Long decidedBy,
                               Date decidedTime,
                               String decisionReason,
                               Long releaseId) {
}
