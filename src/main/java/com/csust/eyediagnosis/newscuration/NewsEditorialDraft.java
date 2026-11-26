package com.csust.eyediagnosis.newscuration;

import cn.hutool.http.HtmlUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WP-08B.2：把已经通过契约校验的冻结结构化内容，确定性地排成中文稿件。
 *
 * <p>这里只做「排版」，不做「判断」：章节顺序、中文小标题、试验状态/阶段/变化的中文映射都是固定的，
 * 所有模型文本逐段转义后拼进 {@code <p>}/{@code <li>} 里，模型永远不能直接产出可执行标记。
 * 同一份冻结输出在任何时候渲染出的标题、正文与摘要都完全相同——草稿可以复算，人工审核看到的就是
 * 当时那一次付费调用所冻结的内容。</p>
 *
 * <p>未知字段一律忽略：渲染只读取已知章节键，模型多写的字段不会进入正文。</p>
 */
public final class NewsEditorialDraft {

    /** 标题长度上限；与 {@code t_news.title} 的列宽保持余量。 */
    public static final int TITLE_MAX = 120;
    /** 摘要长度上限；与既有 {@code resolveCandidateSummary} 的 512 保持一致。 */
    public static final int SUMMARY_MAX = 512;
    /**
     * 写进 {@code t_news_curation_candidate.draftTemplateVersion} 的版本标记。为 {@code null} 的旧候选
     * 仍走旧版拼接建稿，行为不变。
     */
    public static final String TEMPLATE_VERSION = "deterministic-v1";

    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final Pattern MARKUP = Pattern.compile("[<>]");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private static final Map<String, String> TRIAL_STATUS = Map.ofEntries(
            Map.entry("NOT_YET_RECRUITING", "尚未招募"),
            Map.entry("RECRUITING", "招募中"),
            Map.entry("ENROLLING_BY_INVITATION", "邀请入组"),
            Map.entry("ACTIVE_NOT_RECRUITING", "进行中·暂不招募"),
            Map.entry("SUSPENDED", "已暂停"),
            Map.entry("TERMINATED", "已终止"),
            Map.entry("COMPLETED", "已完成"),
            Map.entry("WITHDRAWN", "已撤回"),
            Map.entry("AVAILABLE", "已可获取"),
            Map.entry("NO_LONGER_AVAILABLE", "已不可获取"),
            Map.entry("TEMPORARILY_NOT_AVAILABLE", "暂时不可获取"),
            Map.entry("APPROVED_FOR_MARKETING", "已获批上市"),
            Map.entry("WITHHELD", "暂缓公开"),
            Map.entry("UNKNOWN", "未知"));

    private static final Map<String, String> TRIAL_PHASE = Map.of(
            "EARLY_PHASE1", "早期一期",
            "PHASE1", "一期",
            "PHASE2", "二期",
            "PHASE3", "三期",
            "PHASE4", "四期",
            "NA", "不适用");

    private static final Map<String, String> STUDY_DESIGN = Map.of(
            "INTERVENTIONAL", "干预性研究",
            "OBSERVATIONAL", "观察性研究",
            "EXPANDED_ACCESS", "扩展可及",
            "OTHER_VERIFIED", "其他（已核验）",
            "UNKNOWN", "未标注");

    private static final Map<String, String> TRIAL_CHANGE = Map.of(
            "INITIAL", "首次收录",
            "STATUS", "试验状态变化",
            "PHASES", "试验阶段变化",
            "STUDY_DESIGN", "研究设计变化");

    private static final Map<String, String> TYPE_LABEL = Map.of(
            "RESEARCH_INSIGHT", "研究解析",
            "RESEARCH_RADAR", "研究雷达",
            "TRIAL_WATCH", "试验观察");

    private NewsEditorialDraft() {
    }

    /** Rendered editorial draft; {@code contentType} is echoed so the caller can log traceability. */
    public record Draft(String contentType, String title, String summary, String html) {
    }

    /** Everything the deterministic template needs, taken from the frozen call and its candidate row. */
    public record Input(String contentType,
                        JsonNode sections,
                        List<NewsCurationModels.CurationClaim> claims,
                        List<String> limitations,
                        String source,
                        String sourceRecordId,
                        String sourceUrl,
                        String materialTitle,
                        Integer relevanceScore,
                        Integer researchValueScore,
                        String callId,
                        String evidenceVerificationSha256) {
    }

