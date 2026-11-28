package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.newsingestion.NewsCollectionScheduleProperties.SourcePolicy;
import com.csust.eyediagnosis.newsingestion.NewsCollectionWindowPlanner.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class NewsCollectionScheduler {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NewsIngestionProperties ingestionProperties;
    private final NewsCollectionScheduleProperties scheduleProperties;
    private final NewsIngestionService ingestion;

    public NewsCollectionScheduler(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   NewsIngestionProperties ingestionProperties,
                                   NewsCollectionScheduleProperties scheduleProperties,
                                   NewsIngestionService ingestion) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(5);
        this.ingestionProperties = ingestionProperties;
        this.scheduleProperties = scheduleProperties;
        this.ingestion = ingestion;
    }

    @Scheduled(fixedDelayString = "#{@newsCollectionScheduleProperties.pollInterval.toMillis()}")
    public void tick() {
        if (!ingestionProperties.isEnabled() || !scheduleProperties.isEnabled()) {
            return;
        }
        for (SourcePolicy policy : scheduleProperties.getSources()) {
            if (policy.enabled()) {
                try {
                    transactions.executeWithoutResult(transaction -> planSource(policy));
                } catch (DataAccessException | TransactionException ex) {
                    log.warn("News schedule database operation failed for {}: {}",
                            policy.source(), ex.getClass().getSimpleName());
                }
            }
        }
    }

    private void planSource(SourcePolicy policy) {
        String source = policy.source();
        // The existing claim mutex orders scheduling, manual enqueue, config registration and source toggles.
        if (jdbc.query("""
                SELECT source FROM t_news_source WHERE source = ? AND enabled = 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getString(1), source).isEmpty()) {
            return;
        }
        State previous = first("""
                SELECT sourceConfigVersion, baselineCursor, evaluatedVersion, nextRunAt <= UTC_TIMESTAMP(6) AS due
                FROM t_news_collection_schedule WHERE source = ?
                """, (rs, row) -> new State(rs.getObject("sourceConfigVersion", Long.class),
                rs.getObject("baselineCursor", LocalDate.class), rs.getLong("evaluatedVersion"),
                rs.getBoolean("due")), source);
        long version = policy.sourceConfigVersion();
        if (previous != null && previous.adoptedVersion() != null && version < previous.adoptedVersion()) {
            return; // During a rolling restart an older instance must not replace the active version's status.
        }
        if (previous != null && previous.evaluatedVersion() == version && !previous.due()) {
            return;
        }
        State current = previous == null ? new State(null, null, version, true) : previous;
        String failed = first("""
                SELECT taskId FROM t_news_collection_task
                WHERE source = ? AND status = 'FAILED'
                ORDER BY nextAttemptAt, windowStartOn, windowEndBefore, taskId LIMIT 1
                """, (rs, row) -> rs.getString(1), source);
        if (failed != null) {
            save(policy, current, Status.FAILED_WINDOW, failed);
            return;
        }
        String pending = first("""
                SELECT taskId FROM t_news_collection_task
                WHERE source = ? AND status IN ('PENDING', 'LEASED', 'RETRY_SCHEDULED')
                ORDER BY status, nextAttemptAt, windowStartOn, windowEndBefore, taskId LIMIT 1
                """, (rs, row) -> rs.getString(1), source);
        if (pending != null) {
            save(policy, current, Status.WAITING_FOR_TASK, pending);
            return;
        }
        Cursor cursor = cursor(source, version);
        if (cursor == null || cursor.date() == null) {
            save(policy, current, Status.INITIAL_CURSOR_REQUIRED, null);
            return;
        }

        boolean adopting = !Objects.equals(current.adoptedVersion(), version);
        if (adopting) {
            if (current.adoptedVersion() != null) {
                Cursor predecessor = cursor(source, current.adoptedVersion());
                if (predecessor == null || !cursor.date().equals(predecessor.date())) {
                    save(policy, current, Status.CONFIG_CURSOR_MISMATCH, null);
                    return;
                }
            }
        }
        LocalDate today = jdbc.queryForObject("SELECT UTC_DATE()", (rs, row) -> rs.getObject(1, LocalDate.class));
        LocalDate baseline = adopting ? cursor.date() : current.baseline();
        var plan = NewsCollectionWindowPlanner.plan(policy, cursor.date(), baseline, today);
        if (adopting && plan.status() != Status.BACKFILL_LIMIT) {
            current = new State(version, baseline, version, true);
        }
        if (plan.status() != Status.QUEUED) {
            save(policy, current, plan.status(), null);
            return;
        }

        String taskId = NewsIngestionService.AUTOMATIC_TASK_PREFIX + source + "-" + version + "-"
                + plan.startOn() + "-" + plan.endBefore();
        if (first("SELECT taskId FROM t_news_collection_task WHERE taskId = ?",
                (rs, row) -> rs.getString(1), taskId) != null) {
            save(policy, current, Status.IDENTITY_CONFLICT, taskId);
            return;
        }
        ingestion.enqueueAutomaticWindow(taskId, source, version, cursor.query(), plan.startOn(), plan.endBefore());
        save(policy, current, Status.QUEUED, taskId);
    }

    private Cursor cursor(String source, long version) {
        // The source mutex excludes enqueue, retry and registration. A live completion remains visible
        // as unfinished until its task and cursor commit, so planning needs no cursor lock.
        return first("""
                SELECT queryText, committedCursor FROM t_news_source_cursor
                WHERE source = ? AND sourceConfigVersion = ?
                """, (rs, row) -> new Cursor(rs.getString(1), rs.getObject(2, LocalDate.class)), source, version);
    }

    private void save(SourcePolicy policy, State state, Status status, String taskId) {
        jdbc.update("""
                INSERT INTO t_news_collection_schedule
                    (source, sourceConfigVersion, baselineCursor, evaluatedVersion, lastRunAt, nextRunAt, status, relatedTaskId)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + (? * interval '1 minute'), ?, ?)
                ON CONFLICT (source) DO UPDATE SET sourceConfigVersion = EXCLUDED.sourceConfigVersion,
                    baselineCursor = EXCLUDED.baselineCursor, evaluatedVersion = EXCLUDED.evaluatedVersion,
                    lastRunAt = EXCLUDED.lastRunAt, nextRunAt = EXCLUDED.nextRunAt,
                    status = EXCLUDED.status, relatedTaskId = EXCLUDED.relatedTaskId
                """, policy.source(), state.adoptedVersion(), state.baseline(), policy.sourceConfigVersion(),
                policy.intervalMinutes(), status.name(), taskId);
    }

    private <T> T first(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record State(Long adoptedVersion, LocalDate baseline, long evaluatedVersion, boolean due) {
    }

    private record Cursor(String query, LocalDate date) {
    }

}
