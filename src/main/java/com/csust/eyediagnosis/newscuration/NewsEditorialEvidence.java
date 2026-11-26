package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Editorial routing facts derived from the current source contract. */
public record NewsEditorialEvidence(String source, StudyDesign studyDesign, PeerReview peerReview,
                                   ContentScope contentScope, License license, Integrity integrity,
                                   TrialState trial) {

    public enum StudyDesign { UNKNOWN, INTERVENTIONAL, OBSERVATIONAL, EXPANDED_ACCESS, OTHER_VERIFIED }
    public enum PeerReview { UNKNOWN, VERIFIED, NOT_PEER_REVIEWED }
    public enum Integrity { UNKNOWN, VERIFIED, CONCERN, RETRACTED }
    public enum ContentScope { METADATA, LICENSED_ABSTRACT, LICENSED_RESULTS, TRIAL_REGISTRY }
    public enum License { UNKNOWN, CC0, CC_BY }
    public enum ContentType { RESEARCH_INSIGHT, RESEARCH_RADAR, TRIAL_WATCH, SKIP }

    public record TrialState(String status, List<String> phases) {
    }

    private static final Set<String> TRIAL_STATUSES = Set.of("NOT_YET_RECRUITING", "RECRUITING",
            "ENROLLING_BY_INVITATION", "ACTIVE_NOT_RECRUITING", "SUSPENDED", "TERMINATED",
            "COMPLETED", "WITHDRAWN", "AVAILABLE", "NO_LONGER_AVAILABLE", "TEMPORARILY_NOT_AVAILABLE",
            "APPROVED_FOR_MARKETING", "WITHHELD");

    static NewsEditorialEvidence from(NewsCurationModels.EvidenceSnapshot snapshot, ObjectMapper mapper) {
        License license = switch (Objects.toString(snapshot.abstractLicense(), "")) {
            case "CC0" -> License.CC0;
            case "CC-BY" -> License.CC_BY;
            default -> License.UNKNOWN;
        };
        if ("clinicaltrials-gov".equals(snapshot.source())) {
            JsonNode registry = readTrial(snapshot.trialJson(), mapper);
            String status = registry.path("overallStatus").asText("UNKNOWN");
            Set<String> phases = new TreeSet<>();
            registry.path("phases").forEach(phase -> {
                if (phase.isTextual()) {
                    phases.add(phase.textValue());
                }
            });
            StudyDesign design = switch (registry.path("studyType").asText()) {
                case "INTERVENTIONAL" -> StudyDesign.INTERVENTIONAL;
                case "OBSERVATIONAL" -> StudyDesign.OBSERVATIONAL;
                case "EXPANDED_ACCESS" -> StudyDesign.EXPANDED_ACCESS;
                default -> StudyDesign.UNKNOWN;
            };
            return new NewsEditorialEvidence(snapshot.source(), design, PeerReview.UNKNOWN,
                    ContentScope.TRIAL_REGISTRY, License.UNKNOWN, Integrity.UNKNOWN,
                    new TrialState(TRIAL_STATUSES.contains(status) ? status : "UNKNOWN", List.copyOf(phases)));
        }
        boolean licensed = license != License.UNKNOWN && snapshot.abstractText() != null
                && !snapshot.abstractText().isBlank();
        return new NewsEditorialEvidence(snapshot.source(), StudyDesign.UNKNOWN, PeerReview.UNKNOWN,
                licensed ? ContentScope.LICENSED_ABSTRACT : ContentScope.METADATA,
                licensed ? license : License.UNKNOWN, Integrity.UNKNOWN, null);
    }

    public ContentType route(NewsEditorialEvidence previous, boolean evidenceGatePassed) {
        if (!evidenceGatePassed || !NewsWireModels.SOURCES.contains(source)
                || integrity == Integrity.CONCERN || integrity == Integrity.RETRACTED) {
            return ContentType.SKIP;
        }
        if ("clinicaltrials-gov".equals(source)) {
            boolean changed = previous == null || !Objects.equals(trial, previous.trial)
                    || studyDesign != previous.studyDesign;
            return trial != null && !"UNKNOWN".equals(trial.status()) && changed
                    ? ContentType.TRIAL_WATCH : ContentType.SKIP;
        }
        if (contentScope == ContentScope.LICENSED_RESULTS && license != License.UNKNOWN
                && peerReview == PeerReview.VERIFIED && integrity == Integrity.VERIFIED
                && studyDesign != StudyDesign.UNKNOWN) {
            return ContentType.RESEARCH_INSIGHT;
        }
        return ContentType.RESEARCH_RADAR;
    }

    private static JsonNode readTrial(String json, ObjectMapper mapper) {
        if (json != null) {
            try {
                JsonNode parsed = mapper.readTree(json);
                if (parsed != null && parsed.isObject()) {
                    return parsed;
                }
            } catch (JsonProcessingException ignored) {
                // A malformed snapshot retains UNKNOWN attributes.
            }
        }
        return mapper.createObjectNode();
    }
}
