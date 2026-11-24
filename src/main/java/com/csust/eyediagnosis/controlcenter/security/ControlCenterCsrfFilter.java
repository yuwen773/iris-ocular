package com.csust.eyediagnosis.controlcenter.security;

import com.csust.eyediagnosis.config.SameOriginFilter;
import com.csust.eyediagnosis.security.CsrfTokenService;
import com.csust.eyediagnosis.security.SecurityEventSink;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 控制中心的 Session 绑定 CSRF 校验。
 *
 * <p>与 {@link SameOriginFilter} 分工明确：Origin 校验拦"跨站发起的请求"，
 * 本过滤器拦"同站但由第三方页面借用户凭据发起的请求"（例如反代下 Origin
 * 恰好合法的场景、或浏览器对 GET 的宽松处理）。两者都不是第二套全局过滤器——
 * 本过滤器只在 {@code /admin/control-center/**} 的非安全方法上生效，且仅在
 * {@code eye-diagnosis.control-center.enabled=true} 时注册。
 *
 * <p>规则：请求头 {@value #CSRF_HEADER} 必须与会话内 token 常量时间相等。
 * 会话不存在、token 未签发或值不匹配一律 403，不回显期望值。
 */
@Slf4j
@Component
@Order(2)
@ConditionalOnProperty(prefix = "eye-diagnosis.control-center", name = "enabled", havingValue = "true")
public class ControlCenterCsrfFilter extends OncePerRequestFilter {

    /** 客户端提交 token 的请求头。 */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    /** 失败原因码：与 ADR D11 / MVP API 契约的原因码表逐字一致。 */
    public static final String REASON_CSRF_MISSING = "CSRF_MISSING";
    public static final String REASON_CSRF_MISMATCH = "CSRF_MISMATCH";

    private final CsrfTokenService csrfTokenService;

    private final SecurityEventSink securityEventSink;

    public ControlCenterCsrfFilter(CsrfTokenService csrfTokenService, SecurityEventSink securityEventSink) {
        this.csrfTokenService = csrfTokenService;
        this.securityEventSink = securityEventSink;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SameOriginFilter.isSafeMethod(request.getMethod())
                || !SameOriginFilter.isControlCenterPath(request)) {
            chain.doFilter(request, response);
            return;
        }

        // 不创建新会话：没有会话就不可能有合法 token。
        HttpSession session = request.getSession(false);
        if (!csrfTokenService.hasToken(session)) {
            reject(request, response, REASON_CSRF_MISSING);
            return;
        }
        if (!csrfTokenService.matches(session, request.getHeader(CSRF_HEADER))) {
            reject(request, response, REASON_CSRF_MISMATCH);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        log.warn("拒绝控制中心 CSRF 校验失败请求, reason={}, method={}, path={}",
                reason, request.getMethod(), request.getRequestURI());
        securityEventSink.denied(request, "CSRF_REJECTED", reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40300,\"data\":{\"reasonCode\":\""
                + reason + "\"},\"msg\":\"CSRF_TOKEN_INVALID\"}");
    }
}
