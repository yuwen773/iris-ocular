package com.csust.eyediagnosis.collaboration;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ProductSchemaTest {
    @Test void freshSchemaContainsRealLoginAndEmptyChatDependencies() {
        String url = System.getenv("PRODUCT_ACCEPTANCE_TEST_URL");
        Assumptions.assumeTrue(url != null, "Requires isolated PRODUCT_ACCEPTANCE_TEST_URL");
        // 迁移后产品库是 PostgreSQL：只允许一次性产品验收库，防止误连开发/生产库。
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/product_acceptance_test(\\?.*)?")) {
            throw new IllegalArgumentException("Dedicated PostgreSQL schema required");
        }
        String user = System.getenv().getOrDefault("PRODUCT_ACCEPTANCE_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("PRODUCT_ACCEPTANCE_TEST_PASSWORD", "postgres");
        // 与生产连接串一致：表在 diagnosis schema，向量表在 rag。Flyway 自己的连接
        // 会设 search_path，但 JdbcTemplate 的每条语句是独立连接，必须靠 URL 指定。
        String jdbcUrl = url.contains("currentSchema=") ? url
                : url + (url.contains("?") ? "&" : "?") + "currentSchema=diagnosis,rag,public";
        var source = new DriverManagerDataSource(jdbcUrl, user, password);
        // 与 scripts/postgresql/bootstrap-roles.sql 的首条语句一致：vector 扩展装在
        // public，因为 Flyway 会把 defaultSchema 放在 search_path 最前面，未限定的
        // CREATE EXTENSION 会落进 diagnosis，而 V2 的 HNSW opclass 是 public.*。
        new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        // 用 PG 线的独立 Flyway 历史建库；V1 需要 CREATE EXTENSION vector，
        // 因此 CI 用容器 superuser 连接，而不是最小权限的 iris_app。
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .schemas("diagnosis")
                .defaultSchema("diagnosis")
                .createSchemas(true)
                .locations("classpath:db/postgresql")
                .load().migrate();
        var jdbc = new JdbcTemplate(source);
        // 未加引号的 camelCase 在 PG 里折叠为小写，与迁移里的列名一致。
        assertDoesNotThrow(() -> jdbc.queryForList("SELECT id,userAccount,userPassword,userRole FROM t_user LIMIT 0"));
        assertDoesNotThrow(() -> jdbc.queryForList("SELECT id,sessionId,userId,title,status,expiredTime,isDelete,pinned,pinTime FROM t_agent_session LIMIT 0"));
        assertDoesNotThrow(() -> jdbc.queryForList("SELECT id,sessionId,role,subType,content,tokenCount,isSummary,createdTime FROM t_agent_message LIMIT 0"));
    }
}
