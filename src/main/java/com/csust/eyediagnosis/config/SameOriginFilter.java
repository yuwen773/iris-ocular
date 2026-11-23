package com.csust.eyediagnosis.config;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 评审 P1-06：Cookie 会话应用的状态变更请求必须校验 Origin。
 * CORS 不是 CSRF 防御——浏览器跨站 POST 不受 CORS 约束，只受 SameSite
 * cookie 约束；这里再加一层显式 Origin/Referer 校验作为纵深防御。
 * <p>
 * 规则：非安全方法（POST/PUT/DELETE/PATCH）必须携带 Origin 或 Referer，
 * 且其 host 必须与请求的 Host 一致，或在显式白名单内。同为 GET/SSE 的
 * 请求不校验（只读）。同源比对基于完整 host（含端口），防端口混淆。
 */
@Slf4j
@Component
@Order(1)
public class SameOriginFilter extends OncePerRequestFilter {

    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    /**
     * 无需 Origin/Referer 的路径（相对 context-path）。
     *
     * <p>目前只有小程序登录：它本身就是「换令牌」的入口，此刻还没有令牌可携带；它也不写
     * Cookie、只把令牌放在响应体里，跨站请求读不到响应，构不成登录 CSRF 的收益。
     */
    private static final Set<String> EXEMPT_PATHS = Set.of("/mp/auth/login");

    @Value("${security.same-origin.additional-hosts:}")
    private List<String> additionalHosts;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        // 小程序令牌通道：凭证由调用方显式携带，不走浏览器自动附带的 Cookie，CSRF 不成立。
        if (Boolean.TRUE.equals(request.getAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH))
                || EXEMPT_PATHS.contains(pathWithinApp(request))) {
            chain.doFilter(request, response);
            return;
        }
        String host = request.getServerName() + portSuffix(request);
        String source = originHost(request.getHeader("Origin"));
        if (source == null) source = refererHost(request.getHeader("Referer"));
        if (source == null) {
            log.warn("拒绝无 Origin/Referer 的状态变更请求, method={}, path={}", request.getMethod(), request.getRequestURI());
            reject(response);
            return;
        }
        if (!source.equals(host) && !additionalHosts.contains(source)) {
            log.warn("拒绝跨站状态变更请求, method={}, path={}, sourceHost={}", request.getMethod(), request.getRequestURI(), source);
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 去掉 context-path 后的请求路径，豁免名单按这个口径比对。 */
    private String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40300,\"data\":null,\"msg\":\"CROSS_ORIGIN_BLOCKED\"}");
    }

    private String originHost(String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin)) return null;
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null) return null;
            return host + portSuffix(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String refererHost(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            URI uri = URI.create(referer);
            String host = uri.getHost();
            if (host == null) return null;
            return host + portSuffix(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 显式端口参与比对；默认端口省略（http=80/https=443）。 */
    private String portSuffix(URI uri) {
        int port = uri.getPort();
        if (port == 80 || port == 443) return "";
        return port > 0 ? ":" + port : "";
    }

    private String portSuffix(HttpServletRequest request) {
        int port = request.getServerPort();
        if (port == 80 || port == 443) return "";
        return ":" + port;
    }
}
