package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 原因归因的一行：原因 + 计数 + 占比。
 *
 * <p>{@code reason} 来自契约枚举；契约外取值由 Service 合并为 {@code UNCLASSIFIED}，
 * 原因为空合并为 {@code UNRECORDED}。{@code share} 的分母由所属指标决定
 * （拒识用拒识总数，复核队列用有原因记录数），为 0 时 {@code share} 为 null。
 */
@Data
public class ReasonStatVO implements Serializable {

    /** 原因（契约枚举 / UNCLASSIFIED / UNRECORDED） */
    private String reason;

    /** 计数 */
    private long count;

    /** 占比；分母为 0 时为 null */
    private Double share;

    public ReasonStatVO() {
    }

    public ReasonStatVO(String reason, long count) {
        this.reason = reason;
        this.count = count;
    }

    private static final long serialVersionUID = 1L;
}
