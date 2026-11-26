package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.newsingestion.NewsCollectionScheduleProperties.SourcePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NewsCollectionScheduleStatusService {

    private final JdbcTemplate jdbc;
    private final NewsIngestionProperties ingestionProperties;
    private final NewsCollectionScheduleProperties scheduleProperties;

    public record ScheduleStatus(boolean ingestionEnabled, boolean scheduleEnabled,
                                 List<SourceStatus> sources, List<WorkerPoll> workers) {
    }

    public record SourceStatus(String source, boolean sourceEnabled, boolean policyEnabled, Long configuredVersion,
                               Long activeVersion, String committedCursor, String status, String lastRunAt,
                               String nextRunAt, String relatedTaskId, long backlog, long failed, long retryScheduled,
                               String lastFailureCode, String lastCompletedStartOn, String lastCompletedEndBefore,
                               Integer lastCompletedRecordCount) {
    }

    public record WorkerPoll(String workerId, String lastPollAt, String lastClaimAt, String lastTaskId) {
    }

    public ScheduleStatus status() {
        Map<String, SourcePolicy> policies = scheduleProperties.getSources().stream()
                .collect(Collectors.toMap(SourcePolicy::source, Function.identity()));
        List<SourceStatus> sources = jdbc.query("""
                SELECT s.source, s.enabled, schedule.sourceConfigVersion, schedule.evaluatedVersion,
                       schedule.status, schedule.relatedTaskId,
                       DATE_FORMAT(schedule.lastRunAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS lastRunAt,
                       DATE_FORMAT(schedule.nextRunAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS nextRunAt,
                       DATE_FORMAT(c.committedCursor, '%Y-%m-%d') AS committedCursor,
                       COALESCE(stats.backlog, 0) AS backlog, COALESCE(stats.failed, 0) AS failed,
                       COALESCE(stats.retryScheduled, 0) AS retryScheduled,
                       (SELECT lastFailureCode FROM t_news_collection_task f
                        WHERE f.source = s.source
                          AND f.status IN ('FAILED', 'RETRY_SCHEDULED', 'LEASED', 'PENDING')
                          AND f.lastFailureCode IS NOT NULL
                        ORDER BY f.updatedAt DESC, f.taskId DESC LIMIT 1) AS lastFailureCode,
                       DATE_FORMAT(done.windowStartOn, '%Y-%m-%d') AS lastStart,
                       DATE_FORMAT(done.windowEndBefore, '%Y-%m-%d') AS lastEnd,
                       done.committedRecordCount
                FROM t_news_source s
                LEFT JOIN t_news_collection_schedule schedule ON schedule.source = s.source
                LEFT JOIN t_news_source_cursor c ON c.source = s.source
                    AND c.sourceConfigVersion = schedule.sourceConfigVersion
                LEFT JOIN (
                    SELECT source, COUNT(*) AS backlog, SUM(status = 'FAILED') AS failed,
                           SUM(status = 'RETRY_SCHEDULED') AS retryScheduled
                    FROM t_news_collection_task
                    WHERE status IN ('PENDING', 'LEASED', 'FAILED', 'RETRY_SCHEDULED')
                    GROUP BY source
                ) stats ON stats.source = s.source
                LEFT JOIN t_news_collection_task done ON done.taskId = (
                    SELECT t.taskId FROM t_news_collection_task t
                    WHERE t.source = s.source AND t.taskType = 'AUTO' AND t.completedAt IS NOT NULL
                    ORDER BY t.completedAt DESC, t.taskId DESC LIMIT 1
                )
                ORDER BY s.source
                """, (rs, row) -> {
            String source = rs.getString("source");
            SourcePolicy policy = policies.get(source);
            boolean sourceEnabled = rs.getBoolean("enabled");
            boolean policyEnabled = policy != null && policy.enabled();
            Long configuredVersion = policy == null ? null : policy.sourceConfigVersion();
            Long activeVersion = rs.getObject("sourceConfigVersion", Long.class);
            boolean evaluated = policy != null && rs.getLong("evaluatedVersion") == policy.sourceConfigVersion();
            boolean running = ingestionProperties.isEnabled() && scheduleProperties.isEnabled()
                    && sourceEnabled && policyEnabled;
            String status = status(running, evaluated, configuredVersion, activeVersion, rs.getString("status"));
            boolean hasNextRun = running && evaluated && !"CONFIG_VERSION_STALE".equals(status);
            return new SourceStatus(source, sourceEnabled, policyEnabled, configuredVersion, activeVersion,
                    rs.getString("committedCursor"), status, rs.getString("lastRunAt"),
                    hasNextRun ? rs.getString("nextRunAt") : null, evaluated ? rs.getString("relatedTaskId") : null,
                    rs.getLong("backlog"), rs.getLong("failed"), rs.getLong("retryScheduled"),
                    rs.getString("lastFailureCode"), rs.getString("lastStart"), rs.getString("lastEnd"),
                    rs.getObject("committedRecordCount", Integer.class));
        });
        List<WorkerPoll> workers = jdbc.query("""
                SELECT workerId, DATE_FORMAT(lastPollAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS lastPollAt,
                       DATE_FORMAT(lastClaimAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS lastClaimAt, lastTaskId
                FROM t_news_worker_poll ORDER BY lastPollAt DESC, workerId LIMIT 20
                """, (rs, row) -> new WorkerPoll(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        return new ScheduleStatus(ingestionProperties.isEnabled(), scheduleProperties.isEnabled(), sources, workers);
    }

    private static String status(boolean running, boolean evaluated, Long configured, Long active, String decision) {
        if (!running) {
            return "DISABLED";
        }
        if (active != null && configured < active) {
            return "CONFIG_VERSION_STALE";
        }
        return evaluated ? decision : "AWAITING_EVALUATION";
    }
}
