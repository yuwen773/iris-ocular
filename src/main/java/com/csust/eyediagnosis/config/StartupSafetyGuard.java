package com.csust.eyediagnosis.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 部署期安全基线：带着 server / prod profile 启动时，拒绝带着开发调试口子运行。
 *
 * <p>问题不在某一条配置写错，而在「共享基线里带着开发默认值」：SQL 语句与参数打标准输出、
 * 接口文档全量暴露、模型 prompt 以 debug 级别落盘、会话 Cookie 不要求 Secure。
 * 这些在本地都合理，一旦被部署继承，患者影像路径、会话内容与用户输入就跟着进日志和文档页。
 *
 * <p>默认值已经改成安全的一侧（见 {@code application.yml}），这里是第二道：显式配置回
 * 不安全值、或部署环境带着旧配置启动时，**启动即失败**，而不是安静地跑起来。
 * 本地开发不激活 server/prod，因此不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSafetyGuard {

    /** 视为「部署环境」的 profile。本地开发用的是 local/dev。 */
    private static final List<String> DEPLOYMENT_PROFILES = List.of("server", "prod");

    private final Environment environment;

    @PostConstruct
    void verify() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.stream().noneMatch(DEPLOYMENT_PROFILES::contains)) {
            return;
        }
        List<String> violations = new ArrayList<>();
        String logImpl = environment.getProperty("mybatis-plus.configuration.log-impl", "");
        if (logImpl.toLowerCase().contains("stdout")) {
            violations.add("mybatis-plus.configuration.log-impl=" + logImpl
                    + "（SQL 与参数会打进标准输出；部署环境应使用 NoLoggingImpl）");
        }
        if (environment.getProperty("knife4j.enable", Boolean.class, false)) {
            violations.add("knife4j.enable=true（接口文档页会完整暴露；部署环境应设为 false）");
        }
        String advisor = environment.getProperty(
                "logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor", "");
        if (advisor.equalsIgnoreCase("debug") || advisor.equalsIgnoreCase("trace")) {
            violations.add("SimpleLoggerAdvisor 日志级别=" + advisor + "（模型 prompt 与消息体会落盘）");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            violations.add("server.servlet.session.cookie.secure=false"
                    + "（会话 Cookie 未强制 HTTPS；部署环境应设 SESSION_COOKIE_SECURE=true）");
        }
        if (violations.isEmpty()) {
            log.info("部署安全基线检查通过（profiles={}）", active);
            return;
        }
        throw new IllegalStateException(
                "部署安全基线未满足，拒绝启动（profiles=" + active + "）：\n  - "
                        + String.join("\n  - ", violations)
                        + "\n参考 src/main/resources/application-server.example.yml 的「生产安全基线」一节。");
    }
}
