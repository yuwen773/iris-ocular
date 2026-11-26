package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newscuration.NewsCurationModels.EvidenceSnapshot;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.ModelCallRequest;
import com.csust.eyediagnosis.newscuration.NewsEditorialEvidence.ContentType;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Versions frozen model input and application-owned structured output contracts. */
public final class NewsModelProtocol {

    private static final String CATALOG = "/news/curation/wp08b-v1.json";
    private static final Pattern UNSAFE_TEXT = Pattern.compile(
            "(?iu)[<>]|(?:https?|ftp|javascript|data):|\\bwww\\.|\\[[^]]*]\\(");

    public enum Stage { SCREENING, GENERATION, JOINT }
    public enum TrialChange { INITIAL, STATUS, PHASES, STUDY_DESIGN }

    public enum Prompt {
        P01(Stage.SCREENING, null),
        P02(Stage.GENERATION, ContentType.RESEARCH_INSIGHT),
        P03(Stage.GENERATION, ContentType.RESEARCH_RADAR),
        P04(Stage.GENERATION, ContentType.TRIAL_WATCH),
        P05(Stage.JOINT, null);

        private final Stage stage;
        private final ContentType contentType;

        Prompt(Stage stage, ContentType contentType) {
            this.stage = stage;
            this.contentType = contentType;
        }
    }

    public record Selection(String namespace, Prompt prompt, String profileKey, String upstreamCallId) {
        public Selection {
            if (!("production".equals(namespace) || namespace != null && namespace.startsWith("experiment.")
                    && namespace.length() > "experiment.".length() && NewsModelProfile.identifier(namespace, 64))
                    || prompt == null || !NewsModelProfile.identifier(profileKey, 64)
                    || upstreamCallId != null && (!NewsModelProfile.identifier(upstreamCallId, 64)
                    || prompt.stage != Stage.GENERATION)) {
                throw new IllegalArgumentException("Invalid model call selection");
            }
        }
    }

    public record Identity(long materialRevisionId, String namespace, String policyVersion, Stage stage,
                           NewsModelProfile.Provider provider, NewsModelProfile.Platform servingPlatform,
                           String modelId, String pricingVersion, Prompt prompt, String promptVersion,
                           String promptSha256, String configurationSha256, String upstreamResponseSha256,
                           ContentType eligibleType, List<TrialChange> trialChanges) {
    }

    record Upstream(String digest, JsonNode payload) {
    }

    record Prepared(Identity identity, String identityJson, String logicalCallSha256, String profileKey,
                    NewsModelProfile profile, Template template, String userPrompt) {

        long reservedTokens() {
            return (long) profile.maxInputTokens() + profile.maxOutputTokens();
        }

        long reservedCost() {
            return profile.pricingVersion().cost(profile.maxInputTokens(), 0, profile.maxOutputTokens());
        }

        ModelCallRequest request(String callId, String key, EvidenceSnapshot snapshot) {
            return new ModelCallRequest(callId, key, identity.provider().name(), identity.modelId(),
                    identity.policyVersion(), template.systemPrompt(), userPrompt, identity.promptSha256(),
                    snapshot, profile.connectTimeout().plus(profile.readTimeout()).plusSeconds(2),
                    profileKey, template.schemaJson());
        }
    }

    private record Template(String systemPrompt, String schemaJson, JsonSchema schema) {
    }

    private final ObjectMapper mapper;
    private final String version;
    private final Map<Prompt, Template> templates = new EnumMap<>(Prompt.class);

