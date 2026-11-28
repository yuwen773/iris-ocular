package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.EvidenceSnapshot;
import com.csust.eyediagnosis.newscuration.NewsEditorialEvidence.ContentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NewsModelProtocolTest {

    private final ObjectMapper mapper = JsonConfig.createNewsProtocolObjectMapper();
    private final NewsModelProtocol protocol = new NewsModelProtocol(mapper);

    @Test
    void openLicenseDoesNotProvePeerReviewOrUnlockInsight() {
        var evidence = NewsEditorialEvidence.from(snapshot("pubmed", "Glaucoma methods", "Glaucoma trial methods", "CC-BY", null), mapper);
        assertEquals(NewsEditorialEvidence.PeerReview.UNKNOWN, evidence.peerReview());
        assertEquals(NewsEditorialEvidence.Integrity.UNKNOWN, evidence.integrity());
        assertEquals(NewsEditorialEvidence.ContentScope.LICENSED_ABSTRACT, evidence.contentScope());
        assertEquals(ContentType.RESEARCH_RADAR, evidence.route(null, true));
        assertEquals(ContentType.SKIP, evidence.route(null, false));
    }

    @Test
    void theRegistryRoutesOnlyAnActualTrackedChange() {
        var recruiting = NewsEditorialEvidence.from(snapshot("clinicaltrials-gov", "Trial", null, null,
                "{\"overallStatus\":\"RECRUITING\",\"phases\":[\"PHASE4\"],\"studyType\":\"INTERVENTIONAL\"}"), mapper);
        var completed = NewsEditorialEvidence.from(snapshot("clinicaltrials-gov", "Trial", null, null,
                "{\"overallStatus\":\"COMPLETED\",\"phases\":[\"PHASE4\"],\"studyType\":\"INTERVENTIONAL\"}"), mapper);
        assertEquals(ContentType.TRIAL_WATCH, completed.route(recruiting, true));
        assertEquals(ContentType.SKIP, completed.route(completed, true));
    }

    @Test
    void trialWatchRequiresTheRegistryFactsWithoutInferredOutcomes() {
        var snapshot = snapshot("clinicaltrials-gov", "Registered trial", null, null,
                "{\"overallStatus\":\"RECRUITING\",\"phases\":[\"PHASE1\"],\"studyType\":\"INTERVENTIONAL\"}");
        var selected = new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P04, "qwen", null);
        var identity = protocol.prepare(selected, profile("https://dashscope.aliyuncs.com", "qwen-flash"),
                "v1", snapshot, null, null).identity();
        String watch = """
                {"contentType":"TRIAL_WATCH","sections":{"status":"RECRUITING","phases":["PHASE1"],
                 "studyDesign":"INTERVENTIONAL","changes":["INITIAL"],"limitations":"TRIAL_REGISTRY_ONLY"},
                 "claims":[{"evidenceId":"trial","statement":"RECRUITING","quote":"RECRUITING"}]}
                """;
        assertNotNull(protocol.validatedOutput(watch, identity, snapshot, 2048));
        assertNull(protocol.validatedOutput(watch.replace("\"status\":\"RECRUITING\"", "\"status\":\"COMPLETED\""), identity, snapshot, 2048));
        assertNull(protocol.validatedOutput(watch.replace("PHASE1", "PHASE4"), identity, snapshot, 2048));
        assertNull(protocol.validatedOutput(watch.replace("\"statement\":\"RECRUITING\"", "\"statement\":\"使失明风险减半\""), identity, snapshot, 2048));
    }

    @Test
    void freezesPromptAndConfigSeparatelyAndRejectsInvalidStructuredOutput() {
        var snapshot = snapshot("pubmed", "Glaucoma cohort", null, null, null);
        var profile = profile("https://dashscope.aliyuncs.com", "qwen-flash");
        var selection = new NewsModelProtocol.Selection("experiment.A", NewsModelProtocol.Prompt.P01, "qwen", null);
        var first = protocol.prepare(selection, profile, "v1", snapshot, null, null);
        var second = protocol.prepare(selection, profile("https://dashscope.aliyuncs.com", "qwen-plus"), "v1", snapshot, null, null);
        assertEquals("wp08b-v1", first.identity().promptVersion());
        assertEquals(first.identity().promptSha256(), second.identity().promptSha256());
        assertNotEquals(first.identity().configurationSha256(), second.identity().configurationSha256());
        assertNotEquals(first.logicalCallSha256(), second.logicalCallSha256());
        assertEquals(first.logicalCallSha256(), protocol.prepare(selection, profile, "v1", snapshot, null, null).logicalCallSha256());
        String scoring = """
                {"relevanceScore":90,"researchValueScore":null,"recommendedType":"RESEARCH_INSIGHT","rationale":"Glaucoma cohort"}
                """;
        assertNotNull(protocol.validatedOutput(scoring, first.identity(), snapshot, 2048));
        assertNull(protocol.validatedOutput(scoring.replace("90", "101"), first.identity(), snapshot, 2048));
        assertNull(protocol.validatedOutput(scoring.replace("Glaucoma cohort", "<img src=x>"), first.identity(), snapshot, 2048));
        assertNull(protocol.validatedOutput(scoring.replace("Glaucoma cohort", "https://arbitrary.invalid"), first.identity(), snapshot, 2048));
    }

    @Test
    void onlyDeploymentApprovedHttpsOriginsAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> profile("https://dashscope.aliyuncs.com.attacker.invalid", "qwen-flash"));
        assertThrows(IllegalArgumentException.class, () -> profile("http://dashscope.aliyuncs.com", "qwen-flash"));
        assertThrows(IllegalArgumentException.class, () -> profile("https://hidden@dashscope.aliyuncs.com", "qwen-flash"));
    }

    private static NewsModelProfile profile(String origin, String model) {
        return new NewsModelProfile(NewsModelProfile.Provider.QWEN, NewsModelProfile.Platform.BAILIAN,
                URI.create(origin), "/compatible-mode/v1/chat/completions", model, "IRIS_NEWS_FIXTURE",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 8192, 1024, 0,
                NewsModelProfile.Format.JSON_OBJECT, new NewsModelProfile.Capabilities(
                        true, false, NewsModelProfile.UsageFormat.STANDARD, false),
                new NewsModelProfile.PricingVersion("fixture-v1", 1_000_000, 1_000_000, 2_000_000));
    }

    private static EvidenceSnapshot snapshot(String source, String title, String text, String license, String trial) {
        return new EvidenceSnapshot(1, 2, source, "fixture-id", "a".repeat(64),
                NewsCurationModels.EvidenceTier.METADATA_ONLY, "https://example.invalid", title, null, null,
                null, null, null, text, license, trial);
    }
}
