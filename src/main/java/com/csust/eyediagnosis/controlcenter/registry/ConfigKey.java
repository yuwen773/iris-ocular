package com.csust.eyediagnosis.controlcenter.registry;

/**
 * 允许被配置中心管理的键（ADR D1：定义只在代码里）。
 *
 * <p>这个枚举就是「未知键必须拒绝」（AC-01-02）的完备集合：数据库只存值，
 * 不存定义；新增键必须走代码评审与发版。
 *
 * <p>首期只放开 PRD FR-04 的 6 个品牌键。Provider、模型、存储、安全开关
 * 一律**不在这里**——它们属于部署事实，不是可在线改的内容。
 */
public enum ConfigKey {

    SITE_NAME("brand.siteName", ConfigType.STRING, "Iris", 2, 40),
    SHORT_NAME("brand.shortName", ConfigType.STRING, "Iris", 2, 40),

    LOGO_LIGHT("brand.logo.light", ConfigType.ASSET_REF, null, 0, 0),
    LOGO_DARK("brand.logo.dark", ConfigType.ASSET_REF, null, 0, 0),
    FAVICON_LIGHT("brand.favicon.light", ConfigType.ASSET_REF, null, 0, 0),
    FAVICON_DARK("brand.favicon.dark", ConfigType.ASSET_REF, null, 0, 0);

    private final String key;
    private final ConfigType type;
    private final String defaultValue;
    private final int minLength;
    private final int maxLength;

    ConfigKey(String key, ConfigType type, String defaultValue, int minLength, int maxLength) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public String key() {
        return key;
    }

    public ConfigType type() {
        return type;
    }

    /** 内置默认值；null 表示「使用内置资源」（仅 ASSET_REF）。 */
    public String defaultValue() {
        return defaultValue;
    }

    public int minLength() {
        return minLength;
    }

    public int maxLength() {
        return maxLength;
    }
}
