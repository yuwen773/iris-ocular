package com.csust.eyediagnosis.controlcenter.model.dto;

import lombok.Data;

import java.util.Map;

/**
 * 草稿保存请求（创建与更新共用）。
 *
 * <p>{@code schemaVersion} 必须显式携带且等于当前版本：客户端与后端对载荷
 * 结构的理解不一致时，宁可 40903 也不能把旧结构写进新库。
 */
@Data
public class DraftSaveRequest {

    /** 载荷 schema 版本；必须等于 ConfigRegistry.SCHEMA_VERSION。 */
    private Integer schemaVersion;

    /** 键 → 值。只允许注册表内的键。 */
    private Map<String, Object> values;

    /** 更新时必填：调用方看到的草稿 revision。 */
    private Long expectedRevision;

    /** 可选幂等键；同一 actor 重复提交同一 requestId 会重放首次结果。 */
    private String requestId;
}
