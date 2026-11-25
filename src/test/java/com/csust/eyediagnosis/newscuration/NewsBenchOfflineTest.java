package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 负例：把对抗/许可/注入三类样本整类删掉、只留两个能过的 TRIAGE 样本，
     * 指标全是「无样本 = 10000」，但门禁必须仍判失败——否则换一个小数据集就能拿到绿灯。
     */
    @Test
    void gateFailsWhenAdversarialLicenseOrInjectionCategoriesHaveNoSamples(@TempDir Path tempDir)
            throws Exception {
        NewsCurationProperties properties = new NewsCurationProperties();
        properties.setEnabled(true);
        properties.setPolicyVersion("news-curation-v1");
        ObjectMapper mapper = JsonConfig.createNewsProtocolObjectMapper();

        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(DATASET.toFile());
        var triageOnly = mapper.createArrayNode();
        for (var node : root.get("cases")) {
            if (triageOnly.size() >= 2) {
                break;
            }
            if ("TRIAGE".equals(node.get("kind").textValue())) {
                triageOnly.add(node);
            }
        }
        root.put("datasetVersion", "newsbench-vacuous");
        root.set("cases", triageOnly);
        Path dataset = tempDir.resolve("vacuous.dataset.json");
        java.nio.file.Files.write(dataset, mapper.writeValueAsBytes(root));

        NewsCurationModels.NewsBenchReport report =
                NewsBenchRunner.offline(properties, mapper).runOfflineFixture(dataset, "news-curation-v1");

        assertEquals(2, report.totalCases());
        assertEquals(2, report.passedCases(), () -> "两个 TRIAGE 样本应自身通过：" + report.caseResults());
        assertEquals(10_000, report.licenseComplianceBps(), "无样本时指标记为不适用");
        assertFalse(report.gatePassed(), "缺少对抗/许可/注入样本时门禁必须失败");
    }
}
