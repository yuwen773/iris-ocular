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
            // 换令牌的入口不能被一枚过期令牌堵死：客户端带着旧令牌来登录，照样放行。
            if (MiniAppPaths.isLoginEntry(request)) {
                log.debug("登录入口收到无效令牌，按无令牌处理, path={}", request.getRequestURI());
                chain.doFilter(request, response);
                return;
            }
            log.debug("小程序令牌无效或已过期, path={}", request.getRequestURI());
            MiniAppErrorResponse.write(response, ErrorCode.NOT_LOGIN_ERROR, "登录状态已失效，请重新进入小程序");
            return;
        }
        request.setAttribute(MiniAppConstant.REQUEST_ATTR_USER_ID, userId);
        request.setAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH, Boolean.TRUE);
        chain.doFilter(request, response);
    }
}
