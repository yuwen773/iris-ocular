package com.csust.eyediagnosis.newscuration;

import cn.hutool.http.HtmlUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
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

    /** 单篇正文的字节预算：{@code t_news.content} 是 TEXT（64 KB），留出余量后按固定顺序截断。 */
    static final int CONTENT_BUDGET_BYTES = 60_000;
    private static final String TRUNCATED_NOTE =
            "<p>（正文超出单篇长度上限，已按固定顺序截断；完整内容见该候选冻结的模型回执。）</p>\n";
    /** 证据要点与补充局限的上限：契约允许更多，但草稿要能进 TEXT 列并保持可读。 */
    private static final int MAX_CLAIMS = 8;
    private static final int CLAIM_STATEMENT_MAX = 200;
    private static final int CLAIM_QUOTE_MAX = 150;
    private static final int MAX_LIMITATIONS = 8;
    private static final int LIMITATION_MAX = 200;

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

    /**
     * 试验类章节里的固定标记不是给人读的话术：契约要求 Trial Watch 的 {@code limitations} 恰好是
     * {@code TRIAL_REGISTRY_ONLY}，正文必须把它换成中文说明，不能把内部标记印到稿件里。
     */
    private static final Map<String, String> LIMITATION_TOKEN = Map.of(
            "TRIAL_REGISTRY_ONLY", "以下内容仅来自试验登记信息，未包含研究者公开的结果数据。");

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
        List<String> blocks = new ArrayList<>();
        switch (input.contentType()) {
            case "RESEARCH_INSIGHT" -> {
                blocks.add("<p>" + paragraph(sections, "question") + "</p>\n");
                blocks.add(sectionBlock("研究设计", text(sections, "studyDesign")));
                blocks.add(sectionBlock("主要发现", text(sections, "findings")));
                blocks.add(sectionBlock("临床意义", text(sections, "meaning")));
            }
            case "RESEARCH_RADAR" -> {
                blocks.add("<p>" + paragraph(sections, "introduction") + "</p>\n");
                blocks.add(sectionBlock("值得关注的地方", text(sections, "reasonToWatch")));
            }
            case "TRIAL_WATCH" -> blocks.add(trialBlock(sections));
            default -> throw new IllegalArgumentException("DRAFT_CONTENT_TYPE_UNKNOWN");
        }

        String primaryLimitation = limitationText(text(sections, "limitations"));
        blocks.add(sectionBlock("局限与边界", primaryLimitation));
        blocks.add(limitationsBlock(input.limitations(), primaryLimitation));
        blocks.add(claimsBlock(input.claims()));
        blocks.add(metadataBlock(input));

        return new Draft(input.contentType(), title, summary(input, sections), assemble(blocks));
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

    private static String trialBlock(JsonNode sections) {
        StringBuilder block = new StringBuilder("<h2>登记状态</h2>\n<ul>\n");
        bullet(block, "状态", trialLabel(TRIAL_STATUS, sections.path("status").asText("")));
        bullet(block, "试验阶段", phasesLabel(sections.path("phases")));
        bullet(block, "研究设计", studyDesignLabel(sections.path("studyDesign").asText("")));
        bullet(block, "本次变化", changesLabel(sections.path("changes")));
        block.append("</ul>\n");
        return block.toString();
    }

    private static String limitationsBlock(List<String> limitations, String primary) {
        if (limitations == null || limitations.isEmpty()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (String raw : limitations) {
            String limitation = limitationText(raw);
            if (limitation.isBlank() || limitation.equals(primary) || items.contains(limitation)) {
                continue;
            }
            items.add(bound(limitation, LIMITATION_MAX));
            if (items.size() >= MAX_LIMITATIONS) {
                break;
            }
        }
        return items.isEmpty() ? null : listBlock("补充局限", items);
    }

    private static String claimsBlock(List<NewsCurationModels.CurationClaim> claims) {
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        StringBuilder block = new StringBuilder("<h2>证据要点</h2>\n<ul>\n");
        int rendered = 0;
        for (NewsCurationModels.CurationClaim claim : claims) {
            if (rendered >= MAX_CLAIMS) {
                break;
            }
            block.append("<li><strong>[").append(escape(claim.field())).append("]</strong> ")
                    .append(escape(bound(claim.statement(), CLAIM_STATEMENT_MAX)))
                    .append("（原文引文：&ldquo;").append(escape(bound(claim.quote(), CLAIM_QUOTE_MAX)))
                    .append("&rdquo;）</li>\n");
            rendered++;
        }
        if (claims.size() > rendered) {
            block.append("<li>另有 ").append(claims.size() - rendered).append(" 条证据要点未展开，见冻结回执。</li>\n");
        }
        block.append("</ul>\n");
        return block.toString();
    }

    private static String metadataBlock(Input input) {
        StringBuilder block = new StringBuilder("<h2>溯源与元数据</h2>\n<ul>\n");
        bullet(block, "内容类型", TYPE_LABEL.get(input.contentType()) + "（" + input.contentType() + "）");
        if (input.relevanceScore() != null) {
            bullet(block, "相关性评分", input.relevanceScore() + "/100");
        }
        if (input.researchValueScore() != null) {
            bullet(block, "研究价值评分", input.researchValueScore() + "/100");
        }
        if (input.callId() != null && !input.callId().isBlank()) {
            bullet(block, "模型调用", input.callId() + "（生产命名空间 · 联合精选 P05）");
        }
        if (input.evidenceVerificationSha256() != null && !input.evidenceVerificationSha256().isBlank()) {
            bullet(block, "证据核验摘要", input.evidenceVerificationSha256());
        }
        StringBuilder origin = new StringBuilder();
        origin.append(input.source() == null ? "" : input.source())
                .append(" / ").append(input.sourceRecordId() == null ? "" : input.sourceRecordId());
        if (input.sourceUrl() != null && !input.sourceUrl().isBlank()) {
            origin.append("（").append(input.sourceUrl()).append("）");
        }
        bullet(block, "原始资料", origin.toString());
        block.append("</ul>\n");
        return block.toString();
    }

    private static String sectionBlock(String heading, String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : "<h2>" + heading + "</h2>\n<p>" + escape(trimmed) + "</p>\n";
    }

    private static String listBlock(String heading, List<String> items) {
        StringBuilder block = new StringBuilder("<h2>").append(heading).append("</h2>\n<ul>\n");
        for (String item : items) {
            block.append("<li>").append(escape(item)).append("</li>\n");
        }
        block.append("</ul>\n");
        return block.toString();
    }

    private static String paragraph(JsonNode sections, String key) {
        String value = text(sections, key);
        return value.isBlank() ? "（冻结内容未提供该章节）" : escape(value);
    }

    /** Appends one label/value bullet; the whole value is escaped exactly once, here. */
    private static void bullet(StringBuilder block, String label, String value) {
        block.append("<li><strong>").append(escape(label)).append("：</strong>")
                .append(escape(value)).append("</li>\n");
    }

    /**
     * Joins the blocks in fixed order while staying inside the column byte budget. Truncation is
     * deterministic: blocks are added until the next one would overflow, then one fixed note is appended.
     */
    private static String assemble(List<String> blocks) {
        StringBuilder html = new StringBuilder();
        int bytes = 0;
        for (String block : blocks) {
            if (block == null || block.isEmpty()) {
                continue;
            }
            int blockBytes = block.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + blockBytes > CONTENT_BUDGET_BYTES) {
                html.append(TRUNCATED_NOTE);
                break;
            }
            html.append(block);
            bytes += blockBytes;
        }
        return html.toString().trim();
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

    /** Contract markers are not prose: known tokens are replaced by their Chinese explanation. */
    private static String limitationText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        String mapped = LIMITATION_TOKEN.get(trimmed);
        return mapped == null ? trimmed : mapped;
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
        int end = sentenceEnd(collapsed);
        String sentence = end > 0 ? collapsed.substring(0, keepTerminator ? end + 1 : end) : collapsed;
        return sentence.isBlank() ? collapsed : sentence.trim();
    }

    /**
     * Index of the first real sentence terminator, or {@code -1}.
     *
     * <p>Chinese punctuation always ends a sentence. ASCII punctuation only counts when it is followed by
     * whitespace or the end of the text — otherwise {@code "AUC 0.93，仍需验证。"} would be cut at the decimal
     * point and the draft would publish a wrong number. A bare {@code "1."} style list marker is not an
     * English sentence end either.</p>
     */
    private static int sentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？') {
                return i;
            }
            if ((c == '.' || c == '!' || c == '?') && latinTerminator(text, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean latinTerminator(String text, int index) {
        int next = index + 1;
        if (next < text.length() && !Character.isWhitespace(text.charAt(next))) {
            return false;
        }
        if (text.charAt(index) != '.') {
            return true;
        }
        // 「1.」「2.」这类序号不是句末；「0.93.」这种以数字结尾的句子仍然算句末。
        int start = index;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        return !text.substring(start, index).matches("\\d{1,2}");
    }

    private static String bound(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.codePointCount(0, trimmed.length()) <= max
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, max)).trim() + "…";
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
