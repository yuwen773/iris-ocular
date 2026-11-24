package com.csust.eyediagnosis.controlcenter.model.dto;

import lombok.Data;

/** 草稿校验请求。 */
@Data
public class DraftValidateRequest {

    /** 必填：本次校验针对的 revision。 */
    private Long expectedRevision;

    /** 可选幂等键。 */
    private String requestId;
}
