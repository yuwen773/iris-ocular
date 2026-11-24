package com.csust.eyediagnosis.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import com.csust.eyediagnosis.constant.MiniAppConstant;
import com.csust.eyediagnosis.miniapp.MiniAppPaths;
import com.csust.eyediagnosis.security.SecurityEventSink;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /**
     * 无需 Origin/Referer 的路径（相对 context-path）。
     *
     * <p>目前只有小程序登录：它本身就是「换令牌」的入口，此刻还没有令牌可携带；它也不写
     * Cookie、只把令牌放在响应体里，跨站请求读不到响应，构不成登录 CSRF 的收益。
     *
     * <p>路径判定与 MVC 路由同源（{@link MiniAppPaths#withinApp} 走 UrlPathHelper），
     * 因此 {@code /mp/auth/login;x=1} 这类带矩阵参数的请求在过滤器与控制器里判定一致。
     */
    private static final Set<String> EXEMPT_PATHS = Set.of(MiniAppConstant.LOGIN_PATH);

    /**
     * 与 MVC 计算 lookup path 同源的路径解析器：默认解码 URI 并剥掉 {@code ;} 矩阵参数。
     * 无状态，可并发复用。
     */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

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
        // 控制中心写请求走严格同源，且**不参与**下面的令牌通道豁免：
        // 它是管理面，不能被「调用方显式携带凭证」的宽松口径放过。
        if (controlCenter.isEnabled() && isControlCenterPath(request)) {
            if (!allowsStrictOrigin(request, response)) {
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        // 小程序令牌通道：凭证由调用方显式携带，不走浏览器自动附带的 Cookie，CSRF 不成立。
        if (Boolean.TRUE.equals(request.getAttribute(MiniAppConstant.REQUEST_ATTR_TOKEN_AUTH))
                || EXEMPT_PATHS.contains(MiniAppPaths.withinApp(request))) {
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
     * <p>判定必须与 <b>MVC 实际路由所用的路径</b>一致，不能拿
     * {@code getRequestURI()} 的原始串直接比：Tomcat / Spring 在路由前会把
     * {@code ;name=value} 形式的矩阵参数从每一段里剥掉。若过滤器按原始串判定，
     * {@code POST /api/admin/control-center;test=1/drafts} 会被 MVC 路由到写方法，
     * 过滤器却认为「不是控制中心路径」而整体跳过——严格 Origin 与 CSRF 两道控制同时失效，
     * 同源但无 token 的写请求会被直接执行（2026-11-24 在真实 Tomcat 上复现并修复）。
     *
     * <p>这里用 {@link UrlPathHelper}（{@code removeSemicolonContent} 与 {@code urlDecode}
     * 均为默认 true，也是 MVC 计算 lookup path 用的同一套逻辑）取「应用内路径」，
     * 它已经完成解码、去分号与 context-path 剥离。
     *
     * <p>取不出路径时<b>按控制中心处理</b>（fail-closed）：判不出来就不该跳过校验。
     */
    public static boolean isControlCenterPath(HttpServletRequest request) {
        String path;
        try {
            path = PATH_HELPER.getPathWithinApplication(request);
        } catch (RuntimeException ex) {
            log.warn("路径解析失败，按控制中心路径处理（fail-closed）: uri={}",
                    safeUri(request), ex.toString());
            return true;
        }
        if (path == null) {
            return true;
        }
        return path.equals(CONTROL_CENTER_PREFIX) || path.startsWith(CONTROL_CENTER_PREFIX + "/");
    }

    private static String safeUri(HttpServletRequest request) {
        try {
            return request.getRequestURI();
        } catch (RuntimeException ignored) {
            return "<unavailable>";
        }
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
