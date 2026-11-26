package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.EvidenceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * WP-08B.2 human-verified editorial evidence ledger.
 *
 * <p>Only a reviewer can write these rows, and every row carries the source URL, verification method,
 * source version and reviewer. {@link NewsEditorialEvidence} merges an {@link Overlay} of the *active* rows
 * into the facts derived from the material snapshot; the routing gates themselves are unchanged, so a
 * verified attribute can unlock {@code RESEARCH_INSIGHT} only when every gate condition is satisfied by
 * evidence a human recorded — never by a model guess and never by editing an enum.</p>
 */
public final class NewsEvidenceLedger {

    private NewsEvidenceLedger() {
    }

    public enum Attribute {
        /** Editorial content scope; only {@code LICENSED_RESULTS} may be recorded here. */
        CONTENT_SCOPE,
        PEER_REVIEW,
        INTEGRITY,
        STUDY_DESIGN,
        /** Confirms the license already declared by the snapshot; it can never upgrade it. */
        LICENSE
    }

    public enum Method {
        PUBMED_RECORD,
        EUROPEPMC_RECORD,
        CLINICALTRIALS_RECORD,
        DOI_LANDING_PAGE,
        LICENSE_STATEMENT,
        PEER_REVIEW_STATEMENT,
        RETRACTION_NOTICE
    }

    private static final Map<Attribute, Set<String>> VALUES = new EnumMap<>(Attribute.class);
    private static final Map<Attribute, Set<Method>> METHODS = new EnumMap<>(Attribute.class);
    private static final Map<Method, Set<String>> HOSTS = new EnumMap<>(Method.class);

    static {
        VALUES.put(Attribute.CONTENT_SCOPE, Set.of("LICENSED_RESULTS"));
        VALUES.put(Attribute.PEER_REVIEW, Set.of("VERIFIED", "NOT_PEER_REVIEWED"));
        VALUES.put(Attribute.INTEGRITY, Set.of("VERIFIED", "CONCERN", "RETRACTED"));
        VALUES.put(Attribute.STUDY_DESIGN, Set.of("INTERVENTIONAL", "OBSERVATIONAL", "EXPANDED_ACCESS", "OTHER_VERIFIED"));
        VALUES.put(Attribute.LICENSE, Set.of("CC0", "CC_BY"));

        METHODS.put(Attribute.CONTENT_SCOPE, Set.of(Method.LICENSE_STATEMENT, Method.DOI_LANDING_PAGE,
                Method.PUBMED_RECORD, Method.EUROPEPMC_RECORD));
        METHODS.put(Attribute.PEER_REVIEW, Set.of(Method.PEER_REVIEW_STATEMENT, Method.PUBMED_RECORD,
                Method.EUROPEPMC_RECORD));
        METHODS.put(Attribute.INTEGRITY, Set.of(Method.RETRACTION_NOTICE, Method.PUBMED_RECORD,
                Method.EUROPEPMC_RECORD));
        METHODS.put(Attribute.STUDY_DESIGN, Set.of(Method.PUBMED_RECORD, Method.EUROPEPMC_RECORD,
                Method.CLINICALTRIALS_RECORD));
        METHODS.put(Attribute.LICENSE, Set.of(Method.LICENSE_STATEMENT, Method.DOI_LANDING_PAGE));

        Set<String> scholarly = Set.of("pubmed.ncbi.nlm.nih.gov", "europepmc.org", "www.europepmc.org");
        HOSTS.put(Method.PUBMED_RECORD, Set.of("pubmed.ncbi.nlm.nih.gov"));
        HOSTS.put(Method.EUROPEPMC_RECORD, Set.of("europepmc.org", "www.europepmc.org"));
        HOSTS.put(Method.CLINICALTRIALS_RECORD, Set.of("clinicaltrials.gov", "www.clinicaltrials.gov"));
        HOSTS.put(Method.DOI_LANDING_PAGE, Set.of("doi.org", "dx.doi.org", "www.doi.org"));
        HOSTS.put(Method.LICENSE_STATEMENT, scholarly);
        HOSTS.put(Method.PEER_REVIEW_STATEMENT, scholarly);
        HOSTS.put(Method.RETRACTION_NOTICE, Set.of("pubmed.ncbi.nlm.nih.gov", "europepmc.org", "www.europepmc.org",
                "retractionwatch.com", "www.retractionwatch.com"));
    }

    /** A ledger row as stored; refused rows never exist, so this type is always well-formed. */
    public record Verification(long id, long materialRevisionId, Attribute attribute, String value, Method method,
                               String evidenceUrl, String sourceVersion, String evidenceNote,
                               String verifiedBy, java.time.Instant verifiedAt,
                               String revokedBy, java.time.Instant revokedAt, String revokeNote) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    /** Reviewer-submitted verification; validated against the frozen snapshot before it is stored. */
    public record Request(Attribute attribute, String value, Method method, String evidenceUrl,
                          String sourceVersion, String evidenceNote) {
    }

