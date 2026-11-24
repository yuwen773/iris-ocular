package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.EyeDiagnosisApplication;
import com.csust.eyediagnosis.controlcenter.model.dto.ApprovalDecisionRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.PublishRequestBody;
import com.csust.eyediagnosis.controlcenter.model.dto.RollbackRequestBody;
import com.csust.eyediagnosis.controlcenter.model.vo.PublishRequestVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterPublishService;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.model.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多实例验证用的子进程入口（WP-01D 阶段 2）。
 *
 * <p><b>为什么需要它</b>：WP-01C 的并发证据是「一个 JVM、两个线程、两个独立事务」。
 * 那能证明行锁语义，但证明不了「两个独立进程」的下列性质：
 * <ul>
 *   <li>各自的连接池、各自的会话与事务管理器；</li>
 *   <li>进程被强杀（SIGKILL / destroyForcibly）时未提交事务的回滚；</li>
 *   <li>锁等待超时后另一侧看到的状态。</li>
 * </ul>
 * 本类是一个**完整的应用实例**（真实 Spring 上下文、真实 MySQL 连接池），
 * 父进程通过 {@code ProcessBuilder} 拉起若干个，执行同一个生产代码路径
 * （{@link ControlCenterPublishService} → 事务体），并从 stdout 读回一行机器可读的结果。
 *
 * <p><b>它证明什么、不证明什么</b>：证明的是<b>数据库与事务层</b>的并发与崩溃语义
 * （行锁、唯一键、原子提交、崩溃回滚）。它<b>不</b>经过 HTTP 层，因此不覆盖
 * 鉴权、CSRF、Origin 校验与错误码封装的映射——那些由
 * {@code ControlCenterPublishAcceptanceTest} 与本轮的新增单测覆盖。
 *
 * <p>协议：stdout 打印一行 {@code HARNESS_RESULT {json}}；退出码
 * 0=成功、3=业务错误、4=锁等待超时、5=死锁、1=其他异常。
 */
public final class ControlCenterProcessHarness {

