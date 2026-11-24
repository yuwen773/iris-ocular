package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed parser and deterministic evidence/clinical-safety/license verifier for WP-03.
 */
@Component
public class NewsEvidenceVerifier {

    private static final Set<String> OUTPUT_KEYS = Set.of(
            "decision", "relevanceScore", "suggestedTitle", "suggestedSummary",
            "category", "limitations", "claims"
    );
    private static final Set<String> CLAIM_KEYS = Set.of("id", "field", "statement", "quote");
    private static final Set<String> ALLOWED_CLAIM_FIELDS = Set.of("title", "abstract", "trial");

    private static final List<String> METADATA_LIMITATION_MARKERS = List.of(
            "摘要", "全文", "元数据", "仅标题", "abstract", "full text", "metadata"
    );

    private static final List<String> INJECTION_MARKERS = List.of(
            "ignore previous", "ignore all previous", "system override", "auto_publish",
            "bypass review", "disregard instructions", "忽略此前指令", "忽略以上指令",
            "跳过人工审核", "直接发布"
    );

    private static final List<String> PROHIBITED_CLINICAL_MARKERS = List.of(
            "确诊", "开具处方", "开药", "保证治愈", "彻底根治", "替代医生", "100%有效",
            "guaranteed cure", "definitive diagnosis", "prescribe", "replace your doctor", "100% effective"
    );

    private static final List<String> PROVEN_EFFICACY_MARKERS = List.of(
            "已证实临床疗效", "证实有效", "获批临床治疗",
            "proven effective", "established efficacy", "approved treatment",
            "phase 3 completed", "phase iii completed", "三期临床已完成"
    );

    private static final Pattern PHASE3_MENTION_PATTERN = Pattern.compile(
            "(?i)(?:\\bphase\\s*(?:3|iii)\\b|三期临床|iii期)"
    );

    private final NewsCurationProperties properties;
    private final ObjectMapper mapper;

    public NewsEvidenceVerifier(NewsCurationProperties properties,
                                @Qualifier("newsProtocolObjectMapper") ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    public NewsCurationModels.VerificationResult parseAndVerify(
            String rawJson,
            NewsCurationModels.EvidenceSnapshot snapshot) {
        int maxChars = properties.getMaxResponseChars() > 0 ? properties.getMaxResponseChars() : 16_000;
        if (rawJson == null || rawJson.isBlank() || rawJson.length() > maxChars) {
            return schemaFailure("Response missing, blank, or exceeds " + maxChars + " characters");
        }

        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(rawJson);
        } catch (JsonProcessingException e) {
            return schemaFailure("Malformed or non-strict JSON model output");
        }
        if (rootNode == null || !rootNode.isObject()) {
            return schemaFailure("Model output root must be a JSON object");
        }
        ObjectNode root = (ObjectNode) rootNode;
        if (!hasOnlyAllowedKeys(root, OUTPUT_KEYS)) {
            return schemaFailure("Model output contains unknown fields");
        }

        NewsCurationModels.Decision decision = parseDecision(root.get("decision"));
        Integer relevanceScore = parseIntRange(root.get("relevanceScore"), 0, 100);
        if (decision == null || relevanceScore == null) {
            return schemaFailure("Invalid or missing decision or relevanceScore");
        }

        StringParseResult titleRes = optionalString(root.get("suggestedTitle"), 512);
        StringParseResult summaryRes = optionalString(root.get("suggestedSummary"), 2048);
        StringParseResult categoryRes = optionalString(root.get("category"), 64);
        StringListResult limitationsRes = parseLimitations(root.get("limitations"));
        ClaimsParseResult claimsRes = parseClaims(root.get("claims"));

        if (!titleRes.valid || !summaryRes.valid || !categoryRes.valid
                || !limitationsRes.valid || !claimsRes.valid) {
            return schemaFailure("Malformed suggested fields, limitations, or claims schema");
        }

        NewsCurationModels.ModelCurationOutput parsed = new NewsCurationModels.ModelCurationOutput(
                decision,
                relevanceScore,
                titleRes.value,
                summaryRes.value,
                categoryRes.value,
                limitationsRes.items,
                claimsRes.claims
        );

        List<NewsCurationModels.EvidenceViolation> violations = verifyRules(parsed, snapshot);
        if (!violations.isEmpty()) {
            return new NewsCurationModels.VerificationResult(
                    false,
                    NewsCurationModels.CandidateStatus.REJECTED_EVIDENCE_VIOLATION,
                    parsed,
                    List.copyOf(violations)
            );
        }

        NewsCurationModels.CandidateStatus status = switch (decision) {
            case ACCEPT -> NewsCurationModels.CandidateStatus.CURATED_ACCEPTED;
            case NEEDS_REVIEW -> NewsCurationModels.CandidateStatus.CURATED_NEEDS_REVIEW;
            case REJECT -> NewsCurationModels.CandidateStatus.CURATED_REJECTED;
        };
        return new NewsCurationModels.VerificationResult(true, status, parsed, List.of());
    }

