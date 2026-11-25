package com.csust.eyediagnosis.config;

import com.csust.eyediagnosis.grpc.GrpcHosts;
import com.csust.eyediagnosis.newsingestion.NewsIngestionProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 部署期安全基线：带着 server / prod profile 启动时，拒绝带着开发调试口子运行。
 *
 * <p>问题不在某一条配置写错，而在「共享基线里带着开发默认值」：SQL 语句与参数打标准输出、
 * 接口文档全量暴露、模型 prompt 以 debug 级别落盘、会话 Cookie 不要求 Secure。
 * 这些在本地都合理，一旦被部署继承，患者影像路径、会话内容与用户输入就跟着进日志和文档页。
 *
 * <p>默认值已经改成安全的一侧（见 {@code application.yml}），这里是第二道：显式配置回
 * 不安全值、或部署环境带着旧配置启动时，**启动即失败**，而不是安静地跑起来。
 * 本地开发不激活 server/prod，因此不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSafetyGuard {

    /** 视为「部署环境」的 profile。本地开发用的是 local/dev。 */
    private static final List<String> DEPLOYMENT_PROFILES = List.of("server", "prod");

    private final Environment environment;

    @PostConstruct
    void verify() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.stream().noneMatch(DEPLOYMENT_PROFILES::contains)) {
            return;
        }
        List<String> violations = new ArrayList<>();
        String logImpl = environment.getProperty("mybatis-plus.configuration.log-impl", "");
        if (logImpl.toLowerCase().contains("stdout")) {
            violations.add("mybatis-plus.configuration.log-impl=" + logImpl
                    + "（SQL 与参数会打进标准输出；部署环境应使用 NoLoggingImpl）");
        }
        if (environment.getProperty("knife4j.enable", Boolean.class, false)) {
            violations.add("knife4j.enable=true（接口文档页会完整暴露；部署环境应设为 false）");
        }
        String advisor = environment.getProperty(
                "logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor", "");
        if (advisor.equalsIgnoreCase("debug") || advisor.equalsIgnoreCase("trace")) {
            violations.add("SimpleLoggerAdvisor 日志级别=" + advisor + "（模型 prompt 与消息体会落盘）");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            violations.add("server.servlet.session.cookie.secure=false"
                    + "（会话 Cookie 未强制 HTTPS；部署环境应设 SESSION_COOKIE_SECURE=true）");
        }
        // 影像字节与诊断结论都走 gRPC；非回环地址上的明文链路既无加密也无对端校验。
        // 要么开 TLS，要么显式承认这段链路由网络隔离覆盖——不允许「没注意到就这样跑」。
        String grpcHost = environment.getProperty("diagnosis.grpc.host", "127.0.0.1");
        boolean grpcTls = environment.getProperty("diagnosis.grpc.tls-enabled", Boolean.class, false);
        boolean plaintextAllowed = environment.getProperty(
                "diagnosis.grpc.plaintext-allowed", Boolean.class, false);
        if (!GrpcHosts.isLoopback(grpcHost) && !grpcTls && !plaintextAllowed) {
            violations.add("diagnosis.grpc.host=" + grpcHost + " 且 tls-enabled=false"
                    + "（跨主机明文 gRPC：开 TLS，或确认链路已被网络隔离覆盖后设"
                    + " DIAGNOSIS_GRPC_PLAINTEXT_ALLOWED=true）");
        }
        verifyNewsIngestion(violations);
        verifyNewsCuration(violations);
        if (violations.isEmpty()) {
            log.info("部署安全基线检查通过（profiles={}）", active);
            return;
        }
        throw new IllegalStateException(
                "部署安全基线未满足，拒绝启动（profiles=" + active + "）：\n  - "
                        + String.join("\n  - ", violations)
                        + "\n参考 src/main/resources/application-server.example.yml 的「生产安全基线」一节。");
    }

    private void verifyNewsIngestion(List<String> violations) {
        if (!environment.getProperty("eye-diagnosis.news-ingestion.enabled", Boolean.class, false)) {
            return;
        }
        Map<String, NewsIngestionProperties.WorkerConfig> workers = Binder.get(environment)
                .bind("eye-diagnosis.news-ingestion.workers",
                        Bindable.mapOf(String.class, NewsIngestionProperties.WorkerConfig.class))
                .orElse(Map.of());
        boolean hasConfiguredKey = false;
        for (Map.Entry<String, NewsIngestionProperties.WorkerConfig> entry : workers.entrySet()) {
            String workerId = entry.getKey();
            NewsIngestionProperties.WorkerConfig cfg = entry.getValue();
            String key = cfg == null ? null : cfg.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            hasConfiguredKey = true;
            if (!NewsIngestionProperties.isValidRawKey(key)) {
                violations.add("eye-diagnosis.news-ingestion.workers." + workerId
                        + ".key 格式无效（需 16–256 位可见 ASCII 字符）");
            } else if (NewsIngestionProperties.isPlaceholderKey(key)) {
                violations.add("eye-diagnosis.news-ingestion.workers." + workerId
                        + ".key 不能使用示例或占位凭据");
            }
        }
        if (!hasConfiguredKey) {
            violations.add("eye-diagnosis.news-ingestion.enabled=true 但未配置任何 Worker 凭据"
                    + "（eye-diagnosis.news-ingestion.workers.<id>.key）");
        }
        boolean requireHttps = environment.getProperty(
                "eye-diagnosis.news-ingestion.require-https", Boolean.class, false);
        boolean plaintextAllowed = environment.getProperty(
                "eye-diagnosis.news-ingestion.plaintext-allowed", Boolean.class, false);
        if (!requireHttps && !plaintextAllowed) {
            violations.add("eye-diagnosis.news-ingestion.enabled=true 且 require-https=false"
                    + "（部署环境必须设 require-https=true，或由内网 TLS 终止覆盖并显式设置 plaintext-allowed=true）");
        }
        if (requireHttps) {
            verifyForwardedTransport(violations);
        }
    }

    /**
     * Worker 的 HTTPS 判定读 {@code request.isSecure()}。只有「来源可信」的转发头才能把它置真：
     * {@code framework} 策略的 ForwardedHeaderFilter 不校验来源，外部直连带一个
     * {@code X-Forwarded-Proto: https} 就能伪造；{@code native} 策略只信任
     * {@code server.tomcat.remoteip.internal-proxies} 内的远端地址，未显式限定边界等于没核验。
     *
     * <p>还有一条隐式路径：策略未配置时，Spring Boot 在云平台（Kubernetes / Cloud Foundry 等）
     * 上会**自动**启用 native 并套用默认 {@code internal-proxies}（10/8、172.16/12、192.168/16、127/8）。
     * 那等于「没写配置也开了一个你没决定的信任边界」，所以要求显式写清楚。
     */
    private void verifyForwardedTransport(List<String> violations) {
        String strategy = environment.getProperty("server.forward-headers-strategy");
        if (strategy == null || strategy.isBlank()) {
            CloudPlatform platform = CloudPlatform.getActive(environment);
            if (platform != null) {
                violations.add("server.forward-headers-strategy 未显式配置，但运行在云平台 " + platform
                        + "（Spring Boot 会自动启用 native 转发处理并使用默认 internal-proxies，"
                        + "可信代理边界不在你手里；显式设为 native 并配置 server.tomcat.remoteip.internal-proxies，"
                        + "或设为 none 让伪造头直接失效）");
            }
            return;
        }
        if (strategy.equalsIgnoreCase("framework")) {
            violations.add("server.forward-headers-strategy=framework 且 news-ingestion.require-https=true"
                    + "（ForwardedHeaderFilter 不校验来源，外部请求可伪造 X-Forwarded-Proto 通过 HTTPS 门禁；"
                    + "改用 native 并显式设置 server.tomcat.remoteip.internal-proxies 限定可信代理）");
        } else if (strategy.equalsIgnoreCase("native")
                && environment.getProperty("server.tomcat.remoteip.internal-proxies", "").isBlank()) {
            violations.add("server.forward-headers-strategy=native 但未设置 "
                    + "server.tomcat.remoteip.internal-proxies（未限定可信代理，可信边界不清）");
        }
    }

    private void verifyNewsCuration(List<String> violations) {
        if (!environment.getProperty("eye-diagnosis.news-curation.enabled", Boolean.class, false)) {
            return;
        }
        int maxCalls = environment.getProperty("eye-diagnosis.news-curation.daily-max-calls", Integer.class, 0);
        if (maxCalls <= 0) {
            violations.add("eye-diagnosis.news-curation.daily-max-calls 必须大于 0");
        }
        long maxTokens = environment.getProperty("eye-diagnosis.news-curation.daily-max-tokens", Long.class, 0L);
        if (maxTokens <= 0L) {
            violations.add("eye-diagnosis.news-curation.daily-max-tokens 必须大于 0");
        }
        long maxCostMicros = environment.getProperty(
                "eye-diagnosis.news-curation.daily-max-cost-micros", Long.class, 0L);
        if (maxCostMicros <= 0L) {
            violations.add("eye-diagnosis.news-curation.daily-max-cost-micros 必须大于 0");
        }
        String policyVersion = environment.getProperty("eye-diagnosis.news-curation.policy-version", "");
        if (policyVersion.isBlank()) {
            violations.add("eye-diagnosis.news-curation.policy-version 不能为空");
        }
        String modelName = environment.getProperty("eye-diagnosis.news-curation.model-name", "");
        if (modelName.isBlank()) {
            violations.add("eye-diagnosis.news-curation.model-name 不能为空");
        }
    }
}
