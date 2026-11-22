package com.csust.eyediagnosis.controller;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.vo.stats.AdminAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.AdminOverviewVO;
import com.csust.eyediagnosis.model.vo.stats.ReviewAnalyticsVO;
import com.csust.eyediagnosis.service.AdminAnalyticsService;
import com.csust.eyediagnosis.service.AdminStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两个统计入口的分工与鉴权。
 *
 * <p>仪表盘走 {@code /stats/admin/overview}，数据分析走 {@code /stats/admin/analytics}；
 * 两者只共享鉴权方式，不共享响应体。新增入口不能顺手改掉旧的。
 */
class AdminStatsControllerTest {

    private AdminStatsService adminStatsService;
    private AdminAnalyticsService adminAnalyticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        adminStatsService = mock(AdminStatsService.class);
        adminAnalyticsService = mock(AdminAnalyticsService.class);
        AdminStatsController controller = new AdminStatsController();
        ReflectionTestUtils.setField(controller, "adminStatsService", adminStatsService);
        ReflectionTestUtils.setField(controller, "adminAnalyticsService", adminAnalyticsService);
        // 绕过 AuthInterceptor 与 AuthCheck AOP，只测路由与委托
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 分析入口默认为三十天并委托给分析服务() throws Exception {
        AdminAnalyticsVO vo = new AdminAnalyticsVO();
        vo.setDays(30);
        vo.setReview(new ReviewAnalyticsVO());
        when(adminAnalyticsService.analytics(30)).thenReturn(vo);

        mockMvc.perform(get("/stats/admin/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.days").value(30))
                .andExpect(jsonPath("$.data.review.dataStatus").value("HISTORICAL_ONLY"));

        verify(adminAnalyticsService).analytics(eq(30));
    }

    @Test
    void 分析入口按传入天数调用() throws Exception {
        when(adminAnalyticsService.analytics(90)).thenReturn(new AdminAnalyticsVO());

        mockMvc.perform(get("/stats/admin/analytics").param("days", "90"))
                .andExpect(status().isOk());

        verify(adminAnalyticsService).analytics(eq(90));
    }

    @Test
    void 总览入口保持原行为() throws Exception {
        AdminOverviewVO overview = new AdminOverviewVO();
        overview.setDays(30);
        when(adminStatsService.overview(30)).thenReturn(overview);

        mockMvc.perform(get("/stats/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(30));

        verify(adminStatsService).overview(eq(30));
    }

    @Test
    void 两个入口都要求管理员角色() throws Exception {
        assertEquals(AuthConstant.ADMIN_ROLE, authRoleOf("analytics", int.class));
        assertEquals(AuthConstant.ADMIN_ROLE, authRoleOf("overview", int.class));
    }

    private static String authRoleOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AdminStatsController.class.getMethod(methodName, parameterTypes);
        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
        assertNotNull(authCheck, methodName + " 缺少 @AuthCheck");
        return authCheck.mustRole();
    }
}
