package com.csust.eyediagnosis.controlcenter.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册表：只有代码里注册的键才可管理，部署事实永远不可写。 */
class ConfigRegistryTest {

    private final ConfigRegistry registry = new ConfigRegistry();

    @Test
    void managesExactlyTheSixBrandKeys() {
        assertEquals(6, registry.managedKeys().size());
        assertTrue(registry.managedKeys().contains("brand.siteName"));
        assertTrue(registry.managedKeys().contains("brand.shortName"));
        assertTrue(registry.managedKeys().contains("brand.logo.light"));
        assertTrue(registry.managedKeys().contains("brand.logo.dark"));
        assertTrue(registry.managedKeys().contains("brand.favicon.light"));
        assertTrue(registry.managedKeys().contains("brand.favicon.dark"));
    }

    @Test
    void defaultsCoverEveryManagedKey() {
        Map<String, String> defaults = registry.defaults();
        assertEquals(registry.managedKeys(), defaults.keySet());
        assertEquals("Iris", defaults.get("brand.siteName"));
        assertEquals("Iris", defaults.get("brand.shortName"));
        // ASSET_REF 默认 null = 使用内置资源，不是缺省值缺失。
        assertTrue(defaults.containsKey("brand.logo.light"));
        assertEquals(null, defaults.get("brand.logo.light"));
    }

    @Test
    void deployProtectedPrefixesAreNotManageable() {
        assertTrue(registry.isDeployProtected("eye-diagnosis.control-center.publisher-user-ids"));
        assertTrue(registry.isDeployProtected("spring.datasource.url"));
        assertTrue(registry.isDeployProtected("cos.client.secretKey"));
        assertTrue(registry.isDeployProtected("app.cors.allowed-origins"));
        assertTrue(registry.isDeployProtected("security.same-origin.additional-hosts"));

        assertFalse(registry.isDeployProtected("brand.siteName"));
        assertFalse(registry.find("brand.siteName").isEmpty());
    }

    @Test
    void unknownKeysAreNotManaged() {
        assertTrue(registry.find("brand.unknown").isEmpty());
        assertTrue(registry.find(null).isEmpty());
        assertFalse(registry.isDeployProtected("brand.unknown"));
    }

    @Test
    void definitionsCarryLengthLimitsForText() {
        ConfigKey siteName = registry.find("brand.siteName").orElseThrow();
        assertEquals(ConfigType.STRING, siteName.type());
        assertEquals(2, siteName.minLength());
        assertEquals(40, siteName.maxLength());

        ConfigKey logo = registry.find("brand.logo.light").orElseThrow();
        assertEquals(ConfigType.ASSET_REF, logo.type());
    }
}
