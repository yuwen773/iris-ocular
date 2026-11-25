package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.controlcenter.model.dto.ApprovalDecisionRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.DraftSaveRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.PublishRequestBody;
import com.csust.eyediagnosis.controlcenter.model.vo.DraftVO;
import com.csust.eyediagnosis.controlcenter.model.vo.PublishRequestVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterDraftService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterPublishService;
import com.csust.eyediagnosis.model.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例与故障注入的真实验证（WP-01D 阶段 2）。
 *
 * <p>与 WP-01C 的并发用例的区别：那边是「一个 JVM、两个线程」；这里拉起
 * <b>若干个独立 Java 进程</b>（各自的 Spring 上下文、各自的连接池）连同一个
 * MySQL 8，用同一个生产代码路径竞争同一批数据。这样能覆盖单 JVM 覆盖不到的：
 * <ul>
 *   <li>跨进程的锁等待与串行化（行锁属于数据库，不属于 JVM）；</li>
 *   <li>进程被强杀时未提交事务的回滚；</li>
 *   <li>锁等待超时后两侧看到的状态。</li>
 * </ul>
 *
 * <p>并发窗口用「起跑门」控制：子进程启动完上下文后停在门前，父进程把两个
 * 子进程都推到门前后才同时放行。没有这道门，两个子进程会先后脚完成，
 * 测到的只是串行结果而不是竞争结果。
 *
 * <p>环境约束：只在 CI 的一次性 MySQL 8 上跑（{@code scripts/verify-fast.sh --db}
 * 或 {@code scripts/verify.sh}）。库名不含 {@code ci}/{@code test} 时整类跳过——
 * 宁可显式跳过，也不往开发库或任何非测试库里写配置中心数据。
 */
