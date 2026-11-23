package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 复核时延一行的聚合结果。
 *
 * <p>MySQL 8 没有 {@code PERCENTILE_CONT()}，分位数由
 * {@code ROW_NUMBER() + COUNT() OVER ()} 的最近秩计算，见
 * {@code AdminAnalyticsMapper#reviewLatency}。
 */
@Data
public class LatencyStatVO implements Serializable {

    /** 有效样本数（humanReviewedAt 非空） */
    private long total;

    /** 均值（分钟）；无样本为 null */
    private Double avgMinutes;

    /** 中位数（分钟）；无样本为 null */
    private Double p50Minutes;

    /** P90（分钟）；无样本为 null */
    private Double p90Minutes;

    /** 最大值（分钟）；无样本为 null */
    private Double maxMinutes;

    private static final long serialVersionUID = 1L;
}
