package com.csust.eyediagnosis.controlcenter.registry;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 配置注册表：允许管理的键、默认值与部署保护边界。
 *
 * <p>无状态、无外部依赖，纯代码事实。三件事：
 * <ul>
 *   <li>{@link #managedKeys()} —— 唯一可写集合，别的键一律 40904；</li>
 *   <li>{@link #defaults()} —— 空库时的公开读取结果（AC-01-01）；</li>
 *   <li>{@link #isDeployProtected(String)} —— 明确属于部署事实的前缀，
 *       命中时返回 40905 而不是「未知键」，让调用方知道「不是拼错了，是不许改」。</li>
 * </ul>
 */
@Component
public class ConfigRegistry {

    /** 载荷 schema 版本；不匹配一律 40903。 */
    public static final int SCHEMA_VERSION = 1;

    /**
     * 部署保护前缀：这些配置只允许来自 yml / 环境变量。
     *
     * <p>把它们单独列出而不是混进「未知键」，是因为运维和调用方需要区分
     * 「这个键不存在」与「这个键永远不允许在线改」。
     */
    private static final List<String> DEPLOY_PROTECTED_PREFIXES = List.of(
            "eye-diagnosis.",
            "spring.",
            "server.",
            "security.",
            "app.cors.",
            "cos.",
            "management."
    );

    private final Map<String, ConfigKey> byName;

    public ConfigRegistry() {
        Map<String, ConfigKey> index = new LinkedHashMap<>();
        for (ConfigKey key : ConfigKey.values()) {
            index.put(key.key(), key);
        }
        this.byName = Map.copyOf(index);
    }

    public Set<String> managedKeys() {
        return byName.keySet();
    }

    public Optional<ConfigKey> find(String key) {
        return Optional.ofNullable(key == null ? null : byName.get(key));
    }

    /**
     * 键 → 默认值（ASSET_REF 的默认值为 null，表示内置资源）。
     *
     * <p>不能用 {@code Collectors.toMap}：它不接受 null 值，会直接 NPE。
     * 而「默认值 null = 使用内置资源」是这里的一等语义。
     */
    public Map<String, String> defaults() {
        Map<String, String> defaults = new TreeMap<>();
        byName.values().forEach(key -> defaults.put(key.key(), key.defaultValue()));
        return defaults;
    }

    /**
     * 是否属于部署保护范围。
     *
     * <p>先于「未知键」判断：保护前缀下的键即使拼写正确也永远不可写。
     */
    public boolean isDeployProtected(String key) {
        if (key == null) {
            return false;
        }
        return DEPLOY_PROTECTED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    public List<ConfigKey> definitions() {
        return List.copyOf(byName.values());
    }
}