@SpringBootTest(properties = "eye-diagnosis.control-center.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterMultiProcessTest {

    private static final long ACTOR_ID = 9_300_001L;
    private static final long APPROVER_ID = 9_300_002L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.concurrent.atomic.AtomicInteger CHILD_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ControlCenterDraftService draftService;

    @Autowired
    private ControlCenterPublishService publishService;

    @TempDir
    Path workDir;

    @BeforeEach
    void reset() {
        // 只在一次性 CI 库上跑：本类会起子进程写配置中心五张表，绝不能落到开发库或任何
        // 非测试库。库名不含 ci/test 时显式跳过（跳过会出现在报告里，不能当成通过）。
        org.junit.jupiter.api.Assumptions.assumeTrue(isTestSchema(),
                () -> "ControlCenterMultiProcessTest 只在 CI 测试库上运行，当前数据源：" + jdbcUrl());
        jdbc.update("DELETE FROM t_config_publish_request");
        jdbc.update("DELETE FROM t_config_audit_event");
        jdbc.update("DELETE FROM t_config_request");
        jdbc.update("DELETE FROM t_config_release");
        jdbc.update("DELETE FROM t_config_draft");
        jdbc.update("DELETE FROM t_config_active_pointer");
        jdbc.update("INSERT INTO t_config_active_pointer (id, releaseId) VALUES (1, NULL)");
    }

    // ------------------------------------------------------------------ 跨进程竞争

    @Test
    void twoProcessesApprovingTheSameTicketProduceExactlyOneRelease() throws Exception {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "mp-1");

        List<Outcome> outcomes = race(
                List.of("approve", String.valueOf(approval.id()), "mp-1a", String.valueOf(APPROVER_ID)),
                List.of("approve", String.valueOf(approval.id()), "mp-1b", String.valueOf(APPROVER_ID)));

        assertEquals(1, successes(outcomes), "必须恰好一个进程成功: " + outcomes);
        Outcome loser = loser(outcomes);
        assertEquals("BUSINESS_ERROR", loser.status());
        assertEquals(40909, loser.code(), "输的一方必须是「该申请已被处理」: " + loser);

        assertEquals(1, releaseCount(), "只能产生一条 release");
        assertEquals("APPROVED", approvalStatus(approval.id()));
        assertEquals(1, auditCount("PUBLISH_APPROVE", "SUCCESS"), "成功审计不能丢也不能多");
    }

    @Test
    void twoProcessesWithTheSameRequestIdReplayTheSameRelease() throws Exception {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "mp-2");

        List<Outcome> outcomes = race(
                List.of("approve", String.valueOf(approval.id()), "mp-2-same", String.valueOf(APPROVER_ID)),
                List.of("approve", String.valueOf(approval.id()), "mp-2-same", String.valueOf(APPROVER_ID)));

        assertEquals(2, successes(outcomes), "同号重放必须两次都成功: " + outcomes);
        assertEquals(outcomes.get(0).releaseId(), outcomes.get(1).releaseId(),
                "同号重放必须返回同一条 release");
        assertEquals(1, releaseCount(), "重放不得产生第二条 release");
        assertEquals(1, auditCount("PUBLISH_APPROVE", "SUCCESS"), "重放不得重复写成功审计");
    }

    @Test
    void twoProcessesReusingOneRequestIdForDifferentContentAreRejected() throws Exception {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "mp-3");

        // 同一个 requestId，两个**不同动作**（批准 / 拒绝）。两者请求体只差 requestId，
        // 幂等摘要靠「方法 + 路径」区分——如果摘要只覆盖 body，这一对会被当成同一次请求，
        // 后到者会拿到前者的结果（一个 ReleaseVO 或一张已拒绝的单据），那才是真缺陷。
        List<Outcome> outcomes = race(
                List.of("approve", String.valueOf(approval.id()), "mp-3-same", String.valueOf(APPROVER_ID)),
                List.of("reject", String.valueOf(approval.id()), "mp-3-same", String.valueOf(APPROVER_ID),
                        "多实例冲突验证"));

        assertEquals(1, successes(outcomes), "同号不同内容只能有一个成功: " + outcomes);
        Outcome loser = loser(outcomes);
        assertEquals("BUSINESS_ERROR", loser.status());
        assertEquals(40908, loser.code(), "必须是幂等摘要不一致: " + loser);

        // 胜者是「批准」还是「拒绝」由锁的顺序决定，两种结局都合法，但必须自洽。
        Outcome winner = outcomes.get(0) == loser ? outcomes.get(1) : outcomes.get(0);
        if ("REJECTED".equals(winner.approvalStatus())) {
            assertEquals("REJECTED", approvalStatus(approval.id()), "胜者拒绝后单据必须已拒绝");
            assertEquals(0, releaseCount(), "被拒绝的申请不得产生 release");
            assertEquals(null, activeReleaseId(), "被拒绝的申请不得切指针");
        } else {
            assertEquals("APPROVED", approvalStatus(approval.id()), "胜者批准后单据必须已批准");
            assertEquals(1, releaseCount(), "批准只能产生一条 release");
            assertNotNull(activeReleaseId(), "批准必须切指针");
        }
    }

    @Test
    void twoApprovalsBoundToTheSameActiveVersionCannotBothPublishAcrossProcesses() throws Exception {
        ReleaseVO base = publish("Iris A", "mp-4", "mp-4a");
        long revision = publishableDraft("Iris B");
        PublishRequestVO left = requestPublish(revision, base.id(), "mp-4b");
        PublishRequestVO right = requestPublish(revision, base.id(), "mp-4c");

        List<Outcome> outcomes = race(
                List.of("approve", String.valueOf(left.id()), "mp-4d", String.valueOf(APPROVER_ID)),
                List.of("approve", String.valueOf(right.id()), "mp-4e", String.valueOf(APPROVER_ID)));

        assertEquals(1, successes(outcomes), "只能有一个进程发布成功: " + outcomes);
        Outcome loser = loser(outcomes);
        assertEquals("BUSINESS_ERROR", loser.status());
        assertEquals(40900, loser.code(), "输的一方必须是版本冲突: " + loser);

        assertEquals(2, releaseCount(), "只能多出一条 release");
        long approved = java.util.stream.Stream.of(left.id(), right.id())
                .filter(id -> "APPROVED".equals(approvalStatus(id)))
                .count();
        assertEquals(1, approved, "输掉的审批单必须仍是 PENDING");
    }

    @Test
    void publishAndRollbackInTwoProcessesCannotBothWin() throws Exception {
        ReleaseVO first = publish("Iris A", "mp-5", "mp-5a");
        long revision = publishableDraft("Iris B");
        PublishRequestVO approval = requestPublish(revision, first.id(), "mp-5b");

        List<Outcome> outcomes = race(
                List.of("approve", String.valueOf(approval.id()), "mp-5c", String.valueOf(APPROVER_ID)),
                List.of("rollback", String.valueOf(first.id()), "mp-5d", String.valueOf(APPROVER_ID),
                        String.valueOf(first.id())));

        assertEquals(1, successes(outcomes), "发布与回滚只能有一个成功: " + outcomes);
        assertEquals(2, releaseCount(), "只能多出一条 release");
        assertNotNull(activeReleaseId(), "指针必须仍指向一条合法的 release");
    }

    // ------------------------------------------------------------------ 故障注入

    @Test
    void aKilledInstanceLeavesNoPartialPublishAndTheRequestIdStaysUsable() throws Exception {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "mp-6");
        long releasesBefore = releaseCount();

        // 子进程在一个**未提交**的外层事务里跑生产的 approve，然后停在门后。
        // 父进程在它提交之前强杀它——等价于实例在发布中途被 kill -9。
        Child child = spawn(List.of("tx-open-approve", String.valueOf(approval.id()),
                "mp-6-request", String.valueOf(APPROVER_ID)),
                List.of("-Dharness.holdMillis=600000"));
        child.awaitResultLine();
        assertTrue(child.lastResult().contains("TX_OPEN"),
                "子进程应当已经在事务内完成发布动作: " + child.lastResult());

        child.process().destroyForcibly();
        assertTrue(child.process().waitFor(30, TimeUnit.SECONDS), "子进程应当被强杀");
        awaitRowLockFree("t_config_publish_request", approval.id());

        assertEquals(releasesBefore, releaseCount(), "被强杀的实例不得留下 release");
        assertEquals(null, activeReleaseId(), "被强杀的实例不得切指针");
        assertEquals("PENDING", approvalStatus(approval.id()), "审批单必须仍是 PENDING");
        assertEquals(0, requestRowCount("mp-6-request"), "幂等占位必须随事务回滚，requestId 未被消耗");

        // 同一个 requestId 仍然可用：失败与崩溃都不消耗幂等号。
        ReleaseVO release = approve(approval.id(), "mp-6-request");
        assertEquals(1, release.releaseVersion());
        assertEquals(release.id(), activeReleaseId());
    }

    @Test
    void aLockWaitTimeoutLeavesNoPartialPublish() throws Exception {
        long revision = publishableDraft("Iris A");
        PublishRequestVO approval = requestPublish(revision, null, "mp-7");

        // 父进程持住审批行的行锁，子进程以 2 秒的会话锁等待上限来批准。
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM t_config_publish_request WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, approval.id());
                try (ResultSet ignored = lock.executeQuery()) {
                    assertTrue(ignored.next(), "应当锁住审批行");
                }

                Child child = spawn(List.of("approve", String.valueOf(approval.id()),
                                "mp-7-request", String.valueOf(APPROVER_ID),
                                "--spring.datasource.hikari.connection-init-sql="
                                        + "SET SESSION innodb_lock_wait_timeout=2"),
                        List.of());
                Outcome outcome = child.awaitOutcome();
                assertEquals("LOCK_TIMEOUT", outcome.status(),
                        "子进程应当是锁等待超时而不是别的错误: " + outcome);
                holder.rollback();
            }
        }

        assertEquals(0, releaseCount(), "锁等待超时不得留下 release");
        assertEquals(null, activeReleaseId(), "锁等待超时不得切指针");
        assertEquals("PENDING", approvalStatus(approval.id()));
        assertEquals(0, requestRowCount("mp-7-request"), "锁等待超时的占位必须回滚");

        // 锁放开后，同一个 requestId 仍然可用。
        ReleaseVO release = approve(approval.id(), "mp-7-request");
        assertEquals(1, release.releaseVersion());
        assertEquals(release.id(), activeReleaseId());
    }

    // ------------------------------------------------------------------ 辅助：起跑门与进程

    /**
     * 起跑门：两个子进程都就绪后才同时放行。
     *
     * <p>没有这道门时，两个子进程会先后脚跑完，测到的是串行结果——那种「恰好一个
     * 成功」不能证明任何并发性质。
     */
    private List<Outcome> race(List<String> left, List<String> right) throws Exception {
        Path gate = workDir.resolve("gate-" + System.nanoTime());
        Child first = spawn(left, List.of("-Dharness.goFile=" + gate.toAbsolutePath()));
        Child second = spawn(right, List.of("-Dharness.goFile=" + gate.toAbsolutePath()));
        first.awaitReady();
        second.awaitReady();
        Files.writeString(gate, "go");
        return List.of(first.awaitOutcome(), second.awaitOutcome());
    }

    private Child spawn(List<String> harnessArgs, List<String> jvmArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        // 子进程必须显式声明 UTF-8：父进程的 argLine 不会传给它，本机默认字符集是 GBK，
        // 否则「含中文的业务错误信息」写出的字节既读不回来、日志也会变成乱码。
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        // 两个子进程同时存在：把堆压到够用的最小值，避免在内存紧张的机器上
        // 被操作系统直接杀掉（那会表现为「日志中途截断、退出码丢失」）。
        command.add("-Xmx256m");
        command.add("-XX:MaxMetaspaceSize=192m");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-XX:TieredStopAtLevel=1");
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(resolveClasspath());
        command.add(ControlCenterProcessHarness.class.getName());
        command.addAll(harnessArgs);
        command.addAll(datasourceArgs());

        // 输出落到 target/ 下（而非 @TempDir）：失败时人可以直接打开子进程日志。
        Path logDir = Path.of("target", "multiprocess-logs", workDir.getFileName().toString());
        Files.createDirectories(logDir);
        String name = "child-" + CHILD_SEQUENCE.incrementAndGet() + "-" + harnessArgs.get(0);
        Path stdout = logDir.resolve(name + ".out");
        Path stderr = logDir.resolve(name + ".err");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        builder.directory(new File(System.getProperty("user.dir")));
        return new Child(builder.start(), stdout, stderr, command);
    }

    private static String javaExecutable() {
        Path home = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        return home.toString();
    }

    /**
     * 把父进程**实际在用**的数据源显式传给子进程。
     *
     * <p>不依赖环境变量继承：父进程可能由 profile 文件而不是 {@code SPRING_DATASOURCE_URL}
     * 决定连哪个库，靠继承会悄悄连到另一个 schema——那会让「同一个数据库」的前提失效。
     */
    private List<String> datasourceArgs() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        args.add("--spring.datasource.url=" + hikari.getJdbcUrl());
        args.add("--spring.datasource.username=" + hikari.getUsername());
        if (hikari.getPassword() != null) {
            args.add("--spring.datasource.password=" + hikari.getPassword());
        }
        args.add("--spring.profiles.active=ci");
        return args;
    }

    /**
     * 子进程用的 classpath。
     *
     * <p>Surefire 默认用「manifest-only jar」启动 fork 的 JVM，这时
     * {@code java.class.path} 只是一个引导 jar，不含真实依赖——必须用
     * {@code surefire.real.class.path}，或者把引导 jar 的 manifest 展开。
     */
    private static String resolveClasspath() {
        String real = System.getProperty("surefire.real.class.path");
        if (usable(real)) {
            return real;
        }
        String plain = System.getProperty("java.class.path");
        if (usable(plain)) {
            return plain;
        }
        String expanded = expandManifest(plain);
        if (usable(expanded)) {
            return expanded;
        }
        throw new IllegalStateException("无法解析子进程 classpath（surefire.real.class.path="
                + real + "，java.class.path=" + plain + "）");
    }

    private static boolean usable(String classpath) {
        return classpath != null && !classpath.isBlank() && classpath.contains("test-classes");
    }

    private static String expandManifest(String classpath) {
        if (classpath == null) {
            return null;
        }
        StringBuilder expanded = new StringBuilder();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            expanded.append(entry).append(File.pathSeparator);
            if (!entry.endsWith(".jar")) {
                continue;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(entry)) {
                java.util.jar.Manifest manifest = jar.getManifest();
                if (manifest == null) {
                    continue;
                }
                String value = manifest.getMainAttributes().getValue("Class-Path");
                if (value == null) {
                    continue;
                }
                File base = new File(entry).getParentFile();
                for (String part : value.split("\\s+")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    File resolved = new File(part);
                    if (!resolved.isAbsolute() && base != null) {
                        resolved = new File(base, part);
                    }
                    expanded.append(resolved.getAbsolutePath()).append(File.pathSeparator);
                }
            } catch (IOException ignored) {
                // 读不到就跳过这一项；最后的可用性判断会决定是否报错。
            }
        }
        return expanded.toString();
    }

    /** 等到审批行的行锁被释放（被强杀的实例留下的锁要等数据库发现连接已断）。 */
    private void awaitRowLockFree(String table, long id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (PreparedStatement probe = connection.prepareStatement(
                        "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                    probe.setLong(1, id);
                    try (ResultSet ignored = probe.executeQuery()) {
                        return;
                    }
                } catch (SQLException locked) {
                    Thread.sleep(200L);
                }
            }
        }
        throw new IllegalStateException("行锁在 30 秒内没有释放: " + table + "#" + id);
    }

    // ------------------------------------------------------------------ 辅助：数据准备

    private User actor(long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("admin");
        return user;
    }

    private DraftSaveRequest saveRequest(Map<String, Object> values, Long expectedRevision) {
        DraftSaveRequest request = new DraftSaveRequest();
        request.setSchemaVersion(1);
        request.setValues(values);
        request.setExpectedRevision(expectedRevision);
        return request;
    }

    private long publishableDraft(String siteName) {
        if ("NONE".equals(draftService.get().status())) {
            draftService.create(saveRequest(Map.of("brand.siteName", siteName), null), actor(ACTOR_ID), null);
        } else {
            long current = draftService.get().revision();
            draftService.update(1, saveRequest(Map.of("brand.siteName", siteName), current),
                    actor(ACTOR_ID), null);
        }
        long revision = draftService.get().revision();
        assertTrue(draftService.validate(1, revision, null, actor(ACTOR_ID), null).valid());
        return revision;
    }

    private PublishRequestVO requestPublish(long revision, Long expectedActive, String requestId) {
        PublishRequestBody body = new PublishRequestBody();
        body.setRevision(revision);
        body.setExpectedActiveReleaseId(expectedActive);
        body.setRequestId(requestId);
        return publishService.requestPublish(1, body, actor(ACTOR_ID));
    }

    private ReleaseVO approve(long approvalId, String requestId) {
        ApprovalDecisionRequest body = new ApprovalDecisionRequest();
        body.setRequestId(requestId);
        return publishService.approve(approvalId, body, actor(APPROVER_ID));
    }

    private ReleaseVO publish(String siteName, String requestId, String approveRequestId) {
        long revision = publishableDraft(siteName);
        PublishRequestVO approval = requestPublish(revision, activeReleaseId(), requestId);
        return approve(approval.id(), approveRequestId);
    }

    private Long activeReleaseId() {
        return jdbc.queryForObject(
                "SELECT releaseId FROM t_config_active_pointer WHERE id = 1", Long.class);
    }

    private String approvalStatus(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM t_config_publish_request WHERE id = ?", String.class, id);
    }

    private long releaseCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_release", Long.class);
        return count == null ? 0L : count;
    }

    private long requestRowCount(String requestId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_request WHERE requestId = ?", Long.class, requestId);
        return count == null ? 0L : count;
    }

    private long auditCount(String action, String result) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_audit_event WHERE action = ? AND result = ?",
                Long.class, action, result);
        return count == null ? 0L : count;
    }

    private String jdbcUrl() {
        return dataSource instanceof HikariDataSource hikari ? hikari.getJdbcUrl() : "unknown";
    }

    private boolean isTestSchema() {
        String url = jdbcUrl().toLowerCase(java.util.Locale.ROOT);
        return url.contains("ci") || url.contains("test");
    }

    private static int successes(List<Outcome> outcomes) {
        return (int) outcomes.stream().filter(o -> "SUCCESS".equals(o.status())).count();
    }

    private static Outcome loser(List<Outcome> outcomes) {
        return outcomes.stream().filter(o -> !"SUCCESS".equals(o.status())).findFirst()
                .orElseThrow(() -> new AssertionError("没有失败方: " + outcomes));
    }

    // ------------------------------------------------------------------ 结果载体

    /** 子进程的一次执行结果。 */
    private record Outcome(String status, int code, Long releaseId, String approvalStatus, String raw) {
    }

    /** 一个子进程及其输出文件。 */
    private static final class Child {

        private final Process process;
        private final Path stdout;
        private final Path stderr;
        private final List<String> command;

        private Child(Process process, Path stdout, Path stderr, List<String> command) {
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
            this.command = command;
        }

        private Process process() {
            return process;
        }

        /** 等到子进程打印「已就绪，等放行」。 */
        private void awaitReady() throws Exception {
            awaitLine("HARNESS_READY", 120_000L);
        }

        /** 等到子进程打印结果行（用于事务未提交的崩溃场景）。 */
        private void awaitResultLine() throws Exception {
            awaitLine(ControlCenterProcessHarness.RESULT_PREFIX, 120_000L);
        }

        private void awaitLine(String marker, long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (readStdout().contains(marker)) {
                    return;
                }
                if (!process.isAlive() && readStdout().contains(marker)) {
                    return;
                }
                Thread.sleep(50L);
            }
            throw new IllegalStateException("等待子进程输出超时: " + marker + "\n" + describe());
        }

        private Outcome awaitOutcome() throws Exception {
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("子进程超时未退出\n" + describe());
            }
            String raw = lastResult();
            if (raw.isBlank()) {
                throw new IllegalStateException("子进程没有输出结果行\nexit=" + process.exitValue()
                        + "\n" + describe());
            }
            JsonNode node = MAPPER.readTree(raw);
            int code = node.has("code") ? node.get("code").asInt() : 0;
            Long releaseId = node.has("releaseId") && !node.get("releaseId").isNull()
                    ? node.get("releaseId").asLong() : null;
            String approvalStatus = node.has("approvalStatus") && !node.get("approvalStatus").isNull()
                    ? node.get("approvalStatus").asText() : null;
            return new Outcome(node.path("status").asText("UNKNOWN"), code, releaseId, approvalStatus, raw);
        }

        private String describe() {
            return "command=" + command
                    + "\nstdout(" + stdout + "):\n" + readStdout()
                    + "\nstderr(" + stderr + "):\n" + readStderr();
        }

        private String lastResult() {
            String content = readStdout();
            String result = "";
            for (String line : content.split("\\R")) {
                if (line.startsWith(ControlCenterProcessHarness.RESULT_PREFIX)) {
                    result = line.substring(ControlCenterProcessHarness.RESULT_PREFIX.length()).trim();
                }
            }
            return result;
        }

        private String readStdout() {
            return read(stdout);
        }

        private String readStderr() {
            return read(stderr);
        }

        /** 宽松解码：子进程编码不确定时宁可替换字符，也不能整段读不回来。 */
        private static String read(Path path) {
            try {
                return Files.exists(path)
                        ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                        : "";
            } catch (IOException e) {
                return "";
            }
        }
    }
}
