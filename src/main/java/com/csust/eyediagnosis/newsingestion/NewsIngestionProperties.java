package com.csust.eyediagnosis.newsingestion;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deployment and scheduling settings for the WP-02 News Engine worker control plane.
 *
 * <p>Fail-closed by default ({@code enabled = false}); machine keys live only in external
 * configuration / environment variables and are never persisted in task or audit records.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "eye-diagnosis.news-ingestion")
public class NewsIngestionProperties {

    public static final Set<String> ALL_SOURCES = Set.of("pubmed", "europe-pmc", "clinicaltrials-gov");

    private boolean enabled = false;
    private Map<String, WorkerConfig> workers = new LinkedHashMap<>();
    private List<String> allowedRemoteAddresses = new ArrayList<>();
    private boolean requireHttps = false;
    private boolean plaintextAllowed = false;
    private Duration leaseDuration = Duration.ofMinutes(5);
    private Duration retryBaseDelay = Duration.ofSeconds(30);
    private Duration retryMaxDelay = Duration.ofMinutes(15);
    private int defaultMaxAttempts = 3;

    @Getter
    @Setter
    public static class WorkerConfig {
        private String key;
        private List<String> allowedSources = new ArrayList<>();
    }

    /**
     * Constant-time key lookup across all configured workers without early short-circuiting.
     */
    public Optional<NewsWorkerIdentity> authenticate(String rawKey) {
        if (!isValidRawKey(rawKey) || workers == null || workers.isEmpty()) {
            return Optional.empty();
        }
        byte[] candidateDigest = sha256(rawKey);
        NewsWorkerIdentity matched = null;
        for (Map.Entry<String, WorkerConfig> entry : workers.entrySet()) {
            WorkerConfig cfg = entry.getValue();
            String configuredKey = cfg == null ? null : cfg.getKey();
            if (!isValidRawKey(configuredKey)) {
                continue;
            }
            byte[] configuredDigest = sha256(configuredKey);
            if (MessageDigest.isEqual(candidateDigest, configuredDigest) && matched == null) {
                matched = new NewsWorkerIdentity(entry.getKey(), resolveSources(cfg));
            }
        }
        return Optional.ofNullable(matched);
    }

    public static boolean isValidRawKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 256) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 33 || c > 126) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPlaceholderKey(String key) {
        if (key == null) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.startsWith("your-")
                || lower.startsWith("fixture_")
                || lower.contains("placeholder")
                || lower.contains("change-me")
                || lower.contains("dev-only");
    }

    private static Set<String> resolveSources(WorkerConfig cfg) {
        if (cfg.getAllowedSources() == null || cfg.getAllowedSources().isEmpty()) {
            return ALL_SOURCES;
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String source : cfg.getAllowedSources()) {
            if (source != null && ALL_SOURCES.contains(source.trim())) {
                resolved.add(source.trim());
            }
        }
        return resolved;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
