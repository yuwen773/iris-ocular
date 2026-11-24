package com.csust.eyediagnosis.controlcenter.registry;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置值校验：类型、长度（码点）、控制字符、资产引用与两类拒绝。 */
class ConfigValueValidatorTest {

    private final ConfigRegistry registry = new ConfigRegistry();
    private final ConfigValueValidator validator = new ConfigValueValidator(registry);

    @Test
    void validPayloadPasses() {
        ConfigValueValidator.Result result = validator.validate(Map.of(
                "brand.siteName", "Iris 眼科",
                "brand.shortName", "Iris",
                "brand.logo.light", "0123456789abcdef0123456789abcdef"));
        assertTrue(result.valid());
        assertEquals(Map.of(), result.fieldErrors());
    }

    @Test
    void nullAssetRefMeansBuiltInAndIsAccepted() {
        Map<String, Object> values = new HashMap<>();
        values.put("brand.favicon.light", null);
        assertTrue(validator.validate(values).valid());
    }

    @Test
    void unknownKeyIsRejected() {
        ConfigValueValidator.Result result = validator.validate(Map.of("brand.unknown", "x"));
        assertFalse(result.valid());
        assertEquals(List.of("UNKNOWN_KEY"), result.fieldErrors().get("brand.unknown"));
        assertEquals("UNKNOWN_KEY", result.primaryReason());
    }

    @Test
    void deployProtectedKeyIsRejectedWithItsOwnReason() {
        ConfigValueValidator.Result result = validator.validate(Map.of(
                "eye-diagnosis.control-center.publisher-user-ids", "1"));
        assertFalse(result.valid());
        assertEquals(List.of("DEPLOY_PROTECTED"), result.fieldErrors().get("eye-diagnosis.control-center.publisher-user-ids"));
        assertEquals("DEPLOY_PROTECTED", result.primaryReason());
    }

    @Test
    void tooShortAndTooLongAreRejected() {
        assertFalse(validator.validate(Map.of("brand.siteName", "A")).valid());
        assertFalse(validator.validate(Map.of("brand.siteName", "x".repeat(41))).valid());
        assertTrue(validator.validate(Map.of("brand.siteName", "x".repeat(40))).valid());
    }

    @Test
    void lengthIsCountedInCodePointsNotUtf16Units() {
        // 40 个汉字：String.length() 是 40，这里关键是 emoji 场景；
        // 35 个 emoji（代理对，length=70）在码点口径下仍是 35，必须通过。
        String emoji = "😀".repeat(35);
        assertEquals(70, emoji.length());
        assertTrue(validator.validate(Map.of("brand.siteName", emoji)).valid());
        // 41 个 emoji 才越界。
        assertFalse(validator.validate(Map.of("brand.siteName", "😀".repeat(41))).valid());
    }

    @Test
    void controlCharactersAreRejected() {
        ConfigValueValidator.Result result = validator.validate(Map.of("brand.siteName", "Iris\nAdmin"));
        assertFalse(result.valid());
        assertTrue(result.fieldErrors().get("brand.siteName").contains("CONTROL_CHARACTER"));
    }

    @Test
    void emptyTextIsRejectedAsRequired() {
        Map<String, Object> values = new HashMap<>();
        values.put("brand.siteName", null);
        ConfigValueValidator.Result result = validator.validate(values);
        assertFalse(result.valid());
        assertEquals(List.of("REQUIRED"), result.fieldErrors().get("brand.siteName"));
    }

    @Test
    void nonStringValuesAreRejected() {
        ConfigValueValidator.Result result = validator.validate(Map.of("brand.siteName", 42));
        assertFalse(result.valid());
        assertEquals(List.of("TYPE_MISMATCH"), result.fieldErrors().get("brand.siteName"));
    }

    @Test
    void assetRefOnlyAcceptsServerIssuedIds() {
        for (String bad : List.of("https://evil.example.com/a.png", "/static/logo.png",
                "data:image/png;base64,AAA", "0123456789ABCDEF0123456789ABCDEF", "abc")) {
            assertFalse(validator.validate(Map.of("brand.logo.dark", bad)).valid(),
                    "必须拒绝非法资产引用: " + bad);
        }
        assertTrue(validator.validate(Map.of("brand.logo.dark", "fedcba9876543210fedcba9876543210")).valid());
    }

    @Test
    void nullPayloadIsValid() {
        assertTrue(validator.validate(null).valid());
    }

    @Test
    void unknownKeysHelperIgnoresProtectedOnes() {
        Map<String, Object> values = Map.of(
                "brand.unknown", "x",
                "spring.datasource.url", "jdbc:mysql://x");
        assertEquals(java.util.Set.of("brand.unknown"), validator.unknownKeys(values));
    }
}
