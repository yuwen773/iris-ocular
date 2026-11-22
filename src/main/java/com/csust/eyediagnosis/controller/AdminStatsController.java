package com.csust.eyediagnosis.controller;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.vo.stats.AdminAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.AdminOverviewVO;
import com.csust.eyediagnosis.service.AdminAnalyticsService;
import com.csust.eyediagnosis.service.AdminStatsService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端的统计端点。
 *
 * <p>两个页面、两个入口，各自回答一个问题：
 * <ul>
 *   <li>{@code /admin/overview} → 仪表盘：「现在怎么样？」当前态与环比读数；</li>
 *   <li>{@code /admin/analytics} → 数据分析：「为什么会这样？」归因、分布与质量自检。</li>
 * </ul>
 * 两个接口不共用响应体；口径正文见 {@code docs/design/iris22-admin-metric-registry.md}。
 */
@RestController
@RequestMapping("/stats")
public class AdminStatsController {

    @Resource
    private AdminStatsService adminStatsService;

    @Resource
    private AdminAnalyticsService adminAnalyticsService;

    /**
     * 总览读数。
     *
     * @param days 时间窗长度（天），默认 30，收敛到 [7, 365]
     */
    @GetMapping("/admin/overview")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<AdminOverviewVO> overview(@RequestParam(value = "days", defaultValue = "30") int days) {
        return ResultUtils.success(adminStatsService.overview(days));
    }

    /**
     * 分析读数：复核、拒识归因、模型与图像质量、数据质量自检。
     *
     * <p>响应里带样本数与异常计数，前端据此解释「图表加总与原始记录数不一致」。
     *
     * @param days 时间窗长度（天），默认 30，收敛到 [7, 365]
     */
    @GetMapping("/admin/analytics")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<AdminAnalyticsVO> analytics(@RequestParam(value = "days", defaultValue = "30") int days) {
        return ResultUtils.success(adminAnalyticsService.analytics(days));
    }
}
