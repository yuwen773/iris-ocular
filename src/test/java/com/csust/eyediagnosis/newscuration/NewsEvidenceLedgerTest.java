package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.EvidenceSnapshot;
import com.csust.eyediagnosis.newscuration.NewsEditorialEvidence.ContentType;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Active;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Attribute;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Method;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Overlay;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Request;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-08B.2 unit contract for the human evidence ledger: what a reviewer may record, what the overlay
 * digest commits to, and that verified evidence — not a model guess or an enum edit — is what unlocks
 * {@code RESEARCH_INSIGHT}.
 */
class NewsEvidenceLedgerTest {

    private final ObjectMapper mapper = JsonConfig.createNewsProtocolObjectMapper();
    private final EvidenceSnapshot licensed = snapshot("pubmed", "CC-BY", "Open-access multicenter glaucoma abstract");
    private final EvidenceSnapshot registry = snapshot("clinicaltrials-gov", null, null);

    @Test
    void submissionMustBeCompleteAndAllowlisted() {
        assertCode("EVIDENCE_VERIFICATION_INCOMPLETE", () -> NewsEvidenceLedger.validate(null, licensed));
        assertCode("EVIDENCE_VERIFICATION_INCOMPLETE", () -> NewsEvidenceLedger.validate(
                new Request(null, "VERIFIED", Method.PUBMED_RECORD, pubmedUrl(), "pubmed-2026", null), licensed));
        assertCode("EVIDENCE_VERIFICATION_INCOMPLETE", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", null, pubmedUrl(), "pubmed-2026", null), licensed));

        assertCode("EVIDENCE_VALUE_NOT_ALLOWED", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "TRUST_ME", Method.PUBMED_RECORD, pubmedUrl(), "pubmed-2026", null),
                licensed));
        assertCode("EVIDENCE_METHOD_NOT_ALLOWED", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.CLINICALTRIALS_RECORD, pubmedUrl(),
                        "pubmed-2026", null), licensed));

        // The URL must be an https record on the host that the chosen method belongs to.
        assertCode("EVIDENCE_URL_INVALID", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD,
                        "http://pubmed.ncbi.nlm.nih.gov/42000001/", "pubmed-2026", null), licensed));
        assertCode("EVIDENCE_URL_INVALID", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD,
                        "https://pubmed.ncbi.nlm.nih.gov/", "pubmed-2026", null), licensed));
        assertCode("EVIDENCE_URL_HOST_NOT_ALLOWED", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD,
                        "https://example.invalid/42000001/", "pubmed-2026", null), licensed));

        assertCode("EVIDENCE_SOURCE_VERSION_INVALID", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD, pubmedUrl(), "  ", null),
                licensed));
        assertCode("EVIDENCE_NOTE_INVALID", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD, pubmedUrl(), "pubmed-2026",
                        "<script>alert(1)</script>"), licensed));
        assertCode("EVIDENCE_NOTE_INVALID", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD, pubmedUrl(), "pubmed-2026",
                        "n".repeat(1025)), licensed));

        Request accepted = NewsEvidenceLedger.validate(new Request(Attribute.PEER_REVIEW, "verified",
                Method.PUBMED_RECORD, pubmedUrl(), " pubmed-2026 ", " 审阅 PubMed 记录 "), licensed);
        assertEquals("VERIFIED", accepted.value());
        assertEquals("pubmed-2026", accepted.sourceVersion());
        assertEquals("审阅 PubMed 记录", accepted.evidenceNote());
    }

    @Test
    void licenseOnlyConfirmsTheSnapshotAndScopeNeedsALicensedAbstract() {
        assertCode("EVIDENCE_LICENSE_CONFLICT", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.LICENSE, "CC0", Method.LICENSE_STATEMENT, pubmedUrl(), "doaj-2026", null),
                licensed));
        assertCode("EVIDENCE_LICENSE_CONFLICT", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.LICENSE, "CC_BY", Method.LICENSE_STATEMENT, pubmedUrl(), "doaj-2026", null),
                registry));
        assertEquals("CC_BY", NewsEvidenceLedger.validate(new Request(Attribute.LICENSE, "cc_by",
                Method.LICENSE_STATEMENT, pubmedUrl(), "doaj-2026", null), licensed).value());

        assertCode("EVIDENCE_LICENSE_UNKNOWN", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.CONTENT_SCOPE, "LICENSED_RESULTS", Method.DOI_LANDING_PAGE,
                        doiUrl(), "doi-2026", null), snapshot("pubmed", null, "abstract without license")));
        assertCode("EVIDENCE_SCOPE_NOT_APPLICABLE", () -> NewsEvidenceLedger.validate(
                new Request(Attribute.CONTENT_SCOPE, "LICENSED_RESULTS", Method.DOI_LANDING_PAGE,
                        doiUrl(), "doi-2026", null), registry));
        assertEquals("LICENSED_RESULTS", NewsEvidenceLedger.validate(new Request(Attribute.CONTENT_SCOPE,
                "LICENSED_RESULTS", Method.DOI_LANDING_PAGE, doiUrl(), "doi-2026", null), licensed).value());
    }

    @Test
    void overlayDigestIgnoresOrderAndTracksEveryRecordedFact() {
        Active peerReview = active(Attribute.PEER_REVIEW, "VERIFIED", Method.PUBMED_RECORD);
        Active integrity = active(Attribute.INTEGRITY, "VERIFIED", Method.PUBMED_RECORD);

        Overlay forward = Overlay.of(2L, List.of(peerReview, integrity));
        Overlay reversed = Overlay.of(2L, List.of(integrity, peerReview));
        assertEquals(forward.sha256(), reversed.sha256());
        assertEquals(List.of(Attribute.PEER_REVIEW, Attribute.INTEGRITY), forward.attributes().stream()
                .map(Active::attribute).toList());
        assertNotEquals(Overlay.empty(2L).sha256(), forward.sha256());
        assertEquals(Overlay.empty(2L).sha256(), Overlay.of(2L, List.of()).sha256());
        assertTrue(Overlay.empty(2L).isEmpty());
        assertNotEquals(forward.sha256(), Overlay.of(2L, List.of(peerReview)).sha256());
        assertNotEquals(forward.sha256(), Overlay.of(2L, List.of(peerReview, active(Attribute.INTEGRITY,
                "CONCERN", Method.RETRACTION_NOTICE))).sha256());
    }

    @Test
    void researchInsightNeedsEveryVerifiedGateAndIsNeverGeneratedFromGuesses() {
        assertEquals(NewsEditorialEvidence.ContentScope.LICENSED_ABSTRACT,
                NewsEditorialEvidence.from(licensed, mapper).contentScope());
        assertEquals(ContentType.RESEARCH_RADAR, NewsEditorialEvidence.from(licensed, mapper).route(null, true));

        // A peer-review record alone is not enough to claim verified results.
        assertEquals(ContentType.RESEARCH_RADAR, route(peerReviewOnly()));
        assertEquals(ContentType.RESEARCH_RADAR, route(without(Attribute.STUDY_DESIGN)));
        assertEquals(ContentType.SKIP, route(overlay(Attribute.PEER_REVIEW, "VERIFIED",
                Attribute.INTEGRITY, "CONCERN", Attribute.STUDY_DESIGN, "INTERVENTIONAL",
                Attribute.CONTENT_SCOPE, "LICENSED_RESULTS")));
        assertEquals(ContentType.RESEARCH_RADAR, route(overlay(Attribute.PEER_REVIEW, "NOT_PEER_REVIEWED",
                Attribute.INTEGRITY, "VERIFIED", Attribute.STUDY_DESIGN, "INTERVENTIONAL",
                Attribute.CONTENT_SCOPE, "LICENSED_RESULTS")));
        assertEquals(ContentType.RESEARCH_INSIGHT, route(fullVerification()));

        // Verified scope still needs a licensed abstract to quote from.
        assertCode("EVIDENCE_LICENSE_UNKNOWN", () -> NewsEditorialEvidence.from(
                snapshot("pubmed", null, "abstract without license"), mapper,
                overlay(Attribute.CONTENT_SCOPE, "LICENSED_RESULTS")));
        assertCode("EVIDENCE_OVERLAY_REVISION_MISMATCH", () -> NewsEditorialEvidence.from(
                licensed, mapper, Overlay.of(99L, fullVerification().attributes())));
    }

    private ContentType route(Overlay overlay) {
        return NewsEditorialEvidence.from(licensed, mapper, overlay).route(null, true);
    }

    private Overlay fullVerification() {
        return overlay(Attribute.PEER_REVIEW, "VERIFIED", Attribute.INTEGRITY, "VERIFIED",
                Attribute.STUDY_DESIGN, "OBSERVATIONAL", Attribute.CONTENT_SCOPE, "LICENSED_RESULTS");
    }

    private Overlay peerReviewOnly() {
        return overlay(Attribute.PEER_REVIEW, "VERIFIED");
    }

    private Overlay without(Attribute skipped) {
        return Overlay.of(2L, fullVerification().attributes().stream()
                .filter(active -> active.attribute() != skipped).toList());
    }

    private Overlay overlay(Object... pairs) {
        java.util.List<Active> actives = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Attribute attribute = (Attribute) pairs[i];
            actives.add(active(attribute, (String) pairs[i + 1], methodFor(attribute)));
        }
        return Overlay.of(2L, actives);
    }

    private Active active(Attribute attribute, String value, Method method) {
        return new Active(attribute, value, method, pubmedUrl(), "pubmed-2026", "admin:1001",
                Instant.parse("2026-11-26T08:00:00Z"));
    }

    private static Method methodFor(Attribute attribute) {
        return switch (attribute) {
            case PEER_REVIEW -> Method.PUBMED_RECORD;
            case INTEGRITY -> Method.PUBMED_RECORD;
            case STUDY_DESIGN -> Method.PUBMED_RECORD;
            case CONTENT_SCOPE, LICENSE -> Method.DOI_LANDING_PAGE;
        };
    }

    private static String pubmedUrl() {
        return "https://pubmed.ncbi.nlm.nih.gov/42000001/";
    }

    private static String doiUrl() {
        return "https://doi.org/10.1000/iris.2026.01";
    }

    private static EvidenceSnapshot snapshot(String source, String license, String text) {
        return new EvidenceSnapshot(1, 2, source, "fixture-id", "a".repeat(64),
                NewsCurationModels.EvidenceTier.METADATA_ONLY, "https://example.invalid", "Glaucoma cohort", null,
                null, null, null, null, text, license, null);
    }

    private static void assertCode(String expected, Executable executable) {
        assertEquals(expected, assertThrows(IllegalArgumentException.class, executable).getMessage());
    }
}
