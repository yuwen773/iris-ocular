package com.csust.eyediagnosis.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import com.csust.eyediagnosis.security.SecurityEventSink;
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
import java.util.Locale;

/**
 * 评审 P1-06：Cookie 会话应用的状态变更请求必须校验 Origin。
 * CORS 不是 CSRF 防御——浏览器跨站 POST 不受 CORS 约束，只受 SameSite
 * cookie 约束；这里再加一层显式 Origin/Referer 校验作为纵深防御。
 * <p>
 * 规则：非安全方法（POST/PUT/DELETE/PATCH）必须携带 Origin 或 Referer，
 * 且其 host 必须与请求的 Host 一致，或在显式白名单内。同为 GET/SSE 的
 * 请求不校验（只读）。同源比对基于完整 host（含端口），防端口混淆。
 *
 * <p><b>控制中心严格模式（2026-11-23）：</b>仅在
 * {@code eye-diagnosis.control-center.enabled=true} 时生效；
 * {@code /admin/control-center/**}
 * 的写请求不走上面的宽松比较，而要求 scheme + host + port 完整一致：
 * <ul>
 *   <li>{@code Origin: null} 一律拒绝，不允许回退 Referer；</li>
 *   <li>Origin / Referer 都缺失时拒绝；</li>
 *   <li>可信外部 Origin 必须命中 {@code eye-diagnosis.control-center.allowed-origins}
 *       的完整 origin 精确匹配，不支持通配子域；</li>
 *   <li>{@code X-Forwarded-Proto} 仅在显式开启 {@code trust-proxy-headers} 时读取。</li>
 * </ul>
 * 其余路径保持原行为，避免收紧全站既有上传 / 审核接口。CSRF Token 由
 * {@link ControlCenterCsrfFilter} 在同一路径上追加校验。
 */
@Slf4j
@Component
@Order(1)
public class SameOriginFilter extends OncePerRequestFilter {

    /** 控制中心路径前缀（不含 context-path）。 */
    public static final String CONTROL_CENTER_PREFIX = "/admin/control-center";

    /** 严格模式的失败原因码；写入响应 data 并用于日志，不含请求体。 */
    public static final String REASON_ORIGIN_MISSING = "ORIGIN_MISSING";
    public static final String REASON_ORIGIN_NULL = "ORIGIN_NULL";
    public static final String REASON_ORIGIN_MISMATCH = "ORIGIN_MISMATCH";

    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    @Value("${security.same-origin.additional-hosts:}")
    private List<String> additionalHosts;

    private final ControlCenterProperties controlCenter;

    private final SecurityEventSink securityEventSink;

