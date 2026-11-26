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
}
