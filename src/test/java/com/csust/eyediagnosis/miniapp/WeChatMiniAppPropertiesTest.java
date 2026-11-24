package com.csust.eyediagnosis.miniapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeChatMiniAppPropertiesTest {

    @Test
    void secretIsNotPartOfToString() {
        WeChatMiniAppProperties properties = new WeChatMiniAppProperties();
        properties.setAppid("wx-app");
        properties.setSecret("super-secret");

        // 有人打印这个配置对象时，AppSecret 不能跟着进日志。
        assertFalse(properties.toString().contains("super-secret"));
        assertTrue(properties.toString().contains("wx-app"));
    }

    @Test
    void tokenTtlIsClampedIntoAUsableRange() {
        WeChatMiniAppProperties properties = new WeChatMiniAppProperties();

        properties.setTokenTtlDays(30);
        assertEquals(30L * 24 * 3600, properties.tokenTtlSeconds());

        // 配成 0 会签出即抛即废的令牌。
        properties.setTokenTtlDays(0);
        assertEquals(1L * 24 * 3600, properties.tokenTtlSeconds());

        // 配成十年会造出一枚事实上永不过期的凭据。
        properties.setTokenTtlDays(3650);
        assertEquals(90L * 24 * 3600, properties.tokenTtlSeconds());
    }

    @Test
    void configuredRequiresBothAppidAndSecret() {
        WeChatMiniAppProperties properties = new WeChatMiniAppProperties();
        assertFalse(properties.isConfigured());

        properties.setAppid("wx-app");
        assertFalse(properties.isConfigured());

        properties.setSecret("secret");
        assertTrue(properties.isConfigured());
    }
}
