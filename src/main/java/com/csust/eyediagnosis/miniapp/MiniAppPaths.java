package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * 小程序相关请求的路径判定。
 *
 * <p>两个过滤器都按「应用内路径」比对豁免名单，口径必须一致：
 * 各写一份时，部署到带 context-path 的环境就会出现「登录入口在 A 过滤器被豁免、
 * 在 B 过滤器被拒」。
 *
 * <p>这里用 {@link UrlPathHelper#getPathWithinApplication}——它与 MVC 计算 lookup path
 * 是同一套逻辑（默认解码 URI、剥掉 {@code ;} 矩阵参数、去掉 context-path），
 * 因此 {@code /mp/auth/login;x=1} 这类请求在过滤器与控制器里判定一致。
 * 2026-11-24 控制中心侧修过同一类缺陷（按原始 URI 判定会被矩阵参数绕开）。
 */
public final class MiniAppPaths {

    private MiniAppPaths() {
    }

    /**
     * 与 MVC 计算 lookup path 同源的路径解析器：默认解码 URI 并剥掉 {@code ;} 矩阵参数。
     * 无状态，可并发复用。
     */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    /**
     * 应用内路径（已解码、已去矩阵参数、已去 context-path）。
     *
     * <p>解析失败时返回空串而不是抛异常：调用方只拿它做「是否豁免」的判断，
     * 解析不出来按「不豁免」处理（fail-closed），请求继续走同源校验。
     */
    public static String withinApp(HttpServletRequest request) {
        try {
            String path = PATH_HELPER.getPathWithinApplication(request);
            return path == null ? "" : path;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /** 是否是换令牌的登录入口。 */
    public static boolean isLoginEntry(HttpServletRequest request) {
        return MiniAppConstant.LOGIN_PATH.equals(withinApp(request));
    }
}
