package com.csust.eyediagnosis.newsingestion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Dedicated machine-authentication filter for {@code /internal/news/v1/leases/**}.
 *
 * <p>Enforces strict channel isolation: worker keys authenticate only internal lease routes,
 * while user Cookie sessions and miniapp Bearer tokens are forbidden on those routes.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class NewsWorkerAuthFilter extends OncePerRequestFilter {

    public static final String WORKER_KEY_HEADER = "X-Iris-News-Worker-Key";
    static final String ATTR_WORKER_IDENTITY = NewsWorkerAuthFilter.class.getName() + ".WORKER_IDENTITY";

    private final NewsIngestionProperties properties;

    public static boolean isAuthenticatedWorkerRequest(HttpServletRequest request) {
        return request.getAttribute(ATTR_WORKER_IDENTITY) instanceof NewsWorkerIdentity
                && NewsWorkerPaths.isInternalLeasePath(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!NewsWorkerPaths.isInternalLeasePath(request)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<NewsWorkerIdentity> identity = properties.authenticate(request.getHeader(WORKER_KEY_HEADER));
        if (identity.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED");
            return;
        }

        // Strict channel boundary: reject browser Cookie sessions and Bearer tokens even if a worker key is present.
        if (hasHeader(request, "Cookie") || hasHeader(request, "Authorization")) {
            log.warn("Rejected internal news worker request carrying user credentials, workerId={}, path={}",
                    identity.get().workerId(), request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN");
            return;
        }

        if (properties.isRequireHttps() && !request.isSecure()) {
            log.warn("Rejected non-HTTPS internal news worker request, workerId={}, path={}",
                    identity.get().workerId(), request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN");
            return;
        }

        List<String> allowedAddresses = properties.getAllowedRemoteAddresses();
        if (allowedAddresses != null && !allowedAddresses.isEmpty()) {
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr == null || !allowedAddresses.contains(remoteAddr)) {
                log.warn("Rejected internal news worker request from unauthorized remote address, workerId={}, remoteAddr={}",
                        identity.get().workerId(), remoteAddr);
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN");
                return;
            }
        }

        if (!properties.isEnabled()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "UNAVAILABLE");
            return;
        }

        request.setAttribute(ATTR_WORKER_IDENTITY, identity.get());
        chain.doFilter(request, response);
    }

    private static boolean hasHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value != null && !value.isBlank();
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
