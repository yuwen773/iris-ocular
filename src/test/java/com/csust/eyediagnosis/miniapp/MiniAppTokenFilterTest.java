package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiniAppTokenFilterTest {

    private MiniAppTokenService tokenService;
    private MiniAppTokenFilter filter;
    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        tokenService = mock(MiniAppTokenService.class);
        filter = new MiniAppTokenFilter(tokenService);
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/diagnosis/diagnosis");
        if (authorization != null) {
            request.addHeader(MiniAppConstant.AUTHORIZATION_HEADER, authorization);
        }
        return request;
    }

    @Test
    void validTokenMarksRequestAndContinues() throws Exception {
        when(tokenService.resolve("good")).thenReturn(9L);
        MockHttpServletRequest request = request("Bearer good");

        filter.doFilter(request, response, chain);

        assertEquals(9L, request.getAttribute(MiniAppConstant.REQUEST_ATTR_USER_ID));
        assertEquals(Boolean.TRUE, request.getAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH));
        verify(chain).doFilter(any(), any());
    }

    @Test
    void missingTokenFallsThroughToCookiePath() throws Exception {
        MockHttpServletRequest request = request(null);

        filter.doFilter(request, response, chain);

        assertNull(request.getAttribute(MiniAppConstant.REQUEST_ATTR_USER_ID));
        assertNull(request.getAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH));
        verify(chain).doFilter(any(), any());
        verify(tokenService, never()).resolve(any());
    }

    @Test
    void invalidTokenIsRejectedWithBusinessEnvelope() throws Exception {
        when(tokenService.resolve("stale")).thenReturn(null);

        filter.doFilter(request("Bearer stale"), response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":40100"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void invalidTokenDoesNotBlockSafeMethods() throws Exception {
        when(tokenService.resolve("stale")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/news/list");
        request.addHeader(MiniAppConstant.AUTHORIZATION_HEADER, "Bearer stale");

        filter.doFilter(request, response, chain);

        // 公开的只读接口不该被一枚过期令牌一起打挂；受保护的只读接口由鉴权层给 40100。
        assertNull(request.getAttribute(MiniAppConstant.REQUEST_ATTR_USER_ID));
        assertNull(request.getAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH));
        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    void tokenStoreFailureFailsClosedWithSystemErrorEnvelope() throws Exception {
        when(tokenService.resolve("any")).thenThrow(new RedisConnectionFailureException("redis down"));

        filter.doFilter(request("Bearer any"), response, chain);

        // 令牌库不可用时不能放行匿名请求：回系统错误信封，不进业务链路。
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":50000"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nonBearerHeaderIsIgnored() throws Exception {
        filter.doFilter(request("Basic Zm9v"), response, chain);

        verify(chain).doFilter(any(), any());
        verify(tokenService, never()).resolve(any());
    }

    @Test
    void staleTokenDoesNotBlockTheLoginEntry() throws Exception {
        when(tokenService.resolve("stale")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api" + MiniAppConstant.LOGIN_PATH);
        request.setContextPath("/api");
        request.addHeader(MiniAppConstant.AUTHORIZATION_HEADER, "Bearer stale");

        filter.doFilter(request, response, chain);

        // 过期令牌不能把换令牌的入口堵死：按无令牌处理，交给后面的 Cookie 分支与登录豁免。
        assertNull(request.getAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH));
        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }
}
