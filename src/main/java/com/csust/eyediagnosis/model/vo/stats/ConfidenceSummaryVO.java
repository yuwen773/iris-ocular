package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 置信度汇总与量纲自检。
 *
 * <p>量纲规则（2026-11-22 定稿）：推理链路写入 0–100；历史/演示数据存在 0–1。
 * <b>不得仅凭数值 ≤1 自动乘 100</b>——0.74 既可能是 74%，也可能是 0.74%。
 * 因此只有 {@code 1 < confidence ≤ 100} 的样本参与聚合，其余单独计数、不猜测。
 */
@Data
public class ConfidenceSummaryVO implements Serializable {

    /** 参与聚合的样本数（1 < confidence ≤ 100） */
    private long samples;

    /** 置信度均值（0–100 量纲）；无样本为 null */
    private Double avg;

    /** 量纲无法判定而排除的样本数（0 < confidence ≤ 1） */
    private long ambiguous;

    /** 越界值样本数（confidence ≤ 0 或 > 100） */
    private long outOfRange;

    /** 空值样本数 */
    private long missing;

    private static final long serialVersionUID = 1L;
}
