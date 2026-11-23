package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 数据分析页的聚合读数（{@code GET /stats/admin/analytics}）。
 *
 * <p>与 {@link AdminOverviewVO} 的分工：
 * <ul>
 *   <li>{@code overview} 是仪表盘的当前态读数，字段与口径保持不变；</li>
 *   <li>本 VO 只装数据分析需要的增量维度：复核效率、拒识归因、模型与图像质量。</li>
 * </ul>
 *
 * <p>三条口径约束见 {@code docs/design/iris22-admin-metric-registry.md}：
 * 比率为 {@code null} 表示「不适用」而不是 0；未知枚举不丢弃、进
 * {@link DataQualityVO}；复核维度在写入安全关闭期间标 {@code HISTORICAL_ONLY}。
 */
@Data
public class AdminAnalyticsVO implements Serializable {

    /** 时间窗长度（天），已收敛到 [7, 365] */
    private int days;

    /** 时间窗起点（yyyy-MM-dd） */
    private String rangeStart;

    /** 时间窗终点（yyyy-MM-dd，含当天） */
    private String rangeEnd;

    /** 复核领域（历史数据） */
    private ReviewAnalyticsVO review;

    /** 拒识领域（未出结论） */
    private RejectionAnalyticsVO rejection;

    /** 模型与图像质量领域 */
    private ModelAnalyticsVO model;

    /** 数据质量自检：契约外取值、量纲未知、时间缺失 */
    private DataQualityVO dataQuality;

    private static final long serialVersionUID = 1L;
}