    private List<NewsCurationModels.EvidenceViolation> verifyRules(
            NewsCurationModels.ModelCurationOutput output,
            NewsCurationModels.EvidenceSnapshot snapshot) {
        List<NewsCurationModels.EvidenceViolation> violations = new ArrayList<>();

        checkPromptInjection(violations, "suggestedTitle", output.suggestedTitle());
        checkPromptInjection(violations, "suggestedSummary", output.suggestedSummary());
        checkPromptInjection(violations, "category", output.category());
        for (int i = 0; i < output.limitations().size(); i++) {
            checkPromptInjection(violations, "limitations[" + i + "]", output.limitations().get(i));
        }
        for (int i = 0; i < output.claims().size(); i++) {
            checkPromptInjection(violations, "claims[" + i + "].statement", output.claims().get(i).statement());
        }

        if (output.decision() == NewsCurationModels.Decision.REJECT) {
            return violations;
        }

        if (isBlank(output.suggestedTitle()) || isBlank(output.suggestedSummary()) || isBlank(output.category())) {
            violations.add(new NewsCurationModels.EvidenceViolation(
                    "MISSING_REQUIRED_FIELDS", "$",
                    "ACCEPT and NEEDS_REVIEW decisions require non-blank suggestedTitle, suggestedSummary, and category"));
        }
        if (output.limitations().isEmpty()) {
            violations.add(new NewsCurationModels.EvidenceViolation(
                    "MISSING_LIMITATIONS", "limitations",
                    "ACCEPT and NEEDS_REVIEW decisions require at least one explicit limitation"));
        } else if (snapshot.evidenceTier() == NewsCurationModels.EvidenceTier.METADATA_ONLY
                && !containsMetadataLimitation(output.limitations())) {
            violations.add(new NewsCurationModels.EvidenceViolation(
                    "METADATA_ONLY_MISSING_LIMITATION", "limitations",
                    "METADATA_ONLY curation must disclose missing abstract/full-text scope in limitations"));
        }

        if (output.claims().isEmpty()) {
            violations.add(new NewsCurationModels.EvidenceViolation(
                    "MISSING_CLAIMS", "claims",
                    "ACCEPT and NEEDS_REVIEW decisions require at least one grounded claim"));
        }

        checkProhibitedClinical(violations, "suggestedTitle", output.suggestedTitle());
        checkProhibitedClinical(violations, "suggestedSummary", output.suggestedSummary());

        Set<String> seenClaimIds = new HashSet<>();
        for (int i = 0; i < output.claims().size(); i++) {
            NewsCurationModels.CurationClaim claim = output.claims().get(i);
            String target = "claims[" + i + "]";
            if (isBlank(claim.id()) || !seenClaimIds.add(claim.id())) {
                violations.add(new NewsCurationModels.EvidenceViolation(
                        "MISSING_CLAIMS", target + ".id", "Claim id must be non-blank and unique"));
            }
            if (claim.field() == null || !ALLOWED_CLAIM_FIELDS.contains(claim.field())) {
                violations.add(new NewsCurationModels.EvidenceViolation(
                        "INVALID_CLAIM_FIELD", target + ".field",
                        "Unsupported claim field: " + claim.field()));
                continue;
            }
            if (isBlank(claim.statement())) {
                violations.add(new NewsCurationModels.EvidenceViolation(
                        "MISSING_REQUIRED_FIELDS", target + ".statement", "Claim statement must not be blank"));
            } else {
                checkProhibitedClinical(violations, target + ".statement", claim.statement());
            }
            if (isBlank(claim.quote())) {
                violations.add(new NewsCurationModels.EvidenceViolation(
                        "EMPTY_QUOTE", target + ".quote", "Claim quote must not be blank"));
                continue;
            }
            if ("abstract".equals(claim.field()) && !hasLicensedAbstract(snapshot)) {
                violations.add(new NewsCurationModels.EvidenceViolation(
                        "UNLICENSED_ABSTRACT_QUOTE", target,
                        "Cannot quote abstract without CC0/CC-BY license in snapshot"));
                continue;
            }
            String sourceFieldText = resolveFieldText(snapshot, claim.field());
            int minLength = "trial".equals(claim.field()) ? 4 : 6;
            if (sourceFieldText == null
                    || claim.quote().trim().length() < minLength
                    || !sourceFieldText.contains(claim.quote())) {
                violations.add(new NewsCurationModels.EvidenceViolation(
                        "QUOTE_NOT_FOUND", target + ".quote",
                        "Quote is either shorter than minimum length (" + minLength
                                + ") or not an exact verbatim substring of snapshot." + claim.field()));
            }
        }

        if ("clinicaltrials-gov".equals(snapshot.source()) && !isBlank(snapshot.trialJson())) {
            checkTrialStatusFidelity(violations, output, snapshot.trialJson());
        }

        return violations;
    }