    /** Compact, order-stable view of one active verification, frozen into the model call identity. */
    public record Active(Attribute attribute, String value, Method method, String evidenceUrl,
                         String sourceVersion, String verifiedBy, java.time.Instant verifiedAt) {
    }

    /** Same mapper family as the call identity, so {@link Active#verifiedAt()} digests deterministically. */
    private static final ObjectMapper CANONICAL = JsonConfig.createNewsProtocolObjectMapper();

    /**
     * Active overlay for one material revision plus the digest of exactly what was frozen.
     * An empty overlay means the snapshot's own attributes are used unchanged.
     */
    public record Overlay(long materialRevisionId, List<Active> attributes, String sha256) {

        public static Overlay empty(long materialRevisionId) {
            return new Overlay(materialRevisionId, List.of(), digest(List.of()));
        }

        public static Overlay of(long materialRevisionId, List<Active> attributes) {
            List<Active> sorted = attributes.stream()
                    .sorted((a, b) -> a.attribute().compareTo(b.attribute()))
                    .toList();
            return new Overlay(materialRevisionId, sorted, digest(sorted));
        }

        public boolean isEmpty() {
            return attributes.isEmpty();
        }

        public Map<Attribute, Active> byAttribute() {
            Map<Attribute, Active> map = new EnumMap<>(Attribute.class);
            for (Active active : attributes) {
                map.put(active.attribute(), active);
            }
            return map;
        }

        private static String digest(List<Active> attributes) {
            try {
                return NewsModelProtocol.sha256(CANONICAL.writeValueAsString(attributes));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Cannot freeze evidence overlay", ex);
            }
        }
    }

    /**
     * Validates a reviewer submission against the frozen snapshot.
     *
     * @throws IllegalArgumentException with a stable code prefix when the submission is not verifiable
     */
    public static Request validate(Request request, EvidenceSnapshot snapshot) {
        if (request == null || request.attribute() == null || request.method() == null
                || request.value() == null || request.evidenceUrl() == null || request.sourceVersion() == null) {
            throw new IllegalArgumentException("EVIDENCE_VERIFICATION_INCOMPLETE");
        }
        String value = request.value().trim().toUpperCase(Locale.ROOT);
        if (!VALUES.get(request.attribute()).contains(value)) {
            throw new IllegalArgumentException("EVIDENCE_VALUE_NOT_ALLOWED");
        }
        if (!METHODS.get(request.attribute()).contains(request.method())) {
            throw new IllegalArgumentException("EVIDENCE_METHOD_NOT_ALLOWED");
        }
        String url = requireHttpsUrl(request.evidenceUrl(), HOSTS.get(request.method()));
        if (!NewsModelProfile.identifier(request.sourceVersion().trim(), 64)) {
            throw new IllegalArgumentException("EVIDENCE_SOURCE_VERSION_INVALID");
        }
        String note = request.evidenceNote() == null ? null : request.evidenceNote().trim();
        if (note != null && (note.length() > 1024 || note.contains("<"))) {
            throw new IllegalArgumentException("EVIDENCE_NOTE_INVALID");
        }
        // Editorial facts can never be invented where the snapshot has nothing to verify against.
        if (request.attribute() == Attribute.LICENSE) {
            String declared = declaredLicense(snapshot);
            if (!value.equals(declared)) {
                throw new IllegalArgumentException("EVIDENCE_LICENSE_CONFLICT");
            }
        }
        if ("clinicaltrials-gov".equals(snapshot.source()) && request.attribute() == Attribute.CONTENT_SCOPE) {
            throw new IllegalArgumentException("EVIDENCE_SCOPE_NOT_APPLICABLE");
        }
        if (request.attribute() == Attribute.CONTENT_SCOPE && declaredLicense(snapshot).equals("UNKNOWN")) {
            throw new IllegalArgumentException("EVIDENCE_LICENSE_UNKNOWN");
        }
        return new Request(request.attribute(), value, request.method(), url, request.sourceVersion().trim(), note);
    }

    /** Maps the snapshot's declared abstract license to the ledger vocabulary. */
    public static String declaredLicense(EvidenceSnapshot snapshot) {
        String declared = Objects.toString(snapshot.abstractLicense(), "");
        return switch (declared) {
            case "CC0" -> "CC0";
            case "CC-BY" -> "CC_BY";
            default -> "UNKNOWN";
        };
    }

    private static String requireHttpsUrl(String raw, Set<String> hosts) {
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("EVIDENCE_URL_INVALID");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawQuery() != null && uri.getRawQuery().length() > 512
                || trimmed.length() > 512 || uri.getPath() == null || uri.getPath().equals("/")) {
            throw new IllegalArgumentException("EVIDENCE_URL_INVALID");
        }
        if (!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("EVIDENCE_URL_HOST_NOT_ALLOWED");
        }
        return trimmed;
    }
}
