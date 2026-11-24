package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import com.csust.eyediagnosis.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
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
 *
 * <p>带着<b>无效</b>令牌的请求按方法分两种处理：
 * <ul>
 *   <li>写操作（POST/PUT/PATCH/DELETE）直接回 40100 业务信封。小程序的写操作没有
 *       浏览器自动附带的凭证，同源校验也过不去，必须由这里给出「令牌失效」，
 *       客户端才能静默重登一次再重试。</li>
 *   <li>安全方法（GET/HEAD/OPTIONS）放行，不打令牌标记。公开的只读接口不该被一枚
 *       过期令牌一起打挂；受保护的只读接口由鉴权层照常给 40100，错误码与这里一致。</li>
 * </ul>
 *
 * <p>Redis 是令牌的唯一真源。它不可用时**失败关闭**：回系统错误信封，绝不把
 * 「查不到令牌」当成「没带令牌」放行——那等于把鉴权降级成匿名访问。
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
        Long userId;
        try {
            userId = tokenService.resolve(rawToken);
        } catch (DataAccessException redisDown) {
            // 失败关闭：查不到令牌 ≠ 没带令牌。Redis 挂了就报错，不能放行匿名请求。
            log.error("小程序令牌校验失败（令牌库不可用）, path={}, errorType={}",
                    request.getRequestURI(), redisDown.getClass().getSimpleName());
            MiniAppErrorResponse.write(response, ErrorCode.SYSTEM_ERROR, "服务暂时不可用，请稍后重试");
            return;
        }
        if (userId == null) {
            // 换令牌的入口不能被一枚过期令牌堵死：客户端带着旧令牌来登录，照样放行。
            if (MiniAppPaths.isLoginEntry(request)) {
                log.debug("登录入口收到无效令牌，按无令牌处理, path={}", request.getRequestURI());
                chain.doFilter(request, response);
                return;
            }
            if (isSafeMethod(request.getMethod())) {
                // 只读请求不受一枚过期令牌牵连：公开接口照常返回，受保护接口由鉴权层给 40100。
                log.debug("安全方法收到无效令牌，按未认证放行, path={}", request.getRequestURI());
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

    /** 与 {@code SameOriginFilter} 的安全方法口径一致：这三个方法不改变服务端状态。 */
    private static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