    private void checkTrialStatusFidelity(List<NewsCurationModels.EvidenceViolation> violations,
                                          NewsCurationModels.ModelCurationOutput output,
                                          String trialJson) {
        JsonNode trial;
        try {
            trial = mapper.readTree(trialJson);
        } catch (JsonProcessingException e) {
            return;
        }
        String overallStatus = trial.hasNonNull("overallStatus") ? trial.get("overallStatus").textValue() : null;
        List<String> phases = new ArrayList<>();
        if (trial.has("phases") && trial.get("phases").isArray()) {
            for (JsonNode p : trial.get("phases")) {
                if (p.isTextual()) {
                    phases.add(p.textValue().toUpperCase(Locale.ROOT));
                }
            }
        }
        boolean completedPhase3Or4 = "COMPLETED".equalsIgnoreCase(overallStatus)
                && (phases.contains("PHASE3") || phases.contains("PHASE4"));

        StringBuilder combined = new StringBuilder();
        if (output.suggestedTitle() != null) {
            combined.append(output.suggestedTitle()).append('\n');
        }
        if (output.suggestedSummary() != null) {
            combined.append(output.suggestedSummary()).append('\n');
        }
        for (NewsCurationModels.CurationClaim claim : output.claims()) {
            if (claim.statement() != null) {
                combined.append(claim.statement()).append('\n');
            }
        }
        String combinedText = combined.toString();
        String combinedLower = combinedText.toLowerCase(Locale.ROOT);

        if (!completedPhase3Or4) {
            for (String marker : PROVEN_EFFICACY_MARKERS) {
                if (combinedLower.contains(marker.toLowerCase(Locale.ROOT))) {
                    violations.add(new NewsCurationModels.EvidenceViolation(
                            "TRIAL_STATUS_MISMATCH", "summary/claims",
                            "Claimed proven clinical efficacy or completed Phase 3 despite trial overallStatus="
                                    + overallStatus + ", phases=" + phases));
                    return;
                }
            }
        }

        if (!phases.isEmpty() && !phases.contains("PHASE3")
                && PHASE3_MENTION_PATTERN.matcher(combinedText).find()) {
            violations.add(new NewsCurationModels.EvidenceViolation(
                    "TRIAL_STATUS_MISMATCH", "summary/claims",
                    "Cited Phase 3 clinical trial while snapshot phases=" + phases));
        }
    }

    public static Optional<String> findProhibitedClinicalMarker(String text) {
        return findFirstMarker(text, PROHIBITED_CLINICAL_MARKERS);
    }

    public static Optional<String> findPromptInjectionMarker(String text) {
        return findFirstMarker(text, INJECTION_MARKERS);
    }

    private static void checkPromptInjection(List<NewsCurationModels.EvidenceViolation> violations,
                                             String target,
                                             String text) {
        findPromptInjectionMarker(text).ifPresent(marker -> violations.add(
                new NewsCurationModels.EvidenceViolation(
                        "PROMPT_INJECTION_ARTIFACT", target,
                        "Detected untrusted prompt-injection directive marker: " + marker)));
    }

    private static void checkProhibitedClinical(List<NewsCurationModels.EvidenceViolation> violations,
                                                String target,
                                                String text) {
        findProhibitedClinicalMarker(text).ifPresent(marker -> violations.add(
                new NewsCurationModels.EvidenceViolation(
                        "PROHIBITED_CLINICAL_CLAIM", target,
                        "Detected prohibited diagnostic/prescribing/cure-guarantee phrasing: " + marker)));
    }

