package com.csust.eyediagnosis.constant;

/**
 * 小程序（微信）通道的请求级常量。
 *
 * <p>这里只放「过滤器写、下游读」的标记名。颜色/文案一类业务常量不放在这里。
 */
public final class MiniAppConstant {

    /** 请求头名。 */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 前缀（带一个空格，比对时按不区分大小写处理方案见 TokenService）。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 令牌解析出的 t_user.id，由 {@code MiniAppTokenFilter} 写入。
     * {@code UserServiceImpl#getLoginUser} 优先读它，读不到再走 Cookie 会话。
     */
    public static final String REQUEST_ATTR_USER_ID = "MP_LOGIN_USER_ID";

    /**
     * 「本请求已通过 Bearer 令牌认证」的标记。
     * {@code SameOriginFilter} 看到它就不再校验 Origin/Referer——令牌由调用方显式携带，
     * 不依赖浏览器自动附带的凭证，CSRF 前提不成立。
     */
    public static final String REQUEST_ATTR_TOKEN_AUTH = "MP_TOKEN_AUTHENTICATED";

    private MiniAppConstant() {
    }
}
