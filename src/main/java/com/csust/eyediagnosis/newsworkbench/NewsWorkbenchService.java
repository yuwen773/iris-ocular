package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.common.InsertRaceGuard;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newscuration.NewsCurationModels;
import com.csust.eyediagnosis.newscuration.NewsCurationProperties;
import com.csust.eyediagnosis.newscuration.NewsCurationService;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger;
import com.csust.eyediagnosis.newsingestion.NewsIngestionService;
import com.csust.eyediagnosis.newsingestion.NewsWireCodec;
import com.csust.eyediagnosis.newsingestion.NewsWireModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service layer backing the admin-only Iris News Engine workbench ({@link NewsWorkbenchController}, WP-05).
 *
 * <p>Builds read projections over WP-02..WP-04 tables and delegates write/state transitions to
 * {@link NewsIngestionService} and {@link NewsCurationService} without adding paid live LLM triggers
 * or touching {@code V1..V42} schemas.
 */
@Service
public class NewsWorkbenchService {

    private static final String DEFAULT_BUDGET_SCOPE = "GLOBAL";
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_ALLOWED_ATTEMPTS = 10;
    /** {@code t_news_collection_task_event.note} 是 {@code VARCHAR(512)}（V43）。 */
    private static final int MAX_TASK_EVENT_NOTE_LENGTH = 512;
    private static final long MAX_PAGE_SIZE = 100L;
    /** 页码算术上限：保证 (current - 1) * pageSize 不溢出 long。 */
    private static final long MAX_PAGE_NUMBER = 1_000_000L;

    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING", "LEASED", "RETRY_SCHEDULED", "COMPLETED", "FAILED"
    );
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_COMPLETED = "COMPLETED";

    private static final DateTimeFormatter STRICT_ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DB_UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<NewsCurationModels.CurationClaim>> CLAIM_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<NewsCurationModels.EvidenceViolation>> VIOLATION_LIST_TYPE =
            new TypeReference<>() {
            };

    private static final String LINKED_NEWS_ID_SQL =
            "(SELECT n.id FROM t_news n WHERE n.curationCandidateId = c.id AND n.isDelete = 0 ORDER BY n.id DESC LIMIT 1)";
    private static final String LINKED_NEWS_STATUS_SQL =
            "(SELECT CAST(n.status AS SIGNED) FROM t_news n WHERE n.curationCandidateId = c.id AND n.isDelete = 0 ORDER BY n.id DESC LIMIT 1)";

    /**
     * 阻塞判定，与 Worker 领取逻辑（{@code NewsIngestionService.claimForSource} 的 NOT EXISTS 子句）
     * 完全同源：同一信源、同一配置版本下，更早的窗口任务没有 COMPLETED 时，本窗口不会被领取。
     *
     * <p>系统**不会自动跳过失败窗口**，所以卡住时必须说清「卡在谁身上」，而不是让运营猜。
     */
    private static final String BLOCKING_TASK_SELECT = """
            SELECT e.%s
              FROM t_news_collection_task e
             WHERE e.source = t.source
               AND e.sourceConfigVersion = t.sourceConfigVersion
               AND e.status <> 'COMPLETED'
               AND (e.windowStartOn < t.windowStartOn
                    OR (e.windowStartOn = t.windowStartOn AND e.windowEndBefore < t.windowEndBefore))
             ORDER BY e.windowStartOn ASC, e.windowEndBefore ASC, e.taskId ASC
             LIMIT 1""";
    private static final String BLOCKING_TASK_ID_SQL =
            "(" + String.format(BLOCKING_TASK_SELECT, "taskId") + ")";
    private static final String BLOCKING_TASK_STATUS_SQL =
            "(" + String.format(BLOCKING_TASK_SELECT, "status") + ")";

    /**
     * 采集任务的公共查询列（列表与单条详情共用），含阻塞判定子查询。
     */
    private static final String TASK_SELECT_SQL = """
            SELECT t.taskId, t.source, t.sourceConfigVersion, t.queryText,
                   DATE_FORMAT(t.windowStartOn, '%Y-%m-%d') AS windowStartOn,
                   DATE_FORMAT(t.windowEndBefore, '%Y-%m-%d') AS windowEndBefore,
                   t.status, t.generation, t.attemptCount, t.maxAttempts, t.leaseWorkerId,
                   DATE_FORMAT(t.leaseExpiresAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS leaseExpiresAtUtc,
                   CAST(CASE WHEN t.status = 'LEASED' AND t.leaseExpiresAt <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END AS SIGNED)
                       AS leaseExpired,
                   DATE_FORMAT(t.nextAttemptAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS nextAttemptAtUtc,
                   t.taskType, t.lastFailureCode, t.committedBatchCount, t.committedRecordCount,
                   DATE_FORMAT(t.committedCursor, '%Y-%m-%d') AS committedCursor,
                   DATE_FORMAT(t.completedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS completedAtUtc,
                   DATE_FORMAT(t.createdAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS createdAtUtc,
                   DATE_FORMAT(t.updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc,
            """
            + "                   " + BLOCKING_TASK_ID_SQL + " AS blockedByTaskId,\n"
            + "                   " + BLOCKING_TASK_STATUS_SQL + " AS blockedByStatus\n"
            + "            FROM t_news_collection_task t\n";

    /** 操作审计动作。 */
    private static final String TASK_EVENT_ACTION_ENQUEUE = "ENQUEUE";
    private static final String TASK_EVENT_ACTION_RETRY = "RETRY";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate taskTransactions;
    private final InsertRaceGuard insertRaceGuard;
    private final NewsIngestionService ingestionService;
    private final NewsCurationService curationService;
    private final NewsCurationProperties curationProperties;
    private final ObjectMapper mapper;

    @Autowired
    public NewsWorkbenchService(JdbcTemplate jdbc,
                                PlatformTransactionManager transactionManager,
                                NewsIngestionService ingestionService,
                                NewsCurationService curationService,
                                NewsCurationProperties curationProperties,
                                @Qualifier("newsProtocolObjectMapper") ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.taskTransactions = new TransactionTemplate(transactionManager);
        // 复用同一个事务管理器：入队撞键后的状态回读必须活下来（PostgreSQL 事务会中止）。
        this.insertRaceGuard = new InsertRaceGuard(transactionManager);
        this.ingestionService = ingestionService;
        this.curationService = curationService;
        this.curationProperties = curationProperties;
        this.mapper = mapper;
    }

    public NewsWorkbenchModels.OverviewVO getOverview() {
        NewsWorkbenchModels.SourceCounts sources = jdbc.queryForObject("""
                SELECT COUNT(*) AS totalSources,
                       COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END), 0) AS enabledSources
                FROM t_news_source
                """, (rs, rowNum) -> new NewsWorkbenchModels.SourceCounts(
                rs.getInt("totalSources"),
                rs.getInt("enabledSources")
        ));

        NewsWorkbenchModels.TaskCounts tasks = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pendingCount,
                       COALESCE(SUM(CASE WHEN status = 'LEASED' THEN 1 ELSE 0 END), 0) AS leasedCount,
                       COALESCE(SUM(CASE WHEN status = 'RETRY_SCHEDULED' THEN 1 ELSE 0 END), 0) AS retryScheduledCount,
                       COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedCount,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedCount,
                       COALESCE(SUM(CASE WHEN status = 'LEASED' AND leaseExpiresAt <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END), 0)
                           AS expiredLeaseCount
                FROM t_news_collection_task
                """, (rs, rowNum) -> new NewsWorkbenchModels.TaskCounts(
                rs.getLong("pendingCount"),
                rs.getLong("leasedCount"),
                rs.getLong("retryScheduledCount"),
                rs.getLong("completedCount"),
                rs.getLong("failedCount"),
                rs.getLong("expiredLeaseCount")
        ));

        Long visibleMaterialCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_news_material", Long.class);
        Long visibleRevisionCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_news_material_revision", Long.class);
        NewsWorkbenchModels.MaterialCounts materials = new NewsWorkbenchModels.MaterialCounts(
                visibleMaterialCount == null ? 0L : visibleMaterialCount,
                visibleRevisionCount == null ? 0L : visibleRevisionCount
        );

        Long draftedNewsCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news WHERE curationCandidateId IS NOT NULL AND isDelete = 0", Long.class);
        long drafted = draftedNewsCount == null ? 0L : draftedNewsCount;
        NewsWorkbenchModels.CandidateCounts candidates = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN status = 'CURATED_ACCEPTED' THEN 1 ELSE 0 END), 0) AS acceptedCount,
                       COALESCE(SUM(CASE WHEN status = 'CURATED_NEEDS_REVIEW' THEN 1 ELSE 0 END), 0) AS needsReviewCount,
                       COALESCE(SUM(CASE WHEN status = 'CURATED_REJECTED' THEN 1 ELSE 0 END), 0) AS rejectedCount,
                       COALESCE(SUM(CASE WHEN status = 'REJECTED_EVIDENCE_VIOLATION' THEN 1 ELSE 0 END), 0) AS evidenceViolationCount,
                       COALESCE(SUM(CASE WHEN status = 'NEEDS_RECONCILIATION' THEN 1 ELSE 0 END), 0) AS needsReconciliationCount
                FROM t_news_curation_candidate
                """, (rs, rowNum) -> new NewsWorkbenchModels.CandidateCounts(
                rs.getLong("acceptedCount"),
                rs.getLong("needsReviewCount"),
                rs.getLong("rejectedCount"),
                rs.getLong("evidenceViolationCount"),
                rs.getLong("needsReconciliationCount"),
                drafted
        ));

        NewsWorkbenchModels.ReceiptCounts receipts = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN state = 'OUTCOME_UNKNOWN' THEN 1 ELSE 0 END), 0) AS unknownOutcomeCount,
                       COALESCE(SUM(CASE WHEN state IN ('RESERVED', 'DISPATCHED') THEN 1 ELSE 0 END), 0) AS inFlightCount,
                       COALESCE(SUM(CASE WHEN state IN ('RESERVED', 'DISPATCHED') AND deadlineAt <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END), 0)
                           AS staleInFlightCount,
                       COALESCE(SUM(CASE WHEN state = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeededCount
                FROM t_news_model_call_receipt
                """, (rs, rowNum) -> new NewsWorkbenchModels.ReceiptCounts(
                rs.getLong("unknownOutcomeCount"),
                rs.getLong("inFlightCount"),
                rs.getLong("staleInFlightCount"),
                rs.getLong("succeededCount")
        ));

        List<NewsWorkbenchModels.BudgetSummaryVO> todayRows = jdbc.query("""
                SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, scope,
                       maxCalls, usedCalls, reservedCalls,
                       maxTokens, usedTokens, reservedTokens,
                       maxCostMicros, usedCostMicros, reservedCostMicros,
                       circuitReason IS NOT NULL AS circuitBroken, circuitReason,
                       DATE_FORMAT(updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_curation_budget LEFT JOIN t_news_curation_circuit USING (scope)
                WHERE budgetDate = UTC_DATE() AND scope = ?
                """, (rs, rowNum) -> mapBudgetRow(rs), DEFAULT_BUDGET_SCOPE);
        NewsWorkbenchModels.BudgetSummaryVO todayBudget = todayRows.isEmpty() ? null : todayRows.getFirst();

        List<NewsWorkbenchModels.BenchRunSummaryVO> benchRows = listBenchRuns(1);
        NewsWorkbenchModels.BenchRunSummaryVO latestBenchRun = benchRows.isEmpty() ? null : benchRows.getFirst();

        return new NewsWorkbenchModels.OverviewVO(
                sources,
                tasks,
                materials,
                candidates,
                receipts,
                todayBudget,
                curationProperties.isEnabled(),
                latestBenchRun
        );
    }

    public List<NewsWorkbenchModels.SourceDetailVO> listSources() {
        return loadSources(null);
    }

    public NewsWorkbenchModels.SourceDetailVO getSourceDetail(String source) {
        String normalizedSource = requireSupportedSource(source);
        List<NewsWorkbenchModels.SourceDetailVO> rows = loadSources(normalizedSource);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到信源: " + normalizedSource);
        }
        return rows.getFirst();
    }

    public NewsWorkbenchModels.SourceDetailVO toggleSource(String source, Boolean enabled) {
        String normalizedSource = requireSupportedSource(source);
        if (enabled == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "enabled 不能为空");
        }
        try {
            ingestionService.setSourceEnabled(normalizedSource, enabled);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, ex.getMessage());
        }
        return getSourceDetail(normalizedSource);
    }

    public NewsWorkbenchModels.SourceDetailVO registerSourceConfig(
            String source, NewsWorkbenchModels.RegisterSourceConfigRequest req) {
        String normalizedSource = requireSupportedSource(source);
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "配置请求不能为空");
        }
        if (req.sourceConfigVersion() == null || req.sourceConfigVersion() <= 0
                || req.sourceConfigVersion() > NewsWireModels.MAX_SAFE_INT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sourceConfigVersion 必须是正安全整数");
        }
        if (req.query() == null || req.query().isBlank() || req.query().trim().length() > 1024) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "query 不能为空且长度不得超过 1024");
        }
        LocalDate initialCursor = null;
        if (req.initialCursor() != null && !req.initialCursor().isBlank()) {
            initialCursor = parseStrictIsoDate(req.initialCursor(), "initialCursor");
        }
        try {
            ingestionService.registerSourceConfig(
                    normalizedSource, req.sourceConfigVersion(), req.query(), initialCursor);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, ex.getMessage());
        }
        return getSourceDetail(normalizedSource);
    }

    public NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CollectionTaskVO> listTasks(
            String source, String status, long current, long pageSize) {
        String normalizedSource = optionalSupportedSource(source);
        String normalizedStatus = optionalTaskStatus(status);
        long safeCurrent = clampCurrent(current);
        long safeSize = clampPageSize(pageSize);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (normalizedSource != null) {
            where.append(" AND t.source = ?");
            args.add(normalizedSource);
        }
        if (normalizedStatus != null) {
            where.append(" AND t.status = ?");
            args.add(normalizedStatus);
        }

        Long totalBoxed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_collection_task t" + where,
                Long.class,
                args.toArray()
        );
        long total = totalBoxed == null ? 0L : totalBoxed;

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add((safeCurrent - 1) * safeSize);

        List<NewsWorkbenchModels.CollectionTaskVO> records = total == 0 ? List.of() : jdbc.query(
                TASK_SELECT_SQL + where + " ORDER BY t.createdAt DESC, t.taskId DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapTaskRow(rs),
                pagedArgs.toArray()
        );

        return new NewsWorkbenchModels.PageVO<>(records, total, safeCurrent, safeSize, computePages(total, safeSize));
    }

    public NewsWorkbenchModels.CollectionTaskVO enqueueTask(NewsWorkbenchModels.EnqueueTaskRequest req,
                                                            User operator) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "入队请求不能为空");
        }
        String normalizedSource = requireSupportedSource(req.source());
        if (req.sourceConfigVersion() == null || req.sourceConfigVersion() <= 0
                || req.sourceConfigVersion() > NewsWireModels.MAX_SAFE_INT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sourceConfigVersion 必须是正安全整数");
        }
        if (req.query() == null || req.query().isBlank() || req.query().trim().length() > 1024) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "query 不能为空且长度不得超过 1024");
        }
        LocalDate start = parseStrictIsoDate(req.windowStartOn(), "windowStartOn");
        LocalDate end = parseStrictIsoDate(req.windowEndBefore(), "windowEndBefore");
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "windowStartOn 必须早于 windowEndBefore");
        }
        int maxAttempts = requireAllowedAttempts(req.maxAttempts());

        String taskId = (req.taskId() == null || req.taskId().isBlank())
                ? "manual-" + normalizedSource + "-" + start + "-" + end + "-"
                        + UUID.randomUUID().toString().substring(0, 8)
                : req.taskId().trim();
        if (!NewsWireCodec.isValidIdentifier(taskId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "taskId 格式不合法");
        }

        // 重复入队的语义要分情况说清楚，而不是一律回一句「任务 ID 已存在」：
        // 失败窗口要走重试入口，已完成的窗口重复采集只会产生重复记录（不推进游标）。
        Optional<NewsIngestionService.TaskSnapshot> existing = ingestionService.getTask(taskId);
        if (existing.isPresent()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, describeExistingTask(taskId, existing.get()));
        }

        return taskTransactions.execute(transaction -> {
            if (!insertRaceGuard.tryInsert(() -> {
                try {
                    ingestionService.enqueueWindow(
                            taskId, normalizedSource, req.sourceConfigVersion(), req.query(), start, end, maxAttempts);
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, ex.getMessage());
                }
            })) {
                String status = ingestionService.getTask(taskId)
                        .map(NewsIngestionService.TaskSnapshot::status)
                        .orElse("UNKNOWN");
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "任务 ID " + taskId + " 已存在且当前状态为 " + status + "，本次入队未生效");
            }
            recordTaskEvent(taskId, TASK_EVENT_ACTION_ENQUEUE, null, "PENDING", operator, null);
            return loadTaskView(taskId);
        });
    }

    /**
     * 失败窗口重试：把已经 {@code FAILED} 的窗口重新打开，等价于「再给几次尝试机会」。
     *
     * <p>重开与操作审计在同一事务中提交。状态回退和尝试次数由采集服务负责。
     * <ul>
     *   <li>只有 {@code FAILED} 能重试，其他状态明确拒绝（不静默覆盖进行中的窗口）；</li>
     *   <li>并发重试只有一路生效，另一路收到「已被并发重试」的明确错误；</li>
     *   <li>重试不推进游标——游标只在 {@code complete()} 时前进。</li>
     * </ul>
     */
    public NewsWorkbenchModels.CollectionTaskVO retryFailedTask(String taskId,
                                                                NewsWorkbenchModels.RetryTaskRequest req,
                                                                User operator) {
        if (taskId == null || !NewsWireCodec.isValidIdentifier(taskId.trim())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "taskId 格式不合法");
        }
        String normalizedTaskId = taskId.trim();
        int extraAttempts = requireAllowedAttempts(req == null ? null : req.maxAttempts());
        String note = req == null || req.note() == null ? null : req.note().trim();
        if (note != null && note.length() > MAX_TASK_EVENT_NOTE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "note 不得超过 " + MAX_TASK_EVENT_NOTE_LENGTH + " 个字符");
        }
        NewsIngestionService.TaskSnapshot snapshot = ingestionService.getTask(normalizedTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAMS_ERROR, "任务不存在: " + normalizedTaskId));
        if (!TASK_STATUS_FAILED.equals(snapshot.status())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "任务 " + normalizedTaskId + " 当前状态为 " + snapshot.status()
                            + "，只有 FAILED 的失败窗口可以重试");
        }

        return taskTransactions.execute(transaction -> {
            boolean reopened;
            try {
                reopened = ingestionService.reopenFailedWindow(snapshot, extraAttempts);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, ex.getMessage());
            }
            if (!reopened) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "任务 " + normalizedTaskId + " 已被其他操作改变，本次重试未生效");
            }
            recordTaskEvent(normalizedTaskId, TASK_EVENT_ACTION_RETRY, TASK_STATUS_FAILED, "PENDING",
                    operator, note);
            return loadTaskView(normalizedTaskId);
        });
    }

    /** 采集任务的操作审计记录。 */
    public List<NewsWorkbenchModels.TaskEventVO> listTaskEvents(String taskId) {
        if (taskId == null || !NewsWireCodec.isValidIdentifier(taskId.trim())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "taskId 格式不合法");
        }
        String normalizedTaskId = taskId.trim();
        return jdbc.query("""
                SELECT id, taskId, action, fromStatus, toStatus, actorId, actorRole, note,
                       DATE_FORMAT(createdAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS createdAtUtc
                FROM t_news_collection_task_event
                WHERE taskId = ?
                ORDER BY id ASC
                """, (rs, rowNum) -> new NewsWorkbenchModels.TaskEventVO(
                rs.getLong("id"),
                rs.getString("taskId"),
                rs.getString("action"),
                rs.getString("fromStatus"),
                rs.getString("toStatus"),
                (Long) rs.getObject("actorId"),
                rs.getString("actorRole"),
                rs.getString("note"),
                parseUtcInstant(rs.getString("createdAtUtc"))
        ), normalizedTaskId);
    }

    private NewsWorkbenchModels.CollectionTaskVO loadTaskView(String taskId) {
        List<NewsWorkbenchModels.CollectionTaskVO> rows = jdbc.query(
                TASK_SELECT_SQL + " WHERE t.taskId = ?",
                (rs, rowNum) -> mapTaskRow(rs), taskId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务创建后未能读取");
        }
        return rows.getFirst();
    }

    private void recordTaskEvent(String taskId, String action, String fromStatus, String toStatus,
                                 User operator, String note) {
        Long actorId = operator == null ? null : operator.getId();
        String actorRole = operator == null ? null : operator.getUserRole();
        jdbc.update("""
                INSERT INTO t_news_collection_task_event
                    (taskId, action, fromStatus, toStatus, actorId, actorRole, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                taskId, action, fromStatus, toStatus, actorId, actorRole,
                note == null || note.isBlank() ? null : note);
    }

    /**
     * 尝试次数的唯一校验口径：入队与失败窗口重试共用，避免两处各写一遍再慢慢漂移。
     *
     * @param maxAttempts 请求里的次数，null 表示用默认值
     * @return 解析后的次数
     */
    private static int requireAllowedAttempts(Integer maxAttempts) {
        int resolved = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (resolved < 1 || resolved > MAX_ALLOWED_ATTEMPTS) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "maxAttempts 必须在 1 到 " + MAX_ALLOWED_ATTEMPTS + " 之间");
        }
        return resolved;
    }

    /**
     * 重复入队的错误口径：把「当前状态」和「该走哪条路」一起说清楚。
     */
    private static String describeExistingTask(String taskId, NewsIngestionService.TaskSnapshot existing) {
        String window = existing.windowStartOn() + " → " + existing.windowEndBefore();
        String suffix = "（既有窗口 " + window + "）";
        if (TASK_STATUS_FAILED.equals(existing.status())) {
            return "任务 ID " + taskId + " 已存在且当前状态为 FAILED（失败窗口）" + suffix
                    + "：请使用失败窗口重试入口重新打开，不要重复入队";
        }
        if (TASK_STATUS_COMPLETED.equals(existing.status())) {
            // 别说「会被游标规则拒绝」：enqueueWindow 不比对游标，complete() 在
            // shouldAdvanceCursor=false 时也不抛错。换成新 taskId 会真的重新领取并重采同一窗口。
            return "任务 ID " + taskId + " 已存在且当前状态为 COMPLETED" + suffix
                    + "：该窗口已采集完成。同窗口重复采集不会推进游标，只会在资料池里产生重复记录；"
                    + "确需重采请确认是有意为之，并改用新的 taskId";
        }
        return "任务 ID " + taskId + " 已存在且当前状态为 " + existing.status() + suffix
                + "：请改用新的 taskId";
    }

    public List<NewsWorkbenchModels.LeaseRecordVO> listTaskLeases(String taskId) {
        if (taskId == null || !NewsWireCodec.isValidIdentifier(taskId.trim())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "taskId 格式不合法");
        }
        String normalizedTaskId = taskId.trim();
        return jdbc.query("""
                SELECT taskId, generation, workerId,
                       DATE_FORMAT(leaseExpiresAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS leaseExpiresAtUtc,
                       DATE_FORMAT(claimedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS claimedAtUtc
                FROM t_news_collection_lease
                WHERE taskId = ?
                ORDER BY generation DESC
                """, (rs, rowNum) -> new NewsWorkbenchModels.LeaseRecordVO(
                rs.getString("taskId"),
                rs.getLong("generation"),
                rs.getString("workerId"),
                parseUtcInstant(rs.getString("leaseExpiresAtUtc")),
                parseUtcInstant(rs.getString("claimedAtUtc"))
        ), normalizedTaskId);
    }

    public NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CandidateSummaryVO> listCandidates(
            String status, String source, String evidenceTier, long current, long pageSize) {
        NewsCurationModels.CandidateStatus parsedStatus = optionalCandidateStatus(status);
        String normalizedSource = optionalSupportedSource(source);
        NewsCurationModels.EvidenceTier parsedTier = optionalEvidenceTier(evidenceTier);
        long safeCurrent = clampCurrent(current);
        long safeSize = clampPageSize(pageSize);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (parsedStatus != null) {
            where.append(" AND c.status = ?");
            args.add(parsedStatus.name());
        }
        if (normalizedSource != null) {
            where.append(" AND c.source = ?");
            args.add(normalizedSource);
        }
        if (parsedTier != null) {
            where.append(" AND c.evidenceTier = ?");
            args.add(parsedTier.name());
        }

        Long totalBoxed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_curation_candidate c" + where,
                Long.class,
                args.toArray()
        );
        long total = totalBoxed == null ? 0L : totalBoxed;

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add((safeCurrent - 1) * safeSize);

        List<NewsWorkbenchModels.CandidateSummaryVO> records = total == 0 ? List.of() : jdbc.query("""
                SELECT c.id, c.materialId, c.materialRevisionId, c.source, c.sourceRecordId,
                       c.contentDigest, c.policyVersion, c.callId, c.status, c.decision,
                       c.evidenceTier, c.relevanceScore, c.suggestedTitle, c.suggestedSummary,
                       c.category, c.contentType,
                       COALESCE(CASE WHEN jsonb_typeof(c.claimsJson) = 'array' THEN jsonb_array_length(c.claimsJson) ELSE 0 END, 0) AS claimsCount,
                       COALESCE(CASE WHEN jsonb_typeof(c.violationsJson) = 'array' THEN jsonb_array_length(c.violationsJson) ELSE 0 END, 0) AS violationsCount,
                       c.evidenceSnapshotSha256,
                """ + LINKED_NEWS_ID_SQL + " AS linkedNewsId, "
                + LINKED_NEWS_STATUS_SQL + """
                       AS linkedNewsStatus,
                       DATE_FORMAT(c.createdAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS createdAtUtc,
                       DATE_FORMAT(c.updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_curation_candidate c
                """ + where + " ORDER BY c.createdAt DESC, c.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapCandidateSummary(rs),
                pagedArgs.toArray()
        );

        return new NewsWorkbenchModels.PageVO<>(records, total, safeCurrent, safeSize, computePages(total, safeSize));
    }

    public NewsWorkbenchModels.CandidateDetailVO getCandidateDetail(long candidateId) {
        if (candidateId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "candidateId 必须大于 0");
        }
        List<CandidateDetailRow> rows = jdbc.query("""
                SELECT c.id, c.materialId, c.materialRevisionId, c.source, c.sourceRecordId,
                       c.contentDigest, c.policyVersion, c.callId, c.status, c.decision,
                       c.evidenceTier, c.relevanceScore, c.suggestedTitle, c.suggestedSummary,
                       c.category, c.contentType, c.limitationsJson, c.claimsJson, c.violationsJson,
                       c.evidenceSnapshotSha256, c.evidenceSnapshotJson,
                """ + LINKED_NEWS_ID_SQL + " AS linkedNewsId, "
                + LINKED_NEWS_STATUS_SQL + """
                       AS linkedNewsStatus,
                       DATE_FORMAT(c.createdAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS createdAtUtc,
                       DATE_FORMAT(c.updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_curation_candidate c
                WHERE c.id = ?
                """, (rs, rowNum) -> new CandidateDetailRow(
                rs.getLong("id"),
                rs.getLong("materialId"),
                rs.getLong("materialRevisionId"),
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                rs.getString("contentDigest"),
                rs.getString("policyVersion"),
                rs.getString("callId"),
                rs.getString("status"),
                rs.getString("decision"),
                rs.getString("evidenceTier"),
                getOptionalInt(rs, "relevanceScore"),
                rs.getString("suggestedTitle"),
                rs.getString("suggestedSummary"),
                rs.getString("category"),
                rs.getString("contentType"),
                rs.getString("limitationsJson"),
                rs.getString("claimsJson"),
                rs.getString("violationsJson"),
                rs.getString("evidenceSnapshotSha256"),
                rs.getString("evidenceSnapshotJson"),
                getOptionalLong(rs, "linkedNewsId"),
                getOptionalInt(rs, "linkedNewsStatus"),
                parseUtcInstant(rs.getString("createdAtUtc")),
                parseUtcInstant(rs.getString("updatedAtUtc"))
        ), candidateId);

        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "精选候选不存在");
        }
        CandidateDetailRow row = rows.getFirst();
        NewsWorkbenchModels.CallReceiptVO callReceipt = null;
        if (row.callId != null && !row.callId.isBlank()) {
            callReceipt = curationService.getCallReceipt(row.callId)
                    .map(NewsWorkbenchService::toCallReceiptVO)
                    .orElse(null);
        }

        return new NewsWorkbenchModels.CandidateDetailVO(
                row.id,
                row.materialId,
                row.materialRevisionId,
                row.source,
                row.sourceRecordId,
                row.contentDigest,
                row.policyVersion,
                row.callId,
                row.status,
                row.decision,
                row.evidenceTier,
                row.relevanceScore,
                row.suggestedTitle,
                row.suggestedSummary,
                row.category,
                row.contentType,
                readJsonList(row.limitationsJson, STRING_LIST_TYPE),
                readJsonList(row.claimsJson, CLAIM_LIST_TYPE),
                readJsonList(row.violationsJson, VIOLATION_LIST_TYPE),
                row.evidenceSnapshotSha256,
                row.evidenceSnapshotJson,
                callReceipt,
                row.linkedNewsId,
                row.linkedNewsStatus,
                row.createdAt,
                row.updatedAt
        );
    }

    public List<NewsWorkbenchModels.BudgetSummaryVO> listBudgets(String scope, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 60));
        if (scope != null && !scope.isBlank()) {
            return jdbc.query("""
                    SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, scope,
                           maxCalls, usedCalls, reservedCalls,
                           maxTokens, usedTokens, reservedTokens,
                           maxCostMicros, usedCostMicros, reservedCostMicros,
                           circuitReason IS NOT NULL AS circuitBroken, circuitReason,
                           DATE_FORMAT(updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                    FROM t_news_curation_budget LEFT JOIN t_news_curation_circuit USING (scope)
                    WHERE scope = ?
                    ORDER BY budgetDate DESC, scope ASC
                    LIMIT ?
                    """, (rs, rowNum) -> mapBudgetRow(rs), scope.trim(), safeLimit);
        }
        return jdbc.query("""
                SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, scope,
                       maxCalls, usedCalls, reservedCalls,
                       maxTokens, usedTokens, reservedTokens,
                       maxCostMicros, usedCostMicros, reservedCostMicros,
                       circuitReason IS NOT NULL AS circuitBroken, circuitReason,
                       DATE_FORMAT(updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_curation_budget LEFT JOIN t_news_curation_circuit USING (scope)
                ORDER BY budgetDate DESC, scope ASC
                LIMIT ?
                """, (rs, rowNum) -> mapBudgetRow(rs), safeLimit);
    }

    public NewsWorkbenchModels.BudgetSummaryVO configureBudget(NewsWorkbenchModels.ConfigureBudgetRequest req) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "预算配置请求不能为空");
        }
        LocalDate date = parseStrictIsoDate(req.budgetDate(), "budgetDate");
        String scope = normalizeScope(req.scope());
        if (req.maxCalls() == null || req.maxCalls() < 0
                || req.maxTokens() == null || req.maxTokens() < 0
                || req.maxCostMicros() == null || req.maxCostMicros() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "预算额度字段必须是非负整数");
        }
        try {
            curationService.configureDailyBudget(date, scope, req.maxCalls(), req.maxTokens(), req.maxCostMicros());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, ex.getMessage());
        }
        return requireBudgetSummary(date, scope);
    }

    public NewsWorkbenchModels.BudgetSummaryVO resetCircuitBreaker(
            NewsWorkbenchModels.ResetCircuitRequest req, User operator) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "重置熔断请求不能为空");
        }
        if (operator == null || operator.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "需要已登录管理员执行熔断恢复");
        }
        LocalDate date = parseStrictIsoDate(req.budgetDate(), "budgetDate");
        String scope = normalizeScope(req.scope());
        try {
            curationService.resetCircuitBreaker(date, scope, "admin:" + operator.getId());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, ex.getMessage());
        }
        return requireBudgetSummary(date, scope);
    }

    public NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CallReceiptVO> listReceipts(
            String state, String budgetScope, long current, long pageSize) {
        NewsCurationModels.CallState parsedState = optionalCallState(state);
        String normalizedScope = (budgetScope == null || budgetScope.isBlank()) ? null : budgetScope.trim();
        long safeCurrent = clampCurrent(current);
        long safeSize = clampPageSize(pageSize);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (parsedState != null) {
            where.append(" AND state = ?");
            args.add(parsedState.name());
        }
        if (normalizedScope != null) {
            where.append(" AND budgetScope = ?");
            args.add(normalizedScope);
        }

        Long totalBoxed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_model_call_receipt" + where,
                Long.class,
                args.toArray()
        );
        long total = totalBoxed == null ? 0L : totalBoxed;

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add((safeCurrent - 1) * safeSize);

        List<NewsWorkbenchModels.CallReceiptVO> records = total == 0 ? List.of() : jdbc.query("""
                SELECT callId, idempotencyKey, materialRevisionId, policyVersion, attemptNo,
                       DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, budgetScope,
                       provider, modelName, promptSha256, state, reservedTokens, reservedCostMicros,
                       promptTokens, completionTokens, actualCostMicros, httpStatus, providerRequestId,
                       responseSha256, errorCode, latencyMs, reconciledBy, reconcileNote,
                       DATE_FORMAT(reconciledAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS reconciledAtUtc,
                       DATE_FORMAT(reservedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS reservedAtUtc,
                       DATE_FORMAT(dispatchedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS dispatchedAtUtc,
                       DATE_FORMAT(deadlineAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS deadlineAtUtc,
                       DATE_FORMAT(completedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS completedAtUtc
                FROM t_news_model_call_receipt
                """ + where + " ORDER BY reservedAt DESC, callId DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapReceiptRow(rs),
                pagedArgs.toArray()
        );

        return new NewsWorkbenchModels.PageVO<>(records, total, safeCurrent, safeSize, computePages(total, safeSize));
    }

    public NewsCurationModels.StaleReclaimResult reclaimStaleCalls() {
        return curationService.reclaimStaleCalls();
    }

    public NewsWorkbenchModels.CallReceiptVO reconcileReceipt(
            String callId, NewsWorkbenchModels.ReconcileReceiptRequest req, User operator) {
        if (callId == null || callId.isBlank() || callId.trim().length() > 64) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "callId 不合法");
        }
        if (req == null || req.resolution() == null || req.resolution().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "resolution 不能为空");
        }
        if (operator == null || operator.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "需要已登录管理员执行对账");
        }
        NewsCurationModels.ReconcileResolution resolution;
        try {
            resolution = NewsCurationModels.ReconcileResolution.valueOf(req.resolution().trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的对账结论: " + req.resolution());
        }

        String providerRequestId = (req.providerRequestId() == null || req.providerRequestId().isBlank())
                ? null : req.providerRequestId().trim();
        try {
            NewsCurationModels.CallReceiptSnapshot updated = curationService.reconcileUnknownCall(
                    callId.trim(),
                    resolution,
                    "admin:" + operator.getId(),
                    req.note(),
                    req.recoveredRawJson(),
                    req.promptTokens(),
                    req.completionTokens(),
                    req.actualCostMicros(),
                    providerRequestId
            );
            return toCallReceiptVO(updated);
        } catch (IllegalArgumentException ex) {
            if ("UNKNOWN_CALL_ID".equals(ex.getMessage())) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "调用回执不存在");
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, ex.getMessage());
        }
    }

    public List<NewsWorkbenchModels.BenchRunSummaryVO> listBenchRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbc.query("""
                SELECT runId, datasetVersion, datasetSha256, policyVersion, mode,
                       totalCases, passedCases, precisionBps, recallBps,
                       evidenceCatchBps, licenseComplianceBps, injectionCatchBps,
                       totalCalls, totalTokens, totalCostMicros,
                       CAST(gatePassed AS SIGNED) AS gatePassed,
                       DATE_FORMAT(createdAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS createdAtUtc
                FROM t_news_bench_run
                ORDER BY createdAt DESC, runId DESC
                LIMIT ?
                """, (rs, rowNum) -> new NewsWorkbenchModels.BenchRunSummaryVO(
                rs.getString("runId"),
                rs.getString("datasetVersion"),
                rs.getString("datasetSha256"),
                rs.getString("policyVersion"),
                rs.getString("mode"),
                rs.getInt("totalCases"),
                rs.getInt("passedCases"),
                rs.getInt("precisionBps"),
                rs.getInt("recallBps"),
                rs.getInt("evidenceCatchBps"),
                rs.getInt("licenseComplianceBps"),
                rs.getInt("injectionCatchBps"),
                rs.getInt("totalCalls"),
                rs.getLong("totalTokens"),
                rs.getLong("totalCostMicros"),
                rs.getInt("gatePassed") == 1,
                parseUtcInstant(rs.getString("createdAtUtc"))
        ), safeLimit);
    }

    private List<NewsWorkbenchModels.SourceDetailVO> loadSources(String optionalSource) {
        String sourceSql = """
                SELECT source, CAST(enabled AS SIGNED) AS enabled,
                       DATE_FORMAT(updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_source
                """ + (optionalSource != null ? "WHERE source = ? " : "") + "ORDER BY source ASC";
        List<SourceRow> sourceRows = optionalSource != null
                ? jdbc.query(sourceSql, (rs, rowNum) -> new SourceRow(
                        rs.getString("source"),
                        rs.getInt("enabled") == 1,
                        parseUtcInstant(rs.getString("updatedAtUtc"))
                ), optionalSource)
                : jdbc.query(sourceSql, (rs, rowNum) -> new SourceRow(
                        rs.getString("source"),
                        rs.getInt("enabled") == 1,
                        parseUtcInstant(rs.getString("updatedAtUtc"))
                ));

        if (sourceRows.isEmpty()) {
            return List.of();
        }

        Map<String, List<NewsWorkbenchModels.SourceCursorVO>> cursorsBySource = new LinkedHashMap<>();
        String cursorSql = """
                SELECT source, sourceConfigVersion, queryText,
                       DATE_FORMAT(committedCursor, '%Y-%m-%d') AS committedCursor,
                       DATE_FORMAT(updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_source_cursor
                """ + (optionalSource != null ? "WHERE source = ? " : "") + "ORDER BY source ASC, sourceConfigVersion DESC";
        if (optionalSource != null) {
            jdbc.query(cursorSql, rs -> {
                cursorsBySource.computeIfAbsent(rs.getString("source"), k -> new ArrayList<>())
                        .add(new NewsWorkbenchModels.SourceCursorVO(
                                rs.getLong("sourceConfigVersion"),
                                rs.getString("queryText"),
                                rs.getString("committedCursor"),
                                parseUtcInstant(rs.getString("updatedAtUtc"))
                        ));
            }, optionalSource);
        } else {
            jdbc.query(cursorSql, rs -> {
                cursorsBySource.computeIfAbsent(rs.getString("source"), k -> new ArrayList<>())
                        .add(new NewsWorkbenchModels.SourceCursorVO(
                                rs.getLong("sourceConfigVersion"),
                                rs.getString("queryText"),
                                rs.getString("committedCursor"),
                                parseUtcInstant(rs.getString("updatedAtUtc"))
                        ));
            });
        }

        Map<String, Long> materialCounts = new LinkedHashMap<>();
        String matSql = "SELECT source, COUNT(*) AS cnt FROM t_news_material "
                + (optionalSource != null ? "WHERE source = ? " : "") + "GROUP BY source";
        if (optionalSource != null) {
            jdbc.query(matSql, rs -> {
                materialCounts.put(rs.getString("source"), rs.getLong("cnt"));
            }, optionalSource);
        } else {
            jdbc.query(matSql, rs -> {
                materialCounts.put(rs.getString("source"), rs.getLong("cnt"));
            });
        }

        Map<String, Map<String, Long>> taskCounts = new LinkedHashMap<>();
        String taskSql = "SELECT source, status, COUNT(*) AS cnt FROM t_news_collection_task "
                + (optionalSource != null ? "WHERE source = ? " : "") + "GROUP BY source, status";
        if (optionalSource != null) {
            jdbc.query(taskSql, rs -> {
                taskCounts.computeIfAbsent(rs.getString("source"), k -> new LinkedHashMap<>())
                        .put(rs.getString("status"), rs.getLong("cnt"));
            }, optionalSource);
        } else {
            jdbc.query(taskSql, rs -> {
                taskCounts.computeIfAbsent(rs.getString("source"), k -> new LinkedHashMap<>())
                        .put(rs.getString("status"), rs.getLong("cnt"));
            });
        }

        List<NewsWorkbenchModels.SourceDetailVO> out = new ArrayList<>(sourceRows.size());
        for (SourceRow row : sourceRows) {
            Map<String, Long> perStatus = taskCounts.getOrDefault(row.source, Map.of());
            out.add(new NewsWorkbenchModels.SourceDetailVO(
                    row.source,
                    row.enabled,
                    row.updatedAt,
                    materialCounts.getOrDefault(row.source, 0L),
                    perStatus.getOrDefault("PENDING", 0L),
                    perStatus.getOrDefault("LEASED", 0L),
                    perStatus.getOrDefault("FAILED", 0L),
                    perStatus.getOrDefault("COMPLETED", 0L),
                    List.copyOf(cursorsBySource.getOrDefault(row.source, List.of()))
            ));
        }
        return out;
    }

    private NewsWorkbenchModels.BudgetSummaryVO requireBudgetSummary(LocalDate date, String scope) {
        List<NewsWorkbenchModels.BudgetSummaryVO> rows = jdbc.query("""
                SELECT DATE_FORMAT(budgetDate, '%Y-%m-%d') AS budgetDate, scope,
                       maxCalls, usedCalls, reservedCalls,
                       maxTokens, usedTokens, reservedTokens,
                       maxCostMicros, usedCostMicros, reservedCostMicros,
                       circuitReason IS NOT NULL AS circuitBroken, circuitReason,
                       DATE_FORMAT(updatedAt, '%Y-%m-%dT%H:%i:%s.%fZ') AS updatedAtUtc
                FROM t_news_curation_budget LEFT JOIN t_news_curation_circuit USING (scope)
                WHERE budgetDate = ? AND scope = ?
                """, (rs, rowNum) -> mapBudgetRow(rs), date.toString(), scope);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "预算记录不存在");
        }
        return rows.getFirst();
    }

    private <T> List<T> readJsonList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "候选结构化证据解析失败");
        }
    }

    private static String requireSupportedSource(String source) {
        if (source == null || !NewsWireModels.SOURCES.contains(source.trim())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的信源: " + source);
        }
        return source.trim();
    }

    private static String optionalSupportedSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        return requireSupportedSource(source);
    }

    private static String optionalTaskStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String trimmed = status.trim();
        if (!TASK_STATUSES.contains(trimmed)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的任务状态: " + status);
        }
        return trimmed;
    }

    private static NewsCurationModels.CandidateStatus optionalCandidateStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return NewsCurationModels.CandidateStatus.valueOf(status.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的候选状态: " + status);
        }
    }

    private static NewsCurationModels.EvidenceTier optionalEvidenceTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return null;
        }
        try {
            return NewsCurationModels.EvidenceTier.valueOf(tier.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的证据等级: " + tier);
        }
    }

    private static NewsCurationModels.CallState optionalCallState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return NewsCurationModels.CallState.valueOf(state.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的调用回执状态: " + state);
        }
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return DEFAULT_BUDGET_SCOPE;
        }
        String trimmed = scope.trim();
        if (trimmed.length() > 64) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "scope 长度不能超过 64");
        }
        return trimmed;
    }

    private static LocalDate parseStrictIsoDate(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, fieldName + " 不能为空");
        }
        try {
            return LocalDate.parse(text.trim(), STRICT_ISO_DATE);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, fieldName + " 必须是合法 YYYY-MM-DD 日期");
        }
    }

    private static long clampCurrent(long current) {
        // 上限不是产品口径，是算术边界：current 来自查询串，超大的值会让
        // (current - 1) * pageSize 溢出成负数，OFFSET 为负时 MySQL 直接报错（500 而非空页）。
        return Math.min(Math.max(1L, current), MAX_PAGE_NUMBER);
    }

    private static long clampPageSize(long pageSize) {
        if (pageSize <= 0) {
            return 10L;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static long computePages(long total, long size) {
        return total <= 0 ? 0L : (total + size - 1L) / size;
    }

    private static NewsWorkbenchModels.CollectionTaskVO mapTaskRow(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        String lastFailureCode = rs.getString("lastFailureCode");
        String blockedByTaskId = rs.getString("blockedByTaskId");
        String blockedByStatus = rs.getString("blockedByStatus");
        return new NewsWorkbenchModels.CollectionTaskVO(
                rs.getString("taskId"),
                rs.getString("taskType"),
                rs.getString("source"),
                rs.getLong("sourceConfigVersion"),
                rs.getString("queryText"),
                rs.getString("windowStartOn"),
                rs.getString("windowEndBefore"),
                status,
                rs.getLong("generation"),
                rs.getInt("attemptCount"),
                rs.getInt("maxAttempts"),
                rs.getString("leaseWorkerId"),
                parseUtcInstant(rs.getString("leaseExpiresAtUtc")),
                rs.getInt("leaseExpired") == 1,
                parseUtcInstant(rs.getString("nextAttemptAtUtc")),
                lastFailureCode,
                getOptionalInt(rs, "committedBatchCount"),
                getOptionalInt(rs, "committedRecordCount"),
                rs.getString("committedCursor"),
                parseUtcInstant(rs.getString("completedAtUtc")),
                parseUtcInstant(rs.getString("createdAtUtc")),
                parseUtcInstant(rs.getString("updatedAtUtc")),
                describeBlockingReason(status, lastFailureCode, blockedByTaskId, blockedByStatus),
                blockedByTaskId,
                TASK_STATUS_FAILED.equals(status)
        );
    }

    /**
     * 阻塞原因的口径：先说本窗口自己的问题（失败），再说被谁挡住；都没有就不给理由。
     *
     * <p>系统不会自动跳过失败窗口——所以「卡住了」必须在这里说清楚，
     * 而不是让运营从一堆 PENDING 里猜。
     */
    static String describeBlockingReason(String status, String lastFailureCode,
                                         String blockedByTaskId, String blockedByStatus) {
        StringBuilder reason = new StringBuilder();
        if (TASK_STATUS_FAILED.equals(status)) {
            reason.append("本窗口已失败")
                    .append(lastFailureCode == null || lastFailureCode.isBlank() ? "" : "（" + lastFailureCode + "）")
                    .append("，需从失败窗口重试入口重新打开；系统不会自动跳过");
        }
        if (blockedByTaskId != null && !blockedByTaskId.isBlank()) {
            if (reason.length() > 0) {
                reason.append("；同时");
            }
            reason.append("被更早窗口任务 ").append(blockedByTaskId)
                    .append(blockedByStatus == null || blockedByStatus.isBlank() ? "" : "（状态 " + blockedByStatus + "）")
                    .append(" 阻塞，需先处理该窗口");
        }
        return reason.length() == 0 ? null : reason.toString();
    }

    private static NewsWorkbenchModels.CandidateSummaryVO mapCandidateSummary(ResultSet rs) throws SQLException {
        return new NewsWorkbenchModels.CandidateSummaryVO(
                rs.getLong("id"),
                rs.getLong("materialId"),
                rs.getLong("materialRevisionId"),
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                rs.getString("contentDigest"),
                rs.getString("policyVersion"),
                rs.getString("callId"),
                rs.getString("status"),
                rs.getString("decision"),
                rs.getString("evidenceTier"),
                getOptionalInt(rs, "relevanceScore"),
                rs.getString("suggestedTitle"),
                rs.getString("suggestedSummary"),
                rs.getString("category"),
                rs.getString("contentType"),
                rs.getInt("claimsCount"),
                rs.getInt("violationsCount"),
                rs.getString("evidenceSnapshotSha256"),
                getOptionalLong(rs, "linkedNewsId"),
                getOptionalInt(rs, "linkedNewsStatus"),
                parseUtcInstant(rs.getString("createdAtUtc")),
                parseUtcInstant(rs.getString("updatedAtUtc"))
        );
    }

    private static NewsWorkbenchModels.BudgetSummaryVO mapBudgetRow(ResultSet rs) throws SQLException {
        return new NewsWorkbenchModels.BudgetSummaryVO(
                rs.getString("budgetDate"),
                rs.getString("scope"),
                rs.getInt("maxCalls"),
                rs.getInt("usedCalls"),
                rs.getInt("reservedCalls"),
                rs.getLong("maxTokens"),
                rs.getLong("usedTokens"),
                rs.getLong("reservedTokens"),
                rs.getLong("maxCostMicros"),
                rs.getLong("usedCostMicros"),
                rs.getLong("reservedCostMicros"),
                rs.getInt("circuitBroken") == 1,
                rs.getString("circuitReason"),
                parseUtcInstant(rs.getString("updatedAtUtc"))
        );
    }

    private static NewsWorkbenchModels.CallReceiptVO mapReceiptRow(ResultSet rs) throws SQLException {
        return new NewsWorkbenchModels.CallReceiptVO(
                rs.getString("callId"),
                rs.getString("idempotencyKey"),
                rs.getLong("materialRevisionId"),
                rs.getString("policyVersion"),
                rs.getInt("attemptNo"),
                rs.getString("budgetDate"),
                rs.getString("budgetScope"),
                rs.getString("provider"),
                rs.getString("modelName"),
                rs.getString("promptSha256"),
                rs.getString("state"),
                rs.getLong("reservedTokens"),
                rs.getLong("reservedCostMicros"),
                getOptionalInt(rs, "promptTokens"),
                getOptionalInt(rs, "completionTokens"),
                getOptionalLong(rs, "actualCostMicros"),
                getOptionalInt(rs, "httpStatus"),
                rs.getString("providerRequestId"),
                rs.getString("responseSha256"),
                rs.getString("errorCode"),
                getOptionalInt(rs, "latencyMs"),
                rs.getString("reconciledBy"),
                rs.getString("reconcileNote"),
                parseUtcInstant(rs.getString("reconciledAtUtc")),
                parseUtcInstant(rs.getString("reservedAtUtc")),
                parseUtcInstant(rs.getString("dispatchedAtUtc")),
                parseUtcInstant(rs.getString("deadlineAtUtc")),
                parseUtcInstant(rs.getString("completedAtUtc"))
        );
    }

    private static NewsWorkbenchModels.CallReceiptVO toCallReceiptVO(NewsCurationModels.CallReceiptSnapshot s) {
        return new NewsWorkbenchModels.CallReceiptVO(
                s.callId(),
                s.idempotencyKey(),
                s.materialRevisionId(),
                s.policyVersion(),
                s.attemptNo(),
                s.budgetDate() == null ? null : s.budgetDate().toString(),
                s.budgetScope(),
                s.provider(),
                s.modelName(),
                s.promptSha256(),
                s.state() == null ? null : s.state().name(),
                s.reservedTokens(),
                s.reservedCostMicros(),
                s.promptTokens(),
                s.completionTokens(),
                s.actualCostMicros(),
                s.httpStatus(),
                s.providerRequestId(),
                s.responseSha256(),
                s.errorCode(),
                s.latencyMs(),
                s.reconciledBy(),
                s.reconcileNote(),
                s.reconciledAt(),
                s.reservedAt(),
                s.dispatchedAt(),
                s.deadlineAt(),
                s.completedAt()
        );
    }

    private static Integer getOptionalInt(ResultSet rs, String col) throws SQLException {
        Object val = rs.getObject(col);
        if (val == null) {
            return null;
        }
        if (val instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) val).intValue();
    }

    private static Long getOptionalLong(ResultSet rs, String col) throws SQLException {
        Object val = rs.getObject(col);
        if (val == null) {
            return null;
        }
        if (val instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        return ((Number) val).longValue();
    }

    private static Instant parseUtcInstant(String text) {
        return text == null ? null : Instant.from(DB_UTC_FORMAT.parse(text));
    }

    private record SourceRow(String source, boolean enabled, Instant updatedAt) {
    }

    private record CandidateDetailRow(
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
            String limitationsJson,
            String claimsJson,
            String violationsJson,
            String evidenceSnapshotSha256,
            String evidenceSnapshotJson,
            Long linkedNewsId,
            Integer linkedNewsStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** WP-08B.2：查看某份资料修订的证据核验历史（含已撤回行）。 */
    public List<NewsWorkbenchModels.EvidenceVerificationVO> listEvidenceVerifications(long materialRevisionId) {
        if (materialRevisionId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "materialRevisionId 必须大于 0");
        }
        return curationService.listEvidenceVerifications(materialRevisionId).stream()
                .map(NewsWorkbenchService::toEvidenceVerificationVO)
                .toList();
    }

    /** WP-08B.2：登记一条人工证据核验；只有管理员可以写，值必须能由冻结资料佐证。 */
    public NewsWorkbenchModels.EvidenceVerificationVO verifyEvidence(
            long materialRevisionId,
            NewsWorkbenchModels.SubmitEvidenceVerificationRequest body,
            User operator) {
        if (materialRevisionId <= 0 || body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "证据核验参数不完整");
        }
        String reviewer = requireOperator(operator);
        NewsEvidenceLedger.Attribute attribute = parseEnum(NewsEvidenceLedger.Attribute.class, body.attribute());
        NewsEvidenceLedger.Method method = parseEnum(NewsEvidenceLedger.Method.class, body.method());
        try {
            NewsEvidenceLedger.Verification stored = curationService.verifyEvidence(materialRevisionId,
                    new NewsEvidenceLedger.Request(attribute, body.value(), method, body.evidenceUrl(),
                            body.sourceVersion(), body.evidenceNote()), reviewer);
            return toEvidenceVerificationVO(stored);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, invalid.getMessage());
        } catch (IllegalStateException active) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "EVIDENCE_ALREADY_ACTIVE".equals(active.getMessage())
                            ? "该属性已有生效中的核验，请先撤回再重新登记" : active.getMessage());
        }
    }

    /** WP-08B.2：撤回一条证据核验；撤回后依赖它的候选无法建稿，必须重新核验。 */
    public NewsWorkbenchModels.EvidenceVerificationVO revokeEvidence(
            long verificationId,
            NewsWorkbenchModels.RevokeEvidenceVerificationRequest body,
            User operator) {
        if (verificationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "verificationId 必须大于 0");
        }
        String reviewer = requireOperator(operator);
        try {
            return toEvidenceVerificationVO(curationService.revokeEvidence(verificationId, reviewer,
                    body == null ? null : body.note()));
        } catch (IllegalStateException notActive) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该核验不存在或已撤回");
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, invalid.getMessage());
        }
    }

    /** WP-08B.2：把一条冻结的生产联合精选回执转成待人工审核候选（幂等）。 */
    public NewsWorkbenchModels.MaterializedCandidateVO materializeCandidate(String callId, User operator) {
        return materializeCandidate(callId, operator, null, null);
    }

    /**
     * 同一入口的替代形态：点名一条已失效的旧候选并给出理由，才允许新的成功回执接管它。
     * 未点名时保持旧行为——重复转换一律拒绝，绝不覆盖。
     */
    public NewsWorkbenchModels.MaterializedCandidateVO materializeCandidate(String callId, User operator,
                                                                           Long supersedesCandidateId,
                                                                           String supersedeReason) {
        if (callId == null || callId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "callId 不能为空");
        }
        if (supersedesCandidateId != null && (supersedeReason == null || supersedeReason.isBlank())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "替代旧候选必须写明理由");
        }
        String reviewer = requireOperator(operator);
        try {
            NewsCurationService.MaterializedCandidate materialized =
                    curationService.materializeCandidate(callId.trim(), reviewer, supersedesCandidateId,
                            supersedeReason);
            return new NewsWorkbenchModels.MaterializedCandidateVO(materialized.candidateId(),
                    materialized.status(), materialized.callId(), materialized.created(),
                    materialized.evidenceVerificationSha256(), materialized.supersededCandidateId());
        } catch (IllegalStateException refused) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, materializeMessage(refused.getMessage()));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, invalid.getMessage());
        }
    }

    private static String materializeMessage(String rawCode) {
        String code = String.valueOf(rawCode);
        int separator = code.indexOf(':');
        if (separator > 0) {
            code = code.substring(0, separator);
        }
        return switch (code) {
            case "MODEL_CALL_NOT_FOUND" -> "模型调用回执不存在";
            case "MODEL_CALL_NOT_MATERIALIZABLE" -> "只有已成功且冻结了响应的回执可以转为候选";
            case "MODEL_CALL_NOT_PRODUCTION_JOINT" -> "只有生产命名空间的联合精选结果可以转为候选";
            case "MODEL_CALL_IDENTITY_INVALID" -> "模型调用身份无法解析";
            case "EVIDENCE_VERIFICATION_STALE" -> "证据核验已撤回或变更，请重新核验后再转换";
            case "FROZEN_OUTPUT_INVALID" -> "冻结响应未通过当前契约校验";
            case "CANDIDATE_ALREADY_MATERIALIZED" -> "该资料修订与策略版本已有候选，禁止覆盖；确需替代请点名旧候选并写明理由";
            case "CANDIDATE_POLICY_CONFLICT" -> "同一资料修订已存在其他策略版本的候选，禁止通过改策略版本绕开冲突";
            case "CANDIDATE_SUPERSEDE_TARGET_MISSING" -> "没有可替代的生效候选";
            case "CANDIDATE_SUPERSEDE_TARGET_MISMATCH" -> "指定的替代对象不是当前生效候选";
            case "CANDIDATE_NOT_SUPERSEDABLE" -> "当前候选状态不允许被替代，请先处理对账或状态问题";
            case "CANDIDATE_ALREADY_DRAFTED" -> "旧候选已关联未删除的资讯，请先处置该草稿后再替代";
            default -> "模型结果无法转为候选: " + code;
        };
    }

    private static String requireOperator(User operator) {
        if (operator == null || operator.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "需要管理员身份");
        }
        return "admin:" + operator.getId();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "枚举值不能为空");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的枚举值: " + raw);
        }
    }

    private static NewsWorkbenchModels.EvidenceVerificationVO toEvidenceVerificationVO(
            NewsEvidenceLedger.Verification row) {
        return new NewsWorkbenchModels.EvidenceVerificationVO(row.id(), row.materialRevisionId(),
                row.attribute().name(), row.value(), row.method().name(), row.evidenceUrl(), row.sourceVersion(),
                row.evidenceNote(), row.verifiedBy(), row.verifiedAt() == null ? null : row.verifiedAt().toString(),
                row.active(), row.revokedBy(), row.revokedAt() == null ? null : row.revokedAt().toString(),
                row.revokeNote());
    }
}
