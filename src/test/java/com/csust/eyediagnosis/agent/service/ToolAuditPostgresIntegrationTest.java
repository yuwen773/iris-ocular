package com.csust.eyediagnosis.agent.service;

import com.csust.eyediagnosis.integration.LegacyGateSupport;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具审计表的真实 PostgreSQL 契约测试：表结构可查询，且**没有任何能装下原始入参的列**。
 *
 * <p>原套件跑在 MySQL 线上（当时代码注释写作「V28」）。迁移后这条业务契约不变，
 * 只是改为对着 {@code db/postgresql} 建出来的真实 schema 验证；列名按 PostgreSQL
 * 未加引号标识符折叠成小写，所以断言前统一 {@code toLowerCase()}（与原套件一致）。
 *
 * <p>隔离库由 {@code BUSINESS_TEST_URL} 指定（库名必须叫 {@code business_test}）。
 */
class ToolAuditPostgresIntegrationTest {

    private static final long OWNER = 7L;
    private static final long OTHER = 99L;

    @Test
    void auditTableExistsIsQueryableAndHasNoRawPayloadColumn() throws Exception {
        String url = LegacyGateSupport.assumeIsolated(System.getenv("BUSINESS_TEST_URL"), "business_test");
        DataSource dataSource = LegacyGateSupport.dataSource(url);
        LegacyGateSupport.ensureSchema(url, dataSource, List.of(), () -> {
            throw new IllegalStateException("BUSINESS_TEST_URL must point at PostgreSQL");
        });

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM t_agent_tool_audit WHERE sessionId='s1'");

            Set<String> columns = new HashSet<>();
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='diagnosis' AND table_name='t_agent_tool_audit'")) {
                while (rs.next()) columns.add(rs.getString(1).toLowerCase());
            }
            for (String required : new String[]{
                    "auditid", "sessionid", "userid", "toolcallid", "toolname",
                    "toolstatus", "errorcategory", "argsdigest", "argskeynames",
                    "startedat", "finishedat"}) {
                assertTrue(columns.contains(required), "audit table missing column: " + required);
            }
            // 这张表独立存在的意义：库里没有地方放原始输入。
            for (String forbidden : new String[]{"arguments", "args", "prompt", "content", "patientid"}) {
                assertFalse(columns.contains(forbidden), "must not have a raw-payload column: " + forbidden);
            }

            // 一次调用两条状态 + 另一个用户的行：按 owner 检索只能拿到 owner 的，且按时间序。
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_agent_tool_audit "
                            + "(sessionId,userId,toolCallId,toolName,toolStatus,errorCategory,argsDigest,argsKeyNames,startedAt,finishedAt) "
                            + "VALUES (?,?,?,?,?,?,?,?,CAST(? AS timestamp with time zone),CAST(? AS timestamp with time zone))")) {
                insertRow(insert, OWNER, "call-1", "searchDocuments", "running", "NONE",
                        "hmac-a", "query", "2026-11-14 10:00:00.000000", null);
                insertRow(insert, OWNER, "call-1", "searchDocuments", "succeeded", "NONE",
                        null, null, "2026-11-14 10:00:01.000000", "2026-11-14 10:00:01.000000");
                insertRow(insert, OTHER, "call-2", "analyzeEyeImage", "failed", "TIMEOUT",
                        null, null, "2026-11-14 10:00:02.000000", "2026-11-14 10:00:02.000000");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT userId,toolStatus,finishedAt FROM t_agent_tool_audit "
                                 + "WHERE sessionId='s1' AND userId=" + OWNER + " ORDER BY startedAt ASC")) {
                assertTrue(rs.next());
                assertEquals(OWNER, rs.getLong(1));
                assertEquals("running", rs.getString(2));
                assertNull(rs.getObject(3));
                assertTrue(rs.next());
                assertEquals("succeeded", rs.getString(2));
                assertNotNull(rs.getObject(3));
                assertFalse(rs.next(), "owner filter must exclude user " + OTHER);
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT toolName,COUNT(*) FROM t_agent_tool_audit "
                                 + "WHERE sessionId='s1' AND userId=" + OWNER + " GROUP BY toolName")) {
                assertTrue(rs.next());
                assertEquals("searchDocuments", rs.getString(1));
                assertEquals(2L, rs.getLong(2), "running + succeeded for the owner");
                assertFalse(rs.next());
            }

            connection.createStatement().executeUpdate("DELETE FROM t_agent_tool_audit WHERE sessionId='s1'");
        }
    }

    /** 每一行都走同一条语句；owner 与「另一个用户」的行只差 userId。 */
    private void insertRow(PreparedStatement insert, long user, String callId, String name, String status,
                           String error, String digest, String keys, String startedAt, String finishedAt)
            throws Exception {
        insert.setString(1, "s1");
        insert.setLong(2, user);
        insert.setString(3, callId);
        insert.setString(4, name);
        insert.setString(5, status);
        insert.setString(6, error);
        insert.setString(7, digest);
        insert.setString(8, keys);
        insert.setString(9, startedAt);
        insert.setString(10, finishedAt);
        assertEquals(1, insert.executeUpdate());
    }
}
