package com.csust.eyediagnosis.miniapp;

import lombok.Data;
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

    /** 小程序 AppID。 */
    private String appid;

    /** 小程序 AppSecret（仅服务端持有，不下发、不落日志）。 */
    private String secret;

    /** 登录令牌有效期（天），每次校验滑动续期。 */
    private int tokenTtlDays = 30;

    public boolean isConfigured() {
        return StringUtils.hasText(appid) && StringUtils.hasText(secret);
    }

    public long tokenTtlSeconds() {
        return tokenTtlDays * 24L * 3600L;
    }
}
