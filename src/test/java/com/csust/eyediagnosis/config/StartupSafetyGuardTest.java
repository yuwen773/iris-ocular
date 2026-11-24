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
                .withProperty("eye-diagnosis.news-ingestion.workers.w1.key", "fixture_worker_key_123456");
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
