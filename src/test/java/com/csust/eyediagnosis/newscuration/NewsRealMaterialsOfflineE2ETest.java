package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.EvidenceSnapshot;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.EvidenceTier;
import com.csust.eyediagnosis.newscuration.NewsEditorialEvidence.ContentType;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Active;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Attribute;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Method;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Overlay;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-08B.2 离线端到端：十份真实公开资料的冻结快照 + 人工核验记录，走同一条证据路由与结构化契约。
 *
 * <p>资料于 2026-11-26 取自 Europe PMC 公开记录（含 CC BY 摘要）与 WHO ICTRP 对 ClinicalTrials.gov
 * 的镜像记录；本测试不联网，只回放已固化的快照。模型输出是脚本化的（{@code scriptedOutput}），
 * 不是真实供应商调用结果。断言只关注：证据决定路由、契约拒收越界输出、没有核验就升不到 Research Insight。</p>
 */
class NewsRealMaterialsOfflineE2ETest {

    private static final String FIXTURE = "/news/curation/b2/real-materials.json";
    private static final Instant VERIFIED_AT = Instant.parse("2026-11-26T08:00:00Z");

    private final ObjectMapper mapper = JsonConfig.createNewsProtocolObjectMapper();
    private final NewsModelProtocol protocol = new NewsModelProtocol(mapper);

    @Test
    void tenCapturedMaterialsCoverEveryEditorialCategoryAndRouteByEvidenceAlone() throws Exception {
        JsonNode materials = load().path("materials");
        assertEquals(10, materials.size());
        Set<String> categories = new LinkedHashSet<>();
        Set<String> routes = new LinkedHashSet<>();

        int insights = 0;
        for (int index = 0; index < materials.size(); index++) {
            JsonNode material = materials.get(index);
            categories.add(material.path("category").asText());
            routes.add(material.path("expectedRoute").asText());
            EvidenceSnapshot snapshot = snapshot(material, index);
            ContentType expected = ContentType.valueOf(material.path("expectedRoute").asText());

            Overlay overlay = overlay(material, index);
            ContentType routed = NewsEditorialEvidence.from(snapshot, mapper, overlay).route(null, true);
            assertEquals(expected, routed, material.path("id").asText());
            assertEquals(ContentType.SKIP,
                    NewsEditorialEvidence.from(snapshot, mapper, overlay).route(null, false),
                    material.path("id").asText() + " must stay SKIP when the evidence gate fails");

            if (expected == ContentType.RESEARCH_INSIGHT) {
                insights++;
                // Without the reviewer rows the very same record can only be a Radar item.
                assertEquals(ContentType.RESEARCH_RADAR,
                        NewsEditorialEvidence.from(snapshot, mapper).route(null, true),
                        material.path("id").asText() + " must not reach Insight unverified");
                assertEquals(4, overlay.attributes().size());
            }
        }

        assertEquals(Set.of("RESEARCH_INSIGHT", "RESEARCH_RADAR", "TRIAL_WATCH", "RETRACTED", "METADATA_ONLY",
                "NOT_RELEVANT"), categories);
        assertEquals(Set.of("RESEARCH_INSIGHT", "RESEARCH_RADAR", "TRIAL_WATCH", "SKIP"), routes);
        assertEquals(3, insights);
    }

    @Test
    void scriptedOutputsPassTheFrozenContractAndRefusalsCarryNoContent() throws Exception {
        JsonNode materials = load().path("materials");
        for (int index = 0; index < materials.size(); index++) {
            JsonNode material = materials.get(index);
            String id = material.path("id").asText();
            EvidenceSnapshot snapshot = snapshot(material, index);
            Overlay overlay = overlay(material, index);
            var prepared = protocol.prepare(new NewsModelProtocol.Selection(
                            "production", NewsModelProtocol.Prompt.P05, "fixture", null),
                    fixtureProfile(), "news-curation-2026-11", snapshot, null, null, overlay);
            assertEquals(ContentType.valueOf(material.path("expectedRoute").asText()),
                    prepared.identity().eligibleType(), id);

            JsonNode scripted = material.path("scriptedOutput");
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("relevanceScore", 72);
            envelope.putNull("researchValueScore");
            envelope.put("recommendedType", scripted.isObject()
                    ? scripted.path("contentType").asText() : "SKIP");
            envelope.put("rationale", "clinicaltrials-gov".equals(snapshot.source())
                    ? "TRIAL_REGISTRY_ONLY" : "离线回放脚本输出，用于契约校验。");
            if (scripted.isObject()) {
                envelope.set("content", scripted);
            } else {
                envelope.putNull("content");
            }
            String raw = mapper.writeValueAsString(envelope);
            assertNotNull(protocol.validatedOutput(raw, prepared.identity(), snapshot, 8192), id);

            if (!scripted.isObject()) {
                assertTrue(raw.contains("\"content\":null"), id);
                assertNull(protocol.validatedOutput(raw.replace("\"content\":null", "\"content\":{\"contentType\":\"RESEARCH_RADAR\",\"sections\":{\"introduction\":\"x\",\"reasonToWatch\":\"y\",\"limitations\":\"z\"},\"claims\":[{\"evidenceId\":\"title\",\"statement\":\"t\",\"quote\":\"q\"}]}"),
                        prepared.identity(), snapshot, 8192));
            }
        }
    }

