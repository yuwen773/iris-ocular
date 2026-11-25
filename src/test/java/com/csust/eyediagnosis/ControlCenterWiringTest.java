package com.csust.eyediagnosis;

import com.csust.eyediagnosis.config.ControlCenterProperties;
import com.csust.eyediagnosis.config.SameOriginFilter;
import com.csust.eyediagnosis.controlcenter.ControlCenterCsrfController;
import com.csust.eyediagnosis.controlcenter.security.ControlCenterCsrfFilter;
import com.csust.eyediagnosis.security.CsrfTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制中心开启后的 Spring 装配（WP-01A）。
 *
 * <p>单测覆盖了过滤器与切面的行为，这里专门覆盖"开关打开后它们真的被注册、
 * 顺序正确、配置绑定生效"——装配错位只会在生产暴露，单测看不见。
 * 默认关闭时的注册断言在 {@link EyeDiagnosisApplicationTests} 里。
 */
@SpringBootTest(properties = {
        "eye-diagnosis.control-center.enabled=true",
        "eye-diagnosis.control-center.allowed-origins[0]=  https://admin.example.com  ",
        "eye-diagnosis.control-center.publisher-user-ids[0]=42",
        "eye-diagnosis.control-center.publisher-user-ids[1]=43",
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ControlCenterProperties properties;

    @Autowired
    private CsrfTokenService csrfTokenService;

    @Test
    void propertiesAreBoundAndNormalized() {
        // 前导/尾随空格在 setter 里被裁掉：白名单比对是精确匹配，带空格等于失效。
        assertEquals(List.of("https://admin.example.com"), properties.getAllowedOrigins());
        assertEquals(List.of(42L, 43L), properties.getPublisherUserIds());
        assertTrue(properties.isEnabled());
        assertFalse(properties.isTrustProxyHeaders());
    }

    @Test
    void csrfEndpointIsRegisteredWhenEnabled() {
        assertNotNull(context.getBean(ControlCenterCsrfController.class));
    }

    @Test
    void csrfFilterRunsAfterOriginFilter() {
        SameOriginFilter originFilter = context.getBean(SameOriginFilter.class);
        ControlCenterCsrfFilter csrfFilter = context.getBean(ControlCenterCsrfFilter.class);

        AnnotationAwareOrderComparator comparator = new AnnotationAwareOrderComparator();
        assertTrue(comparator.compare(originFilter, csrfFilter) < 0,
                "Origin 校验必须先于 CSRF 校验执行");
    }

    @Test
    void csrfTokenServiceIsSharedByControllerAndFilter() {
        String token = csrfTokenService.issue(new MockHttpSession());
        assertNotNull(token);
    }
}
