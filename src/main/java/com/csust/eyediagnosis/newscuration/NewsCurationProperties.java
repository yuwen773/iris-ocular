package com.csust.eyediagnosis.newscuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Deployment and policy settings for WP-03 News Engine curation, paid-model call budgets,
 * and strict evidence gating. Fail-closed by default ({@code enabled = false}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "eye-diagnosis.news-curation")
public class NewsCurationProperties {

    private boolean enabled = false;
    private String policyVersion = "news-curation-v1";
    private String provider = "dashscope";
    private String modelName = "qwen3.5-plus";
    private Duration callTimeout = Duration.ofSeconds(20);
    private int dailyMaxCalls = 100;
    private long dailyMaxTokens = 500_000L;
    private long dailyMaxCostMicros = 5_000_000L;
    private long estimatedTokensPerCall = 4_000L;
    private long estimatedCostMicrosPerCall = 50_000L;
    private int maxResponseChars = 16_000;
    private Map<String, NewsModelProfile> modelProfiles = Map.of();
}
