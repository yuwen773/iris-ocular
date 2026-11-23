package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 状态分布一格：状态 + 计数。
 *
 * <p>用于 OOD 状态与校准状态。空值在 Service 里归为 {@code UNRECORDED}，
 * 契约外取值原样保留并计入数据质量，不丢弃。
 */
@Data
public class StatusStatVO implements Serializable {

    /** 状态值（契约枚举 / UNRECORDED / 契约外原值） */
    private String status;

    /** 计数 */
    private long count;

    public StatusStatVO() {
    }

    public StatusStatVO(String status, long count) {
        this.status = status;
        this.count = count;
    }

    private static final long serialVersionUID = 1L;
}
