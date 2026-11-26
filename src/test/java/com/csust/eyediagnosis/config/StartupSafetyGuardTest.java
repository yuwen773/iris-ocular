package com.csust.eyediagnosis.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupSafetyGuardTest {

    private static MockEnvironment deployment() {
        return new MockEnvironment()
                .withProperty("mybatis-plus.configuration.log-impl",
                        "org.apache.ibatis.logging.nologging.NoLoggingImpl")
                .withProperty("knife4j.enable", "false")
                .withProperty("logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor", "warn")
                .withProperty("server.servlet.session.cookie.secure", "true");
    }

    @Test
    void localDevelopmentIsNotConstrained() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("mybatis-plus.configuration.log-impl", "org.apache.ibatis.logging.stdout.StdOutImpl");
        env.setActiveProfiles("local", "dev");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }

    @Test
    void safeDeploymentConfigurationPasses() {
        MockEnvironment env = deployment();
        env.setActiveProfiles("local", "dev", "server");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }

    @Test
    void sqlStdoutLoggingBlocksStartup() {
        MockEnvironment env = deployment()
                .withProperty("mybatis-plus.configuration.log-impl", "org.apache.ibatis.logging.stdout.StdOutImpl");
        env.setActiveProfiles("server");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        assertTrue(failure.getMessage().contains("log-impl"), failure.getMessage());
    }

    @Test
    void everyLeakIsReportedTogether() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("mybatis-plus.configuration.log-impl", "org.apache.ibatis.logging.stdout.StdOutImpl")
                .withProperty("knife4j.enable", "true")
                .withProperty("logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor", "debug")
                .withProperty("server.servlet.session.cookie.secure", "false");
        env.setActiveProfiles("prod");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        String message = failure.getMessage();
        assertTrue(message.contains("log-impl"), message);
        assertTrue(message.contains("knife4j"), message);
        assertTrue(message.contains("SimpleLoggerAdvisor"), message);
        assertTrue(message.contains("cookie.secure"), message);
    }

    @Test
    void plaintextGrpcToARemoteHostBlocksStartup() {
        MockEnvironment env = deployment().withProperty("diagnosis.grpc.host", "algorithm.internal");
        env.setActiveProfiles("server");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        assertTrue(failure.getMessage().contains("tls-enabled=false"), failure.getMessage());
    }

    @Test
    void tlsOrExplicitNetworkIsolationMakesRemoteGrpcAcceptable() {
        MockEnvironment tls = deployment()
                .withProperty("diagnosis.grpc.host", "algorithm.internal")
                .withProperty("diagnosis.grpc.tls-enabled", "true");
        tls.setActiveProfiles("server");
        assertDoesNotThrow(() -> new StartupSafetyGuard(tls).verify());

        // 「已知这段链路被私有网络隔离覆盖」也是明确答复，但必须是显式承认
        MockEnvironment isolated = deployment()
                .withProperty("diagnosis.grpc.host", "10.0.0.12")
                .withProperty("diagnosis.grpc.plaintext-allowed", "true");
        isolated.setActiveProfiles("server");
        assertDoesNotThrow(() -> new StartupSafetyGuard(isolated).verify());
    }

    @Test
    void loopbackGrpcNeedsNoTls() {
        MockEnvironment env = deployment().withProperty("diagnosis.grpc.host", "127.0.0.1");
        env.setActiveProfiles("server");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }

    @Test
    void newsIngestionDisabledPassesWithoutWorkerKeys() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "false");
        env.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }

    @Test
    void newsIngestionEnabledWithoutKeyOrTransportPolicyBlocksStartup() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true");
        env.setActiveProfiles("server");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        String msg = failure.getMessage();
        assertTrue(msg.contains("workers.<id>.key"), msg);
        assertTrue(msg.contains("require-https=false"), msg);
    }

    @Test
    void newsIngestionRejectsWeakOrPlaceholderKeys() {
        MockEnvironment weak = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "too-short");
        weak.setActiveProfiles("prod");
        IllegalStateException weakFailure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(weak).verify());
        assertTrue(weakFailure.getMessage().contains("格式无效"), weakFailure.getMessage());

        MockEnvironment placeholder = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "fixture_worker_key_1234567890abcdef");
        placeholder.setActiveProfiles("prod");
        IllegalStateException placeholderFailure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(placeholder).verify());
        assertTrue(placeholderFailure.getMessage().contains("占位凭据"), placeholderFailure.getMessage());
    }

    @Test
    void newsIngestionEnabledWithStrongKeyAndHttpsOrExplicitPlaintextPasses() {
        MockEnvironment https = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e");
        https.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new StartupSafetyGuard(https).verify());

        MockEnvironment privateNet = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.plaintext-allowed", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e");
        privateNet.setActiveProfiles("server");
        assertDoesNotThrow(() -> new StartupSafetyGuard(privateNet).verify());
    }

    @Test
    void newsIngestionUnsetForwardingOutsideCloudPlatformPasses() {
        // 策略未配置且不在云平台：没有转发处理，伪造 X-Forwarded-Proto 不会把 isSecure() 置真（fail-closed）。
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e");
        env.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }

    @Test
    void newsIngestionUnsetForwardingOnCloudPlatformBlocksStartup() {
        // 云平台上「没写配置」会被 Spring Boot 自动补成 native + 默认 internal-proxies，
        // 等于开了一条运维没决定的信任边界。
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                // 与真实部署等价：Boot 在 KUBERNETES_SERVICE_HOST 存在时判定为云平台；
                // 单元测试里用同名显式覆盖，避免依赖宿主机环境变量。
                .withProperty("spring.main.cloud-platform", "kubernetes");
        env.setActiveProfiles("prod");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        assertTrue(failure.getMessage().contains("云平台"), failure.getMessage());
    }

    @Test
    void newsIngestionRejectsForgeableFrameworkForwarding() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                .withProperty("server.forward-headers-strategy", "framework");
        env.setActiveProfiles("prod");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        assertTrue(failure.getMessage().contains("forward-headers-strategy=framework"), failure.getMessage());
    }

    @Test
    void newsIngestionNativeForwardingRequiresExplicitTrustedProxies() {
        MockEnvironment unbounded = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                .withProperty("server.forward-headers-strategy", "native");
        unbounded.setActiveProfiles("prod");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(unbounded).verify());
        assertTrue(failure.getMessage().contains("remoteip.internal-proxies"), failure.getMessage());

        // 声明了 Tomcat 的可信范围，但没有声明运维视角的可信代理地址 → 边界无法核对。
        MockEnvironment undeclared = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.5");
        undeclared.setActiveProfiles("prod");
        IllegalStateException undeclaredFailure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(undeclared).verify());
        assertTrue(undeclaredFailure.getMessage().contains("trusted-proxy-addresses"),
                undeclaredFailure.getMessage());

        // 声明与 Tomcat 的可信范围一致 → 放行。
        MockEnvironment bounded = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.5")
                .withProperty("eye-diagnosis.news-ingestion.trusted-proxy-addresses[0]", "10.0.0.5");
        bounded.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new StartupSafetyGuard(bounded).verify());
    }

    @Test
    void newsIngestionRejectsTrustedProxyRangeBroaderThanDeclared() {
        // Tomcat 信任整个 10/8，运维只声明了一台反代 → 同网段主机都能伪造 X-Forwarded-Proto。
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                .withProperty("eye-diagnosis.news-ingestion.trusted-proxy-addresses[0]", "10.0.0.5");
        env.setActiveProfiles("prod");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        assertTrue(failure.getMessage().contains("信任范围比声明宽"), failure.getMessage());
    }

    @Test
    void newsIngestionRejectsDeclaredProxyOutsideTomcatTrust() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-ingestion.enabled", "true")
                .withProperty("eye-diagnosis.news-ingestion.require-https", "true")
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "k9X2pL8mQ4vR7tW1zN6bC3fH5jD0sA2e")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.5")
                .withProperty("eye-diagnosis.news-ingestion.trusted-proxy-addresses[0]", "10.0.0.6");
        env.setActiveProfiles("prod");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        assertTrue(failure.getMessage().contains("不在 server.tomcat.remoteip.internal-proxies 的信任范围内"),
                failure.getMessage());
    }

    @Test
    void newsCurationDisabledPassesWithoutBudgetConfig() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-curation.enabled", "false");
        env.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }

    @Test
    void newsCurationEnabledWithMissingOrZeroBudgetsBlocksStartup() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-curation.enabled", "true")
                .withProperty("eye-diagnosis.news-curation.daily-max-calls", "0")
                .withProperty("eye-diagnosis.news-curation.daily-max-tokens", "0")
                .withProperty("eye-diagnosis.news-curation.daily-max-cost-micros", "-1");
        env.setActiveProfiles("server");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupSafetyGuard(env).verify());
        String msg = failure.getMessage();
        assertTrue(msg.contains("daily-max-calls"), msg);
        assertTrue(msg.contains("daily-max-tokens"), msg);
        assertTrue(msg.contains("daily-max-cost-micros"), msg);
        assertTrue(msg.contains("policy-version"), msg);
        assertTrue(msg.contains("model-name"), msg);
    }

    @Test
    void newsCurationEnabledWithValidCeilingsAndMetadataPasses() {
        MockEnvironment env = deployment()
                .withProperty("eye-diagnosis.news-curation.enabled", "true")
                .withProperty("eye-diagnosis.news-curation.daily-max-calls", "100")
                .withProperty("eye-diagnosis.news-curation.daily-max-tokens", "500000")
                .withProperty("eye-diagnosis.news-curation.daily-max-cost-micros", "5000000")
                .withProperty("eye-diagnosis.news-curation.policy-version", "news-curation-v1")
                .withProperty("eye-diagnosis.news-curation.model-name", "qwen3.5-plus");
        env.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new StartupSafetyGuard(env).verify());
    }
}
