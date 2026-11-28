package com.csust.eyediagnosis.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 把数据库时间列读成 API 契约里的「UTC 墙钟」{@code LocalDateTime}。
 *
 * <p>MySQL 线的 DATETIME 列存的是 UTC 墙钟，JDBC 直接给 {@code LocalDateTime}；
 * PostgreSQL 线把这些列建成 {@code timestamptz}（存的是瞬时点），pgjdbc 对
 * {@code getObject(column, LocalDateTime.class)} 会直接拒绝：
 * {@code Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime}。
 *
 * <p>直接换成本地时区换算会悄悄改变返回值：同一行在 UTC 与非 UTC 会话里读出不同的墙钟。
 * 这里固定按 UTC 还原，保证 MySQL 时代写入、PostgreSQL 时代读出的时间字符串逐字一致。
 */
public final class UtcTimestamps {

    private UtcTimestamps() {
    }

    /** 读取时间列；NULL 保持 NULL。 */
    public static LocalDateTime read(ResultSet rs, String column) throws SQLException {
        return toUtc(rs.getObject(column), column);
    }

    /** 单个已取出值的换算（{@link #read} 的实现，单独暴露给无数据库的回归用例）。 */
    static LocalDateTime toUtc(Object value, String column) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime local) {
            return local;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        throw new SQLException("Unsupported timestamp representation for " + column + ": "
                + value.getClass().getName());
    }
}
