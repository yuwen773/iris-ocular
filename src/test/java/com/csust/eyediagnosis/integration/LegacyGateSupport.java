package com.csust.eyediagnosis.integration;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 「隔离库门禁」套件的方言与装配层。
 *
 * <p>这些套件原先只接受一次性 MySQL 库（环境变量形如 {@code ISSUE20_TEST_URL}）。
 * 迁到 PostgreSQL 之后，同样的验收契约必须继续跑，所以这里做三件事：
 *
 * <ul>
 *   <li><b>URL 校验</b>：只接受回环地址上的隔离库（MySQL 或 PostgreSQL），库名必须
 *       与门禁约定一致，防止误连共享库；</li>
 *   <li><b>schema 装配</b>：PostgreSQL 用 {@code db/postgresql} 的 Flyway 迁移建库，
 *       MySQL 仍走套件自己的建表迁移；PG 侧顺便丢掉套件要用最小夹具重建的表，
 *       否则真实 schema 的 NOT NULL 列会把最小夹具拒掉；</li>
 *   <li><b>SQL 片段</b>：{@code JSON_ARRAY}/{@code JSON_SET}/{@code JSON_OBJECT}、
 *       {@code NOW(6)}、{@code DATABASE()}、{@code ENGINE=InnoDB} 这些 MySQL 方言
 *       在这里按数据库生成对应写法，套件里不再出现分叉。</li>
 * </ul>
 *
 * <p>PG 侧的 JSON 片段全部使用原生 {@code jsonb_set} / JSON 字面量，不做 MySQL
 * 函数仿真：测试断言的是数据库真实行为，仿真层出偏差时测试也发现不了。
 */
public final class LegacyGateSupport {

    private LegacyGateSupport() {
    }

    // ------------------------------------------------------------------ URL

