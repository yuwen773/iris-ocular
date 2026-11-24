package com.csust.eyediagnosis.newsingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Strongly typed Java bindings matching {@code contracts/news/v1.schema.json}.
 */
public final class NewsWireModels {

    public static final int MAX_BATCH_RECORDS = 50;
    public static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    public static final int MAX_WINDOW_RECORDS = 10_000;
    public static final int MAX_WINDOW_BATCHES = 10_000;
    public static final long MAX_SAFE_INT = 9_007_199_254_740_991L;

    public static final Set<String> SOURCES = Set.of("pubmed", "europe-pmc", "clinicaltrials-gov");
    public static final Set<String> ABSTRACT_LICENSES = Set.of("CC0", "CC-BY");

    private NewsWireModels() {
    }

    public enum FailureCode {
        BAD_SOURCE_DATA,
        DEADLINE_EXCEEDED,
        INTERRUPTED,
        CONTROL_UNAVAILABLE,
        CONTROL_REJECTED,
        SOURCE_BLOCKED,
        SOURCE_REJECTED,
        SOURCE_UNAVAILABLE,
        RESPONSE_TOO_LARGE,
        WINDOW_TOO_LARGE,
        INTERNAL
    }

    public record CollectionWindow(
            String source,
            long sourceConfigVersion,
            String query,
            LocalDate startOn,
            LocalDate endBefore
    ) {
    }

    public record Lease(
            String taskId,
            long generation,
            String leaseToken,
            Instant leaseExpiresAt,
            CollectionWindow window
    ) {
    }

    public record TrialDetails(
            String studyType,
            String overallStatus,
            List<String> phases,
            List<String> conditions
    ) {
    }

    public record Material(
            String source,
            String sourceRecordId,
            String url,
            String title,
            String language,
            String publishedOn,
            LocalDate updatedOn,
            String pmid,
            String doi,
            String abstractText,
            String abstractLicense,
            TrialDetails trial
    ) {
    }

    public record Batch(
            long generation,
            String leaseToken,
            int sequence,
            List<Material> records
    ) {
    }

    public record BatchReceipt(int sequence, int recordCount) {
    }

    public record Complete(
            long generation,
            String leaseToken,
            int batchCount,
            int recordCount,
            LocalDate nextCursor
    ) {
    }

    public record CompleteReceipt(LocalDate committedCursor) {
    }

    public record Fail(
            long generation,
            String leaseToken,
            FailureCode code,
            boolean retryable
    ) {
    }

    public record FailureReceipt(String status) {
    }

    public record ControlError(String code) {
    }
}
