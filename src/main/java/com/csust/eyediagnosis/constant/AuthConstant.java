package com.csust.eyediagnosis.constant;

/**
 * @ClassNameUserAuthConstant
 * @Author钰玟
 * @Date2025/4/12 下午5:55
 * @Version 1.0
 **/

public class AuthConstant {
    /**
     * 默认角色
     */
    public static final String DEFAULT_ROLE = "user";
    /**
     * 管理员角色
     */
    public static final String ADMIN_ROLE = "admin";
    /**
     * 封禁角色（账号保留但禁止登录与访问）
     */
    public static final String BAN_ROLE = "ban";

    /**
     * 封禁判据的唯一实现。
     *
     * <p>密码登录、会话回查、小程序换令牌必须用同一条判据：各处自己写一遍
     * {@code BAN_ROLE.equals(...)}，将来多一个停用档位就会只改一处。
     */
    public static boolean isBanned(String userRole) {
        return BAN_ROLE.equals(userRole);
    }
}
