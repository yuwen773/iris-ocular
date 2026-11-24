package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.constant.NotificationType;
import com.csust.eyediagnosis.grpc.DiagnosisGrpcClient;
import com.csust.eyediagnosis.grpc.DiagnosisProto;
import com.csust.eyediagnosis.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Component
public class DiagnosisTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(DiagnosisTaskRunner.class);
    private final JdbcTemplate jdbc;
    private final DiagnosisTaskStorage storage;
    private final DiagnosisGrpcClient grpc;
    private final Clock clock;
    /** 通知是旁路：为 null 时（纯单元测试）不发送，正文逻辑不受影响。 */
    private final NotificationService notifications;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "diagnosis-task-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();

    @Autowired
    public DiagnosisTaskRunner(JdbcTemplate jdbc, DiagnosisTaskStorage storage, DiagnosisGrpcClient grpc,
                               NotificationService notifications) {
        this(jdbc, storage, grpc, null, notifications);
    }

    /** 测试用：不接通知服务。 */
    public DiagnosisTaskRunner(JdbcTemplate jdbc, DiagnosisTaskStorage storage, DiagnosisGrpcClient grpc) {
        this(jdbc, storage, grpc, null, null);
    }

    DiagnosisTaskRunner(JdbcTemplate jdbc, DiagnosisTaskStorage storage, DiagnosisGrpcClient grpc, Clock clock) {
        this(jdbc, storage, grpc, clock, null);
    }

    DiagnosisTaskRunner(JdbcTemplate jdbc, DiagnosisTaskStorage storage, DiagnosisGrpcClient grpc, Clock clock,
                        NotificationService notifications) {
        this.jdbc = jdbc; this.storage = storage; this.grpc = grpc; this.clock = clock;
        this.notifications = notifications;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    @Scheduled(fixedDelayString = "${eye-diagnosis.task.poll-ms:5000}")
    public void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> { try { runOnce(); } finally { scheduled.set(false); } });
        } catch (java.util.concurrent.RejectedExecutionException e) { scheduled.set(false); }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    public void runOnce() {
        try { processOne(); }
        catch (Exception e) { log.error("Diagnosis task polling failed", e); }
    }

    private void processOne() throws Exception {
        Timestamp now = now();
        var candidates = jdbc.query("SELECT taskId,version FROM t_diagnosis_task WHERE BINARY status=BINARY 'PENDING' OR (BINARY status=BINARY 'RUNNING' AND leaseUntil<=COALESCE(?,CURRENT_TIMESTAMP(6))) ORDER BY createdAt LIMIT 1",
                (rs, n) -> new Candidate(rs.getString(1), rs.getLong(2)), now);
        if (candidates.isEmpty()) return;
        var candidate = candidates.getFirst();
        String owner = UUID.randomUUID().toString();
        int claimed = fencedUpdate(candidate.id(), () -> jdbc.update("UPDATE t_diagnosis_task SET status='RUNNING', leaseOwner=?, leaseUntil=TIMESTAMPADD(SECOND,120,COALESCE(?,CURRENT_TIMESTAMP(6))), leaseToken=leaseToken+1, version=version+1, updatedAt=COALESCE(?,CURRENT_TIMESTAMP(6)) WHERE taskId=? AND version=? AND (BINARY status=BINARY 'PENDING' OR (BINARY status=BINARY 'RUNNING' AND leaseUntil<=COALESCE(?,CURRENT_TIMESTAMP(6))))",
                owner, now(), now(), candidate.id(), candidate.version(), now()));
        if (claimed != 1) return;
        var rows = jdbc.query("SELECT userId,objectKey,objectVersion,imageDigest,imageFormat,attemptCount,leaseToken,version FROM t_diagnosis_task WHERE taskId=? AND leaseOwner=?",
                (rs, n) -> new Claimed(candidate.id(), owner, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getLong(8)), candidate.id(), owner);
        if (rows.isEmpty()) return;
        var task = rows.getFirst();
        if (task.attempts() >= 2) { finish(task, "RESTRICTED", "ATTEMPTS_EXHAUSTED", null); return; }
        byte[] bytes;
        try {
            bytes = storage.readVersion(task.objectKey(), task.objectVersion());
            if (bytes == null || !task.imageDigest().equals(DiagnosisTaskService.digest(bytes))) throw new IllegalStateException("Input digest mismatch");
        } catch (Exception e) { finish(task, "RESTRICTED", "IMAGE_UNREADABLE", null); return; }
        var toReserve = task;
        int reserved = fencedUpdate(task.id(), () -> jdbc.update("UPDATE t_diagnosis_task SET attemptCount=attemptCount+1, version=version+1, updatedAt=COALESCE(?,CURRENT_TIMESTAMP(6)) WHERE taskId=? AND BINARY status=BINARY 'RUNNING' AND leaseOwner=? AND leaseToken=? AND version=? AND leaseUntil>COALESCE(?,CURRENT_TIMESTAMP(6)) AND attemptCount<2",
                now(), toReserve.id(), toReserve.owner(), toReserve.token(), toReserve.version(), now()));
        if (reserved != 1) return;
        task = task.reserved();
        DiagnosisProto.DiagnosisResponse response;
        try { response = grpc.diagnose(bytes, "image/" + task.format()); }
        catch (Exception e) {
            var code = io.grpc.Status.fromThrowable(e).getCode();
            boolean deterministic = code == io.grpc.Status.Code.INVALID_ARGUMENT
                    || code == io.grpc.Status.Code.FAILED_PRECONDITION
                    || code == io.grpc.Status.Code.OUT_OF_RANGE;
            boolean retry = !deterministic && task.attempts() < 2;
            finish(task, retry ? "PENDING" : "RESTRICTED", retry ? null : "INFERENCE_FAILED", null);
            return;
        }
        if (response == null) { finish(task, "RESTRICTED", "INFERENCE_UNAVAILABLE", null); return; }
        String snapshot = InferenceSnapshot.write(
                new InferenceSnapshot.Identity(task.id(), task.userId(), task.objectKey(),
                        task.objectVersion(), task.imageDigest(), task.format()),
                response);
        // 准入 fail-closed，并把「为什么受限」记下来：只有 IN_DISTRIBUTION 才说明
        // 有版本绑定的 OOD 证据。NOT_EVALUATED / EVALUATION_FAILED 都不是「在分布内」，
        // 不能为了跑通把它们当成通过；前端据此说明受限原因，而不是只说一句「受限」。
        String restrictReason = restrictReason(response);
        finish(task, restrictReason == null ? "WAITING_REVIEW" : "RESTRICTED", restrictReason, snapshot);
    }

    /** 患者任务准入的拒绝原因；返回 null 表示四条准入都满足。 */
    static String restrictReason(DiagnosisProto.DiagnosisResponse response) {
        if (response.getRejected()) {
            String abstain = response.getAbstainReason();
            return abstain == null || abstain.isBlank() ? "ABSTAIN_UNSPECIFIED" : "ABSTAIN_" + abstain;
        }
        if (response.getIsOod() || response.getOodStatus() == DiagnosisProto.OodStatus.OUT_OF_DISTRIBUTION) {
            return "OOD_OUT_OF_DISTRIBUTION";
        }
        if (response.getOodStatus() == DiagnosisProto.OodStatus.EVALUATION_FAILED) {
            return "OOD_EVALUATION_FAILED";
        }
        if (response.getOodStatus() != DiagnosisProto.OodStatus.IN_DISTRIBUTION) {
            // NOT_EVALUATED 与任何认不出的取值：没有版本绑定的 OOD 证据，准入不成立。
            return "OOD_NOT_EVALUATED";
        }
        if (response.getPredictedLabel().isBlank()) return "PREDICTION_MISSING";
        return null;
    }

    private void finish(Claimed task, String status, String restrictReason, String snapshot) {
        int rows = fencedUpdate(task.id(), () -> jdbc.update("UPDATE t_diagnosis_task SET status=?, restrictReason=?, inferenceSnapshot=?, leaseOwner=NULL, leaseUntil=NULL, version=version+1, updatedAt=COALESCE(?,CURRENT_TIMESTAMP(6)) WHERE taskId=? AND BINARY status=BINARY 'RUNNING' AND leaseOwner=? AND leaseToken=? AND version=? AND leaseUntil>COALESCE(?,CURRENT_TIMESTAMP(6)) AND inferenceSnapshot IS NULL",
                status, restrictReason, snapshot, now(), task.id(), task.owner(), task.token(), task.version(), now()));
        // fencedUpdate 自带事务，返回时已提交，所以这里就是「提交后发送」。
        // 只有落到两个对外可见的终态才通知：PENDING（重试）用户看不到变化。
        if (rows == 1 && notifications != null) {
            if ("WAITING_REVIEW".equals(status)) {
                notifications.notify(task.userId(), NotificationType.DIAGNOSIS_READY, task.id());
            } else if ("RESTRICTED".equals(status)) {
                notifications.notify(task.userId(), NotificationType.DIAGNOSIS_RESTRICTED, task.id());
            }
        }
    }

    private int fencedUpdate(String taskId, java.util.function.IntSupplier update) {
        Integer result = transactions.execute(status -> {
            var locked = jdbc.query("SELECT taskId FROM t_diagnosis_task WHERE taskId=? FOR UPDATE", (rs, n) -> rs.getString(1), taskId);
            return locked.isEmpty() ? 0 : update.getAsInt();
        });
        return result == null ? 0 : result;
    }

    private Timestamp now() { return clock == null ? null : Timestamp.from(clock.instant()); }
    private record Candidate(String id, long version) { }
    private record Claimed(String id, String owner, long userId, String objectKey, String objectVersion,
                           String imageDigest, String format, int attempts, long token, long version) {
        Claimed reserved() { return new Claimed(id, owner, userId, objectKey, objectVersion, imageDigest, format, attempts + 1, token, version + 1); }
    }
}