    /**
     * 校验隔离库地址。
     *
     * @param url      环境变量值
     * @param envName  环境变量名（报错信息用，不留真实数据路径）
     * @param database 约定的隔离库名，例如 {@code issue20_test}
     */
    public static String requireIsolated(String url, String envName, String database) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Set " + envName + " for the isolated gate (" + database + ")");
        }
        if (!isIsolated(url, database)) {
            throw new IllegalArgumentException("Isolated database required: " + database);
        }
        return url;
    }

    /**
     * 套件入口的守卫：环境变量未设置时整类**跳过**（JUnit Assumptions），
     * 设置了但不是约定隔离库时**失败**。
     *
     * <p>这两个分支不能合并成抛异常：`mvn test` 默认不设这些变量，抛异常会把
     * 契约里的「opt-in 跳过」变成 203 条红色错误；反过来把错库放行则会让套件
     * 静默跑在共享库上。子 JVM（崩溃/重放 worker）仍用 {@link #requireIsolated}，
     * 它必须直接失败而不是跳过。
     */
    /** 与 {@link #requireIsolated} 同签名，但未设置时跳过整类（套件入口用）。 */
    public static String assumeIsolated(String url, String envName, String database) {
        return assumeIsolated(url, database);
    }

    public static String assumeIsolated(String url, String database) {        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Requires an isolated " + database + " database");
        if (!isIsolated(url, database)) {
            throw new IllegalArgumentException("Isolated database required: " + database);
        }
        return url;
    }

    /** 是否为回环地址上的指定隔离库（MySQL 或 PostgreSQL 均可）。 */
    public static boolean isIsolated(String url, String database) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String name = Pattern.quote(database);
        return url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/" + name + "(\\?.*)?")
                || url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/" + name + "(\\?.*)?");
    }

    public static boolean isPostgres(String url) {
        return url.startsWith("jdbc:postgresql:");
    }

    /**
     * 数据源：PostgreSQL 侧补上 {@code currentSchema}, {@code stringtype}（jsonb 列要
     * 接受字符串参数），账号取 {@code PG_TEST_USER/PG_TEST_PASSWORD}（CI service 与本地
     * 一次性实例都用超级用户建一次性库）；MySQL 侧沿用旧门禁的「库名即账号」约定。
     */
    public static DataSource dataSource(String url) {
        String[] credentials = credentials(url);
        return new DriverManagerDataSource(jdbcUrl(url), credentials[0], credentials[1]);
    }

    /** 连接串：PostgreSQL 侧补齐 {@code currentSchema} 与 {@code stringtype}。 */
    public static String jdbcUrl(String url) {
        return isPostgres(url) ? withPostgresParameters(url) : url;
    }

    /** 连接账号：PostgreSQL 取 {@code PG_TEST_USER/PG_TEST_PASSWORD}，MySQL 沿用「库名即账号」。 */
    public static String[] credentials(String url) {
        if (isPostgres(url)) {
            return new String[]{env("PG_TEST_USER", "postgres"), env("PG_TEST_PASSWORD", "postgres")};
        }
        String database = url.substring(url.lastIndexOf('/') + 1);
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        String user = database.endsWith("_test") ? database.substring(0, database.length() - "_test".length()) : database;
        return new String[]{user, user};
    }

    private static String withPostgresParameters(String url) {
        StringBuilder result = new StringBuilder(url);
        char separator = url.contains("?") ? '&' : '?';
        if (!url.contains("currentSchema=")) {
            result.append(separator).append("currentSchema=diagnosis,rag,public");
            separator = '&';
        }
        if (!url.contains("stringtype=")) {
            result.append(separator).append("stringtype=unspecified");
        }
        return result.toString();
    }

    // -------------------------------------------------------------- schema

    /**
     * 装配隔离库 schema。
     *
     * @param fixtureTables 套件会用最小夹具重建的表：PG 侧先删掉，让最小夹具生效
     *                      （一次性库，删的只是本次建的基线表）
     * @param mysqlBootstrap 旧 MySQL 路径的建表逻辑（原样保留）
     */
    public static void ensureSchema(String url, DataSource dataSource, List<String> fixtureTables,
                                    Runnable mysqlBootstrap) {
        if (!isPostgres(url)) {
            mysqlBootstrap.run();
            return;
        }
        // 迁移里的向量类型统一写成 public.vector：扩展必须先落在 public，否则
        // Flyway 的默认 schema 会把它装进 diagnosis，V2 的列类型就解析失败。
        new JdbcTemplate(dataSource).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        applyMysqlCoercions(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/postgresql")
                .schemas("diagnosis", "rag")
                .defaultSchema("diagnosis")
                .createSchemas(true)
                .load()
                .migrate();
        for (String table : fixtureTables) {
            if (!table.matches("[a-zA-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException("table name");
            }
            new JdbcTemplate(dataSource).execute("DROP TABLE IF EXISTS " + table + " CASCADE");
        }
    }

    /** 当前 schema 名：MySQL 的 {@code DATABASE()}，PostgreSQL 的 {@code current_schema()}。 */
    public static String schema(JdbcTemplate jdbc) {
        return isPostgres(jdbc.getDataSource())
                ? jdbc.queryForObject("SELECT current_schema()", String.class)
                : jdbc.queryForObject("SELECT DATABASE()", String.class);
    }

    /**
     * 与 {@code scripts/postgresql/bootstrap-roles.sql} 同一组 MySQL 兼容强制转换。
     *
     * <p>Java 侧的历史代码把 {@code boolean} 绑到 TINYINT 标志列（{@code isTest}、
     * {@code enabled}、{@code isDelete}），MySQL 会替我们转换，PostgreSQL 不会。
     * 生产/CI 靠 bootstrap 脚本建这条 AS ASSIGNMENT cast；一次性测试库必须照做，
     * 否则测试跑的不是部署形态。
     */
    private static void applyMysqlCoercions(DataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE OR REPLACE FUNCTION iris_bool_to_smallint(boolean) RETURNS smallint "
                + "LANGUAGE sql IMMUTABLE PARALLEL SAFE "
                + "AS $$ SELECT CASE WHEN $1 THEN 1::smallint ELSE 0::smallint END $$");
        jdbc.execute("DROP CAST IF EXISTS (boolean AS smallint)");
        jdbc.execute("CREATE CAST (boolean AS smallint) WITH FUNCTION iris_bool_to_smallint(boolean) AS ASSIGNMENT");
    }

    private static boolean isPostgres(DataSource dataSource) {        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot inspect data source", e);
        }
    }

    // ----------------------------------------------------------- SQL pieces

    /** {@code NOW(6)} 在两边的公共写法：PostgreSQL 无 {@code now(fsp)} 重载。 */
    public static String now() {
        return "CURRENT_TIMESTAMP";
    }

    /** {@code DATE_SUB(UTC_TIMESTAMP(6), INTERVAL n SECOND)}：alter 一个「已过期」的时刻。 */
    public static String utcNowMinusSeconds(DataSource dataSource, int seconds) {
        return isPostgres(dataSource)
                ? "(now() - interval '" + seconds + " second')"
                : "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL " + seconds + " SECOND)";
    }

    /** {@code TIMESTAMPADD(SECOND, n, CURRENT_TIMESTAMP(6))}：当前时刻加/减若干秒。 */
    public static String timestampPlusSeconds(DataSource dataSource, int seconds) {
        return isPostgres(dataSource)
                ? "(CURRENT_TIMESTAMP + interval '" + seconds + " second')"
                : "TIMESTAMPADD(SECOND," + seconds + ",CURRENT_TIMESTAMP(6))";
    }

    /**
     * 故障注入触发器：写入时抛错，验证事务整体回滚。
     *
     * <p>MySQL 用 {@code SIGNAL SQLSTATE}，PostgreSQL 用 {@code RAISE EXCEPTION}；
     * 两边的失败语义一致（语句级错误 → 事务回滚），消息文本保持同一个常量。
     */
    public static void createFailTrigger(JdbcTemplate jdbc, String name, String event, String table, String message) {
        createTrigger(jdbc, name, event, table, null, message);
    }

    /**
     * 有条件的故障注入：只有 {@code NEW.<column> = value} 的那一行才失败。
     *
     * <p>图执行套件要在「只让 EVIDENCE:1 节点写入失败」的前提下验证事务回滚，
     * 因此必须保留行级条件；MySQL 用 {@code BEGIN … END} 复合语句，
     * PostgreSQL 用 plpgsql 的 {@code IF}，条件列按未加引号的标识符书写（两边都折叠成小写）。
     */
    public static void createFailTriggerWhen(JdbcTemplate jdbc, String name, String event, String table,
                                             String column, String value, String message) {
        requireIdentifier(column);
        createTrigger(jdbc, name, event, table, column == null ? null : "NEW." + column + "=" + sqlString(value),
                message);
    }

    /**
     * 有条件的故障注入，条件是 {@code NEW.<column> LIKE pattern}。
     *
     * <p>新闻调度套件要「只让自动计划出来的 pubmed 任务审计失败」：那批 taskId 共享
     * {@code auto-pubmed-} 前缀，等值条件表达不出来。两边的 {@code LIKE} 语义一致。
     */
    public static void createFailTriggerLike(JdbcTemplate jdbc, String name, String event, String table,
                                             String column, String pattern, String message) {
        requireIdentifier(column);
        createTrigger(jdbc, name, event, table, "NEW." + column + " LIKE " + sqlString(pattern), message);
    }

    private static void createTrigger(JdbcTemplate jdbc, String name, String event, String table,
                                      String condition, String message) {
        requireIdentifier(name);
        requireIdentifier(table);
        if (!event.matches("INSERT|UPDATE|DELETE")) {
            throw new IllegalArgumentException("event");
        }
        if (isPostgres(jdbc.getDataSource())) {
            // 有条件的那个分支必须 RETURN NEW：plpgsql 的 BEFORE 触发器走到函数末尾仍要
            // 有返回值，否则条件不匹配的行也会以「control reached end of trigger procedure
            // without RETURN」失败。那会让「只让目标行失败」的注入退化成「所有行都失败」，
            // 用例却照样绿——断言的范围比它声称的小。MySQL 的触发器没有返回值，不需要这一步。
            String body = condition == null
                    ? "BEGIN RAISE EXCEPTION " + sqlString(message) + "; END;"
                    : "BEGIN IF " + condition + " THEN RAISE EXCEPTION " + sqlString(message)
                    + "; END IF; RETURN NEW; END;";
            jdbc.execute("CREATE OR REPLACE FUNCTION " + name + "() RETURNS trigger AS $$ " + body + " $$ LANGUAGE plpgsql");
            // 先清掉可能残留的同名触发器：用例在失败路径上不会走到 dropFailTrigger，
            // 残留下来的会让下一次运行在 CREATE TRIGGER 上直接报 already exists——
            // 一次真实的失败被伪装成「环境坏了」，掩盖了真正要看的断言。
            jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        } else if (condition == null) {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table
                    + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=" + sqlString(message));
        } else {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table
                    + " FOR EACH ROW BEGIN IF " + condition + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT="
                    + sqlString(message) + "; END IF; END");
        }
    }

    public static void dropFailTrigger(JdbcTemplate jdbc, String name, String table) {
        requireIdentifier(name);
        requireIdentifier(table);
        if (isPostgres(jdbc.getDataSource())) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
            jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
        } else {
            jdbc.execute("DROP TRIGGER " + name);
        }
    }

    private static void requireIdentifier(String value) {
        if (!value.matches("[a-zA-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("identifier: " + value);
        }
    }

    /** {@code CREATE TABLE ... ENGINE=InnoDB} 的尾部。 */
    public static String engine(DataSource dataSource) {
        return isPostgres(dataSource) ? "" : " ENGINE=InnoDB";
    }

    /** 建表夹具里的微秒时间列类型。 */
    public static String timestampType(DataSource dataSource) {
        return isPostgres(dataSource) ? "TIMESTAMP" : "DATETIME";
    }

    /** {@code JSON_ARRAY(...)}。 */
    public static String jsonArray(DataSource dataSource, String... values) {
        if (isPostgres(dataSource)) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(jsonString(values[i]));
            }
            return "'" + json.append(']') + "'::jsonb";
        }
        StringBuilder sql = new StringBuilder("JSON_ARRAY(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(sqlString(values[i]));
        }
        return sql.append(')').toString();
    }

    /** {@code JSON_OBJECT()} —— 空对象。 */
    public static String jsonEmptyObject(DataSource dataSource) {
        return isPostgres(dataSource) ? "'{}'::jsonb" : "JSON_OBJECT()";
    }

    /** {@code JSON_OBJECT('changed', true)} —— 篡改用例里的「内容变了」标记。 */
    public static String jsonChangedFlag(DataSource dataSource) {
        return isPostgres(dataSource) ? "'{\"changed\": true}'::jsonb" : "JSON_OBJECT('changed',true)";
    }

    /** {@code JSON_OBJECT('claims', JSON_ARRAY())} —— 空 claims 的草稿。 */
    public static String jsonClaimsEmpty(DataSource dataSource) {
        return isPostgres(dataSource) ? "'{\"claims\": []}'::jsonb" : "JSON_OBJECT('claims',JSON_ARRAY())";
    }

    /** {@code JSON_OBJECT('claims', JSON_ARRAY())} 之外的「清空引用」：{@code JSON_ARRAY()}。 */
    public static String jsonEmptyArray(DataSource dataSource) {
        return isPostgres(dataSource) ? "'[]'::jsonb" : "JSON_ARRAY()";
    }

    /**
     * {@code JSON_SET(column, path, value, ...)}。
     *
     * <p>PostgreSQL 侧用嵌套的 {@code jsonb_set}，值一律经 {@code to_jsonb} —— 文本
     * 落成 JSON 字符串、数字落成 JSON 数字，与 MySQL 的隐式转换同义。
     */
    public static String jsonSet(DataSource dataSource, String column, PathValue... entries) {
        if (isPostgres(dataSource)) {
            String expression = column;
            for (PathValue entry : entries) {
                String value = entry.textValue() ? entry.literal() + "::text" : entry.literal();
                expression = "jsonb_set(" + expression + ", '" + jsonPath(entry.path()) + "', to_jsonb(" + value + "))";
            }
            return expression;
        }
        StringBuilder sql = new StringBuilder("JSON_SET(").append(column);
        for (PathValue entry : entries) {
            sql.append(',').append(sqlString(entry.path())).append(',').append(entry.literal());
        }
        return sql.append(')').toString();
    }

    /** jsonSet 的一对「路径 + 值」。 */
    public record PathValue(String path, String literal, boolean textValue) {
    }

    /** 文本值：MySQL 直接写字面量，PG 侧经 {@code to_jsonb(text)} 落成 JSON 字符串。 */
    public static PathValue text(String path, String value) {
        return new PathValue(path, sqlString(value), true);
    }

    /** 数字值。 */
    public static PathValue number(String path, int value) {
        return new PathValue(path, Integer.toString(value), false);
    }

    /** {@code $.claims[0].text} → {@code {claims,0,text}}。 */
    private static String jsonPath(String mysqlPath) {
        if (!mysqlPath.startsWith("$")) {
            throw new IllegalArgumentException("JSON path must start with '$': " + mysqlPath);
        }
        StringBuilder path = new StringBuilder("{");
        boolean first = true;
        for (String token : mysqlPath.substring(1).split("\\.")) {
            if (token.isEmpty()) {
                continue;
            }
            String key = token;
            String index = null;
            int bracket = token.indexOf('[');
            if (bracket >= 0) {
                key = token.substring(0, bracket);
                index = token.substring(bracket + 1, token.indexOf(']', bracket));
            }
            if (!key.isEmpty()) {
                if (!first) {
                    path.append(',');
                }
                path.append(key);
                first = false;
            }
            if (index != null) {
                if (!first) {
                    path.append(',');
                }
                path.append(index);
                first = false;
            }
        }
        return path.append('}').toString();
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String jsonString(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            quoted.append(switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
            });
        }
        return quoted.append('"').toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
