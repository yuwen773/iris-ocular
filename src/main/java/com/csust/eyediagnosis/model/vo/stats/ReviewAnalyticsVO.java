package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 复核效率读数。
 *
 * <p><b>数据状态恒为 {@code HISTORICAL_ONLY}</b>：正式临床复核写入已被
 * {@code ClinicalReviewQualification} 安全关闭（D-01b），新记录不会产生复核人、
 * 复核时间或 REVIEWED/OVERRIDDEN。这里读到的是历史记录与演示数据，
 * 页面不得当作当前运营读数展示。
 */
@Data
public class ReviewAnalyticsVO implements Serializable {

    /** 数据状态，固定 {@code HISTORICAL_ONLY} */
    private String dataStatus = "HISTORICAL_ONLY";

    /** 待复核积压（全站快照，不限时间窗） */
    private long awaitingTotal;

    /** 窗口内创建、当前处于复核队列的记录数（A-07，按当前状态） */
    private long queuedInWindow;

    /** 窗口内创建、当前已完成复核的记录数（A-08 分子） */
    private long completedInWindow;

    /** 复核完成率 = completedInWindow / queuedInWindow；分母为 0 时为 null */
    private Double completionRate;

    /** 按复核时间统计的已完成复核数（A-09 分母） */
    private long reviewedInWindow;

    /** 其中复核结论推翻 AI 的数量 */
    private long overriddenInWindow;

    /** 复核改写率 = overriddenInWindow / reviewedInWindow；分母为 0 时为 null */
    private Double overrideRate;

    /** 有效时延样本数（humanReviewedAt 非空） */
    private long latencySamples;

    /** 端到端复核时延（判读创建 → 复核完成）均值，单位分钟；无样本为 null */
    private Double avgMinutes;

    /** 端到端复核时延中位数（最近秩），单位分钟；无样本为 null */
    private Double p50Minutes;

    /** 端到端复核时延 P90，单位分钟；无样本为 null */
    private Double p90Minutes;

    /** 端到端复核时延最大值，单位分钟；无样本为 null */
    private Double maxMinutes;

    /** 复核人工作量（最多 50 行，按复核量降序） */
    private List<ReviewerStatVO> reviewers = new ArrayList<>();

    /** 触发复核原因构成（A-12，含 LOW_CONFIDENCE） */
    private List<ReasonStatVO> queueReasons = new ArrayList<>();

    /** 契约外原因取值的记录数（不丢弃，单独计数） */
    private long unclassifiedReasonCount;

    /** 原因为空的记录数 */
    private long unknownReasonCount;

    private static final long serialVersionUID = 1L;
}
