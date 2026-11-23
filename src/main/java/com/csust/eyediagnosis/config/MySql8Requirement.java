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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 最低版本门禁。
 *
 * <p>数据分析接口（{@code /api/stats/admin/analytics}）的时延与分位数统计
 * 使用 CTE 与窗口函数，**只有 MySQL 8.0 及以上支持**。本机若同时装有
 * 5.7（常见于 3306）与 8.0（常见于 3307），连错实例时接口会在运行时返回
 * `50000 系统错误`，很难与业务缺陷区分。
 *
 * <p>因此把版本要求提前到启动阶段：低于 8.0 直接拒绝启动，并在错误里写清
 * 需要改哪一项配置；确实要在旧库上运行非统计功能时，显式设置
 * {@code eye-diagnosis.database.require-mysql8=false} 承担后果。
 *
 * <p>非 MySQL 数据库（测试替身）不参与判定，避免把单元测试挡在门外。
 *
 * <p>为什么是 {@link ApplicationRunner} 而不是更早的 bean 初始化：门禁必须拿到
 * 已建好的 {@link DataSource}，而数据源与 Flyway 的创建顺序由自动配置决定，
 * 用 {@code InitializingBean} 并不能稳定抢在 Flyway 之前。选择 runner 的代价是
 * 检查发生在 Web 端口绑定之后、{@code ApplicationReadyEvent} 之前——runner 抛异常
 * 会让 Spring 关闭上下文并以非零码退出，端口随即释放，不会进入就绪状态被流量打到。
 * 连 MySQL 5.7 的实测见 {@code docs/design/iris22-admin-analytics-b2-field-mapping.md} §9.4。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MySql8Requirement implements ApplicationRunner {

    /** 最低支持版本：Analytics 的 CTE + 窗口函数从 8.0 开始可用。 */
    static final int MIN_MAJOR = 8;
    static final int MIN_MINOR = 0;

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    private final DataSource dataSource;

    @Value("${eye-diagnosis.database.require-mysql8:true}")
    private boolean required = true;

    public MySql8Requirement(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!required) {
            log.warn("已关闭 MySQL 8 版本门禁：数据分析接口在低于 8.0 的数据库上会失败");
            return;
        }
        check();
    }

    /**
     * 检查当前连接的实际数据库版本。
     *
     * @throws IllegalStateException 版本低于 8.0（启动失败，错误信息给出处置方式）
     */
    public void check() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                log.info("数据库为 {}，跳过 MySQL 8 版本门禁", product);
                return;
            }
            String version = meta.getDatabaseProductVersion();
            if (!isSupported(version)) {
                throw new IllegalStateException(
                        "数据分析需要 MySQL 8.0+，当前连接的是 MySQL " + version + "。"
                                + "请把数据源指向 MySQL 8 实例（本机通常为 3307，不是 3306 的 5.7），"
                                + "或升级该实例；不要为兼容 5.7 改写统计 SQL。"
                                + "若确认只使用非统计功能，可显式设置 "
                                + "eye-diagnosis.database.require-mysql8=false。");
            }
            log.info("数据库版本 MySQL {}，满足数据分析最低要求 8.0", version);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取数据库版本，拒绝启动: " + e.getMessage(), e);
        }
    }

    static boolean isSupported(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(version.trim());
        if (!matcher.find()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR);
    }
}
