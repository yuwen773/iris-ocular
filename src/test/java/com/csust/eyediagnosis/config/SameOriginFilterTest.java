package com.csust.eyediagnosis.config;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 评审 P1-06：状态变更请求必须通过 Origin/Referer 同源校验。 */
class SameOriginFilterTest {

    private SameOriginFilter filter;
    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        // 控制中心默认关闭：本类只覆盖既有全局行为，严格模式见
        // SameOriginFilterControlCenterTest。
        filter = new SameOriginFilter(new ControlCenterProperties(), (request, action, reason) -> { });
        ReflectionTestUtils.setField(filter, "additionalHosts", List.of("review.example.com"));
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest request(String method, String origin, String referer) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/agent/collaboration-runs");
        request.setServerName("localhost");
        request.setServerPort(8081);
        if (origin != null) request.addHeader("Origin", origin);
        if (referer != null) request.addHeader("Referer", referer);
        return request;
    }

    @Test
    void crossSitePostIsRejectedWithJsonFault() throws Exception {
        filter.doFilter(request("POST", "http://evil.example.com", null), response, chain);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("CROSS_ORIGIN_BLOCKED"));
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingOriginAndRefererOnPostIsRejected() throws Exception {
        filter.doFilter(request("POST", null, null), response, chain);
        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void crossSiteRefererIsRejected() throws Exception {
        filter.doFilter(request("POST", null, "https://evil.example.com/form"), response, chain);
        assertEquals(403, response.getStatus());
    }

    @Test
    void whitelistedHostIsAccepted() throws Exception {
        filter.doFilter(request("POST", "https://review.example.com", null), response, chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void defaultPortsAreNormalized() throws Exception {
        // https 默认 443 端口不参与比对
        filter.doFilter(request("POST", "https://localhost:443", null), response, chain);
        assertEquals(403, response.getStatus()); // host localhost:8081 ≠ localhost
    }

    @Test
    void sameHostDifferentPortIsRejected() throws Exception {
        filter.doFilter(request("POST", "http://localhost:9999", null), response, chain);
        assertEquals(403, response.getStatus());
    }

    @Test
    void safeMethodsBypassCheck() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            MockHttpServletResponse perCall = new MockHttpServletResponse();
            filter.doFilter(request(method, "http://evil.example.com", null), perCall, chain);
            assertEquals(200, perCall.getStatus());
        }
        verify(chain, org.mockito.Mockito.times(3))
                .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nullOriginLiteralIsRejected() throws Exception {
        // 浏览器把「无来源」标为 Origin: null，必须拒绝
        filter.doFilter(request("POST", "null", null), response, chain);
        assertEquals(403, response.getStatus());
    }

    @Test
    void viteDevProxyRequestIsAcceptedWithoutDisablingTheFilter() throws Exception {
        // TASK-08B：前端 dev 用 Vite 代理（5173→8081，changeOrigin 改写 Host），
        // vite.config.ts 显式把 Origin 改写为 target（http://localhost:8081），
        // 到达后端时是同源请求——过滤器保持开启即可放行，不存在「为了跨域关过滤器」。
        MockHttpServletRequest proxied = request("POST", "http://localhost:8081", null);
        proxied.setServerName("localhost");
        proxied.setServerPort(8081);
        filter.doFilter(proxied, response, chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void proxiedPortMismatchIsStillRejected() throws Exception {
        // 若 dev server 换端口而代理头没有跟着改（Origin: 8081 vs Host 5199），
        // 过滤器必须拒绝——端口混淆不能因为代理而放行。
        MockHttpServletRequest stale = request("POST", "http://localhost:8081", null);
        stale.setServerName("localhost");
        stale.setServerPort(5199);
        filter.doFilter(stale, response, chain);
        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wechatLoginPathIsExemptFromOriginCheck() throws Exception {
        // 小程序登录本身就是「换令牌」的入口，此刻还没有令牌可携带。
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/mp/auth/login");
        login.setContextPath("/api");
        login.setServerName("localhost");
        login.setServerPort(8081);
        filter.doFilter(login, response, chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wechatLoginPathWithMatrixParametersIsStillExempt() throws Exception {
        // 路径判定必须与 MVC 路由同源：MVC 会把 `;name=value` 从路径里剥掉，
        // `/api/mp/auth/login;x=1` 仍路由到登录入口。过滤器若按原始 URI 判定，
        // 就会把这条请求当成普通接口要求 Origin，小程序拿不到令牌。
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/mp/auth/login;x=1");
        login.setContextPath("/api");
        login.setServerName("localhost");
        login.setServerPort(8081);
        filter.doFilter(login, response, chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tokenAuthenticatedRequestSkipsOriginCheck() throws Exception {
        // 令牌由调用方显式携带，不依赖浏览器自动附带的 Cookie，CSRF 前提不成立。
        MockHttpServletRequest tokenRequest = request("POST", null, null);
        tokenRequest.setAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH, Boolean.TRUE);
        filter.doFilter(tokenRequest, response, chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wechatRefererWithoutTokenIsStillRejected() throws Exception {
        // 小程序未带令牌时 Referer 固定为 servicewechat.com；不能因为它长得「像微信」就放行。
        filter.doFilter(request("POST", null, "https://servicewechat.com/wx123/0/page-frame.html"),
                response, chain);
        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
