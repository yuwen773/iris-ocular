package com.csust.eyediagnosis.postgres;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库角色最小权限（真实 PostgreSQL）。
 *
 * <p>应用账号 {@code iris_app} 只有 USAGE + DML：不能建表、改表、删表、TRUNCATE、
 * 装扩展、建 schema，也不能读写迁移历史。迁移账号 {@code iris_migrator} 拥有 DDL。
 * 这条边界是「应用被攻破也改不了 schema、改不了迁移账本」的最后一道闸。
 *
 * <p>门槛：{@code PG_IT_URL} / {@code PG_IT_APP_PASSWORD} / {@code PG_IT_MIGRATOR_PASSWORD}。
 */
class PgPrivilegeIntegrationTest {

    private static final String[] APP_DENIED = {
            "CREATE TABLE diagnosis.priv_probe (id int)",
            "ALTER TABLE diagnosis.t_news_source ADD COLUMN priv_probe int",
            "DROP TABLE diagnosis.t_news_source",
            "TRUNCATE diagnosis.t_news_source",
            "CREATE EXTENSION IF NOT EXISTS hstore",
            "CREATE SCHEMA priv_probe",
            "SELECT count(*) FROM diagnosis.flyway_schema_history",
            "DELETE FROM diagnosis.flyway_schema_history",
            "UPDATE diagnosis.flyway_schema_history SET success = false",
            "INSERT INTO diagnosis.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)"
                    + " VALUES (999, '999', 'forged', 'SQL', 'forged.sql', 1, 'iris_app', now(), 0, true)",
            "ALTER TABLE rag.iris_knowledge_vectors DROP COLUMN embedding",
            "DROP SCHEMA rag CASCADE"
    };

    @Test
    void appRoleIsDeniedSchemaAndHistoryChanges() throws Exception {
        Assumptions.assumeTrue(appUrl() != null, "PG_IT_URL not set");
        for (String sql : APP_DENIED) {
            SQLException e = assertThrows(SQLException.class, () -> execute(appConnection(), sql), sql);
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            boolean denied = message.contains("permission denied") || message.contains("must be owner");
            assertTrue(denied, "expected a privilege error for [" + sql + "] but got: " + e.getMessage());
        }
        assertSchemaIntact(appConnection());
    }

    @Test
    void appRoleCanStillDoBusinessDml() throws Exception {
        Assumptions.assumeTrue(appUrl() != null, "PG_IT_URL not set");
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO diagnosis.t_news_source (source, enabled) VALUES ('priv_probe', 1)");
                s.executeUpdate("UPDATE diagnosis.t_news_source SET enabled = 0 WHERE source = 'priv_probe'");
                assertEquals(0, count(s, "SELECT enabled FROM diagnosis.t_news_source WHERE source = 'priv_probe'"));
                s.executeUpdate("DELETE FROM diagnosis.t_news_source WHERE source = 'priv_probe'");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void appRoleIsNotPrivileged() throws Exception {
        Assumptions.assumeTrue(appUrl() != null, "PG_IT_URL not set");
        try (Connection c = appConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT rolsuper, rolbypassrls, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user");
            assertTrue(rs.next());
            assertFalse(rs.getBoolean("rolsuper"), "app role must not be superuser");
            assertFalse(rs.getBoolean("rolbypassrls"), "app role must not bypass row-level security");
            assertFalse(rs.getBoolean("rolcreatedb"));
            assertFalse(rs.getBoolean("rolcreaterole"));
        }
    }

    @Test
    void migratorRoleCanRunDdl() throws Exception {
        Assumptions.assumeTrue(appUrl() != null, "PG_IT_URL not set");
        try (Connection c = migratorConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS rag.priv_probe_migrator (id int)");
            s.executeUpdate("DROP TABLE rag.priv_probe_migrator");
            var rs = s.executeQuery("SELECT count(*) FROM diagnosis.flyway_schema_history");
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 3, "migrator must see the migration history");
        }
    }

    private static int count(Statement s, String sql) throws SQLException {
        var rs = s.executeQuery(sql);
        rs.next();
        return rs.getInt(1);
    }

    private static void assertSchemaIntact(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            // pg_class 不受权限过滤（information_schema 只列「当前用户有权限」的表，
            // 而迁移历史恰好被收回权限，用它计数会少一）。
            var rs = s.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'diagnosis' AND c.relkind IN ('r','p')");
            rs.next();
            assertEquals(60, rs.getInt(1), "diagnosis schema must be intact after denied probes");
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (c; Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), envOrDefault("PG_IT_APP_USER", "iris_app"), env("PG_IT_APP_PASSWORD"));
    }

    private static Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), envOrDefault("PG_IT_MIGRATOR_USER", "iris_migrator"), env("PG_IT_MIGRATOR_PASSWORD"));
    }

    private static String appUrl() {
        return env("PG_IT_URL");
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String envOrDefault(String name, String fallback) {
        String v = env(name);
        return v == null ? fallback : v;
    }
}
