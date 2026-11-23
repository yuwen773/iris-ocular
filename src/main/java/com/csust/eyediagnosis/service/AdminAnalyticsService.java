package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.model.vo.stats.AdminAnalyticsVO;

/**
 * 数据分析页的聚合读数。
 *
 * <p>与 {@link AdminStatsService#overview(int)} 的区别：overview 是仪表盘的当前态读数，
 * 本接口只提供数据分析需要的增量维度（复核、拒识、模型与质量）。
 * 口径正文见 {@code docs/design/iris22-admin-metric-registry.md}。
 */
public interface AdminAnalyticsService {

    /**
     * 取时间窗内的分析读数。
     *
     * @param days 时间窗长度（天），实现内部收敛到 [7, 365]
     * @return 分析读数
     */
    AdminAnalyticsVO analytics(int days);
}
