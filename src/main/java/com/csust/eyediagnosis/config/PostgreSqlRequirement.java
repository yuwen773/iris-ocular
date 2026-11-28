package com.csust.eyediagnosis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 目标兼容性门禁（原 MySQL 8 门禁的等价替代）。
 *
 * <p>Iris 的持久层与 RAG 已迁到 PostgreSQL 18 + pgvector：
 * <ul>
 *   <li>数据分析接口（{@code /api/stats/admin/analytics}）依赖 CTE + 窗口函数；</li>
 *   <li>RAG 依赖 {@code vector} 扩展与 {@code rag.iris_knowledge_vectors} 表；</li>
 *   <li>schema 基线使用 PostgreSQL 18 的 virtual generated column。</li>
 * </ul>
 *
 * <p>连接错库（例如仍指向 MySQL，或连到没装 pgvector 的 PostgreSQL）时，
 * 接口会在运行时才返回 50000，很难与业务缺陷区分。因此把要求提前到启动阶段：
 * 不是 PostgreSQL、版本低于 18、或 {@code vector} 扩展缺失，都直接拒绝启动，
 * 并在错误里写清处置方式。确实要排障时显式设置
 * {@code eye-diagnosis.database.require-postgresql=false} 承担后果。
 *
 * <p>为什么是 {@link ApplicationRunner}：门禁必须拿到已建好的 {@link DataSource}，
 * 而数据源与 Flyway 的创建顺序由自动配置决定。runner 抛异常会让 Spring 关闭上下文
 * 并以非零码退出，端口随即释放，不会进入就绪状态被流量打到。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PostgreSqlRequirement implements ApplicationRunner {

    /**
     * 最低支持主版本：这是运维口径的下限（18 是本轮验收与 pgvector 镜像的基线），
     * 不是 schema 语法要求——基线只用 {@code GENERATED … STORED}（PostgreSQL 12+）。
     */
    static final int MIN_MAJOR = 18;

    private static final Pattern VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?");

    private final DataSource dataSource;

    @Value("${eye-diagnosis.database.require-postgresql:true}")
    private boolean required = true;

    @Value("${eye-diagnosis.database.min-major-version:18}")
    private int minMajor = MIN_MAJOR;

    public PostgreSqlRequirement(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!required) {
            log.warn("已关闭 PostgreSQL 目标兼容性门禁：连接错库或缺少 pgvector 时只会在运行时暴露");
            return;
        }
        check();
    }

    /**
     * 检查当前连接的实际数据库产品、版本与 vector 扩展。
     *
     * @throws IllegalStateException 不满足目标环境（启动失败，错误信息给出处置方式）
     */
    public void check() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgresql")) {
                throw new IllegalStateException(
                        "Iris 需要 PostgreSQL 18+，当前连接的是 " + product + "。"
                                + "请把 spring.datasource.url 指向 PostgreSQL 实例"
                                + "（jdbc:postgresql://host:5432/iris）；MySQL 线已退役。"
                                + "若确认只是排障，可显式设置 "
                                + "eye-diagnosis.database.require-postgresql=false。");
            }
            String version = meta.getDatabaseProductVersion();
            if (!isSupported(version, minMajor)) {
                throw new IllegalStateException(
                        "Iris 需要 PostgreSQL " + minMajor + "+，当前连接的是 PostgreSQL " + version + "。"
                                + "schema 基线使用 PostgreSQL 18 的 virtual generated column，"
                                + "低版本会在 Flyway 迁移阶段失败；请升级实例，"
                                + "或显式设置 eye-diagnosis.database.require-postgresql=false 承担后果。");
            }
            String jdbcUrl = meta.getURL();
            // jsonb columns (config payload, RAG metadata, analytics snapshots) receive
            // String parameters from MyBatis; without stringtype=unspecified the driver
            // declares them varchar and PostgreSQL rejects the write at runtime.
            if (jdbcUrl != null && !jdbcUrl.toLowerCase(Locale.ROOT).contains("stringtype=unspecified")) {
                throw new IllegalStateException(
                        "JDBC URL 缺少 stringtype=unspecified：jsonb 列（配置中心 payload、RAG 元数据）"
                                + "会拒绝 MyBatis 传入的字符串参数。请在 spring.datasource.url 追加 "
                                + "&stringtype=unspecified 后重启。");
            }
            String vectorVersion = vectorExtensionVersion(connection);
            if (vectorVersion == null) {
                throw new IllegalStateException(
                        "当前 PostgreSQL 未安装 vector 扩展（pgvector），RAG 知识库无法工作。"
                                + "请先用迁移账号执行 CREATE EXTENSION vector（见 "
                                + "scripts/postgresql/bootstrap-roles.sql），再启动应用。");
            }
            log.info("数据库版本 PostgreSQL {}，pgvector {}，满足目标要求", version, vectorVersion);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取数据库版本，拒绝启动: " + e.getMessage(), e);
        }
    }

    static boolean isSupported(String version, int minMajor) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(version.trim());
        if (!matcher.find()) {
            return false;
        }
        return Integer.parseInt(matcher.group(1)) >= minMajor;
    }

    private static String vectorExtensionVersion(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
