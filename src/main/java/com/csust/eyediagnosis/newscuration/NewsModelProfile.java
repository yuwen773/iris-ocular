package com.csust.eyediagnosis.newscuration;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Deployment-owned model settings. Only the credential reference enters the configuration hash. */
public record NewsModelProfile(Provider provider, Platform servingPlatform, URI baseUrl, String completionsPath,
                               String modelId, String secretRef, Duration connectTimeout, Duration readTimeout,
                               int maxInputTokens, int maxOutputTokens, double temperature, Format responseFormat,
                               Capabilities providerCapabilities, PricingVersion pricingVersion) {

    public enum Provider { QWEN, DEEPSEEK, SPARK }
    public enum Platform { BAILIAN, DEEPSEEK, SPARK }
    public enum Format { PROMPT_JSON, JSON_OBJECT, JSON_SCHEMA }
    public enum UsageFormat { STANDARD, DEEPSEEK_CACHE }

    public record Capabilities(boolean jsonObject, boolean jsonSchema, UsageFormat usageFormat) {
    }

    /** All rates use the same deployment currency, in millionths of a unit per million tokens. */
    public record PricingVersion(String version, long inputPerMillion, long cachedInputPerMillion,
                                 long outputPerMillion) {
        public PricingVersion {
            if (!identifier(version, 64) || inputPerMillion < 1 || outputPerMillion < 1
                    || cachedInputPerMillion < 0 || cachedInputPerMillion > inputPerMillion
                    || inputPerMillion > 1_000_000_000L || outputPerMillion > 1_000_000_000L) {
                throw new IllegalArgumentException("Invalid news model pricing version");
            }
        }

        long cost(int promptTokens, int cachedTokens, int outputTokens) {
            BigInteger raw = BigInteger.valueOf(promptTokens - cachedTokens).multiply(BigInteger.valueOf(inputPerMillion))
                    .add(BigInteger.valueOf(cachedTokens).multiply(BigInteger.valueOf(cachedInputPerMillion)))
                    .add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(outputPerMillion)));
            return raw.add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000)).longValueExact();
        }
    }

    public NewsModelProfile {
        if (provider == null || servingPlatform == null || baseUrl == null || !allowedEndpoint(servingPlatform, baseUrl, completionsPath)
                || !identifier(modelId, 128) || secretRef == null || !secretRef.matches("[A-Z][A-Z0-9_]{0,127}")
                || !boundedTimeout(connectTimeout) || !boundedTimeout(readTimeout)
                || maxInputTokens < 512 || maxInputTokens > 131_072 || maxOutputTokens < 1 || maxOutputTokens > 8192
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 1
                || responseFormat == null || providerCapabilities == null || providerCapabilities.usageFormat() == null
                || pricingVersion == null
                || responseFormat == Format.JSON_OBJECT && !providerCapabilities.jsonObject()
                || responseFormat == Format.JSON_SCHEMA && !providerCapabilities.jsonSchema()
                || servingPlatform == Platform.SPARK && (provider != Provider.SPARK || responseFormat != Format.PROMPT_JSON)
                || servingPlatform == Platform.DEEPSEEK && provider != Provider.DEEPSEEK
                || servingPlatform == Platform.BAILIAN && provider == Provider.SPARK
                || providerCapabilities.usageFormat() == UsageFormat.DEEPSEEK_CACHE && servingPlatform != Platform.DEEPSEEK) {
            throw new IllegalArgumentException("Invalid news model profile");
        }
    }

    private static boolean allowedEndpoint(Platform platform, URI origin, String path) {
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getPort() != -1 || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))) {
            return false;
        }
        return switch (platform) {
            case BAILIAN -> Set.of("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com").contains(origin.getHost())
                    && "/compatible-mode/v1/chat/completions".equals(path);
            case DEEPSEEK -> "api.deepseek.com".equals(origin.getHost())
                    && ("/chat/completions".equals(path) || "/v1/chat/completions".equals(path));
            case SPARK -> "spark-api-open.xf-yun.com".equals(origin.getHost()) && "/v1/chat/completions".equals(path);
        };
    }

    private static boolean boundedTimeout(Duration duration) {
        return duration != null && duration.toMillis() >= 1 && duration.compareTo(Duration.ofMinutes(2)) <= 0;
    }

    static boolean identifier(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }
}
