package com.csust.eyediagnosis.agent.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
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
 * P1-A-04：词法召回语料必须按 owner 元数据直取，不能再拿「语义近邻前 1000 条」
 * 冒充全量。分页、上限与数值还原都要有据可查。
 */
class LexicalCorpusServiceTest {

    /** 模拟 Chroma 的 get 接口：按 offset/limit 分页，取完返回空页。 */
    private static ChromaApi apiServing(int totalChunks, Map<String, String> metadata) {
        ChromaApi api = mock(ChromaApi.class);
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            ChromaApi.GetEmbeddingsRequest request = invocation.getArgument(3);
            int offset = request.offset() == null ? 0 : request.offset();
            int limit = request.limit() == null ? 0 : request.limit();
            int remaining = totalChunks - offset;
            if (remaining <= 0 || limit <= 0) {
                return new ChromaApi.GetEmbeddingResponse(List.of(), List.of(), List.of(), List.of());
            }
            int count = Math.min(limit, remaining);
            List<String> ids = new ArrayList<>();
            List<String> documents = new ArrayList<>();
            List<Map<String, String>> metadatas = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add("c" + (offset + i));
                documents.add("片段 " + (offset + i));
                metadatas.add(metadata);
            }
            return new ChromaApi.GetEmbeddingResponse(ids, List.of(), documents, metadatas);
        });
        return api;
    }

    private static LexicalCorpusService serviceWith(ChromaApi api) {
        LexicalCorpusService service = new LexicalCorpusService();
        ReflectionTestUtils.setField(service, "chromaApi", api);
        return service;
    }

    @Test
    void ownerCorpusIsFetchedWithOwnerFilterAndPagination() {
        ChromaApi api = apiServing(1200, Map.of("ownerUserId", "7"));

        List<Document> corpus = serviceWith(api).loadOwnerCorpus(7L, 1000);

        assertEquals(1000, corpus.size(), "语料应按页取满上限，而不是只取一页");
        assertEquals("c0", corpus.get(0).getId());
        assertEquals("c999", corpus.get(999).getId());
        verify(api, times(2)).getEmbeddings(anyString(), anyString(), anyString(), any());
    }

    @Test
    void ownerFilterUsesTheOwnerMetadataNotSemanticNeighbours() {
        ChromaApi api = apiServing(1, Map.of("ownerUserId", "7"));

        serviceWith(api).loadOwnerCorpus(2123010432165113858L, 100);

        ArgumentCaptor<ChromaApi.GetEmbeddingsRequest> captor =
                ArgumentCaptor.forClass(ChromaApi.GetEmbeddingsRequest.class);
        verify(api, times(2)).getEmbeddings(anyString(), anyString(), anyString(), captor.capture());

        Map<String, Object> where = captor.getAllValues().get(0).where();
        Map<?, ?> clause = (Map<?, ?>) ((List<?>) where.get("$and")).get(0);
        Map<?, ?> in = (Map<?, ?>) clause.get("ownerUserId");
        List<?> owners = (List<?>) in.get("$in");
        assertTrue(owners.contains(2123010432165113858L), "owner 必须是数字雪花 ID，不能被转成字符串");
        assertTrue(owners.contains(BuiltinCorpus.SYSTEM_OWNER_ID), "内置语料的哨兵 owner 必须一起取");
    }

    @Test
    void numericMetadataIsRestoredToNumbers() {
        ChromaApi api = apiServing(1, Map.of(
                "ownerUserId", "7", "documentId", "42", "chunkIndex", "3", "indexVersion", "v1"));

        Map<String, Object> metadata = serviceWith(api).loadOwnerCorpus(7L, 100).get(0).getMetadata();
        assertEquals(7L, metadata.get("ownerUserId"));
        assertEquals(42L, metadata.get("documentId"));
        assertEquals(3, metadata.get("chunkIndex"));
        assertEquals("v1", metadata.get("indexVersion"));
    }

    @Test
    void corpusIsCappedAtTheConfiguredMaximum() {
        ChromaApi api = apiServing(100, Map.of("ownerUserId", "7"));

        assertEquals(5, serviceWith(api).loadOwnerCorpus(7L, 5).size(), "语料上限是硬边界");
    }

    @Test
    void unavailableVectorStoreYieldsEmptyCorpusInsteadOfFakeRecall() {
        LexicalCorpusService service = new LexicalCorpusService();
        assertTrue(service.loadOwnerCorpus(7L, 100).isEmpty());
        assertFalse(service.available());
    }

    @Test
    void vectorStoreFailureYieldsEmptyCorpus() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("chroma down"));

        assertTrue(serviceWith(api).loadOwnerCorpus(7L, 100).isEmpty(),
                "取不到语料时必须是没有候选，而不是一份不完整的语料");
    }

    @Test
    void paginationStopsOnEmptyPage() {
        ChromaApi api = apiServing(1, Map.of("ownerUserId", "7"));

        assertEquals(1, serviceWith(api).loadOwnerCorpus(7L, 1000).size());
        // 取完最后一页还会再问一次，答案是空页，然后停止。
        verify(api, times(2)).getEmbeddings(anyString(), anyString(), anyString(), any());
    }
}
