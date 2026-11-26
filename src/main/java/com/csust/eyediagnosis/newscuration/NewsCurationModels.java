package com.csust.eyediagnosis.newscuration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Strongly typed domain, receipt, evidence-verification, and NewsBench models for WP-03.
 */
public final class NewsCurationModels {

    private NewsCurationModels() {
    }

    public enum Decision {
        ACCEPT,
        NEEDS_REVIEW,
        REJECT
    }

    public enum EvidenceTier {
        /** Historical codec value retained for frozen WP-03 snapshots. */
        PEER_REVIEWED_ABSTRACT,
        LICENSED_ABSTRACT,
        CLINICAL_TRIAL_REGISTRY,
        METADATA_ONLY
    }

    public enum CandidateStatus {
        CURATED_ACCEPTED,
        CURATED_NEEDS_REVIEW,
        CURATED_REJECTED,
        REJECTED_EVIDENCE_VIOLATION,
        NEEDS_RECONCILIATION
    }

    public enum CallState {
        RESERVED,
        DISPATCHED,
        SUCCEEDED,
        FAILED_DEFINITIVE,
        ABORTED_PRE_IO,
        OUTCOME_UNKNOWN,
        FAILED_CHARGED,
        FAILED_UNCHARGED
    }

    public enum ReconcileResolution {
        CONFIRMED_CHARGED_WITH_OUTPUT,
        CONFIRMED_CHARGED_NO_OUTPUT,
        CONFIRMED_NOT_CHARGED
    }

    public record EvidenceSnapshot(
            long materialId,
            long materialRevisionId,
            String source,
            String sourceRecordId,
            String contentDigest,
            EvidenceTier evidenceTier,
            String url,
            String title,
            String language,
            String publishedOn,
            LocalDate updatedOn,
            String pmid,
            String doi,
            String abstractText,
            String abstractLicense,
            String trialJson
    ) {
    }

    public record CurationClaim(
            String id,
            String field,
            String statement,
            String quote
    ) {
    }

    public record ModelCurationOutput(
            Decision decision,
            int relevanceScore,
            String suggestedTitle,
            String suggestedSummary,
            String category,
            List<String> limitations,
            List<CurationClaim> claims
    ) {
    }

    public record EvidenceViolation(
            String code,
            String target,
            String message
    ) {
    }

    public record VerificationResult(
            boolean valid,
            CandidateStatus effectiveStatus,
            ModelCurationOutput parsedOutput,
            List<EvidenceViolation> violations
    ) {
    }

    public record ModelCallRequest(
            String callId,
            String idempotencyKey,
            String provider,
            String modelName,
            String policyVersion,
            String systemPrompt,
            String userPrompt,
            String promptSha256,
            EvidenceSnapshot evidenceSnapshot,
            Duration timeout,
            String profileKey,
            String outputSchema
    ) {
        public ModelCallRequest(String callId, String idempotencyKey, String provider, String modelName,
                                String policyVersion, String systemPrompt, String userPrompt, String promptSha256,
                                EvidenceSnapshot evidenceSnapshot, Duration timeout) {
            this(callId, idempotencyKey, provider, modelName, policyVersion, systemPrompt, userPrompt,
                    promptSha256, evidenceSnapshot, timeout, null, null);
        }
    }

    public sealed interface ModelCallOutcome permits Succeeded, ChargedFailure, DefinitiveFailure, UnknownOutcome {
    }

    public record Succeeded(
            String rawJson,
            int promptTokens,
            int completionTokens,
            long actualCostMicros,
            int httpStatus,
            String providerRequestId,
            int latencyMs,
            Integer cachedPromptTokens
    ) implements ModelCallOutcome {
        public Succeeded(String rawJson, int promptTokens, int completionTokens, long actualCostMicros,
                         int httpStatus, String providerRequestId, int latencyMs) {
            this(rawJson, promptTokens, completionTokens, actualCostMicros, httpStatus, providerRequestId, latencyMs, null);
        }
    }

    public record ChargedFailure(String errorCode, Succeeded usage) implements ModelCallOutcome {
    }

    public record DefinitiveFailure(
            String errorCode,
            Integer httpStatus,
            String providerRequestId,
            boolean authFailure,
            int latencyMs
    ) implements ModelCallOutcome {
    }

    public record UnknownOutcome(
            String errorCode,
            Integer httpStatus,
            String providerRequestId,
            int latencyMs
    ) implements ModelCallOutcome {
    }

    public record CurationResult(
            boolean replayed,
            CandidateStatus status,
            String callId,
            Decision decision,
            EvidenceTier evidenceTier,
            Integer relevanceScore,
            String suggestedTitle,
            String suggestedSummary,
            String category,
            List<String> limitations,
            List<CurationClaim> claims,
            List<EvidenceViolation> violations,
            String evidenceSnapshotSha256,
            String evidenceSnapshotJson
    ) {
    }

    public record CallReceiptSnapshot(
            String callId,
            String idempotencyKey,
            long materialRevisionId,
            String policyVersion,
            int attemptNo,
            LocalDate budgetDate,
            String budgetScope,
            String provider,
            String modelName,
            String promptSha256,
            CallState state,
            long reservedTokens,
            long reservedCostMicros,
            Integer promptTokens,
            Integer completionTokens,
            Long actualCostMicros,
            Integer httpStatus,
            String providerRequestId,
            String responseSha256,
            String errorCode,
            Integer latencyMs,
            String reconciledBy,
            String reconcileNote,
            Instant reconciledAt,
            Instant reservedAt,
            Instant dispatchedAt,
            Instant deadlineAt,
            Instant completedAt,
            String logicalCallSha256,
            Integer cachedPromptTokens
    ) {
    }

    public record BudgetSnapshot(
            LocalDate budgetDate,
            String scope,
            int maxCalls,
            int usedCalls,
            int reservedCalls,
            long maxTokens,
            long usedTokens,
            long reservedTokens,
            long maxCostMicros,
            long usedCostMicros,
            long reservedCostMicros,
            boolean circuitBroken,
            String circuitReason
    ) {
    }

    public record StaleReclaimResult(int abortedPreIoCount, int markedUnknownCount) {
    }

    public record NewsBenchCaseResult(
            String caseId,
            String kind,
            boolean passed,
            String expectedStatus,
            String actualStatus,
            String detail
    ) {
    }

    public record NewsBenchReport(
            String runId,
            String datasetVersion,
            String datasetSha256,
            String policyVersion,
            String mode,
            int totalCases,
            int passedCases,
            int precisionBps,
            int recallBps,
            int evidenceCatchBps,
            int licenseComplianceBps,
            int injectionCatchBps,
            int totalCalls,
            long totalTokens,
            long totalCostMicros,
            boolean gatePassed,
            List<NewsBenchCaseResult> caseResults
    ) {
    }
}
