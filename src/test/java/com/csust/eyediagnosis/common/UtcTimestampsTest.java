package com.csust.eyediagnosis.common;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 回归锁：PostgreSQL 的 {@code timestamptz} 必须按 UTC 还原成 API 契约里的墙钟字符串。
 *
 * <p>病例上下文：{@code ControlledReferenceService} 与 {@code NewsIssueService} 曾用
 * {@code getObject(column, LocalDateTime.class)} 读 {@code timestamptz}，pgjdbc 直接抛
 * {@code Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime}。
 * 修好之后，任何「按会话时区换算」的改写都会让同一条记录在不同时区读出不同的字符串，
 * 这两类值在这里被钉死。
 */
class UtcTimestampsTest {

    @Test
    void nullStaysNull() throws SQLException {
        assertNull(UtcTimestamps.toUtc(null, "reviewedAt"));
    }

    @Test
    void localDateTimeIsPassedThroughUnchanged() throws SQLException {
        LocalDateTime wall = LocalDateTime.parse("2026-11-28T10:15:30.123456");
        assertEquals(wall, UtcTimestamps.toUtc(wall, "publishTime"));
    }

    @Test
    void offsetDateTimeIsConvertedToTheSameInstantInUtc() throws SQLException {
        OffsetDateTime beijing = OffsetDateTime.parse("2026-11-28T18:15:30+08:00");
        assertEquals(LocalDateTime.parse("2026-11-28T10:15:30"), UtcTimestamps.toUtc(beijing, "publishTime"));
    }

    @Test
    void timestampAndInstantAgree() throws SQLException {
        Instant instant = Instant.parse("2026-11-28T10:15:30Z");
        LocalDateTime expected = LocalDateTime.parse("2026-11-28T10:15:30");
        assertEquals(expected, UtcTimestamps.toUtc(Timestamp.from(instant), "reviewedAt"));
        assertEquals(expected, UtcTimestamps.toUtc(instant, "reviewedAt"));
    }

    @Test
    void mysqlEraLocalDateTimeSurvivesARoundTrip() throws SQLException {
        // MySQL 的 DATETIME 存的就是 UTC 墙钟：同一行换到 PostgreSQL 读出必须逐字相同。
        LocalDateTime stored = LocalDateTime.parse("2026-10-05T08:00:00");
        Instant asInstant = stored.toInstant(ZoneOffset.UTC);
        assertEquals(stored, UtcTimestamps.toUtc(Timestamp.from(asInstant), "createdAt"));
    }

    @Test
    void unsupportedRepresentationFailsLoudly() {
        SQLException failure = assertThrows(SQLException.class, () -> UtcTimestamps.toUtc(42, "reviewedAt"));
        assertEquals(true, failure.getMessage().contains("reviewedAt"));
    }
}
