package com.csust.eyediagnosis.controlcenter.model.dto;

import lombok.Data;

/**
 * 回滚请求。
 *
 * <p>回滚不修改任何既有 release：它按目标 release 的内容<b>新建</b>一条 release，
 * 再用 {@code rollbackFromReleaseId} 记下来源（ADR D2）。因此
 * {@code expectedActiveReleaseId} 仍是必需项——回滚同样会覆盖线上配置，
 * 两个并发回滚必须只能成功一个。
 */
@Data
public class RollbackRequestBody {

    /** 必填：调用方看到的当前活跃 release id；未发布过时为 null。 */
    private Long expectedActiveReleaseId;

    /** 必填幂等键。 */
    private String requestId;

    /** 可选理由，最长 255。 */
    private String reason;
}
