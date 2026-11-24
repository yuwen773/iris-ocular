package com.csust.eyediagnosis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 配置中心的部署级安全与授权配置。
 *
 * <p>这些值只允许来自部署侧（yml / 环境变量），**不得**由配置中心自身发布或
 * 由数据库覆盖：它们决定谁能发布、哪些外部 Origin 可信，属于安全边界。
 */
@Data
@Component
@ConfigurationProperties(prefix = "eye-diagnosis.control-center")
public class ControlCenterProperties {

    /**
     * 控制中心是否启用。
     *
     * <p>默认关闭：关闭时严格 Origin 校验与 CSRF 校验都不生效，控制中心端点也不注册，
     * 部署行为与引入配置中心之前完全一致。开启后才按最严格策略执行。
     */
    private boolean enabled = false;

    /**
     * 控制中心写端点的严格 Origin 白名单。
     *
     * <p>必须写完整 origin（{@code scheme://host[:port]}）；不支持通配符或后缀匹配。
     * 反向代理未改写 Host / scheme 时，必须在这里显式登记外部 Origin。
     */
    private List<String> allowedOrigins = List.of();

    /**
     * 是否信任 {@code X-Forwarded-Proto}。
     *
     * <p>只有部署确认反向代理会覆写该头时才可开启；默认关闭，避免客户端伪造。
     */
    private boolean trustProxyHeaders = false;

    /**
     * 允许执行发布 / 回滚的管理员 userId。
     *
     * <p>为空表示没有人可以发布；普通 {@code CONFIG_READ/EDIT/VALIDATE} 仍授予所有管理员。
     */
    private List<Long> publisherUserIds = List.of();

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
    }

    public void setPublisherUserIds(List<Long> publisherUserIds) {
        this.publisherUserIds = publisherUserIds == null
                ? List.of()
                : List.copyOf(publisherUserIds);
    }
}
