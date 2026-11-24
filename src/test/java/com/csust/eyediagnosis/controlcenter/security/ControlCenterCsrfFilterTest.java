package com.csust.eyediagnosis.controlcenter.security;

import com.csust.eyediagnosis.security.CsrfTokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 控制中心 CSRF 过滤器（WP-01A）：正例、负例、路径与方法边界。 */
class ControlCenterCsrfFilterTest {

    private final java.util.List<String> auditEvents = new java.util.ArrayList<>();
    private CsrfTokenService tokenService;
    private ControlCenterCsrfFilter filter;
    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        tokenService = new CsrfTokenService();
        auditEvents.clear();
        filter = new ControlCenterCsrfFilter(tokenService,
                (request, action, reason) -> auditEvents.add(action + ":" + reason));
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest request(String method, String path, MockHttpSession session, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContextPath("/api");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8081);
        if (session != null) request.setSession(session);
        if (token != null) request.addHeader(ControlCenterCsrfFilter.CSRF_HEADER, token);
        return request;
    }

    private void assertRejected(MockHttpServletRequest request, String reason) throws Exception {
        filter.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(reason), "期望原因码 " + reason + "，实际响应: " + body);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void writeWithValidTokenPasses() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = tokenService.issue(session);
        filter.doFilter(request("POST", "/api/admin/control-center/config", session, token), response, chain);
        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    void putAndDeleteAndPatchAreAlsoChecked() throws Exception {
        for (String method : new String[]{"PUT", "DELETE", "PATCH"}) {
            MockHttpServletResponse perCall = new MockHttpServletResponse();
            MockHttpSession session = new MockHttpSession();
            tokenService.issue(session);
            filter.doFilter(request(method, "/api/admin/control-center/config/1", session, "wrong"), perCall, chain);
            assertEquals(403, perCall.getStatus(), method + " 必须校验 CSRF");
        }
    }

    @Test
    void missingSessionIsRejectedAsMissing() throws Exception {
        assertRejected(request("POST", "/api/admin/control-center/config", null, "anything"),
                ControlCenterCsrfFilter.REASON_CSRF_MISSING);
        assertTrue(auditEvents.contains("CSRF_REJECTED:" + ControlCenterCsrfFilter.REASON_CSRF_MISSING),
                "CSRF 拒绝必须留痕，实际: " + auditEvents);
    }

    @Test
    void sessionWithoutIssuedTokenIsRejectedAsMissing() throws Exception {
        assertRejected(request("POST", "/api/admin/control-center/config", new MockHttpSession(), "anything"),
                ControlCenterCsrfFilter.REASON_CSRF_MISSING);
    }

    @Test
    void missingHeaderIsRejectedAsInvalid() throws Exception {
        MockHttpSession session = new MockHttpSession();
        tokenService.issue(session);
        assertRejected(request("POST", "/api/admin/control-center/config", session, null),
                ControlCenterCsrfFilter.REASON_CSRF_MISMATCH);
    }

    @Test
    void wrongTokenIsRejectedAsInvalid() throws Exception {
        MockHttpSession session = new MockHttpSession();
        tokenService.issue(session);
        assertRejected(request("POST", "/api/admin/control-center/config", session, "not-the-token"),
                ControlCenterCsrfFilter.REASON_CSRF_MISMATCH);
    }

    @Test
    void tokenFromAnotherSessionIsRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        tokenService.issue(session);
        String foreign = tokenService.issue(new MockHttpSession());
        assertRejected(request("POST", "/api/admin/control-center/config", session, foreign),
                ControlCenterCsrfFilter.REASON_CSRF_MISMATCH);
    }

    @Test
    void safeMethodsBypassCsrf() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            MockHttpServletResponse perCall = new MockHttpServletResponse();
            filter.doFilter(request(method, "/api/admin/control-center/csrf", null, null), perCall, chain);
            assertEquals(200, perCall.getStatus());
        }
        verify(chain, org.mockito.Mockito.times(3)).doFilter(any(), any());
    }

    @Test
    void nonControlCenterPathsAreUntouched() throws Exception {
        // 全站既有写接口不受影响：既没有 token 也不应该有会话要求。
        filter.doFilter(request("POST", "/api/news", null, null), response, chain);
        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    void lookalikePathIsNotProtected() throws Exception {
        filter.doFilter(request("POST", "/api/admin/control-center-fake", null, null), response, chain);
        verify(chain).doFilter(any(), any());
    }
}
