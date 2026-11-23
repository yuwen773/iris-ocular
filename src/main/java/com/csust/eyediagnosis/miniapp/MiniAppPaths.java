package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 小程序相关请求的路径判定。
 *
 * <p>两个过滤器都按「去掉 context-path 的相对路径」比对豁免名单，口径必须一致：
 * 各写一份时，部署到带 context-path 的环境就会出现「登录入口在 A 过滤器被豁免、
 * 在 B 过滤器被拒」。
 */
public final class MiniAppPaths {

    private MiniAppPaths() {
    }

    /** 去掉 context-path 后的请求路径。 */
    public static String withinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 是否是换令牌的登录入口。 */
    public static boolean isLoginEntry(HttpServletRequest request) {
        return MiniAppConstant.LOGIN_PATH.equals(withinApp(request));
    }
}
