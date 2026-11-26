package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.newscuration.NewsCurationModels;

import java.time.Instant;
import java.util.List;

/**
 * Typed DTOs and response views for {@link NewsWorkbenchController} (WP-05).
 */
public final class NewsWorkbenchModels {

    private NewsWorkbenchModels() {
    }

    public record PageVO<T>(
            List<T> records,
            long total,
            long current,
            long size,
            long pages
    ) {
    }

    public record SourceCounts(
            int totalSources,
            int enabledSources
    ) {
    }

    public record TaskCounts(
            long pendingCount,
            long leasedCount,
            long retryScheduledCount,
            long completedCount,
            long failedCount,
            long expiredLeaseCount
    ) {
    }

    public record MaterialCounts(
            long visibleMaterialCount,
            long visibleRevisionCount
    ) {
    }

    public record CandidateCounts(
            long acceptedCount,
            long needsReviewCount,
            long rejectedCount,
            long evidenceViolationCount,
            long needsReconciliationCount,
            long draftedNewsCount
    ) {
    }

    public record ReceiptCounts(
            long unknownOutcomeCount,
            long inFlightCount,
            long staleInFlightCount,
            long succeededCount
    ) {
    }

    public record OverviewVO(
            SourceCounts sources,
            TaskCounts tasks,
            MaterialCounts materials,
            CandidateCounts candidates,
            ReceiptCounts receipts,
            BudgetSummaryVO todayBudget,
            boolean curationEnabled,
            BenchRunSummaryVO latestBenchRun
    ) {
    }

    public record SourceCursorVO(
            long sourceConfigVersion,
            String queryText,
            String committedCursor,
            Instant updatedAt
    ) {
    }

    public record SourceDetailVO(
            String source,
            boolean enabled,
            Instant updatedAt,
            long visibleMaterialCount,
            long pendingTaskCount,
            long leasedTaskCount,
            long failedTaskCount,
            long completedTaskCount,
            List<SourceCursorVO> cursors
    ) {
    }

    public record CollectionTaskVO(
            String taskId,
            String taskType,
            String source,
            long sourceConfigVersion,
            String queryText,
            String windowStartOn,
            String windowEndBefore,
            String status,
            long generation,
            int attemptCount,
            int maxAttempts,
            String leaseWorkerId,
            Instant leaseExpiresAt,
            boolean leaseExpired,
            Instant nextAttemptAt,
            String lastFailureCode,
            Integer committedBatchCount,
            Integer committedRecordCount,
            String committedCursor,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt,
            /**
             * 阻塞原因：更早的窗口任务没有完成时，本窗口不会被 Worker 领取。
             * 系统不自动跳过失败窗口，所以这里必须说清「卡在谁身上」。
             */
            String blockingReason,
            /** 阻塞本窗口的更早任务 ID；无阻塞时为 null。 */
            String blockedByTaskId,
            /** 是否处于「失败窗口」状态（可走失败窗口重试入口重新打开）。 */
            boolean retryable
    ) {
    }

    /**
     * Admin operation audit record for a collection window task (WP-07G).
     */
    public record TaskEventVO(
            long id,
            String taskId,
            String action,
            String fromStatus,
            String toStatus,
            Long actorId,
            String actorRole,
            String note,
            Instant createdAt
    ) {
    }

    /**
     * Public-to-admin lease audit view. Deliberately omits {@code leaseTokenHash}.
     */
    public record LeaseRecordVO(
            String taskId,
            long generation,
            String workerId,
            Instant leaseExpiresAt,
            Instant claimedAt
    ) {
    }

    public record CandidateSummaryVO(
            long id,
            long materialId,
            long materialRevisionId,
            String source,
            String sourceRecordId,
            String contentDigest,
            String policyVersion,
            String callId,
            String status,
            String decision,
            String evidenceTier,
            Integer relevanceScore,
            String suggestedTitle,
            String suggestedSummary,
            String category,
            String contentType,
            int claimsCount,
            int violationsCount,
            String evidenceSnapshotSha256,
            Long linkedNewsId,
            Integer linkedNewsStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CandidateDetailVO(
            long id,
            long materialId,
            long materialRevisionId,
            String source,
            String sourceRecordId,
            String contentDigest,
            String policyVersion,
            String callId,
            String status,
            String decision,
            String evidenceTier,
            Integer relevanceScore,
            String suggestedTitle,
            String suggestedSummary,
            String category,
            String contentType,
            List<String> limitations,
            List<NewsCurationModels.CurationClaim> claims,
            List<NewsCurationModels.EvidenceViolation> violations,
            String evidenceSnapshotSha256,
            String evidenceSnapshotJson,
            CallReceiptVO callReceipt,
            Long linkedNewsId,
            Integer linkedNewsStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record BudgetSummaryVO(
            String budgetDate,
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
            String circuitReason,
            Instant updatedAt
    ) {
    }

    public record CallReceiptVO(
            String callId,
            String idempotencyKey,
            long materialRevisionId,
            String policyVersion,
            int attemptNo,
            String budgetDate,
            String budgetScope,
            String provider,
            String modelName,
            String promptSha256,
            String state,
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
            Instant completedAt
    ) {
    }

    public record BenchRunSummaryVO(
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
            Instant createdAt
    ) {
    }

    public record SourceToggleRequest(Boolean enabled) {
    }

    public record RegisterSourceConfigRequest(
            Long sourceConfigVersion,
            String query,
            String initialCursor
    ) {
    }

    public record EnqueueTaskRequest(
            String taskId,
            String source,
            Long sourceConfigVersion,
            String query,
            String windowStartOn,
            String windowEndBefore,
            Integer maxAttempts
    ) {
    }

    /**
     * 失败窗口重试请求。{@code maxAttempts} 是**追加**的尝试次数，不是任务的总尝试上限；
     * {@code note} 会写进操作审计。
     */
    public record RetryTaskRequest(
            Integer maxAttempts,
            String note
    ) {
    }

    public record ConfigureBudgetRequest(
            String budgetDate,
            String scope,
            Integer maxCalls,
            Long maxTokens,
            Long maxCostMicros
    ) {
    }

    public record ResetCircuitRequest(
            String budgetDate,
            String scope
    ) {
    }

    public record ReconcileReceiptRequest(
            String resolution,
            String note,
            String recoveredRawJson,
            Integer promptTokens,
            Integer completionTokens,
            Long actualCostMicros,
            String providerRequestId
    ) {
    }

    /** One row of the human evidence verification ledger. */
    public record EvidenceVerificationVO(
            long id,
            long materialRevisionId,
            String attribute,
            String value,
            String method,
            String evidenceUrl,
            String sourceVersion,
            String evidenceNote,
            String verifiedBy,
            String verifiedAt,
            boolean active,
            String revokedBy,
            String revokedAt,
            String revokeNote
    ) {
    }

    public record SubmitEvidenceVerificationRequest(
            String attribute,
            String value,
            String method,
            String evidenceUrl,
            String sourceVersion,
            String evidenceNote
    ) {
    }

    public record RevokeEvidenceVerificationRequest(String note) {
    }

    /** Result of converting one frozen production model call into a review candidate. */
    public record MaterializedCandidateVO(
            long candidateId,
            String status,
            String callId,
            boolean created,
            String evidenceVerificationSha256
    ) {
    }
}
