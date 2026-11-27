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
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate,
                                     EmbeddingModel dashscopeEmbeddingModel,
                                     @Value("${eye-diagnosis.rag.vector-store.schema:rag}") String schemaName,
                                     @Value("${eye-diagnosis.rag.vector-store.table:iris_knowledge_vectors}") String tableName,
                                     @Value("${eye-diagnosis.rag.vector-store.dimensions:1024}") int dimensions) {
        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
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
    }
}
