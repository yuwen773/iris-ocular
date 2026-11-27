package com.csust.eyediagnosis.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 词法召回语料必须按 owner 元数据直取全量，不能拿「语义近邻前 1000 条」冒充全量。
 * 分页、上限、owner 口径与数值还原都要有据可查（PostgreSQL + pgvector 版）。
 */
class LexicalCorpusServiceTest {

    /** 模拟按 LIMIT/OFFSET 分页的查询：取完返回空页。 */
    private static JdbcTemplate jdbcServing(int totalChunks, String metadataJson) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            // Mockito 会把可变参数摊开：getArguments() 是 [sql, owner, system, limit, offset]。
            Object[] args = invocation.getArguments();
            int limit = ((Number) args[args.length - 2]).intValue();
            int offset = ((Number) args[args.length - 1]).intValue();
            int remaining = totalChunks - offset;
            if (remaining <= 0 || limit <= 0) {
                return List.of();
            }
            int count = Math.min(limit, remaining);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "c" + (offset + i));
                row.put("content", "片段 " + (offset + i));
                row.put("metadata", metadataJson);
                rows.add(row);
            }
            return rows;
        });
        return jdbc;
    }

    private static LexicalCorpusService serviceWith(JdbcTemplate jdbc) {
        LexicalCorpusService service = new LexicalCorpusService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }

    @Test
    void ownerCorpusIsFetchedWithOwnerFilterAndPagination() {
        JdbcTemplate jdbc = jdbcServing(1200, "{\"ownerUserId\":7}");

        List<Document> corpus = serviceWith(jdbc).loadOwnerCorpus(7L, 1000);

        assertEquals(1000, corpus.size(), "语料应按页取满上限，而不是只取一页");
        assertEquals("c0", corpus.get(0).getId());
        assertEquals("c999", corpus.get(999).getId());
        verify(jdbc, times(2)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void ownerFilterUsesTextComparisonSoSnowflakeIdsAndLegacyStringsBothMatch() {
        JdbcTemplate jdbc = jdbcServing(1, "{\"ownerUserId\":2123010432165113858}");

        serviceWith(jdbc).loadOwnerCorpus(2123010432165113858L, 100);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).queryForList(anyString(), captor.capture());

        Object[] args = captor.getAllValues().get(0);
        assertEquals("2123010432165113858", args[0], "owner 以十进制文本比较，雪花 ID 不经过 Integer 解析");
        assertEquals("0", args[1], "内置语料的哨兵 owner 必须一起取");
    }

    @Test
    void numericMetadataIsRestoredToNumbers() {
        JdbcTemplate jdbc = jdbcServing(1,
                "{\"ownerUserId\":\"7\",\"documentId\":\"42\",\"chunkIndex\":\"3\",\"indexVersion\":\"v1\"}");

        Map<String, Object> metadata = serviceWith(jdbc).loadOwnerCorpus(7L, 100).get(0).getMetadata();
        assertEquals(7L, metadata.get("ownerUserId"), "迁移来的字符串 owner 必须还原成 Number");
        assertEquals(42L, metadata.get("documentId"));
        assertEquals(3, metadata.get("chunkIndex"));
        assertEquals("v1", metadata.get("indexVersion"));
    }

    @Test
    void corpusIsCappedAtTheConfiguredMaximum() {
        JdbcTemplate jdbc = jdbcServing(100, "{\"ownerUserId\":7}");

        assertEquals(5, serviceWith(jdbc).loadOwnerCorpus(7L, 5).size(), "语料上限是硬边界");
    }

    @Test
    void unavailableVectorStoreYieldsEmptyCorpusInsteadOfFakeRecall() {
        LexicalCorpusService service = new LexicalCorpusService();
        assertTrue(service.loadOwnerCorpus(7L, 100).isEmpty());
        assertFalse(service.available());
    }

    @Test
    void vectorStoreFailureYieldsEmptyCorpus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("pg down"));

        assertTrue(serviceWith(jdbc).loadOwnerCorpus(7L, 100).isEmpty(),
                "取不到语料时必须是没有候选，而不是一份不完整的语料");
    }

    @Test
    void paginationStopsOnEmptyPage() {
        JdbcTemplate jdbc = jdbcServing(1, "{\"ownerUserId\":7}");

        assertEquals(1, serviceWith(jdbc).loadOwnerCorpus(7L, 1000).size());
        // 取完最后一页还会再问一次，答案是空页，然后停止。
        verify(jdbc, times(2)).queryForList(anyString(), any(Object[].class));
    }
}