    private JsonNode load() throws Exception {
        try (InputStream stream = NewsRealMaterialsOfflineE2ETest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(stream, "missing fixture " + FIXTURE);
            return mapper.readTree(stream);
        }
    }

    private EvidenceSnapshot snapshot(JsonNode material, int index) {
        JsonNode row = material.path("material");
        String license = row.path("abstractLicense").isTextual() ? row.path("abstractLicense").asText() : null;
        boolean licensed = license != null && !row.path("abstract").asText("").isBlank();
        boolean registry = "clinicaltrials-gov".equals(row.path("source").asText());
        EvidenceTier tier = registry ? EvidenceTier.CLINICAL_TRIAL_REGISTRY
                : licensed ? EvidenceTier.LICENSED_ABSTRACT : EvidenceTier.METADATA_ONLY;
        return new EvidenceSnapshot(
                1000L + index,
                2000L + index,
                row.path("source").asText(),
                row.path("sourceRecordId").asText(),
                NewsMaterialCanonicalizer.sha256Hex(material.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                tier,
                row.path("url").isTextual() ? row.path("url").asText() : "https://example.invalid/" + index,
                row.path("title").asText(),
                null,
                row.path("publishedOn").isTextual() ? row.path("publishedOn").asText() : null,
                null,
                row.path("pmid").isTextual() ? row.path("pmid").asText() : null,
                row.path("doi").isTextual() ? row.path("doi").asText() : null,
                licensed ? row.path("abstract").asText() : null,
                licensed ? license : null,
                row.path("trial").isObject() ? trialJson(row) : null);
    }

    private String trialJson(JsonNode row) {
        try {
            return mapper.writeValueAsString(row.path("trial"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Overlay overlay(JsonNode material, int index) {
        long revisionId = 2000L + index;
        List<Active> actives = new ArrayList<>();
        for (JsonNode verification : material.path("verifications")) {
            Request request = new Request(
                    Attribute.valueOf(verification.path("attribute").asText()),
                    verification.path("value").asText(),
                    Method.valueOf(verification.path("method").asText()),
                    verification.path("evidenceUrl").asText(),
                    verification.path("sourceVersion").asText(),
                    verification.path("note").isTextual() ? verification.path("note").asText() : null);
            Request validated = NewsEvidenceLedger.validate(request, snapshot(material, index));
            actives.add(new Active(validated.attribute(), validated.value(), validated.method(),
                    validated.evidenceUrl(), validated.sourceVersion(), "admin:1001", VERIFIED_AT));
        }
        return Overlay.of(revisionId, actives);
    }

    private static NewsModelProfile fixtureProfile() {
        return new NewsModelProfile(NewsModelProfile.Provider.QWEN, NewsModelProfile.Platform.BAILIAN,
                URI.create("https://dashscope.aliyuncs.com"), "/compatible-mode/v1/chat/completions",
                "fixture-model", "FIXTURE_KEY", Duration.ofSeconds(2), Duration.ofSeconds(5), 8192, 1024, 0,
                NewsModelProfile.Format.JSON_OBJECT, new NewsModelProfile.Capabilities(
                        true, false, NewsModelProfile.UsageFormat.STANDARD, false),
                new NewsModelProfile.PricingVersion("fixture-v1", 1_000_000, 1_000_000, 2_000_000));
    }
}
