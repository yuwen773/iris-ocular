package com.csust.eyediagnosis.newscuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.csust.eyediagnosis.config.JsonConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-08B.2：确定性建稿模板的契约测试。
 *
 * <p>这里锁住三件事：冻结的结构化内容被**完整**搬进正文（Insight 的问题/设计/发现/意义、
 * Radar 的简介/值得关注、Trial Watch 的登记状态/阶段/变化），标题与摘要来自冻结字段而不是另一次模型调用，
 * 以及模型文本永远只能以转义后的文本出现。</p>
 */
class NewsEditorialDraftTest {

    private final ObjectMapper mapper = JsonConfig.createNewsProtocolObjectMapper();

    @Test
    void insightKeepsEveryVerifiedSectionInChinese() throws Exception {
        JsonNode sections = sections("""
                {"question":"深度学习筛查在真实世界队列中能否稳定识别需转诊的视网膜病变？",
                 "studyDesign":"多中心前瞻性观察研究。",
                 "findings":"研究报告了较高的筛查敏感度，仍需外部验证。",
                 "meaning":"为基层筛查评估提供了可复用的路径。",
                 "limitations":"未评估长期结局。",
                 "unknownField":"不应出现在正文里"}
                """);

        NewsEditorialDraft.Draft draft = NewsEditorialDraft.render(insightInput(sections));

        assertEquals("RESEARCH_INSIGHT", draft.contentType());
        assertEquals("研究解析：深度学习筛查在真实世界队列中能否稳定识别需转诊的视网膜病变", draft.title());
        assertEquals("研究报告了较高的筛查敏感度，仍需外部验证。", draft.summary());
        String html = draft.html();
        assertTrue(html.contains("深度学习筛查在真实世界队列中能否稳定识别需转诊的视网膜病变？"), html);
        assertTrue(html.contains("<h2>研究设计</h2>"), html);
        assertTrue(html.contains("多中心前瞻性观察研究。"), html);
        assertTrue(html.contains("<h2>主要发现</h2>"), html);
        assertTrue(html.contains("<h2>临床意义</h2>"), html);
        assertTrue(html.contains("<h2>局限与边界</h2>"), html);
        assertTrue(html.contains("未评估长期结局。"), html);
        assertTrue(html.contains("<h2>证据要点</h2>"), html);
        assertTrue(html.contains("Open-access multicenter ophthalmic abstract"), html);
        assertTrue(html.contains("内容类型：</strong>研究解析（RESEARCH_INSIGHT）"), html);
        assertTrue(html.contains("相关性评分：</strong>84/100"), html);
        assertTrue(html.contains("研究价值评分：</strong>71/100"), html);
        assertTrue(html.contains("call-1（生产命名空间 · 联合精选 P05）"), html);
        assertTrue(html.contains("evidence-sha"), html);
        assertTrue(html.contains("pubmed / PMID-1"), html);
        assertTrue(html.contains("https://pubmed.ncbi.nlm.nih.gov/42000001/"), html);
        assertFalse(html.contains("不应出现在正文里"), "未知章节键不得进入正文");
    }

    @Test
    void radarKeepsIntroductionAndReasonToWatch() throws Exception {
        JsonNode sections = sections("""
                {"introduction":"本期雷达聚焦真实世界筛查队列的随访设计。",
                 "reasonToWatch":"样本量足够大，可能在下一轮指南讨论中被引用。",
                 "limitations":"仅登记阶段性结果。"}
                """);

        NewsEditorialDraft.Draft draft = NewsEditorialDraft.render(new NewsEditorialDraft.Input(
                "RESEARCH_RADAR", sections, List.of(), List.of("未披露资金来源。"), "pubmed", "PMID-2",
                "https://pubmed.ncbi.nlm.nih.gov/42000002/", "Radar material title", 60, 55, "call-2", "sha-2"));

        assertEquals("研究雷达：本期雷达聚焦真实世界筛查队列的随访设计", draft.title());
        assertTrue(draft.html().contains("<h2>值得关注的地方</h2>"), draft.html());
        assertTrue(draft.html().contains("样本量足够大，可能在下一轮指南讨论中被引用。"), draft.html());
        assertTrue(draft.html().contains("<h2>补充局限</h2>"), draft.html());
        assertTrue(draft.html().contains("未披露资金来源。"), draft.html());
    }