    public NewsModelProtocol(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (var stream = NewsModelProtocol.class.getResourceAsStream(CATALOG)) {
            JsonNode catalog = this.mapper.readTree(stream);
            version = catalog.required("version").textValue();
            String common = catalog.required("common").textValue();
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            for (Prompt prompt : Prompt.values()) {
                JsonNode definition = catalog.required("prompts").required(prompt.name());
                ObjectNode schema = definition.required("schema").deepCopy();
                if (prompt == Prompt.P05) {
                    var definitions = schema.putObject("$defs");
                    for (Prompt generation : List.of(Prompt.P02, Prompt.P03, Prompt.P04)) {
                        definitions.set(generation.name(), catalog.required("prompts")
                                .required(generation.name()).required("schema"));
                    }
                }
                String schemaJson = json(schema);
                templates.put(prompt, new Template(common + "\n" + definition.required("instruction").textValue()
                        + "\nJSON Schema: " + schemaJson, schemaJson, factory.getSchema(schema)));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load news model protocol catalog", ex);
        }
    }

    Prepared prepare(Selection selection, NewsModelProfile profile, String policy, EvidenceSnapshot snapshot,
                     NewsEditorialEvidence previous, Upstream upstream) {
        if (!NewsModelProfile.identifier(policy, 64)) {
            throw new IllegalArgumentException("Invalid news policy version");
        }
        NewsEditorialEvidence evidence = NewsEditorialEvidence.from(snapshot, mapper);
        ContentType eligible = evidence.route(previous, true);
        List<TrialChange> changes = trialChanges(evidence, previous);
        Prompt prompt = selection.prompt();
        if (prompt.contentType != null && prompt.contentType != eligible) {
            throw new IllegalArgumentException("EDITORIAL_EVIDENCE_INSUFFICIENT");
        }
        Template template = templates.get(prompt);
        var user = mapper.createObjectNode();
        user.put("eligibleType", eligible.name());
        user.set("evidenceAttributes", mapper.valueToTree(evidence));
        user.set("trialChanges", mapper.valueToTree(changes));
        if (previous != null && evidence.trial() != null) {
            user.set("previousTrialState", mapper.valueToTree(previous.trial()));
            user.put("previousStudyDesign", previous.studyDesign().name());
        }
        var fields = user.putObject("evidence");
        fields.put("title", snapshot.title());
        if (evidence.license() != NewsEditorialEvidence.License.UNKNOWN) {
            fields.put("abstract", snapshot.abstractText());
        }
        if (evidence.trial() != null) {
            fields.put("trial", snapshot.trialJson());
        }
        if (upstream != null) {
            user.set("upstreamScreening", upstream.payload());
        }
        String userPrompt = json(user);
        int size = template.systemPrompt().getBytes(StandardCharsets.UTF_8).length
                + userPrompt.getBytes(StandardCharsets.UTF_8).length + 256;
        if (profile.responseFormat() == NewsModelProfile.Format.JSON_SCHEMA) {
            size += template.schemaJson().getBytes(StandardCharsets.UTF_8).length;
        }
        if (size > profile.maxInputTokens()) {
            throw new IllegalArgumentException("MODEL_INPUT_EXCEEDS_RESERVED_ENVELOPE");
        }
        String promptHash = sha256(json(List.of(version, prompt.name(), template.systemPrompt(), userPrompt)));
        String configHash = sha256(json(profile));
        Identity identity = new Identity(snapshot.materialRevisionId(), selection.namespace(), policy, prompt.stage,
                profile.provider(), profile.servingPlatform(), profile.modelId(), profile.pricingVersion().version(),
                prompt, version, promptHash, configHash, upstream == null ? null : upstream.digest(), eligible, changes);
        String identityJson = json(identity);
        return new Prepared(identity, identityJson, sha256(identityJson), selection.profileKey(), profile, template, userPrompt);
    }

    JsonNode validatedOutput(String raw, Identity identity, EvidenceSnapshot snapshot, int maxChars) {
        if (!version.equals(identity.promptVersion()) || raw == null || raw.isBlank() || raw.length() > maxChars) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            return null;
        }
        if (root == null || !templates.get(identity.prompt()).schema().validate(root).isEmpty()
                || !safeText(root, "clinicaltrials-gov".equals(snapshot.source()))) {
            return null;
        }
        boolean registry = "clinicaltrials-gov".equals(snapshot.source());
        if (registry && root.has("rationale") && !"TRIAL_REGISTRY_ONLY".equals(root.path("rationale").asText())) {
            return null;
        }
        JsonNode content = switch (identity.stage()) {
            case SCREENING -> null;
            case GENERATION -> root;
            case JOINT -> root.get("content");
        };
        if (content != null && !content.isNull()) {
            if (!identity.eligibleType().name().equals(content.path("contentType").asText())) {
                return null;
            }
            if (registry && !validTrialSections(content.path("sections"), identity, snapshot)) {
                return null;
            }
            for (JsonNode claim : content.path("claims")) {
                if (registry && (!"trial".equals(claim.path("evidenceId").asText())
                        || !claim.path("quote").asText().equals(claim.path("statement").asText()))) {
                    return null;
                }
                String evidence = switch (claim.path("evidenceId").textValue()) {
                    case "title" -> snapshot.title();
                    case "abstract" -> NewsEditorialEvidence.from(snapshot, mapper).license()
                            == NewsEditorialEvidence.License.UNKNOWN ? null : snapshot.abstractText();
                    case "trial" -> "clinicaltrials-gov".equals(snapshot.source()) ? snapshot.trialJson() : null;
                    default -> null;
                };
                if (evidence == null || !evidence.contains(claim.path("quote").textValue())) {
                    return null;
                }
            }
        }
        return root;
    }

    private boolean validTrialSections(JsonNode sections, Identity identity, EvidenceSnapshot snapshot) {
        NewsEditorialEvidence evidence = NewsEditorialEvidence.from(snapshot, mapper);
        return evidence.trial() != null
                && evidence.trial().status().equals(sections.path("status").asText())
                && mapper.valueToTree(evidence.trial().phases()).equals(sections.path("phases"))
                && evidence.studyDesign().name().equals(sections.path("studyDesign").asText())
                && mapper.valueToTree(identity.trialChanges()).equals(sections.path("changes"))
                && "TRIAL_REGISTRY_ONLY".equals(sections.path("limitations").asText());
    }

    private static List<TrialChange> trialChanges(NewsEditorialEvidence current, NewsEditorialEvidence previous) {
        if (current.trial() == null) {
            return List.of();
        }
        if (previous == null || previous.trial() == null) {
            return List.of(TrialChange.INITIAL);
        }
        List<TrialChange> changes = new ArrayList<>();
        if (!current.trial().status().equals(previous.trial().status())) {
            changes.add(TrialChange.STATUS);
        }
        if (!current.trial().phases().equals(previous.trial().phases())) {
            changes.add(TrialChange.PHASES);
        }
        if (current.studyDesign() != previous.studyDesign()) {
            changes.add(TrialChange.STUDY_DESIGN);
        }
        return List.copyOf(changes);
    }

    private static boolean safeText(JsonNode node, boolean trial) {
        if (node.isTextual()) {
            String value = node.textValue();
            return !UNSAFE_TEXT.matcher(value).find()
                    && NewsEvidenceVerifier.findPromptInjectionMarker(value).isEmpty()
                    && NewsEvidenceVerifier.findProhibitedClinicalMarker(value).isEmpty()
                    && (!trial || NewsEvidenceVerifier.findUnsupportedTrialOutcome(value).isEmpty());
        }
        for (JsonNode child : node) {
            if (!safeText(child, trial)) {
                return false;
            }
        }
        return true;
    }

    static String sha256(String value) {
        return NewsMaterialCanonicalizer.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot freeze model call metadata", ex);
        }
    }
}
