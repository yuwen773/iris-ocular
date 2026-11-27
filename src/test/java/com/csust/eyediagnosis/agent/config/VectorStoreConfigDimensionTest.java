package com.csust.eyediagnosis.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 维度闸门：PgVectorStore 自己的启动校验只查列是否存在，配置的 dimensions 与
 * 迁移里的 vector(N) 不一致时，原来要等第一次 add()（付费嵌入之后）才炸。
 * 这里锁住「启动即拒绝」和「一致时放行」。
 */
class VectorStoreConfigDimensionTest {

    private static JdbcTemplate jdbcWithTypmod(Integer typmod) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(typmod);
        return jdbc;
    }

    @Test
    void mismatchedColumnDimensionFailsAtStartup() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new VectorStoreConfig().pgVectorStore(
                        jdbcWithTypmod(768), mock(EmbeddingModel.class), "rag", "iris_knowledge_vectors", 1024));
        assertTrue(failure.getMessage().contains("768"), failure.getMessage());
    }

    @Test
    void matchingDimensionPasses() {
        assertDoesNotThrow(() -> new VectorStoreConfig().pgVectorStore(
                jdbcWithTypmod(1024), mock(EmbeddingModel.class), "rag", "iris_knowledge_vectors", 1024));
    }
}
