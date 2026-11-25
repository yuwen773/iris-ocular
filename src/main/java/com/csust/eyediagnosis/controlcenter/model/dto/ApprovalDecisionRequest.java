package com.csust.eyediagnosis.controlcenter.model.dto;

import lombok.Data;

/** 批准 / 拒绝一条发布申请的共用请求体；{@code requestId} 必填。 */
@Data
public class ApprovalDecisionRequest {

    /** 必填幂等键。 */
    private String requestId;

    /** 仅拒绝时使用：拒绝理由，最长 255。 */
    private String reason;
}
