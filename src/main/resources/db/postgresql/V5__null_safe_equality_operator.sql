-- MySQL 的空安全等值运算符 `<=>`（a <=> b 在任一侧为 NULL 时也不返回 NULL）。
--
-- 配置中心的条件指针切换用它区分「期望 NULL（首次发布）」与「期望某个 releaseId」：
--   UPDATE t_config_active_pointer SET releaseId = ? WHERE id = 1 AND releaseId <=> ?
-- 改成 `=` 会让首次发布永远匹配 0 行，改成 IS NOT DISTINCT FROM 则让 Java 侧 SQL
-- 变成 PostgreSQL 专属——两条线的 SQL 就分叉了。
--
-- 因此与 date_format / utc_timestamp / curdate 同一策略：Java 保留 MySQL 方言，
-- PostgreSQL 侧补一个同名运算符。pgvector 的 `<=>` 只定义在 vector 类型上，
-- 与 bigint 重载不冲突。
CREATE OR REPLACE FUNCTION iris_null_safe_eq_bigint(left_value bigint, right_value bigint)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT left_value IS NOT DISTINCT FROM right_value
$$;

CREATE OPERATOR <=> (
    LEFTARG = bigint,
    RIGHTARG = bigint,
    FUNCTION = iris_null_safe_eq_bigint
);
