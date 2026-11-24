package com.csust.eyediagnosis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户反馈的功能开关与限额，一处读取、两个服务共用。
 *
 * <p>为什么单独成一个配置对象：开关要同时管住「读写端点」
 * （{@code UserFeedbackLifecycleService}）和「截图上传」
 * （{@code UserFeedbackAttachmentService}）。两处各写一个
 * {@code @Value} 就等于把同一个概念复制两份，关掉一处等于没关。
 */
@Component
@ConfigurationProperties(prefix = "eye-diagnosis.feedback")
@Data
public class UserFeedbackSettings {

    /** 默认开启；关闭时端点回 40300「反馈功能暂未启用」，不是 404 */
    private boolean enabled = true;

    /** 每人每分钟最多提交条数；≤0 表示不限流 */
    private int rateLimitPerMinute = 5;
}
