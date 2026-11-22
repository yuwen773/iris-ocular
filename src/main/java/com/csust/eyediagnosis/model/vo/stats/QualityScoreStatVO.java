package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 图像质量分档一格。
 *
 * <p>契约值是 {100,75,50,25,0}（{@code 100 - 25 × 原因数}），按精确值分档，
 * 不按百分位重切。契约外值不进这些桶，单独计入
 * {@link ModelAnalyticsVO#getQualityOutOfContract()}。
 *
 * <p>{@code score} 允许为 null：查询会返回质量分为空的一组，Service 据此
 * 计入 {@link ModelAnalyticsVO#getQualityUnknown()}，而不是把空值当成 0 分。
 */
@Data
public class QualityScoreStatVO implements Serializable {

    /** 质量分（契约值；null 表示该组记录质量分为空） */
    private Integer score;

    /** 计数 */
    private long count;

    public QualityScoreStatVO() {
    }

    public QualityScoreStatVO(Integer score, long count) {
        this.score = score;
        this.count = count;
    }

    private static final long serialVersionUID = 1L;
}
