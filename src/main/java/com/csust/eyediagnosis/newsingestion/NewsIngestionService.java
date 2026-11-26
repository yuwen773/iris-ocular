package com.csust.eyediagnosis.newsingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Transactional scheduling, lease fencing, idempotency receipt, and material pool service (WP-02).
 *
 * <p>All lease validity and retry backoff comparisons run against MySQL {@code UTC_TIMESTAMP(6)} so
 * JVM clock skew or local default timezones never weaken lease fencing. Staged batches remain
 * invisible to editorial queries until {@code complete()} commits atomically.
 */
@Slf4j
@Service
public class NewsIngestionService {

    private static final int PROMOTE_CHUNK_SIZE = 200;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final DateTimeFormatter DB_UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate taskRetryTransactions;
    private final NewsIngestionProperties properties;
    private final NewsWireCodec codec;

    @Autowired
    public NewsIngestionService(JdbcTemplate jdbc,
                                PlatformTransactionManager transactionManager,
                                NewsIngestionProperties properties,
                                NewsWireCodec codec) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.taskRetryTransactions = new TransactionTemplate(transactionManager);
        this.taskRetryTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.properties = properties;
        this.codec = codec;
    }

    public NewsIngestionService(JdbcTemplate jdbc, NewsIngestionProperties properties, NewsWireCodec codec) {
        this(jdbc, new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())), properties, codec);
    }

    public record TaskSnapshot(
            String taskId,
            String source,
            long sourceConfigVersion,
            String query,
            LocalDate windowStartOn,
            LocalDate windowEndBefore,
            String status,
            long generation,
            int attemptCount,
            int maxAttempts,
            String leaseWorkerId,
            Instant leaseExpiresAt,
            Instant nextAttemptAt,
            String lastFailureCode,
            Integer committedBatchCount,
            Integer committedRecordCount,
            LocalDate committedCursor,
            Instant completedAt
    ) {
    }

    public record VisibleMaterial(
            long materialId,
            String source,
            String sourceRecordId,
            Long latestRevisionId,
            String latestContentDigest,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }

    public record VisibleRevision(
            long revisionId,
            long materialId,
            String source,
            String sourceRecordId,
            String contentDigest,
            String url,
            String title,
            String language,
            String publishedOn,
            LocalDate updatedOn,
            String pmid,
            String doi,
            String abstractText,
            String abstractLicense,
            String trialJson,
            String canonicalJson,
            String firstTaskId,
            long firstGeneration,
            Instant visibleAt
    ) {
    }

    public void registerSourceConfig(String source, long sourceConfigVersion, String query, LocalDate initialCursor) {
        validateSourceAndConfig(source, sourceConfigVersion, query);
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT IGNORE INTO t_news_source (source, enabled) VALUES (?, 1)", source);
            lockOrCreateCursorRow(source, sourceConfigVersion, query.trim(), initialCursor);
        });
    }

    public void enqueueWindow(String taskId, String source, long sourceConfigVersion, String query,
                              LocalDate startOn, LocalDate endBefore, int maxAttempts) {
        if (!NewsWireCodec.isValidIdentifier(taskId)
                || startOn == null || endBefore == null || !startOn.isBefore(endBefore)
                || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid collection window parameters");
        }
        validateSourceAndConfig(source, sourceConfigVersion, query);
        String trimmedQuery = query.trim();
        // REQUIRED joins the workbench transaction when creation needs an operation audit.
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT IGNORE INTO t_news_source (source, enabled) VALUES (?, 1)", source);
            jdbc.update("""
                    INSERT INTO t_news_collection_task
                        (taskId, source, sourceConfigVersion, queryText, windowStartOn, windowEndBefore, maxAttempts, nextAttemptAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """,
                    taskId, source, sourceConfigVersion, trimmedQuery,
                    startOn.toString(), endBefore.toString(), maxAttempts);
            lockOrCreateCursorRow(source, sourceConfigVersion, trimmedQuery, null);
        });
    }

    /** Reopens the observed failed generation inside the caller's task-audit transaction. */
    public boolean reopenFailedWindow(TaskSnapshot task, int extraAttempts) {
        if (extraAttempts < 1) {
            throw new IllegalArgumentException("Invalid retry attempt count");
        }
        return Boolean.TRUE.equals(taskRetryTransactions.execute(status -> {
            jdbc.update("INSERT IGNORE INTO t_news_source (source, enabled) VALUES (?, 1)", task.source());
            int reopened = jdbc.update("""
                    UPDATE t_news_collection_task
                    SET status = 'PENDING', maxAttempts = attemptCount + ?, nextAttemptAt = UTC_TIMESTAMP(6)
                    WHERE taskId = ? AND source = ? AND sourceConfigVersion = ?
                      AND BINARY queryText = BINARY ? AND windowStartOn = ? AND windowEndBefore = ?
                      AND status = 'FAILED' AND generation = ?
                    """, extraAttempts, task.taskId(), task.source(), task.sourceConfigVersion(), task.query(),
                    task.windowStartOn().toString(), task.windowEndBefore().toString(), task.generation());
            if (reopened == 1) {
                lockOrCreateCursorRow(task.source(), task.sourceConfigVersion(), task.query(), null);
            }
            return reopened == 1;
        }));
    }

    /** Enables or disables a registered news source for worker claim scheduling. */
    public boolean setSourceEnabled(String source, boolean enabled) {
        if (source == null || !NewsWireModels.SOURCES.contains(source)) {
            throw new IllegalArgumentException("Unsupported news source: " + source);
        }
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO t_news_source (source, enabled)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)
                """, source, enabled ? 1 : 0));
        return enabled;
    }

    public Optional<NewsWireModels.Lease> claim(NewsWorkerIdentity worker) {
        Objects.requireNonNull(worker, "worker must not be null");
        TreeSet<String> sources = new TreeSet<>(worker.allowedSources());
        for (String source : sources) {
            if (!NewsWireModels.SOURCES.contains(source)) {
                continue;
            }
            Optional<NewsWireModels.Lease> lease = Optional.ofNullable(
                    transactions.execute(status -> claimForSource(worker, source)));
            if (lease.isPresent()) {
                return lease;
            }
        }
        return Optional.empty();
    }

    public String submitBatch(NewsWorkerIdentity worker, String taskId, String idempotencyKey, byte[] rawBody) {
        Objects.requireNonNull(worker, "worker must not be null");
        if (!NewsWireCodec.isValidIdentifier(taskId)) {
            throw NewsControlException.invalidRequest();
        }
        NewsWireModels.Batch batch = codec.parseBatch(rawBody);
        String expectedKey = batch.generation() + ":" + batch.sequence();
        if (!expectedKey.equals(idempotencyKey)) {
            throw NewsControlException.invalidRequest();
        }
        byte[] bodySha256Bytes = NewsMaterialCanonicalizer.sha256Hex(rawBody).getBytes(StandardCharsets.US_ASCII);
        String bodySha256 = new String(bodySha256Bytes, StandardCharsets.US_ASCII);

        return transactions.execute(status -> {
            TaskRow task = lockTask(taskId);
            verifyWorkerAccessAndGenerationOwnership(worker, task, batch.generation());

            Optional<String> replayed = checkIdempotencyReceipt(
                    taskId, batch.generation(), "batches", idempotencyKey, worker.workerId(), bodySha256Bytes);
            if (replayed.isPresent()) {
                return replayed.get();
            }

            verifyActiveLease(task, worker.workerId(), batch.generation(), batch.leaseToken());

            for (NewsWireModels.Material record : batch.records()) {
                if (!task.source.equals(record.source())) {
                    throw NewsControlException.invalidRequest();
                }
            }
            Integer existingRecords = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(recordCount), 0)
                    FROM t_news_collection_batch
                    WHERE taskId = ? AND generation = ?
                    """, Integer.class, taskId, batch.generation());
            int totalAfter = (existingRecords == null ? 0 : existingRecords) + batch.records().size();
            if (totalAfter > NewsWireModels.MAX_WINDOW_RECORDS) {
                throw NewsControlException.invalidRequest();
            }

            jdbc.update("""
                    INSERT INTO t_news_collection_batch (taskId, generation, sequence, recordCount)
                    VALUES (?, ?, ?, ?)
                    """, taskId, batch.generation(), batch.sequence(), batch.records().size());

            List<Object[]> itemArgs = new ArrayList<>(batch.records().size());
            for (int i = 0; i < batch.records().size(); i++) {
                NewsWireModels.Material material = batch.records().get(i);
                String canonicalJson = NewsMaterialCanonicalizer.canonicalMaterialJson(material);
                String contentDigest = NewsMaterialCanonicalizer.sha256Hex(canonicalJson.getBytes(StandardCharsets.UTF_8));
                itemArgs.add(new Object[]{
                        taskId,
                        batch.generation(),
                        batch.sequence(),
                        i,
                        material.source(),
                        material.sourceRecordId(),
                        contentDigest,
                        canonicalJson
                });
            }
            jdbc.batchUpdate("""
                    INSERT INTO t_news_batch_item
                        (taskId, generation, sequence, itemIndex, source, sourceRecordId, contentDigest, canonicalJson)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, itemArgs);

            String responseJson = codec.writeBatchReceipt(
                    new NewsWireModels.BatchReceipt(batch.sequence(), batch.records().size()));
            insertReceipt(taskId, batch.generation(), "batches", idempotencyKey,
                    worker.workerId(), bodySha256, responseJson);
            return responseJson;
        });
    }

    public String complete(NewsWorkerIdentity worker, String taskId, String idempotencyKey, byte[] rawBody) {
        Objects.requireNonNull(worker, "worker must not be null");
        if (!NewsWireCodec.isValidIdentifier(taskId)) {
            throw NewsControlException.invalidRequest();
        }
        NewsWireModels.Complete complete = codec.parseComplete(rawBody);
        String expectedKey = complete.generation() + ":complete";
        if (!expectedKey.equals(idempotencyKey)) {
            throw NewsControlException.invalidRequest();
        }
        byte[] bodySha256Bytes = NewsMaterialCanonicalizer.sha256Hex(rawBody).getBytes(StandardCharsets.US_ASCII);
        String bodySha256 = new String(bodySha256Bytes, StandardCharsets.US_ASCII);

        return transactions.execute(status -> {
            TaskRow task = lockTask(taskId);
            verifyWorkerAccessAndGenerationOwnership(worker, task, complete.generation());

            Optional<String> replayed = checkIdempotencyReceipt(
                    taskId, complete.generation(), "complete", idempotencyKey, worker.workerId(), bodySha256Bytes);
            if (replayed.isPresent()) {
                return replayed.get();
            }

            verifyActiveLease(task, worker.workerId(), complete.generation(), complete.leaseToken());

            List<BatchHeaderRow> batches = jdbc.query("""
                    SELECT sequence, recordCount
                    FROM t_news_collection_batch
                    WHERE taskId = ? AND generation = ?
                    ORDER BY sequence ASC
                    """, (rs, rowNum) -> new BatchHeaderRow(rs.getInt("sequence"), rs.getInt("recordCount")),
                    taskId, complete.generation());

            if (batches.size() != complete.batchCount() || !complete.nextCursor().equals(task.windowEndBefore)) {
                throw NewsControlException.incompleteBatches();
            }
            int summedRecords = 0;
            for (int i = 0; i < batches.size(); i++) {
                BatchHeaderRow row = batches.get(i);
                if (row.sequence != i + 1) {
                    throw NewsControlException.incompleteBatches();
                }
                summedRecords += row.recordCount;
            }
            if (summedRecords != complete.recordCount()) {
                throw NewsControlException.incompleteBatches();
            }

            CursorRow cursorRow = lockOrCreateCursorRow(task.source, task.sourceConfigVersion, task.queryText, null);
            boolean shouldAdvanceCursor = cursorRow.committedCursor == null
                    || task.windowEndBefore.isAfter(cursorRow.committedCursor);
            if (shouldAdvanceCursor) {
                if (cursorRow.committedCursor != null && task.windowStartOn.isAfter(cursorRow.committedCursor)) {
                    throw NewsControlException.staleLease();
                }
                Integer earlierUnfinished = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM t_news_collection_task
                        WHERE source = ?
                          AND sourceConfigVersion = ?
                          AND taskId <> ?
                          AND status <> 'COMPLETED'
                          AND (windowStartOn < ? OR (windowStartOn = ? AND windowEndBefore < ?))
                        """, Integer.class,
                        task.source, task.sourceConfigVersion, taskId,
                        task.windowStartOn.toString(), task.windowStartOn.toString(), task.windowEndBefore.toString());
                if (earlierUnfinished != null && earlierUnfinished > 0) {
                    throw NewsControlException.staleLease();
                }
            }

            String visibleAtDb = jdbc.queryForObject(
                    "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%d %H:%i:%s.%f')", String.class);

            if (complete.batchCount() > 0) {
                promoteStagedItems(taskId, complete.generation(), task.source, visibleAtDb);
            }

            if (shouldAdvanceCursor) {
                jdbc.update("""
                        UPDATE t_news_source_cursor
                        SET committedCursor = ?
                        WHERE source = ? AND sourceConfigVersion = ?
                        """, task.windowEndBefore.toString(), task.source, task.sourceConfigVersion);
            }

            jdbc.update("""
                    UPDATE t_news_collection_task
                    SET status = 'COMPLETED',
                        committedBatchCount = ?,
                        committedRecordCount = ?,
                        committedCursor = ?,
                        completedAt = ?
                    WHERE taskId = ?
                    """,
                    complete.batchCount(), complete.recordCount(),
                    complete.nextCursor().toString(), visibleAtDb, taskId);

            String responseJson = codec.writeCompleteReceipt(
                    new NewsWireModels.CompleteReceipt(complete.nextCursor()));
            insertReceipt(taskId, complete.generation(), "complete", idempotencyKey,
                    worker.workerId(), bodySha256, responseJson);
            return responseJson;
        });
    }

    public String fail(NewsWorkerIdentity worker, String taskId, String idempotencyKey, byte[] rawBody) {
        Objects.requireNonNull(worker, "worker must not be null");
        if (!NewsWireCodec.isValidIdentifier(taskId)) {
            throw NewsControlException.invalidRequest();
        }
        NewsWireModels.Fail fail = codec.parseFail(rawBody);
        String expectedKey = fail.generation() + ":fail";
        if (!expectedKey.equals(idempotencyKey)) {
            throw NewsControlException.invalidRequest();
        }
        byte[] bodySha256Bytes = NewsMaterialCanonicalizer.sha256Hex(rawBody).getBytes(StandardCharsets.US_ASCII);
        String bodySha256 = new String(bodySha256Bytes, StandardCharsets.US_ASCII);

        return transactions.execute(status -> {
            TaskRow task = lockTask(taskId);
            verifyWorkerAccessAndGenerationOwnership(worker, task, fail.generation());

            Optional<String> replayed = checkIdempotencyReceipt(
                    taskId, fail.generation(), "fail", idempotencyKey, worker.workerId(), bodySha256Bytes);
            if (replayed.isPresent()) {
                return replayed.get();
            }

            verifyActiveLease(task, worker.workerId(), fail.generation(), fail.leaseToken());

            boolean retryScheduled = fail.retryable() && task.attemptCount < task.maxAttempts;
            String nextStatus = retryScheduled ? "RETRY_SCHEDULED" : "FAILED";
            if (retryScheduled) {
                long backoffMicros = computeBackoffMicros(task.attemptCount);
                jdbc.update("""
                        UPDATE t_news_collection_task
                        SET status = 'RETRY_SCHEDULED',
                            lastFailureCode = ?,
                            nextAttemptAt = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? MICROSECOND)
                        WHERE taskId = ?
                        """, fail.code().name(), backoffMicros, taskId);
            } else {
                jdbc.update("""
                        UPDATE t_news_collection_task
                        SET status = 'FAILED',
                            lastFailureCode = ?
                        WHERE taskId = ?
                        """, fail.code().name(), taskId);
            }

            String responseJson = codec.writeFailureReceipt(new NewsWireModels.FailureReceipt(nextStatus));
            insertReceipt(taskId, fail.generation(), "fail", idempotencyKey,
                    worker.workerId(), bodySha256, responseJson);
            return responseJson;
        });
    }

    public Optional<TaskSnapshot> getTask(String taskId) {
        List<TaskSnapshot> rows = jdbc.query("""
                SELECT taskId, source, sourceConfigVersion, queryText,
                       DATE_FORMAT(windowStartOn, '%Y-%m-%d') AS windowStartOn,
                       DATE_FORMAT(windowEndBefore, '%Y-%m-%d') AS windowEndBefore,
                       status, generation, attemptCount, maxAttempts, leaseWorkerId,
                       DATE_FORMAT(leaseExpiresAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS leaseExpiresAtUtc,
                       DATE_FORMAT(nextAttemptAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS nextAttemptAtUtc,
                       lastFailureCode, committedBatchCount, committedRecordCount,
                       DATE_FORMAT(committedCursor, '%Y-%m-%d') AS committedCursor,
                       DATE_FORMAT(completedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS completedAtUtc
                FROM t_news_collection_task
                WHERE taskId = ?
                """, (rs, rowNum) -> new TaskSnapshot(
                rs.getString("taskId"),
                rs.getString("source"),
                rs.getLong("sourceConfigVersion"),
                rs.getString("queryText"),
                LocalDate.parse(rs.getString("windowStartOn")),
                LocalDate.parse(rs.getString("windowEndBefore")),
                rs.getString("status"),
                rs.getLong("generation"),
                rs.getInt("attemptCount"),
                rs.getInt("maxAttempts"),
                rs.getString("leaseWorkerId"),
                parseUtcInstant(rs.getString("leaseExpiresAtUtc")),
                parseUtcInstant(rs.getString("nextAttemptAtUtc")),
                rs.getString("lastFailureCode"),
                (Integer) rs.getObject("committedBatchCount"),
                (Integer) rs.getObject("committedRecordCount"),
                parseOptionalDate(rs.getString("committedCursor")),
                parseUtcInstant(rs.getString("completedAtUtc"))
        ), taskId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<LocalDate> getCommittedCursor(String source, long sourceConfigVersion) {
        List<String> rows = jdbc.query("""
                SELECT DATE_FORMAT(committedCursor, '%Y-%m-%d') AS committedCursor
                FROM t_news_source_cursor
                WHERE source = ? AND sourceConfigVersion = ?
                """, (rs, rowNum) -> rs.getString("committedCursor"), source, sourceConfigVersion);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.parse(rows.getFirst()));
    }

    public List<VisibleMaterial> listVisibleMaterials(String source) {
        return jdbc.query("""
                SELECT id, source, sourceRecordId, latestRevisionId, latestContentDigest,
                       DATE_FORMAT(firstSeenAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS firstSeenAtUtc,
                       DATE_FORMAT(lastSeenAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS lastSeenAtUtc
                FROM t_news_material
                WHERE source = ?
                ORDER BY id ASC
                """, (rs, rowNum) -> new VisibleMaterial(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                (Long) rs.getObject("latestRevisionId"),
                rs.getString("latestContentDigest"),
                parseUtcInstant(rs.getString("firstSeenAtUtc")),
                parseUtcInstant(rs.getString("lastSeenAtUtc"))
        ), source);
    }

    public List<VisibleRevision> listVisibleRevisions(String source, String sourceRecordId) {
        return jdbc.query("""
                SELECT id, materialId, source, sourceRecordId, contentDigest, url, title,
                       language, publishedOn,
                       DATE_FORMAT(updatedOn, '%Y-%m-%d') AS updatedOn,
                       pmid, doi, abstractText, abstractLicense, trialJson, canonicalJson,
                       firstTaskId, firstGeneration,
                       DATE_FORMAT(visibleAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS visibleAtUtc
                FROM t_news_material_revision
                WHERE source = ? AND sourceRecordId = ?
                ORDER BY id ASC
                """, (rs, rowNum) -> new VisibleRevision(
                rs.getLong("id"),
                rs.getLong("materialId"),
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                rs.getString("contentDigest"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("language"),
                rs.getString("publishedOn"),
                parseOptionalDate(rs.getString("updatedOn")),
                rs.getString("pmid"),
                rs.getString("doi"),
                rs.getString("abstractText"),
                rs.getString("abstractLicense"),
                rs.getString("trialJson"),
                rs.getString("canonicalJson"),
                rs.getString("firstTaskId"),
                rs.getLong("firstGeneration"),
                parseUtcInstant(rs.getString("visibleAtUtc"))
        ), source, sourceRecordId);
    }

    public long countVisibleRevisions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_news_material_revision", Long.class);
        return count == null ? 0L : count;
    }

    private NewsWireModels.Lease claimForSource(NewsWorkerIdentity worker, String source) {
        List<String> lockedSource = jdbc.query("""
                SELECT source
                FROM t_news_source
                WHERE source = ? AND enabled = 1
                FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getString(1), source);
        if (lockedSource.isEmpty()) {
            return null;
        }

        // Move exhausted expired leases to FAILED so they stop blocking capacity or cursor checks.
        jdbc.update("""
                UPDATE t_news_collection_task
                SET status = 'FAILED',
                    lastFailureCode = 'DEADLINE_EXCEEDED'
                WHERE source = ?
                  AND status = 'LEASED'
                  AND leaseExpiresAt <= UTC_TIMESTAMP(6)
                  AND attemptCount >= maxAttempts
                """, source);

        Integer activeCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM t_news_collection_task
                WHERE source = ?
                  AND status = 'LEASED'
                  AND leaseExpiresAt > UTC_TIMESTAMP(6)
                """, Integer.class, source);
        if (activeCount != null && activeCount > 0) {
            return null;
        }

        List<TaskRow> candidates = jdbc.query("""
                SELECT t.taskId, t.source, t.sourceConfigVersion, t.queryText,
                       DATE_FORMAT(t.windowStartOn, '%Y-%m-%d') AS windowStartOn,
                       DATE_FORMAT(t.windowEndBefore, '%Y-%m-%d') AS windowEndBefore,
                       t.status, t.generation, t.leaseTokenHash, t.leaseWorkerId,
                       t.attemptCount, t.maxAttempts,
                       0 AS leaseActive
                FROM t_news_collection_task t
                WHERE t.source = ?
                  AND (
                        t.status = 'PENDING'
                     OR (t.status = 'RETRY_SCHEDULED' AND t.nextAttemptAt <= UTC_TIMESTAMP(6))
                     OR (t.status = 'LEASED' AND t.leaseExpiresAt <= UTC_TIMESTAMP(6))
                  )
                  AND t.attemptCount < t.maxAttempts
                  AND NOT EXISTS (
                      SELECT 1
                      FROM t_news_collection_task earlier
                      WHERE earlier.source = t.source
                        AND earlier.sourceConfigVersion = t.sourceConfigVersion
                        AND earlier.status <> 'COMPLETED'
                        AND (
                              earlier.windowStartOn < t.windowStartOn
                           OR (earlier.windowStartOn = t.windowStartOn AND earlier.windowEndBefore < t.windowEndBefore)
                        )
                  )
                ORDER BY t.windowStartOn ASC, t.windowEndBefore ASC, t.createdAt ASC, t.taskId ASC
                LIMIT 1
                FOR UPDATE
                """, (rs, rowNum) -> mapTaskRow(rs), source);

        if (candidates.isEmpty()) {
            return null;
        }

        TaskRow candidate = candidates.getFirst();
        long nextGeneration = candidate.generation + 1;
        String leaseToken = generateLeaseToken();
        String leaseTokenHash = NewsMaterialCanonicalizer.sha256Hex(leaseToken.getBytes(StandardCharsets.US_ASCII));
        long leaseMicros = Math.max(1_000L, properties.getLeaseDuration().toNanos() / 1_000L);

        jdbc.update("""
                UPDATE t_news_collection_task
                SET status = 'LEASED',
                    generation = ?,
                    attemptCount = attemptCount + 1,
                    leaseTokenHash = ?,
                    leaseWorkerId = ?,
                    leaseExpiresAt = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? MICROSECOND)
                WHERE taskId = ?
                """, nextGeneration, leaseTokenHash, worker.workerId(), leaseMicros, candidate.taskId);

        String expiresUtc = jdbc.queryForObject("""
                SELECT DATE_FORMAT(leaseExpiresAt, '%Y-%m-%dT%H:%i:%s.%fZ')
                FROM t_news_collection_task
                WHERE taskId = ?
                """, String.class, candidate.taskId);
        Instant leaseExpiresAt = parseUtcInstant(expiresUtc);

        jdbc.update("""
                INSERT INTO t_news_collection_lease
                    (taskId, generation, workerId, leaseTokenHash, leaseExpiresAt)
                SELECT taskId, generation, leaseWorkerId, leaseTokenHash, leaseExpiresAt
                FROM t_news_collection_task
                WHERE taskId = ?
                """, candidate.taskId);

        return new NewsWireModels.Lease(
                candidate.taskId,
                nextGeneration,
                leaseToken,
                leaseExpiresAt,
                new NewsWireModels.CollectionWindow(
                        candidate.source,
                        candidate.sourceConfigVersion,
                        candidate.queryText,
                        candidate.windowStartOn,
                        candidate.windowEndBefore
                )
        );
    }

    private TaskRow lockTask(String taskId) {
        List<TaskRow> rows = jdbc.query("""
                SELECT taskId, source, sourceConfigVersion, queryText,
                       DATE_FORMAT(windowStartOn, '%Y-%m-%d') AS windowStartOn,
                       DATE_FORMAT(windowEndBefore, '%Y-%m-%d') AS windowEndBefore,
                       status, generation, leaseTokenHash, leaseWorkerId,
                       attemptCount, maxAttempts,
                       (leaseExpiresAt IS NOT NULL AND leaseExpiresAt > UTC_TIMESTAMP(6)) AS leaseActive
                FROM t_news_collection_task
                WHERE taskId = ?
                FOR UPDATE
                """, (rs, rowNum) -> mapTaskRow(rs), taskId);
        if (rows.isEmpty()) {
            throw NewsControlException.unknownTask();
        }
        return rows.getFirst();
    }

    private void verifyWorkerAccessAndGenerationOwnership(NewsWorkerIdentity worker, TaskRow task, long generation) {
        if (!worker.allowsSource(task.source)) {
            throw NewsControlException.forbidden();
        }
        List<String> leaseOwners = jdbc.query("""
                SELECT workerId
                FROM t_news_collection_lease
                WHERE taskId = ? AND generation = ?
                """, (rs, rowNum) -> rs.getString(1), task.taskId, generation);
        if (!leaseOwners.isEmpty()) {
            if (!worker.workerId().equals(leaseOwners.getFirst())) {
                throw NewsControlException.forbidden();
            }
            return;
        }
        if (task.leaseWorkerId != null && !worker.workerId().equals(task.leaseWorkerId)) {
            throw NewsControlException.forbidden();
        }
    }

    private Optional<String> checkIdempotencyReceipt(String taskId,
                                                     long generation,
                                                     String action,
                                                     String idempotencyKey,
                                                     String workerId,
                                                     byte[] bodySha256Bytes) {
        List<ReceiptRow> rows = jdbc.query("""
                SELECT workerId, requestBodySha256, responseJson
                FROM t_news_worker_receipt
                WHERE taskId = ? AND generation = ? AND action = ? AND idempotencyKey = ?
                """, (rs, rowNum) -> new ReceiptRow(
                rs.getString("workerId"),
                rs.getString("requestBodySha256"),
                rs.getString("responseJson")
        ), taskId, generation, action, idempotencyKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ReceiptRow existing = rows.getFirst();
        if (!workerId.equals(existing.workerId)) {
            throw NewsControlException.forbidden();
        }
        byte[] existingHashBytes = existing.requestBodySha256.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(existingHashBytes, bodySha256Bytes)) {
            throw NewsControlException.idempotencyConflict();
        }
        return Optional.of(existing.responseJson);
    }

    private static void verifyActiveLease(TaskRow task, String workerId, long generation, String leaseToken) {
        if (!"LEASED".equals(task.status)
                || task.generation != generation
                || !workerId.equals(task.leaseWorkerId)
                || !task.leaseActive
                || task.leaseTokenHash == null) {
            throw NewsControlException.staleLease();
        }
        String candidateHash = NewsMaterialCanonicalizer.sha256Hex(leaseToken.getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(
                task.leaseTokenHash.getBytes(StandardCharsets.US_ASCII),
                candidateHash.getBytes(StandardCharsets.US_ASCII))) {
            throw NewsControlException.staleLease();
        }
    }

    private CursorRow lockOrCreateCursorRow(String source, long sourceConfigVersion,
                                          String queryText, LocalDate initialCursor) {
        jdbc.update("""
                INSERT IGNORE INTO t_news_source_cursor (source, sourceConfigVersion, queryText, committedCursor)
                VALUES (?, ?, ?, ?)
                """, source, sourceConfigVersion, queryText, initialCursor);
        CursorRow cursor = jdbc.queryForObject("""
                SELECT queryText, DATE_FORMAT(committedCursor, '%Y-%m-%d') AS committedCursor
                FROM t_news_source_cursor
                WHERE source = ? AND sourceConfigVersion = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CursorRow(rs.getString("queryText"),
                        parseOptionalDate(rs.getString("committedCursor"))),
                source, sourceConfigVersion);
        if (!queryText.equals(cursor.queryText)) {
            throw new IllegalArgumentException("查询变更需要新的 sourceConfigVersion");
        }
        return cursor;
    }

    private void promoteStagedItems(String taskId, long generation, String source, String visibleAtDb) {
        List<StagedItem> staged = jdbc.query("""
                SELECT source, sourceRecordId, contentDigest, canonicalJson
                FROM t_news_batch_item
                WHERE taskId = ? AND generation = ?
                ORDER BY sequence ASC, itemIndex ASC
                """, (rs, rowNum) -> new StagedItem(
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                rs.getString("contentDigest"),
                rs.getString("canonicalJson")
        ), taskId, generation);

        if (staged.isEmpty()) {
            return;
        }

        // Last-seen item per (source, sourceRecordId) determines latestContentDigest; first-seen distinct
        // (source, sourceRecordId, contentDigest) yields the immutable revision payload.
        Map<String, StagedItem> latestByRecord = new LinkedHashMap<>();
        Map<String, StagedItem> distinctRevisions = new LinkedHashMap<>();
        for (StagedItem item : staged) {
            latestByRecord.put(item.source + "\0" + item.sourceRecordId, item);
            distinctRevisions.putIfAbsent(
                    item.source + "\0" + item.sourceRecordId + "\0" + item.contentDigest, item);
        }

        List<StagedItem> materialsSorted = new ArrayList<>(latestByRecord.values());
        materialsSorted.sort(Comparator.comparing((StagedItem i) -> i.source).thenComparing(i -> i.sourceRecordId));

        for (int start = 0; start < materialsSorted.size(); start += PROMOTE_CHUNK_SIZE) {
            List<StagedItem> chunk = materialsSorted.subList(start, Math.min(start + PROMOTE_CHUNK_SIZE, materialsSorted.size()));
            List<Object[]> upsertArgs = new ArrayList<>(chunk.size());
            for (StagedItem item : chunk) {
                upsertArgs.add(new Object[]{
                        item.source, item.sourceRecordId, item.contentDigest, visibleAtDb, visibleAtDb
                });
            }
            jdbc.batchUpdate("""
                    INSERT INTO t_news_material
                        (source, sourceRecordId, latestContentDigest, firstSeenAt, lastSeenAt)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        latestContentDigest = VALUES(latestContentDigest),
                        lastSeenAt = VALUES(lastSeenAt)
                    """, upsertArgs);
        }

        Map<String, Long> materialIds = loadMaterialIds(source, materialsSorted);

        List<StagedItem> revisionsSorted = new ArrayList<>(distinctRevisions.values());
        revisionsSorted.sort(Comparator.comparing((StagedItem i) -> i.source)
                .thenComparing(i -> i.sourceRecordId)
                .thenComparing(i -> i.contentDigest));

        for (int start = 0; start < revisionsSorted.size(); start += PROMOTE_CHUNK_SIZE) {
            List<StagedItem> chunk = revisionsSorted.subList(start, Math.min(start + PROMOTE_CHUNK_SIZE, revisionsSorted.size()));
            List<Object[]> revArgs = new ArrayList<>(chunk.size());
            for (StagedItem item : chunk) {
                long materialId = materialIds.get(item.sourceRecordId);
                NewsWireModels.Material m = codec.parseBatchRecordCanonical(item.canonicalJson);
                revArgs.add(new Object[]{
                        materialId,
                        item.source,
                        item.sourceRecordId,
                        item.contentDigest,
                        m.url(),
                        m.title(),
                        m.language(),
                        m.publishedOn(),
                        m.updatedOn() == null ? null : m.updatedOn().toString(),
                        m.pmid(),
                        m.doi(),
                        m.abstractText(),
                        m.abstractLicense(),
                        NewsMaterialCanonicalizer.canonicalTrialJson(m.trial()),
                        item.canonicalJson,
                        taskId,
                        generation,
                        visibleAtDb
                });
            }
            jdbc.batchUpdate("""
                    INSERT IGNORE INTO t_news_material_revision
                        (materialId, source, sourceRecordId, contentDigest, url, title, language,
                         publishedOn, updatedOn, pmid, doi, abstractText, abstractLicense,
                         trialJson, canonicalJson, firstTaskId, firstGeneration, visibleAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, revArgs);
        }

        Map<String, Long> revisionIds = loadRevisionIds(source, materialsSorted);
        List<Object[]> latestUpdateArgs = new ArrayList<>(materialsSorted.size());
        for (StagedItem item : materialsSorted) {
            long materialId = materialIds.get(item.sourceRecordId);
            Long revisionId = revisionIds.get(item.sourceRecordId + "\0" + item.contentDigest);
            if (revisionId != null) {
                latestUpdateArgs.add(new Object[]{revisionId, materialId});
            }
        }
        for (int start = 0; start < latestUpdateArgs.size(); start += PROMOTE_CHUNK_SIZE) {
            jdbc.batchUpdate("""
                    UPDATE t_news_material
                    SET latestRevisionId = ?
                    WHERE id = ?
                    """, latestUpdateArgs.subList(start, Math.min(start + PROMOTE_CHUNK_SIZE, latestUpdateArgs.size())));
        }
    }

    private Map<String, Long> loadMaterialIds(String source, List<StagedItem> materialsSorted) {
        Map<String, Long> ids = new HashMap<>(materialsSorted.size() * 2);
        for (int start = 0; start < materialsSorted.size(); start += PROMOTE_CHUNK_SIZE) {
            List<StagedItem> chunk = materialsSorted.subList(start, Math.min(start + PROMOTE_CHUNK_SIZE, materialsSorted.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> params = new ArrayList<>(chunk.size() + 1);
            params.add(source);
            for (StagedItem item : chunk) {
                params.add(item.sourceRecordId);
            }
            jdbc.query("""
                    SELECT id, sourceRecordId
                    FROM t_news_material
                    WHERE source = ? AND sourceRecordId IN (""" + placeholders + ")",
                    rs -> {
                        ids.put(rs.getString("sourceRecordId"), rs.getLong("id"));
                    },
                    params.toArray());
        }
        return ids;
    }

    private Map<String, Long> loadRevisionIds(String source, List<StagedItem> materialsSorted) {
        Map<String, Long> ids = new HashMap<>(materialsSorted.size() * 2);
        for (int start = 0; start < materialsSorted.size(); start += PROMOTE_CHUNK_SIZE) {
            List<StagedItem> chunk = materialsSorted.subList(start, Math.min(start + PROMOTE_CHUNK_SIZE, materialsSorted.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "(?,?)"));
            List<Object> params = new ArrayList<>(chunk.size() * 2 + 1);
            params.add(source);
            for (StagedItem item : chunk) {
                params.add(item.sourceRecordId);
                params.add(item.contentDigest);
            }
            jdbc.query("""
                    SELECT id, sourceRecordId, contentDigest
                    FROM t_news_material_revision
                    WHERE source = ? AND (sourceRecordId, contentDigest) IN (""" + placeholders + ")",
                    rs -> {
                        ids.put(rs.getString("sourceRecordId") + "\0" + rs.getString("contentDigest"), rs.getLong("id"));
                    },
                    params.toArray());
        }
        return ids;
    }

    private void insertReceipt(String taskId, long generation, String action, String idempotencyKey,
                               String workerId, String bodySha256, String responseJson) {
        jdbc.update("""
                INSERT INTO t_news_worker_receipt
                    (taskId, generation, action, idempotencyKey, workerId, requestBodySha256, responseJson)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskId, generation, action, idempotencyKey, workerId, bodySha256, responseJson);
    }

    private long computeBackoffMicros(int attemptCount) {
        Duration base = properties.getRetryBaseDelay() == null ? Duration.ofSeconds(30) : properties.getRetryBaseDelay();
        Duration max = properties.getRetryMaxDelay() == null ? Duration.ofMinutes(15) : properties.getRetryMaxDelay();
        long baseMicros = Math.max(0L, base.toNanos() / 1_000L);
        long maxMicros = Math.max(baseMicros, max.toNanos() / 1_000L);
        int shift = Math.max(0, Math.min(attemptCount - 1, 10));
        long multiplied;
        try {
            multiplied = Math.multiplyExact(baseMicros, 1L << shift);
        } catch (ArithmeticException overflow) {
            multiplied = maxMicros;
        }
        return Math.min(maxMicros, multiplied);
    }

    private static void validateSourceAndConfig(String source, long sourceConfigVersion, String query) {
        if (source == null || !NewsWireModels.SOURCES.contains(source)
                || sourceConfigVersion <= 0 || sourceConfigVersion > NewsWireModels.MAX_SAFE_INT
                || query == null || query.isBlank() || query.trim().length() > 1024) {
            throw new IllegalArgumentException("Invalid source config parameters");
        }
    }

    private static String generateLeaseToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private static TaskRow mapTaskRow(ResultSet rs) throws SQLException {
        return new TaskRow(
                rs.getString("taskId"),
                rs.getString("source"),
                rs.getLong("sourceConfigVersion"),
                rs.getString("queryText"),
                LocalDate.parse(rs.getString("windowStartOn")),
                LocalDate.parse(rs.getString("windowEndBefore")),
                rs.getString("status"),
                rs.getLong("generation"),
                rs.getString("leaseTokenHash"),
                rs.getString("leaseWorkerId"),
                rs.getInt("attemptCount"),
                rs.getInt("maxAttempts"),
                rs.getInt("leaseActive") == 1
        );
    }

    private static LocalDate parseOptionalDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static Instant parseUtcInstant(String value) {
        return value == null ? null : Instant.from(DB_UTC_FORMAT.parse(value));
    }

    private record TaskRow(
            String taskId,
            String source,
            long sourceConfigVersion,
            String queryText,
            LocalDate windowStartOn,
            LocalDate windowEndBefore,
            String status,
            long generation,
            String leaseTokenHash,
            String leaseWorkerId,
            int attemptCount,
            int maxAttempts,
            boolean leaseActive
    ) {
    }

    private record BatchHeaderRow(int sequence, int recordCount) {
    }

    private record ReceiptRow(String workerId, String requestBodySha256, String responseJson) {
    }

    private record CursorRow(String queryText, LocalDate committedCursor) {
    }

    private record StagedItem(String source, String sourceRecordId, String contentDigest, String canonicalJson) {
    }
}