    private static Optional<String> findFirstMarker(String text, List<String> markers) {
        if (isBlank(text)) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return Optional.of(marker);
            }
        }
        return Optional.empty();
    }

    private static boolean containsMetadataLimitation(List<String> limitations) {
        for (String item : limitations) {
            String lower = item.toLowerCase(Locale.ROOT);
            for (String marker : METADATA_LIMITATION_MARKERS) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasLicensedAbstract(NewsCurationModels.EvidenceSnapshot snapshot) {
        return snapshot.abstractLicense() != null
                && NewsWireModels.ABSTRACT_LICENSES.contains(snapshot.abstractLicense())
                && !isBlank(snapshot.abstractText());
    }

    private static String resolveFieldText(NewsCurationModels.EvidenceSnapshot snapshot, String field) {
        return switch (field) {
            case "title" -> snapshot.title();
            case "abstract" -> snapshot.abstractText();
            case "trial" -> snapshot.trialJson();
            default -> null;
        };
    }

    private static NewsCurationModels.VerificationResult schemaFailure(String message) {
        return new NewsCurationModels.VerificationResult(
                false,
                NewsCurationModels.CandidateStatus.REJECTED_EVIDENCE_VIOLATION,
                null,
                List.of(new NewsCurationModels.EvidenceViolation("INVALID_OUTPUT_SCHEMA", "$", message))
        );
    }

    private static boolean hasOnlyAllowedKeys(ObjectNode obj, Set<String> allowed) {
        Iterator<String> names = obj.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static NewsCurationModels.Decision parseDecision(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return NewsCurationModels.Decision.valueOf(node.textValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer parseIntRange(JsonNode node, int min, int max) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        BigInteger value = node.bigIntegerValue();
        if (value.compareTo(BigInteger.valueOf(min)) < 0 || value.compareTo(BigInteger.valueOf(max)) > 0) {
            return null;
        }
        return value.intValue();
    }

    private static StringParseResult optionalString(JsonNode node, int maxLen) {
        if (node == null || node.isNull()) {
            return new StringParseResult(true, null);
        }
        if (!node.isTextual()) {
            return new StringParseResult(false, null);
        }
        String value = node.textValue();
        if (value.length() > maxLen) {
            return new StringParseResult(false, null);
        }
        return new StringParseResult(true, value);
    }

    private static StringListResult parseLimitations(JsonNode node) {
        if (node == null || node.isNull()) {
            return new StringListResult(true, List.of());
        }
        if (!node.isArray() || node.size() > 32) {
            return new StringListResult(false, List.of());
        }
        List<String> items = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 512) {
                return new StringListResult(false, List.of());
            }
            items.add(item.textValue());
        }
        return new StringListResult(true, List.copyOf(items));
    }

    private static ClaimsParseResult parseClaims(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ClaimsParseResult(true, List.of());
        }
        if (!node.isArray() || node.size() > 32) {
            return new ClaimsParseResult(false, List.of());
        }
        List<NewsCurationModels.CurationClaim> claims = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isObject()) {
                return new ClaimsParseResult(false, List.of());
            }
            ObjectNode obj = (ObjectNode) item;
            if (!hasOnlyAllowedKeys(obj, CLAIM_KEYS)) {
                return new ClaimsParseResult(false, List.of());
            }
            JsonNode idNode = obj.get("id");
            JsonNode fieldNode = obj.get("field");
            JsonNode statementNode = obj.get("statement");
            JsonNode quoteNode = obj.get("quote");
            if (idNode == null || !idNode.isTextual()
                    || fieldNode == null || !fieldNode.isTextual()
                    || statementNode == null || !statementNode.isTextual()
                    || quoteNode == null || !quoteNode.isTextual()) {
                return new ClaimsParseResult(false, List.of());
            }
            if (idNode.textValue().length() > 64
                    || fieldNode.textValue().length() > 32
                    || statementNode.textValue().length() > 1024
                    || quoteNode.textValue().length() > 2048) {
                return new ClaimsParseResult(false, List.of());
            }
            claims.add(new NewsCurationModels.CurationClaim(
                    idNode.textValue(),
                    fieldNode.textValue(),
                    statementNode.textValue(),
                    quoteNode.textValue()
            ));
        }
        return new ClaimsParseResult(true, List.copyOf(claims));
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private record StringParseResult(boolean valid, String value) {
    }

    private record StringListResult(boolean valid, List<String> items) {
    }

    private record ClaimsParseResult(boolean valid, List<NewsCurationModels.CurationClaim> claims) {
    }
}