    public static Draft render(Input input) {
        require(input != null && input.contentType() != null, "DRAFT_CONTENT_TYPE_MISSING");
        require(TYPE_LABEL.containsKey(input.contentType()), "DRAFT_CONTENT_TYPE_UNKNOWN");
        JsonNode sections = input.sections();
        require(sections != null && sections.isObject(), "DRAFT_SECTIONS_MISSING");

        String title = title(input);
        StringBuilder html = new StringBuilder();
        switch (input.contentType()) {
            case "RESEARCH_INSIGHT" -> {
                html.append("<p>").append(paragraph(sections, "question")).append("</p>\n");
                section(html, "研究设计", sections, "studyDesign");
                section(html, "主要发现", sections, "findings");
                section(html, "临床意义", sections, "meaning");
            }
            case "RESEARCH_RADAR" -> {
                html.append("<p>").append(paragraph(sections, "introduction")).append("</p>\n");
                section(html, "值得关注的地方", sections, "reasonToWatch");
            }
            case "TRIAL_WATCH" -> {
                html.append("<h2>登记状态</h2>\n<ul>\n");
                bullet(html, "状态", trialLabel(TRIAL_STATUS, sections.path("status").asText("")));
                bullet(html, "试验阶段", phasesLabel(sections.path("phases")));
                bullet(html, "研究设计", studyDesignLabel(sections.path("studyDesign").asText("")));
                bullet(html, "本次变化", changesLabel(sections.path("changes")));
                html.append("</ul>\n");
            }
            default -> throw new IllegalArgumentException("DRAFT_CONTENT_TYPE_UNKNOWN");
        }

        section(html, "局限与边界", sections, "limitations");
        if (input.limitations() != null && !input.limitations().isEmpty()) {
            html.append("<h2>补充局限</h2>\n<ul>\n");
            for (String limitation : input.limitations()) {
                if (limitation != null && !limitation.isBlank()) {
                    html.append("<li>").append(escape(limitation)).append("</li>\n");
                }
            }
            html.append("</ul>\n");
        }
        if (input.claims() != null && !input.claims().isEmpty()) {
            html.append("<h2>证据要点</h2>\n<ul>\n");
            for (NewsCurationModels.CurationClaim claim : input.claims()) {
                html.append("<li><strong>[").append(escape(claim.field())).append("]</strong> ")
                        .append(escape(claim.statement()))
                        .append("（原文引文：&ldquo;").append(escape(claim.quote()))
                        .append("&rdquo;）</li>\n");
            }
            html.append("</ul>\n");
        }
        metadata(html, input);

        return new Draft(input.contentType(), title, summary(input, sections), html.toString().trim());
    }

    /** Chinese title: fixed frame + the most specific verified field, never raw model prose alone. */
    public static String title(Input input) {
        String frame = TYPE_LABEL.get(input.contentType());
        for (String detail : new String[]{titleDetail(input), firstSentence(input.materialTitle())}) {
            String composed = compose(frame, detail);
            if (composed != null) {
                return composed;
            }
        }
        // The fixed Chinese frame alone always satisfies the contract: a frozen answer whose prose cannot
        // yield a publishable title still produces a draft, and the reviewer retitles it.
        return validateTitle(frame);
    }

    private static String titleDetail(Input input) {
        return switch (input.contentType()) {
            case "RESEARCH_INSIGHT" -> firstSentence(text(input.sections(), "question"));
            case "RESEARCH_RADAR" -> firstSentence(text(input.sections(), "introduction"));
            case "TRIAL_WATCH" -> {
                JsonNode sections = input.sections();
                String status = trialLabel(TRIAL_STATUS, sections.path("status").asText(""));
                String phases = phasesLabel(sections.path("phases"));
                String design = studyDesignLabel(sections.path("studyDesign").asText(""));
                yield status + "｜" + phases + "｜" + design;
            }
            default -> throw new IllegalArgumentException("DRAFT_CONTENT_TYPE_UNKNOWN");
        };
    }

    /** Composes and validates one candidate title; {@code null} when this detail cannot be published. */
    private static String compose(String frame, String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        int room = TITLE_MAX - frame.codePointCount(0, frame.length()) - 1;
        if (detail.codePointCount(0, detail.length()) > room) {
            int cut = detail.offsetByCodePoints(0, Math.max(1, room - 1));
            detail = detail.substring(0, cut).trim() + "…";
        }
        if (detail.isBlank()) {
            return null;
        }
        try {
            return validateTitle(frame + "：" + detail);
        } catch (IllegalArgumentException unusable) {
            return null;
        }
    }

    /**
     * Title contract: Chinese, bounded, plain text.
     *
     * @throws IllegalArgumentException with a stable code when the generated title is not publishable
     */
    static String validateTitle(String title) {
        require(title != null && !title.isBlank(), "DRAFT_TITLE_EMPTY");
        String trimmed = title.trim();
        require(trimmed.codePointCount(0, trimmed.length()) <= TITLE_MAX, "DRAFT_TITLE_TOO_LONG");
        require(!MARKUP.matcher(trimmed).find(), "DRAFT_TITLE_MARKUP");
        require(!CONTROL.matcher(trimmed).find(), "DRAFT_TITLE_CONTROL_CHAR");
        require(HAN.matcher(trimmed).find(), "DRAFT_TITLE_NOT_CHINESE");
        return trimmed;
    }

