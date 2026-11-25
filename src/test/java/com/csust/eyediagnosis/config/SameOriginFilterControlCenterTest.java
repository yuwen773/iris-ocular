package com.csust.eyediagnosis.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 控制中心严格 Origin 策略（WP-01A）。
 *
 * <p>只覆盖 {@code eye-diagnosis.control-center.enabled=true} 时
 * {@code /admin/control-center/**} 的写请求；其余路径的既有行为由
 * {@link SameOriginFilterTest} 回归。
 */
class SameOriginFilterControlCenterTest {

    private static final String CONTROL_PATH = "/api/admin/control-center/config";

    private final java.util.List<String> auditEvents = new java.util.ArrayList<>();
    private ControlCenterProperties properties;
    private SameOriginFilter filter;
    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        auditEvents.clear();
        properties = new ControlCenterProperties();
        properties.setEnabled(true);
        filter = new SameOriginFilter(properties, (request, action, reason) -> auditEvents.add(action + ":" + reason));
        ReflectionTestUtils.setField(filter, "additionalHosts", List.of());
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest request(String method, String origin, String referer) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, CONTROL_PATH);
        request.setContextPath("/api");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8081);
        if (origin != null) request.addHeader("Origin", origin);
        if (referer != null) request.addHeader("Referer", referer);
        return request;
    }

    private void assertRejected(MockHttpServletRequest request, String reason) throws Exception {
        filter.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(reason), "期望原因码 " + reason + "，实际响应: " + body);
        verify(chain, never()).doFilter(any(), any());
    }

    private void assertPassed(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void exactSameOriginPasses() throws Exception {
        assertPassed(request("POST", "http://localhost:8081", null));
    }

    @Test
    void sameOriginWithTrailingRootPathPasses() throws Exception {
        // 部分浏览器 / 反代会带末尾斜杠；Origin 语义本身不含 path，视为合法。
        assertPassed(request("POST", "http://localhost:8081/", null));
    }

    @Test
    void crossSiteOriginIsRejectedWithMismatch() throws Exception {
        assertRejected(request("POST", "http://evil.example.com", null),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
        assertTrue(auditEvents.contains("ORIGIN_REJECTED:" + SameOriginFilter.REASON_ORIGIN_MISMATCH),
                "严格拒绝必须留痕，实际: " + auditEvents);
    }

    @Test
    void nullOriginIsRejectedEvenWithValidReferer() throws Exception {
        // 沙箱 iframe / file:// 场景：Referer 合法也不能放行。
        assertRejected(request("POST", "null", "http://localhost:8081/admin/control-center"),
                SameOriginFilter.REASON_ORIGIN_NULL);
    }

    @Test
    void missingOriginAndRefererIsRejected() throws Exception {
        assertRejected(request("POST", null, null), SameOriginFilter.REASON_ORIGIN_MISSING);
    }

    @Test
    void blankOriginIsRejected() throws Exception {
        assertRejected(request("POST", "   ", null), SameOriginFilter.REASON_ORIGIN_MISSING);
    }

    @Test
    void schemeMismatchIsRejected() throws Exception {
        // 同一 host:port 但 scheme 不同，就不是同源。
        assertRejected(request("POST", "https://localhost:8081", null),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void portMismatchIsRejected() throws Exception {
        assertRejected(request("POST", "http://localhost:9999", null),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void originWithPathIsRejectedAsMalformed() throws Exception {
        assertRejected(request("POST", "http://localhost:8081/admin/control-center", null),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void refererOnlyFallbackIsAcceptedWhenItMatches() throws Exception {
        assertPassed(request("POST", null, "http://localhost:8081/admin/control-center"));
    }

    @Test
    void refererOnlyFallbackIsRejectedWhenCrossSite() throws Exception {
        assertRejected(request("POST", null, "http://evil.example.com/form"),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void configuredAllowedOriginIsAcceptedByExactMatch() throws Exception {
        properties.setAllowedOrigins(List.of("https://admin.example.com"));
        assertPassed(request("POST", "https://admin.example.com", null));
    }

    @Test
    void configuredAllowedOriginDoesNotMatchSubdomain() throws Exception {
        properties.setAllowedOrigins(List.of("https://admin.example.com"));
        assertRejected(request("POST", "https://evil.admin.example.com", null),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void legacyAdditionalHostsWhitelistDoesNotApplyToControlCenter() throws Exception {
        // 既有 additional-hosts 只按 host 比对、不带 scheme/port；
        // 控制中心不能用它绕过严格校验。
        ReflectionTestUtils.setField(filter, "additionalHosts", List.of("admin.example.com"));
        assertRejected(request("POST", "https://admin.example.com", null),
                SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void forwardedProtoIsIgnoredWhenProxyTrustDisabled() throws Exception {
        MockHttpServletRequest request = request("POST", "https://localhost:8081", null);
        request.addHeader("X-Forwarded-Proto", "https");
        assertRejected(request, SameOriginFilter.REASON_ORIGIN_MISMATCH);
    }

    @Test
    void forwardedProtoIsHonouredWhenProxyTrustEnabled() throws Exception {
        properties.setTrustProxyHeaders(true);
        MockHttpServletRequest request = request("POST", "https://localhost:8081", null);
        request.addHeader("X-Forwarded-Proto", "https");
        assertPassed(request);
    }

    @Test
    void safeMethodsAreNotSubjectToStrictCheck() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            MockHttpServletResponse perCall = new MockHttpServletResponse();
            filter.doFilter(request(method, "http://evil.example.com", null), perCall, chain);
            assertEquals(200, perCall.getStatus());
        }
    }

    @Test
    void nonControlCenterPathKeepsLegacySemanticsWhenEnabled() throws Exception {
        // 启用控制中心不得收紧其他业务路径：host 相同即通过（旧规则不看 scheme）。
        MockHttpServletRequest legacy = new MockHttpServletRequest("POST", "/api/news");
        legacy.setContextPath("/api");
        legacy.setServerName("localhost");
        legacy.setServerPort(8081);
        legacy.addHeader("Origin", "https://localhost:8081");
        assertPassed(legacy);
    }

    @Test
    void disabledControlCenterKeepsLegacySemantics() throws Exception {
        properties.setEnabled(false);
        MockHttpServletResponse perCall = new MockHttpServletResponse();
        // 关闭时允许 scheme 差异——与引入严格模式之前一致。
        filter.doFilter(request("POST", "https://localhost:8081", null), perCall, chain);
        verify(chain).doFilter(any(), any());
        assertEquals(200, perCall.getStatus());
    }

    @Test
    void pathDetectionStripsContextPath() {
        MockHttpServletRequest inside = request("POST", "http://localhost:8081", null);
        MockHttpServletRequest outside = new MockHttpServletRequest("POST", "/api/news");
        outside.setContextPath("/api");
        assertTrue(SameOriginFilter.isControlCenterPath(inside));
        assertFalse(SameOriginFilter.isControlCenterPath(outside));
    }

    @Test
    void pathDetectionHandlesPrefixBoundary() {
        MockHttpServletRequest prefixOnly = new MockHttpServletRequest("POST", "/admin/control-center");
        prefixOnly.setContextPath("");
        MockHttpServletRequest lookalike = new MockHttpServletRequest("POST", "/admin/control-center-fake");
        lookalike.setContextPath("");
        assertTrue(SameOriginFilter.isControlCenterPath(prefixOnly));
        assertFalse(SameOriginFilter.isControlCenterPath(lookalike));
    }
}
