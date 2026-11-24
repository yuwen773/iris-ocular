package com.csust.eyediagnosis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通知的运维参数。
 *
 * <p>只有一项可配：已读通知保留天数。限流、开关之类都不放在这里——
 * 通知是主流程的旁路，写失败也不阻断业务，没有需要单独关停的理由。
 */
@Component
@ConfigurationProperties(prefix = "eye-diagnosis.notification")
@Data
public class NotificationSettings {

    /**
     * 已读通知保留天数（默认 90）。未读通知不清理：用户还没看到的消息
     * 不该因为时间到了就消失。≤0 表示关闭清理。
     */
    private int retentionDays = 90;
}
