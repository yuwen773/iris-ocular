package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import com.csust.eyediagnosis.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 小程序令牌通道。
 *
 * <p>必须早于 {@code SameOriginFilter}（@Order(1)）：小程序请求的 Referer 被平台固定为
 * {@code https://servicewechat.com/...}，Origin 不发送，同源校验不可能通过。令牌由调用方
 * 显式携带，不依赖浏览器自动附带的凭证，因此对通过令牌认证的请求跳过同源校验是成立的；
 * Cookie 通道的校验保持原样。
 *
 * <p>没有 Bearer 头的请求原样放行，交给后面的 Cookie 会话路径处理——web/ 端行为不变。
 */
@Slf4j
@Component
@Order(0)
public class MiniAppTokenFilter extends OncePerRequestFilter {

    private final MiniAppTokenService tokenService;

    public MiniAppTokenFilter(MiniAppTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawToken = MiniAppTokenService.parseBearer(
                request.getHeader(MiniAppConstant.AUTHORIZATION_HEADER));
        if (rawToken == null) {
            chain.doFilter(request, response);
            return;
        }
        Long userId = tokenService.resolve(rawToken);
        if (userId == null) {
            log.debug("小程序令牌无效或已过期, path={}", request.getRequestURI());
            reject(response);
            return;
        }
        request.setAttribute(MiniAppConstant.REQUEST_ATTR_USER_ID, userId);
        request.setAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        // 与 GlobalExceptionHandler 的错误信封保持一致：HTTP 200 + 业务码。
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + ErrorCode.NOT_LOGIN_ERROR.getCode()
                + ",\"data\":null,\"msg\":\"登录状态已失效，请重新进入小程序\"}");
    }
}
