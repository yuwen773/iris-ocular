package com.csust.eyediagnosis.newscuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployment profile in {@code docs/product/news/wp-08b-model-curation.md} is only usable if Spring can
 * actually bind it. This guards the shape promised to operators — including the nested capability flags —
 * because a record with more than one constructor silently refuses constructor binding.
 */
class NewsCurationPropertiesBindingTest {

    private static final String P = "eye-diagnosis.news-curation.";

    @Test
    void documentedProfileShapeBindsThroughSpringBinder() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(P + "enabled", "true");
        raw.put(P + "run-profile-key", "prod");
        raw.put(P + "model-profiles.prod.provider", "QWEN");
        raw.put(P + "model-profiles.prod.serving-platform", "BAILIAN");
        raw.put(P + "model-profiles.prod.base-url", "https://dashscope.aliyuncs.com");
        raw.put(P + "model-profiles.prod.completions-path", "/compatible-mode/v1/chat/completions");
        raw.put(P + "model-profiles.prod.model-id", "qwen3.8-max-0902");
        raw.put(P + "model-profiles.prod.secret-ref", "DASHSCOPE_API_KEY");
        raw.put(P + "model-profiles.prod.connect-timeout", "5s");
        raw.put(P + "model-profiles.prod.read-timeout", "30s");
        raw.put(P + "model-profiles.prod.max-input-tokens", "8192");
        raw.put(P + "model-profiles.prod.max-output-tokens", "1024");
        raw.put(P + "model-profiles.prod.temperature", "0.2");
        raw.put(P + "model-profiles.prod.response-format", "JSON_OBJECT");
        raw.put(P + "model-profiles.prod.provider-capabilities.json-object", "true");
        raw.put(P + "model-profiles.prod.provider-capabilities.json-schema", "false");
        raw.put(P + "model-profiles.prod.provider-capabilities.usage-format", "STANDARD");
        raw.put(P + "model-profiles.prod.provider-capabilities.disable-thinking", "true");
        raw.put(P + "model-profiles.prod.pricing-version.version", "fixture-v1");
        raw.put(P + "model-profiles.prod.pricing-version.input-per-million", "12000000");
        raw.put(P + "model-profiles.prod.pricing-version.cached-input-per-million", "1500000");
        raw.put(P + "model-profiles.prod.pricing-version.output-per-million", "36000000");

        NewsCurationProperties properties = new Binder(new MapConfigurationPropertySource(raw))
                .bind("eye-diagnosis.news-curation", NewsCurationProperties.class)
                .orElseThrow(() -> new AssertionError("properties did not bind"));

        assertTrue(properties.isEnabled());
        assertEquals("prod", properties.getRunProfileKey());
        NewsModelProfile profile = properties.getModelProfiles().get("prod");
        assertNotNull(profile);
        assertEquals(NewsModelProfile.Provider.QWEN, profile.provider());
        assertEquals(NewsModelProfile.Platform.BAILIAN, profile.servingPlatform());
        assertEquals("qwen3.8-max-0902", profile.modelId());
        assertEquals(NewsModelProfile.Format.JSON_OBJECT, profile.responseFormat());
        assertTrue(profile.providerCapabilities().jsonObject());
        assertTrue(profile.providerCapabilities().disableThinking(),
                "嵌套能力对象必须真正绑定，否则整份 profile 会在启动时被判为非法");
        assertEquals(NewsModelProfile.UsageFormat.STANDARD, profile.providerCapabilities().usageFormat());
        assertEquals(12_000_000L, profile.pricingVersion().inputPerMillion());
    }
}
