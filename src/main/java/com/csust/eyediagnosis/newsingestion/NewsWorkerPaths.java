package com.csust.eyediagnosis.newsingestion;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared path matching for the internal News Engine worker endpoints.
 *
 * <p>Both {@link NewsWorkerAuthFilter} and {@link com.csust.eyediagnosis.config.SameOriginFilter}
 * use the same context-path-stripped view and forbid path traversal segments so machine-auth
 * bypasses can never reach unrelated routes.
 */
public final class NewsWorkerPaths {

    public static final String INTERNAL_LEASE_PREFIX = "/internal/news/v1/leases";

    private NewsWorkerPaths() {
    }

    public static String withinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    public static boolean isInternalLeasePath(HttpServletRequest request) {
        String path = withinApp(request);
        if (path.contains("..") || path.contains(";") || path.contains("\\") || path.contains("//")) {
            return false;
        }
        return path.equals(INTERNAL_LEASE_PREFIX) || path.startsWith(INTERNAL_LEASE_PREFIX + "/");
    }
}
