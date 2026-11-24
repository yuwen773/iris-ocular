package com.csust.eyediagnosis.controlcenter.model.dto;

import lombok.Data;

/**
 * 发布申请。
 *
 * <p>申请不携带配置值：值只在草稿里，申请只钉住「哪一版草稿」。
 * {@code revision} 与 {@code expectedActiveReleaseId} 都必须显式给出——
 * 申请人和审批人都要能证明自己看到的是同一份内容、同一个线上版本。
 */
@Data
public class PublishRequestBody {

    /** 必填：本次申请针对的草稿 revision。 */
    private Long revision;

    /** 必填：申请人看到的当前活跃 release id；未发布过时为 null。 */
    private Long expectedActiveReleaseId;

    /** 可选备注，最长 255。 */
    private String note;

    /** 必填幂等键。 */
    private String requestId;
}
