package com.csust.eyediagnosis.agent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 向量存储：PostgreSQL + pgvector。
 *
 * <p>存储适配层只负责装配；表、索引、扩展与权限由
 * {@code src/main/resources/db/postgresql} 的 Flyway 迁移统一建立：
 * <ul>
 *   <li>{@code initializeSchema=false} —— 应用启动不建表；</li>
 *   <li>{@code removeExistingVectorStoreTable=false} —— 任何情况下都不删历史向量；</li>
 *   <li>{@code idType=TEXT} —— 分块 id 保持既有字符串（{@code builtin_<slug>_chunk_<n>}、
 *       {@code doc_<docId>_<version>_chunk_<n>}），引用与去重不需要 id 重映射表；</li>
 *   <li>{@code COSINE} —— 与 Chroma collection 的 {@code hnsw:space=cosine} 同口径，
 *       相似度阈值（0.5）含义不变：pgvector 距离 = 1 - 余弦相似度。</li>
 * </ul>
 *
 * <p>维度固定为 text-embedding-v3 的默认输出 1024；换模型必须先做重嵌入迁移，
 * 不能在代码里静默改一个数字。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    // PgVectorStore 在 afterPropertiesSet() 里无条件校验表和列（只要 validations
    // 开启），而用户 @Bean 的实例化早于自动配置的 Flyway 初始化器。没有这个声明时，
    // 空库首次启动的成败取决于 bean 创建顺序这一巧合；加上它，Flyway 先建表再校验。
    @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate,
                                     EmbeddingModel dashscopeEmbeddingModel,
                                     @Value("${eye-diagnosis.rag.vector-store.schema:rag}") String schemaName,
                                     @Value("${eye-diagnosis.rag.vector-store.table:iris_knowledge_vectors}") String tableName,
                                     @Value("${eye-diagnosis.rag.vector-store.dimensions:1024}") int dimensions) {
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .schemaName(schemaName)
                .vectorTableName(tableName)
                .idType(PgVectorStore.PgIdType.TEXT)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .removeExistingVectorStoreTable(false)
                .vectorTableValidationsEnabled(true)
                .build();
        // PgVectorStore 的启动校验只确认列存在，不比对维度：配置改了 dimensions 而迁移
        // 里的 vector(N) 没改时，要到第一次 add()（已在付费嵌入之后）才报错。这里在启动
        // 时直接比对列的实际 typmod，不一致就拒绝启动。
        verifyEmbeddingDimension(jdbcTemplate, schemaName, tableName, dimensions);
        // PgVectorStore 把 cosine distance 写进 Document.score；应用侧按 Chroma 的
        // 相似度口径理解这个数字，必须在存储边界换算（见包装类的说明）。
        return new com.csust.eyediagnosis.agent.rag.SimilarityScoredVectorStore(store);
    }

    private static void verifyEmbeddingDimension(JdbcTemplate jdbc, String schema, String table, int dimensions) {
        Integer typmod;
        try {
            typmod = jdbc.queryForObject("""
                    SELECT a.atttypmod FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ? AND a.attname = 'embedding' AND a.attnum > 0
                    """, Integer.class, schema, table);
        } catch (org.springframework.dao.EmptyResultDataAccessException absent) {
            // 表不存在时交给 PgVectorStore 自己的表校验报错，别在这里制造第二个错误来源。
            return;
        }
        // pgvector 把维度直接存在 atttypmod 里（vector(1024) → atttypmod=1024），
        // 不像 varchar 那样加 VARHDRSZ；实测确认过，别按普通类型减 4。
        int actual = typmod == null ? -1 : typmod;
        if (actual > 0 && actual != dimensions) {
            throw new IllegalStateException(String.format(
                    "向量维度不一致：%s.%s.embedding 是 vector(%d)，配置 eye-diagnosis.rag.vector-store.dimensions=%d。"
                            + "换模型必须先做重嵌入迁移，不能只改配置。", schema, table, actual, dimensions));
        }
    }
}
