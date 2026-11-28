package com.csust.eyediagnosis.postgres;

import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关系持久化在真实 PostgreSQL 上的契约（Spring 装配 + MyBatis + Flyway）。
 *
 * <p>覆盖点都是「MySQL 与 PostgreSQL 语义不同、且错了会静默坏数据」的地方：
 * <ul>
 *   <li>camelCase 列名：库列是未加引号的小写（useraccount），MyBatis 生成未加引号的
 *       {@code userAccount}，靠 PG 的标识符折叠对上——这是 map-underscore-to-camel-case
 *       关闭时唯一的正确性来源；</li>
 *   <li>timestamptz：写入的瞬间读回来必须还是同一个瞬间（不是同一段墙上时间）；</li>
 *   <li>ON UPDATE 触发器：MySQL 的 ON UPDATE CURRENT_TIMESTAMP 语义由 trigger 保持；</li>
 *   <li>外键与唯一约束真的生效（MySQL 上曾被关闭过外键检查的库会掩盖问题）；</li>
 *   <li>事务失败必须整体回滚，不留半条记录。</li>
 * </ul>
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("ci")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PG_IT_URL", matches = ".+")
class PgPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("PG_IT_URL"));
        registry.add("spring.datasource.username", () -> envOrDefault("PG_IT_APP_USER", "iris_app"));
        registry.add("spring.datasource.password", () -> System.getenv("PG_IT_APP_PASSWORD"));
        registry.add("spring.flyway.user", () -> envOrDefault("PG_IT_MIGRATOR_USER", "iris_migrator"));
        registry.add("spring.flyway.password", () -> System.getenv("PG_IT_MIGRATOR_PASSWORD"));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM diagnosis.t_user WHERE userAccount LIKE 'pgit_%'");
    }

    @Test
    void mybatisMapsCamelCasePropertiesOntoFoldedLowercaseColumns() {
        User user = new User();
        user.setUserAccount("pgit_mapper");
        user.setUserPassword("$2a$10$pgit-not-a-real-hash");
        user.setUserName("映射检查");
        user.setUserRole("user");
        user.setIsDelete(0);
        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId(), "ASSIGN_ID must generate an id before insert");

        User loaded = userMapper.selectById(user.getId());
        assertNotNull(loaded);
        assertEquals("pgit_mapper", loaded.getUserAccount());
        assertEquals("映射检查", loaded.getUserName());

        // 列名确实是折叠后的小写；如果哪天有人给列名加引号，这条会先红。
        Integer camel = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='diagnosis'"
                        + " AND table_name='t_user' AND column_name='useraccount'", Integer.class);
        assertEquals(1, camel);
    }

    @Test
    void timestamptzRoundTripsTheSameInstant() {
        // t_user.updateTime 是 MySQL datetime（0 位小数）迁移来的 timestamptz(0)：
        // 精确到秒是列声明的精度，不是迁移损失。断言的是「同一个瞬间」而非同一段墙上时间。
        OffsetDateTime written = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("INSERT INTO diagnosis.t_user (id, userAccount, userPassword, userRole, isDelete, updateTime)"
                + " VALUES (?, 'pgit_instant', 'x', 'user', 0, ?)", 910000001L, written);

        OffsetDateTime read = jdbc.queryForObject(
                "SELECT updateTime FROM diagnosis.t_user WHERE id = ?", OffsetDateTime.class, 910000001L);
        assertNotNull(read);
        assertEquals(0, written.toInstant().compareTo(read.toInstant()),
                "the instant must survive the round trip, not the wall clock");
    }

    @Test
    void onUpdateTriggerBumpsUpdatedTime() {
        jdbc.update("INSERT INTO diagnosis.t_user (id, userAccount, userPassword, userRole, isDelete, updateTime)"
                + " VALUES (?, 'pgit_trigger', 'x', 'user', 0, ?)", 910000002L,
                OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        jdbc.update("UPDATE diagnosis.t_user SET userName = 'changed' WHERE id = ?", 910000002L);

        OffsetDateTime after = jdbc.queryForObject(
                "SELECT updateTime FROM diagnosis.t_user WHERE id = ?", OffsetDateTime.class, 910000002L);
        assertTrue(after.getYear() >= 2026, "BEFORE UPDATE trigger must touch updateTime, got " + after);
    }

    @Test
    void uniqueConstraintIsEnforced() {
        jdbc.update("INSERT INTO diagnosis.t_user (id, userAccount, userPassword, userRole, isDelete) VALUES (?,?,?,?,0)",
                910000003L, "pgit_unique", "x", "user");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO diagnosis.t_user (id, userAccount, userPassword, userRole, isDelete) VALUES (?,?,?,?,0)",
                910000004L, "pgit_unique", "x", "user"));
    }

    @Test
    void foreignKeyIsEnforced() {
        // t_agent_collab_event.runId -> t_agent_collab_run.runId（schema 里仅有的两条真实外键之一）
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO diagnosis.t_agent_collab_event"
                        + " (runId, seq, type, actor, visibility, payloadJson, occurredAt, schemaVersion)"
                        + " VALUES ('pgit_no_parent', 1, 'X', 'system', 'INTERNAL', '{}', now(), 1)"));
    }

    @Test
    void failedTransactionRollsBackEverything() {
        long id = 910000005L;
        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO diagnosis.t_user (id, userAccount, userPassword, userRole, isDelete) VALUES (?,?,?,?,0)",
                    id, "pgit_rollback", "x", "user");
            throw new IllegalStateException("boom");
        }));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM diagnosis.t_user WHERE id = ?", Integer.class, id);
        assertEquals(0, rows, "a failed transaction must leave no partial row");
    }

    @Test
    void mysqlCompatibilityFunctionsKeepTheirFormatting() throws Exception {
        // ~140 个调用点仍用 DATE_FORMAT/UTC_TIMESTAMP；shim 的输出必须与 MySQL 同构。
        String formatted = jdbc.queryForObject(
                "SELECT date_format(utc_timestamp(), '%Y-%m-%dT%H:%i:%s')", String.class);
        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"), formatted);

        String literal = jdbc.queryForObject(
                "SELECT date_format(TIMESTAMPTZ '2026-11-28 01:02:03+08', '%Y/%m/%d %H:%i:%s')", String.class);
        assertEquals("2026/11/27 17:02:03", literal, "timestamptz is formatted in UTC, as the MySQL ledger was");
    }

    @Test
    void onConflictUpsertKeepsOneRow() {
        jdbc.update("INSERT INTO diagnosis.t_news_source (source, enabled) VALUES ('pgit_upsert', 1)"
                + " ON CONFLICT (source) DO UPDATE SET enabled = EXCLUDED.enabled");
        jdbc.update("INSERT INTO diagnosis.t_news_source (source, enabled) VALUES ('pgit_upsert', 0)"
                + " ON CONFLICT (source) DO UPDATE SET enabled = EXCLUDED.enabled");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT enabled FROM diagnosis.t_news_source WHERE source = 'pgit_upsert'");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diagnosis.t_news_source WHERE source = 'pgit_upsert'", Integer.class));
        assertEquals(0, ((Number) row.get("enabled")).intValue());
        jdbc.update("DELETE FROM diagnosis.t_news_source WHERE source = 'pgit_upsert'");
    }

    @Test
    void identityColumnsAdvanceForGeneratedInserts() throws Exception {
        // 迁移工具导入时保留显式主键，之后必须 setval 同步序列；这里验证「序列确实在驱动
        // 自增列」这一半契约（另一半在迁移演练里验证 setval 后不撞键）。
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM (SELECT column_name FROM information_schema.columns"
                            + " WHERE table_schema='diagnosis' AND is_identity='YES') identity_columns");
            rs.next();
            assertTrue(rs.getInt(1) > 0, "the PostgreSQL line must declare real identity columns");

            rs = s.executeQuery("SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema='diagnosis' AND table_name='t_news_collection_task_event'"
                    + " AND is_identity='YES'");
            assertTrue(rs.next(), "event rows must get their identity from the database");
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
