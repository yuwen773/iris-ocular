package com.csust.eyediagnosis.collaboration;

import com.csust.eyediagnosis.controlledreference.ControlledEvidenceRetriever;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import com.csust.eyediagnosis.patientdraft.PatientDraftCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A separate JVM can replay durable facts and decrypt the cursor without calling any model. */
public class CollaborationReplayWorker {
    public static void main(String[] args) throws Exception {
        String url = LegacyGateSupport.requireIsolated(System.getenv("COLLABORATION_TEST_URL"), "COLLABORATION_TEST_URL", "collaboration_test");
        String[] credentials = LegacyGateSupport.credentials(url);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(LegacyGateSupport.jdbcUrl(url), credentials[0], credentials[1]));
        try (var coordinator = new PatientDraftCoordinator(jdbc, new ControlledEvidenceRetriever(jdbc), prompt -> { throw new AssertionError("Replay must not call the model"); });
             var runs = new CollaborationRunService(jdbc, new CollaborationEvents(jdbc), new CollaborationCursor("03".repeat(32)), coordinator)) {
            CollaborationRunTest.enableFixture(runs);
            var subscription = runs.subscribe(args[0], 42, args[1], null);
            var batch = runs.read(subscription, subscription.sequence());
            System.out.println("TASK03_REPLAY=" + new ObjectMapper().findAndRegisterModules().writeValueAsString(batch.frames().stream().map(CollaborationRunService.Frame::event).toList()));
        }
    }
}