    private static String summary(Input input, JsonNode sections) {
        String candidate = switch (input.contentType()) {
            case "RESEARCH_INSIGHT" -> text(sections, "findings");
            case "RESEARCH_RADAR" -> text(sections, "reasonToWatch");
            case "TRIAL_WATCH" -> trialLabel(TRIAL_STATUS, sections.path("status").asText(""))
                    + "，研究设计为" + studyDesignLabel(sections.path("studyDesign").asText(""))
                    + "，阶段：" + phasesLabel(sections.path("phases")) + "。";
            default -> "";
        };
        String summary = firstSentence(candidate, true);
        if (summary.isBlank()) {
            summary = firstSentence(input.materialTitle());
        }
        return summary.length() > SUMMARY_MAX ? summary.substring(0, SUMMARY_MAX) : summary;
    }

    private static void metadata(StringBuilder html, Input input) {
        html.append("<h2>溯源与元数据</h2>\n<ul>\n");
        bullet(html, "内容类型", TYPE_LABEL.get(input.contentType()) + "（" + input.contentType() + "）");
        if (input.relevanceScore() != null) {
            bullet(html, "相关性评分", input.relevanceScore() + "/100");
        }
        if (input.researchValueScore() != null) {
            bullet(html, "研究价值评分", input.researchValueScore() + "/100");
        }
        if (input.callId() != null && !input.callId().isBlank()) {
            bullet(html, "模型调用", input.callId() + "（生产命名空间 · 联合精选 P05）");
        }
        if (input.evidenceVerificationSha256() != null && !input.evidenceVerificationSha256().isBlank()) {
            bullet(html, "证据核验摘要", input.evidenceVerificationSha256());
        }
        String origin = escape(input.source()) + " / " + escape(input.sourceRecordId());
        if (input.sourceUrl() != null && !input.sourceUrl().isBlank()) {
            origin += "（" + escape(input.sourceUrl()) + "）";
        }
        bullet(html, "原始资料", origin);
        html.append("</ul>\n");
    }

    private static void section(StringBuilder html, String heading, JsonNode sections, String key) {
        String value = text(sections, key);
        if (!value.isBlank()) {
            html.append("<h2>").append(heading).append("</h2>\n<p>").append(escape(value)).append("</p>\n");
        }
    }

    private static String paragraph(JsonNode sections, String key) {
        String value = text(sections, key);
        return value.isBlank() ? "（冻结内容未提供该章节）" : escape(value);
    }

    private static void bullet(StringBuilder html, String label, String value) {
        html.append("<li><strong>").append(escape(label)).append("：</strong>")
                .append(escape(value)).append("</li>\n");
    }

    private static String text(JsonNode sections, String key) {
        if (sections == null) {
            return "";
        }
        JsonNode node = sections.path(key);
        return node.isTextual() ? node.textValue().trim() : "";
    }

    /** Registry facts are mapped from the frozen enumeration, never from model prose. */
    private static String trialLabel(Map<String, String> labels, String raw) {
        String key = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return labels.getOrDefault(key, key.isBlank() ? "未知" : key + "（未映射）");
    }

    private static String studyDesignLabel(String raw) {
        return trialLabel(STUDY_DESIGN, raw);
    }

    private static String phasesLabel(JsonNode phases) {
        if (phases == null || !phases.isArray() || phases.isEmpty()) {
            return "未标注";
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode phase : phases) {
            if (phase.isTextual() && !phase.textValue().isBlank()) {
                labels.add(trialLabel(TRIAL_PHASE, phase.textValue()));
            }
        }
        return labels.isEmpty() ? "未标注" : String.join("、", labels);
    }

    private static String changesLabel(JsonNode changes) {
        if (changes == null || !changes.isArray() || changes.isEmpty()) {
            return "无明显变化";
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode change : changes) {
            if (change.isTextual() && !change.textValue().isBlank()) {
                labels.add(trialLabel(TRIAL_CHANGE, change.textValue()));
            }
        }
        return labels.isEmpty() ? "无明显变化" : String.join("、", labels);
    }

    /** First sentence (Chinese or Latin punctuation), collapsed whitespace, bounded. */
    static String firstSentence(String text) {
        return firstSentence(text, false);
    }

    /**
     * @param keepTerminator keep the closing punctuation (summaries read better with it; titles drop it)
     */
    static String firstSentence(String text, boolean keepTerminator) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return "";
        }
        int end = -1;
        for (char stop : new char[]{'。', '！', '？', '.', '!', '?'}) {
            int index = collapsed.indexOf(stop);
            if (index >= 0 && (end < 0 || index < end)) {
                end = index;
            }
        }
        String sentence = end > 0 ? collapsed.substring(0, keepTerminator ? end + 1 : end) : collapsed;
        return sentence.isBlank() ? collapsed : sentence.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtil.escape(value);
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new IllegalArgumentException(code);
        }
    }
}
