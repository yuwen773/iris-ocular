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
    /**
     * Whole-experiment ceiling. Unlike the daily knobs these are counted across every UTC date, every budget
     * scope and every process restart: a value of 0 means "no lifetime cap" for that dimension, and the first
     * non-zero value ever written to {@code t_news_curation_lifetime_budget} can only be tightened, never
     * removed by editing configuration later.
     */
    private int lifetimeMaxCalls = 0;
    private long lifetimeMaxTokens = 0L;
    private long lifetimeMaxCostMicros = 0L;
    private long estimatedTokensPerCall = 4_000L;
    private long estimatedCostMicrosPerCall = 50_000L;
    private int maxResponseChars = 16_000;
    private Map<String, NewsModelProfile> modelProfiles = Map.of();

    /**
     * Server-side arbitration of the model profile used by the admin-triggered production run (P05).
     * The client never chooses a model: when this is set it must name a configured profile; when it is
     * blank the run entry only proceeds if exactly one profile is configured.
     */
    private String runProfileKey;
}