    /** 结果行前缀；父进程按此前缀截取最后一行。 */
    public static final String RESULT_PREFIX = "HARNESS_RESULT ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ControlCenterProcessHarness() {
    }

    public static void main(String[] args) {
        // 位置参数用于命令；以 -- 开头的是 Spring 属性（数据源、锁等待上限等），
        // 由 SpringApplication 解析，命令分发不碰它们。
        String[] positional = java.util.Arrays.stream(args)
                .filter(argument -> !argument.startsWith("--"))
                .toArray(String[]::new);
        String command = positional.length == 0 ? "" : positional[0];
        ConfigurableApplicationContext context = null;
        int exitCode;
        try {
            SpringApplication application = new SpringApplication(EyeDiagnosisApplication.class);
            // 必须是 SERVLET：本应用注册了 WebMvcConfigurer，NONE 模式下
            // resourceHandlerMapping 会因 "No ServletContext set" 直接启动失败。
            // 端口用 0，两个子进程不会抢端口；堆由父进程用 -Xmx 压到够用的最小值。
            application.setWebApplicationType(WebApplicationType.SERVLET);
            List<String> springArgs = new ArrayList<>(List.of(
                    "--server.port=0",
                    "--spring.flyway.enabled=false",
                    "--eye-diagnosis.control-center.enabled=true",
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN",
                    "--logging.level.com.csust.eyediagnosis.mapper=INFO"));
            for (String argument : args) {
                if (argument.startsWith("--")) {
                    springArgs.add(argument);
                }
            }
            context = application.run(springArgs.toArray(String[]::new));
            awaitStartGate();
            exitCode = dispatch(context, command, positional);
        } catch (Throwable failure) {
            emit(status("ERROR", failure));
            exitCode = 1;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败不影响已经落定的结果行。
                }
            }
        }
        System.exit(exitCode);
    }

    // ------------------------------------------------------------------ 命令

    /**
     * 起跑门：等父进程放行再执行命令。
     *
     * <p>父进程用 {@code -Dharness.goFile=<路径>} 指定门。上下文启动完（连接池就绪）
     * 后打印 {@code HARNESS_READY}，然后轮询门文件是否出现。两个子进程都就绪后父进程
     * 才创建门文件——这样两边进入事务的时刻相差只有毫秒级，测到的才是竞争而不是串行。
     */
    private static void awaitStartGate() {
        String gate = System.getProperty("harness.goFile");
        if (gate == null || gate.isBlank()) {
            return;
        }
        System.out.println("HARNESS_READY");
        System.out.flush();
        Path path = Path.of(gate);
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("起跑门超时未放行: " + gate);
    }

    private static int dispatch(ConfigurableApplicationContext context, String command, String[] args) {
        ControlCenterPublishService publish = context.getBean(ControlCenterPublishService.class);
        try {
            switch (command) {
                case "approve" -> {
                    ReleaseVO release = publish.approve(Long.parseLong(args[1]),
                            decision(args[2]), actor(Long.parseLong(args[3])));
                    Map<String, Object> payload = status("SUCCESS", null);
                    payload.put("releaseId", release.id());
                    payload.put("releaseVersion", release.releaseVersion());
                    payload.put("digest", release.digest());
                    emit(payload);
                    return 0;
                }
                case "reject" -> {
                    ApprovalDecisionRequest rejection = decision(args[2]);
                    rejection.setReason(args.length > 4 ? args[4] : null);
                    PublishRequestVO request = publish.reject(Long.parseLong(args[1]),
                            rejection, actor(Long.parseLong(args[3])));
                    Map<String, Object> payload = status("SUCCESS", null);
                    // 不能写成 payload.put("status", ...)：那会把 harness 自己的结果状态
                    // 覆盖成单据终态（REJECTED），父进程就再也分不出「执行成功」与「失败」。
                    payload.put("approvalStatus", request.status());
                    emit(payload);
                    return 0;
                }
                case "rollback" -> {
                    ReleaseVO release = publish.rollback(Long.parseLong(args[1]),
                            rollbackBody(args[2], nullableLong(args[4])),
                            actor(Long.parseLong(args[3])));
                    Map<String, Object> payload = status("SUCCESS", null);
                    payload.put("releaseId", release.id());
                    payload.put("releaseVersion", release.releaseVersion());
                    payload.put("rollbackFromReleaseId", release.rollbackFromReleaseId());
                    emit(payload);
                    return 0;
                }
                case "request-publish" -> {
                    PublishRequestVO request = publish.requestPublish(Long.parseLong(args[1]),
                            publishBody(args[2], Long.parseLong(args[4]), nullableLong(args[5])),
                            actor(Long.parseLong(args[3])));
                    Map<String, Object> payload = status("SUCCESS", null);
                    payload.put("approvalId", request.id());
                    payload.put("status", request.status());
                    emit(payload);
                    return 0;
                }
                case "tx-open-approve" -> {
                    return holdTransactionOpen(context, publish, args);
                }
                default -> {
                    emit(status("ERROR", new IllegalArgumentException("未知命令：" + command)));
                    return 1;
                }
            }
        } catch (BusinessException business) {
            Map<String, Object> payload = status("BUSINESS_ERROR", business);
            payload.put("code", business.getCode());
            payload.put("message", String.valueOf(business.getMessage()));
            emit(payload);
            return 3;
        } catch (DeadlockLoserDataAccessException deadlock) {
            emit(status("DEADLOCK", deadlock));
            return 5;
        } catch (CannotAcquireLockException timeout) {
            emit(status("LOCK_TIMEOUT", timeout));
            return 4;
        }
    }

    /**
     * 「事务开着不提交」模式：在一个外层事务里调用生产的 approve，随后一直占着连接。
     *
     * <p>父进程在这个窗口里把本进程强杀——等价于实例在发布中途崩溃。
     * 由于 approve 的全部写都在这个未提交事务里，MySQL 必须在连接断开时回滚它们。
     * 这是「进程异常中断后数据库一致性」的真实证据，而不是模拟出来的。
     */
    private static int holdTransactionOpen(ConfigurableApplicationContext context,
                                          ControlCenterPublishService publish,
                                          String[] args) {
        PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
        TransactionTemplate template = new TransactionTemplate(manager);
        long holdMillis = Long.parseLong(System.getProperty("harness.holdMillis", "600000"));
        template.executeWithoutResult(status -> {
            ReleaseVO release = publish.approve(Long.parseLong(args[1]),
                    decision(args[2]), actor(Long.parseLong(args[3])));
            Map<String, Object> payload = status("TX_OPEN", null);
            payload.put("releaseId", release.id());
            payload.put("releaseVersion", release.releaseVersion());
            emit(payload);
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        return 0;
    }

    // ------------------------------------------------------------------ 参数

    private static ApprovalDecisionRequest decision(String requestId) {
        ApprovalDecisionRequest body = new ApprovalDecisionRequest();
        body.setRequestId(requestId);
        return body;
    }

    private static RollbackRequestBody rollbackBody(String requestId, Long expectedActive) {
        RollbackRequestBody body = new RollbackRequestBody();
        body.setRequestId(requestId);
        body.setExpectedActiveReleaseId(expectedActive);
        body.setReason("多实例回滚验证");
        return body;
    }

    private static PublishRequestBody publishBody(String requestId, long revision, Long expectedActive) {
        PublishRequestBody body = new PublishRequestBody();
        body.setRequestId(requestId);
        body.setRevision(revision);
        body.setExpectedActiveReleaseId(expectedActive);
        return body;
    }

    private static User actor(long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("admin");
        return user;
    }

    private static Long nullableLong(String raw) {
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw) || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        return Long.parseLong(raw);
    }

    // ------------------------------------------------------------------ 输出

    private static Map<String, Object> status(String status, Throwable failure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (failure != null) {
            payload.put("exception", failure.getClass().getName());
            payload.put("detail", String.valueOf(failure.getMessage()));
        }
        return payload;
    }

    private static void emit(Map<String, Object> payload) {
        try {
            System.out.println(RESULT_PREFIX + MAPPER.writeValueAsString(payload));
            System.out.flush();
        } catch (Exception failure) {
            System.out.println(RESULT_PREFIX + "{\"status\":\"ERROR\",\"exception\":\""
                    + failure.getClass().getName() + "\"}");
            System.out.flush();
        }
    }

    /** 供父进程复用：把结果行拆成「状态 + 字段」。 */
    public static List<String> resultLines(String stdout) {
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            if (line.startsWith(RESULT_PREFIX)) {
                lines.add(line.substring(RESULT_PREFIX.length()).trim());
            }
        }
        return lines;
    }
}
