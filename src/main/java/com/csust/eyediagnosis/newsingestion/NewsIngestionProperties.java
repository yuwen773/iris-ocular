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
    /**
     * 允许**直连**到本服务的对端地址（Worker 自身地址）。
     *
     * <p>语义只有一种：直连对端。它<b>不是</b>「反代转发的客户端地址」——Tomcat 的 native
     * 转发处理会用 {@code X-Forwarded-For} 改写 {@code getRemoteAddr()}，应用侧拿不到原始对端，
     * 因此带转发头的请求无法判定对端，会被 {@link NewsWorkerAuthFilter} 直接拒绝（fail closed）。
     */
    private List<String> allowedRemoteAddresses = new ArrayList<>();
    /**
     * 显式声明可信反向代理的地址（精确 IP 或 CIDR）。
     *
     * <p>启用 {@code server.forward-headers-strategy=native} 时必须声明：它与 Tomcat 的
     * {@code server.tomcat.remoteip.internal-proxies} 会被启动基线交叉核对——声明的每个地址
     * 都必须落在正则内，正则也不得命中声明范围之外的探针地址（例如信任整个 10/8 会被拒绝）。
     * 核对基于哨兵地址与网段边界探针，能拦住常见过宽写法，但<b>不是</b>对任意正则覆盖范围的
     * 穷尽证明；运维仍需保证声明与实际部署一致。
     */
    private List<String> trustedProxyAddresses = new ArrayList<>();
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

    /**
     * 机器凭据格式：32–256 位可见 ASCII。
     *
     * <p>下限取 32 而不是 16：这条通道的凭据是唯一真正的认证（IP 白名单只是纵深防御，
     * 且在转发头存在时会被 fail closed 掉），16 位可见 ASCII 的熵不足以当作凭据。
     * 32 位随机可见 ASCII 约合 200 bit 熵。
     */
    public static boolean isValidRawKey(String key) {
        if (key == null || key.length() < 32 || key.length() > 256) {
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
