package com.csust.eyediagnosis.miniapp;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 微信小程序服务端凭据与令牌参数。
 *
 * <p>AppSecret 只从环境变量读取，仓库里不出现真实值；未配置时小程序登录直接失败，
 * 而不是带着空 AppID 去请求微信、拿一个难查的 errcode 回来。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WeChatMiniAppProperties {

    /** 令牌有效期的下限（天）：至少一天，不能签出即抛即废的令牌。 */
    private static final int MIN_TTL_DAYS = 1;

    /** 令牌有效期的上限（天）：三个月，避免误配出事实上永不过期的凭据。 */
    private static final int MAX_TTL_DAYS = 90;

    /** 小程序 AppID。 */
    private String appid;

    /**
     * 小程序 AppSecret（仅服务端持有，不下发、不落日志）。
     *
     * <p>{@code @Data} 生成的 {@code toString()} 会把字段值拼进去——一旦有人打印这个
     * 配置对象，密钥就进了日志。{@code @ToString.Exclude} 把这一项排除掉。
     */
    @ToString.Exclude
    private String secret;

    /** 登录令牌有效期（天），每次校验滑动续期。 */
    private int tokenTtlDays = 30;

    public boolean isConfigured() {
        return StringUtils.hasText(appid) && StringUtils.hasText(secret);
    }

    /**
     * 令牌有效期（秒）。
     *
     * <p>上下限都要有：配成 0 会签出「即抛即废」的令牌，配成几千天会造出一枚
     * 事实上永不过期的凭据。越界的配置在这里被夹回区间，不靠运维记得填对。
     */
    public long tokenTtlSeconds() {
        int days = Math.max(MIN_TTL_DAYS, Math.min(tokenTtlDays, MAX_TTL_DAYS));
        return days * 24L * 3600L;
    }
}
