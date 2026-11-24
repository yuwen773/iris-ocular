package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 offline NewsBench acceptance gate running the frozen {@code contracts/news/newsbench/v1.dataset.json}
 * benchmark without paid network calls.
 */
class NewsBenchOfflineTest {

    private static final Path DATASET = Path.of("contracts/news/newsbench/v1.dataset.json");

    @Test
    void offlineNewsBenchDatasetPassesAllTriageLicenseInjectionAndGroundingGates() {
        NewsCurationProperties properties = new NewsCurationProperties();
        properties.setEnabled(true);
        properties.setPolicyVersion("news-curation-v1");
        ObjectMapper mapper = JsonConfig.createNewsProtocolObjectMapper();

        NewsBenchRunner runner = NewsBenchRunner.offline(properties, mapper);
        NewsCurationModels.NewsBenchReport report = runner.runOfflineFixture(DATASET, "news-curation-v1");

        assertEquals("newsbench-v1.0.0", report.datasetVersion());
        assertEquals(18, report.totalCases());
        assertEquals(18, report.passedCases(), () -> "Failed NewsBench cases: "
                + report.caseResults().stream().filter(r -> !r.passed()).toList());
        assertEquals(10_000, report.precisionBps());
        assertEquals(10_000, report.recallBps());
        assertEquals(10_000, report.evidenceCatchBps());
        assertEquals(10_000, report.licenseComplianceBps());
        assertEquals(10_000, report.injectionCatchBps());
        assertEquals(0, report.totalCalls(), "Offline L1 run must consume zero paid calls");
        assertTrue(report.gatePassed());
    }
}