    @Test
    void trialWatchMapsRegistryFactsToChinese() throws Exception {
        JsonNode sections = sections("""
                {"status":"RECRUITING",
                 "studyDesign":"INTERVENTIONAL",
                 "phases":["PHASE2","PHASE3"],
                 "changes":["STATUS","PHASES"],
                 "limitations":"仅登记信息，未提供结果。"}
                """);

        NewsEditorialDraft.Draft draft = NewsEditorialDraft.render(new NewsEditorialDraft.Input(
                "TRIAL_WATCH", sections, List.of(), List.of(), "clinicaltrials-gov", "NCT-1",
                "https://clinicaltrials.gov/study/NCT00000001", "Trial title", 70, 65, "call-3", "sha-3"));

        assertEquals("试验观察：招募中｜二期、三期｜干预性研究", draft.title());
        assertEquals("招募中，研究设计为干预性研究，阶段：二期、三期。", draft.summary());
        String html = draft.html();
        assertTrue(html.contains("<h2>登记状态</h2>"), html);
        assertTrue(html.contains("状态：</strong>招募中"), html);
        assertTrue(html.contains("试验阶段：</strong>二期、三期"), html);
        assertTrue(html.contains("研究设计：</strong>干预性研究"), html);
        assertTrue(html.contains("本次变化：</strong>试验状态变化、试验阶段变化"), html);
    }

    @Test
    void modelTextIsEscapedAndNeverBecomesMarkup() throws Exception {
        JsonNode sections = sections("""
                {"question":"<script>alert(1)</script>",
                 "findings":"<img src=x onerror=alert(1)>",
                 "studyDesign":"正常文本",
                 "meaning":"正常文本",
                 "limitations":"正常文本"}
                """);

        NewsEditorialDraft.Draft draft = NewsEditorialDraft.render(insightInput(sections));

        // 带标记的模型文本不能做标题：回退到中文框架 + 冻结资料标题，并且标题里永远不会有标记。
        assertTrue(draft.title().startsWith("研究解析："), draft.title());
        assertFalse(draft.title().contains("<"), draft.title());
        assertFalse(draft.html().contains("<script>"), draft.html());
        assertFalse(draft.html().contains("<img"), draft.html());
        assertTrue(draft.html().contains("&lt;script&gt;"), draft.html());
    }

    @Test
    void titleContractRejectsMarkupControlAndNonChinese() {
        assertEquals("DRAFT_TITLE_EMPTY",
                assertThrows(IllegalArgumentException.class, () -> NewsEditorialDraft.validateTitle("   ")).getMessage());
        assertEquals("DRAFT_TITLE_MARKUP",
                assertThrows(IllegalArgumentException.class,
                        () -> NewsEditorialDraft.validateTitle("研究解析：<b>标题</b>")).getMessage());
        assertEquals("DRAFT_TITLE_CONTROL_CHAR",
                assertThrows(IllegalArgumentException.class,
                        () -> NewsEditorialDraft.validateTitle("研究解析：换行\n标题")).getMessage());
        assertEquals("DRAFT_TITLE_NOT_CHINESE",
                assertThrows(IllegalArgumentException.class,
                        () -> NewsEditorialDraft.validateTitle("Research insight only")).getMessage());
        assertEquals("DRAFT_TITLE_TOO_LONG",
                assertThrows(IllegalArgumentException.class,
                        () -> NewsEditorialDraft.validateTitle("研究".repeat(80))).getMessage());
    }

    @Test
    void unknownContentTypeIsRefusedInsteadOfRenderingSomethingElse() throws Exception {
        JsonNode sections = sections("{\"question\":\"研究问题？\"}");
        assertEquals("DRAFT_CONTENT_TYPE_UNKNOWN", assertThrows(IllegalArgumentException.class,
                () -> NewsEditorialDraft.render(new NewsEditorialDraft.Input("PRESS_RELEASE", sections,
                        List.of(), List.of(), "pubmed", "PMID-9", null, null, null, null, null, null)))
                .getMessage());
        assertEquals("DRAFT_SECTIONS_MISSING", assertThrows(IllegalArgumentException.class,
                () -> NewsEditorialDraft.render(new NewsEditorialDraft.Input("RESEARCH_INSIGHT", null,
                        List.of(), List.of(), "pubmed", "PMID-9", null, null, null, null, null, null)))
                .getMessage());
    }

    @Test
    void longTitleIsTruncatedOnCodePointBoundaryAndStaysPublishable() throws Exception {
        String longQuestion = "在真实世界队列中评估深度学习筛查能否稳定识别需要转诊的视网膜病变并减少漏诊".repeat(6);
        JsonNode sections = sections("{\"question\":\"" + longQuestion + "\",\"findings\":\"结论。\"}");

        String title = NewsEditorialDraft.render(insightInput(sections)).title();

        assertTrue(title.startsWith("研究解析："), title);
        assertTrue(title.endsWith("…"), title);
        assertTrue(title.codePointCount(0, title.length()) <= NewsEditorialDraft.TITLE_MAX, title);
        assertTrue(title.contains("研究"), title);
    }

