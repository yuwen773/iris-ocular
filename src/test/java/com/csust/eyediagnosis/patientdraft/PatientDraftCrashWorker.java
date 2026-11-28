package com.csust.eyediagnosis.patientdraft;

import com.csust.eyediagnosis.controlledreference.ControlledEvidenceRetriever;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate JVM dies after the coordinator durably reserves its first provider call. */
public class PatientDraftCrashWorker {
    public static void main(String[] args) {
        String url = LegacyGateSupport.requireIsolated(System.getenv("ISSUE19_TEST_URL"), "ISSUE19_TEST_URL", "issue19_test");
        String[] credentials = LegacyGateSupport.credentials(url);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(LegacyGateSupport.jdbcUrl(url), credentials[0], credentials[1]));
        var entered = new java.util.concurrent.CountDownLatch(2);
        ChatModel haltedProvider = prompt -> {
            entered.countDown();
            try { if (!entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Both calls must be reserved"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            Runtime.getRuntime().halt(23); return null;
        };
        new PatientDraftCoordinator(jdbc, new ControlledEvidenceRetriever(jdbc), haltedProvider).run(args[0]);
        throw new AssertionError("The provider must have terminated this process");
    }
}