    public SameOriginFilter(ControlCenterProperties controlCenter, SecurityEventSink securityEventSink) {
        this.controlCenter = controlCenter;
        this.securityEventSink = securityEventSink;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isSafeMethod(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (controlCenter.isEnabled() && isControlCenterPath(request)) {
            if (!allowsStrictOrigin(request, response)) {
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!allowsLegacyOrigin(request, response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    /** 安全方法无需 Origin 校验（只读；控制中心的 CSRF 端点也走 GET）。 */
    public static boolean isSafeMethod(String method) {
        return SAFE_METHODS.contains(method);
    }

    /**
     * 请求是否落在控制中心路径下。
     *
     * <p>比较前先剥掉 {@code server.servlet.context-path}（本仓库为 {@code /api}），
     * 避免把路径判断绑死在部署前缀上。
     */
    public static boolean isControlCenterPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.equals(CONTROL_CENTER_PREFIX) || path.startsWith(CONTROL_CENTER_PREFIX + "/");
    }

    private boolean allowsLegacyOrigin(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String host = request.getServerName() + portSuffix(request);
        String source = originHost(request.getHeader("Origin"));
        if (source == null) source = refererHost(request.getHeader("Referer"));
        if (source == null) {
            log.warn("拒绝无 Origin/Referer 的状态变更请求, method={}, path={}",
                    request.getMethod(), request.getRequestURI());
            rejectLegacy(response);
            return false;
        }
        if (!source.equals(host) && !safeAdditionalHosts().contains(source)) {
            log.warn("拒绝跨站状态变更请求, method={}, path={}, sourceHost={}",
                    request.getMethod(), request.getRequestURI(), source);
            rejectLegacy(response);
            return false;
        }
        return true;
    }

    private boolean allowsStrictOrigin(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");

        String source;
        if (origin != null) {
            if (origin.isBlank()) {
                return rejectStrict(request, response, REASON_ORIGIN_MISSING);
            }
            if ("null".equalsIgnoreCase(origin.trim())) {
                // 沙箱 iframe / file:// / 部分重定向：即使 Referer 合法也不接受。
                return rejectStrict(request, response, REASON_ORIGIN_NULL);
            }
            source = normalizedOrigin(origin, true);
            if (source == null) {
                return rejectStrict(request, response, REASON_ORIGIN_MISMATCH);
            }
        } else if (referer != null && !referer.isBlank()) {
            source = normalizedOrigin(referer, false);
            if (source == null) {
                return rejectStrict(request, response, REASON_ORIGIN_MISMATCH);
            }
        } else {
            return rejectStrict(request, response, REASON_ORIGIN_MISSING);
        }

        if (source.equals(requestOrigin(request)) || safeAllowedOrigins().contains(source)) {
            return true;
        }
        return rejectStrict(request, response, REASON_ORIGIN_MISMATCH);
    }

    private boolean rejectStrict(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        log.warn("拒绝控制中心跨站请求, reason={}, method={}, path={}",
                reason, request.getMethod(), request.getRequestURI());
        securityEventSink.denied(request, "ORIGIN_REJECTED", reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40300,\"data\":{\"reasonCode\":\""
                + reason + "\"},\"msg\":\"CROSS_ORIGIN_BLOCKED\"}");
        return false;
    }

    private void rejectLegacy(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40300,\"data\":null,\"msg\":\"CROSS_ORIGIN_BLOCKED\"}");
    }

    /** 请求自身的 origin：scheme + host + port。 */
    private String requestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        if (controlCenter.isTrustProxyHeaders()) {
            String forwardedProto = request.getHeader("X-Forwarded-Proto");
            if (forwardedProto != null && !forwardedProto.isBlank()) {
                scheme = forwardedProto.split(",")[0].trim();
            }
        }
        return buildOrigin(scheme, request.getServerName(), request.getServerPort());
    }

    /**
     * 解析并规范化一个 origin。
     *
     * @param raw               原始头值
     * @param requireBareOrigin 为 true 时，Origin 头不允许带 path/query/fragment
     */
    private String normalizedOrigin(String raw, boolean requireBareOrigin) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            if (uri.getRawUserInfo() != null) {
                return null;
            }
            if (requireBareOrigin) {
                String path = uri.getRawPath();
                if (path != null && !path.isEmpty() && !"/".equals(path)) {
                    return null;
                }
                if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    return null;
                }
            }
            return buildOrigin(scheme, uri.getHost(), uri.getPort());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String buildOrigin(String scheme, String host, int port) {
        if (scheme == null || host == null || host.isBlank()) {
            return null;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            return null;
        }
        int defaultPort = "https".equals(normalizedScheme) ? 443 : 80;
        String portPart = (port <= 0 || port == defaultPort) ? "" : ":" + port;
        return normalizedScheme + "://" + host.toLowerCase(Locale.ROOT) + portPart;
    }

    private List<String> safeAdditionalHosts() {
        return additionalHosts == null
                ? List.of()
                : additionalHosts.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList();
    }

    private List<String> safeAllowedOrigins() {
        return controlCenter.getAllowedOrigins() == null
                ? List.of()
                : controlCenter.getAllowedOrigins().stream()
                        .map(value -> normalizedOrigin(value, false))
                        .filter(value -> value != null)
                        .toList();
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
        return port > 0 ? ":" + port : "";
    }
}