    @Test
    void decimalNumbersAreNotTreatedAsSentenceEnds() throws Exception {
        JsonNode sections = sections("""
                {"question":"深度学习筛查的 AUC 0.93，能否在外部队列复现？",
                 "studyDesign":"回顾性队列。",
                 "findings":"研究报告的 AUC 为 0.93. 下一步是外部验证。",
                 "meaning":"提示需要前瞻性研究。",
                 "limitations":"单中心。"}
                """);

        NewsEditorialDraft.Draft draft = NewsEditorialDraft.render(insightInput(sections));

        // 小数点后若被当成句末，标题与摘要会印出「AUC 0」这种错误的数字。
        assertEquals("研究解析：深度学习筛查的 AUC 0.93，能否在外部队列复现", draft.title());
        assertEquals("研究报告的 AUC 为 0.93.", draft.summary());
        assertTrue(draft.html().contains("AUC 为 0.93. 下一步是外部验证。"), draft.html());
    }

    @Test
    void trialRegistryOnlyMarkerBecomesChineseAndIsNotDuplicated() throws Exception {
        JsonNode sections = sections("""
                {"status":"RECRUITING",
                 "studyDesign":"OBSERVATIONAL",
                 "phases":["NA"],
                 "changes":["INITIAL"],
                 "limitations":"TRIAL_REGISTRY_ONLY"}
                """);

        // 建稿时 limitationsJson 与章节里的限制是同一条：渲染只能出现一次，且不能印内部标记。
        NewsEditorialDraft.Draft draft = NewsEditorialDraft.render(new NewsEditorialDraft.Input(
                "TRIAL_WATCH", sections, List.of(), List.of("TRIAL_REGISTRY_ONLY"), "clinicaltrials-gov",
                "NCT-2", null, "Trial title", 70, 65, "call-4", "sha-4"));

        String html = draft.html();
        assertFalse(html.contains("TRIAL_REGISTRY_ONLY"), html);
        assertEquals(1, occurrences(html, "以下内容仅来自试验登记信息，未包含研究者公开的结果数据。"), html);
        assertFalse(html.contains("<h2>补充局限</h2>"), "同一限制不得重复成两节");
        assertTrue(html.contains("本次变化：</strong>首次收录"), html);
        assertEquals("试验观察：招募中｜不适用｜观察性研究", draft.title());
    }

    @Test
    void oversizedContentIsTruncatedInsideTheTextColumnBudget() throws Exception {
        // 契约允许的章节长度叠加后足以超过 TEXT 的 64 KB：截断必须确定性地发生在块边界上。
        String huge = "正文段落".repeat(12_000);
        JsonNode sections = sections("""
                {"question":"研究问题？",
                 "studyDesign":"研究设计。",
                 "findings":"%s",
                 "meaning":"临床意义。",
                 "limitations":"局限。"}
                """.formatted(huge));

        String html = NewsEditorialDraft.render(insightInput(sections)).html();

        assertTrue(html.contains("正文超出单篇长度上限"), html.substring(0, Math.min(200, html.length())));
        assertTrue(html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 65_535,
                "截断后必须还能写进 t_news.content（TEXT，64 KB）");
        assertFalse(html.contains(huge), "超长章节不得整段写入");
    }

    @Test
    void claimListIsCappedAndEachClaimIsBounded() throws Exception {
        List<NewsCurationModels.CurationClaim> claims = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            claims.add(new NewsCurationModels.CurationClaim("c" + i, "abstract",
                    "要点" + i + "。", "引文" + i + "。"));
        }
        claims.set(0, new NewsCurationModels.CurationClaim("c0", "abstract", "长".repeat(500), "引文。"));
        JsonNode sections = sections("{\"question\":\"研究问题？\",\"findings\":\"结论。\"}");

        String html = NewsEditorialDraft.render(new NewsEditorialDraft.Input(
                "RESEARCH_INSIGHT", sections, claims, List.of(), "pubmed", "PMID-3", null,
                "Material title", 80, 70, "call-5", "sha-5")).html();

        assertEquals(8, occurrences(html, "<li><strong>["), "正文最多展开 8 条证据要点");
        assertTrue(html.contains("另有 4 条证据要点未展开"), html);
        assertFalse(html.contains("要点9"), html);
        assertTrue(html.contains("长".repeat(200) + "…"), "单条陈述必须截断到 200 字");
        assertFalse(html.contains("长".repeat(201)), html);
    }

    private NewsEditorialDraft.Input insightInput(JsonNode sections) {
        return new NewsEditorialDraft.Input("RESEARCH_INSIGHT", sections,
                List.of(new NewsCurationModels.CurationClaim("c1", "abstract",
                        "公开摘要记录了筛查评估结果。", "Open-access multicenter ophthalmic abstract")),
                List.of("未评估长期结局。"), "pubmed", "PMID-1",
                "https://pubmed.ncbi.nlm.nih.gov/42000001/", "Material title", 84, 71, "call-1", "evidence-sha");
    }

    private JsonNode sections(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
