package com.csust.eyediagnosis.agent.tools;

import com.csust.eyediagnosis.agent.rag.LexicalCorpusService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-A-04：混合检索的词法通道必须是「按 owner 直取的全量语料」，
 * 且词法命中不得绕过 similarity-threshold 进入证据。
 */
class DocumentSearchToolHybridTest {

    private static Document chunk(String id, Long documentId, Long ownerUserId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("ownerUserId", ownerUserId);
        metadata.put("indexVersion", "v1");
        return new Document(id, "片段 " + id, metadata);
    }

    private static DocumentSearchTool toolWith(VectorStore vectorStore, LexicalCorpusService corpusService) {
        DocumentSearchTool tool = new DocumentSearchTool();
        ReflectionTestUtils.setField(tool, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(tool, "lexicalCorpusService", corpusService);
        ReflectionTestUtils.setField(tool, "maxCorpusChunks", 100);
        ReflectionTestUtils.setField(tool, "cacheMaxUsers", 2);
        ReflectionTestUtils.setField(tool, "searchTopK", 20);
        ReflectionTestUtils.setField(tool, "similarityThreshold", 0.5);
        return tool;
    }

    @Test
    void lexicalCorpusComesFromOwnerScopedEnumerationNotSemanticNeighbours() {
        VectorStore vectorStore = mock(VectorStore.class);
        LexicalCorpusService corpusService = mock(LexicalCorpusService.class);
        when(corpusService.available()).thenReturn(true);
        when(corpusService.loadOwnerCorpus(anyLong(), anyInt()))
                .thenReturn(List.of(chunk("lex-1", 5L, 7L)));

        DocumentSearchTool tool = toolWith(vectorStore, corpusService);

        @SuppressWarnings("unchecked")
        List<Document> corpus = (List<Document>) ReflectionTestUtils.invokeMethod(
                tool, "refreshDocumentCache", 7L);

        assertEquals(List.of("lex-1"), corpus.stream().map(Document::getId).toList());
        verify(corpusService).loadOwnerCorpus(7L, 100);
        // 关键回归：旧实现会用 similaritySearch("*", topK=1000) 冒充全量语料。
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void lexicalHitAlreadyInVectorResultsIsKept() {
        VectorStore vectorStore = mock(VectorStore.class);
        DocumentSearchTool tool = toolWith(vectorStore, null);

        Document lexical = chunk("lex-1", 5L, 7L);
        List<Document> kept = tool.dropUnconfirmedLexicalHits(
                "青光眼", List.of(lexical), List.of(lexical), 7L);

        assertEquals(List.of("lex-1"), kept.stream().map(Document::getId).toList());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void lexicalHitConfirmedByVectorStoreIsKept() {
        VectorStore vectorStore = mock(VectorStore.class);
        DocumentSearchTool tool = toolWith(vectorStore, null);

        Document lexical = chunk("lex-1", 5L, 7L);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(lexical));

        List<Document> kept = tool.dropUnconfirmedLexicalHits(
                "青光眼", List.of(lexical), List.of(), 7L);

        assertEquals(List.of("lex-1"), kept.stream().map(Document::getId).toList());
    }

    @Test
    void lexicalHitBelowSimilarityThresholdIsDropped() {
        VectorStore vectorStore = mock(VectorStore.class);
        DocumentSearchTool tool = toolWith(vectorStore, null);

        Document lexical = chunk("lex-1", 5L, 7L);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<Document> kept = tool.dropUnconfirmedLexicalHits(
                "青光眼", List.of(lexical), List.of(), 7L);

        assertEquals(List.of(), kept, "词法命中不能绕过相似度阈值成为证据");
    }

    @Test
    void vectorStoreFailureDropsLexicalHitsInsteadOfLettingThemThrough() {
        VectorStore vectorStore = mock(VectorStore.class);
        DocumentSearchTool tool = toolWith(vectorStore, null);

        Document lexical = chunk("lex-1", 5L, 7L);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("chroma down"));

        List<Document> kept = tool.dropUnconfirmedLexicalHits(
                "青光眼", List.of(lexical), List.of(), 7L);

        assertEquals(List.of(), kept, "核对失败按不通过处理：阈值是安全边界");
    }

    @Test
    void corpusCacheEvictsLeastRecentlyUsedUser() {
        VectorStore vectorStore = mock(VectorStore.class);
        LexicalCorpusService corpusService = mock(LexicalCorpusService.class);
        when(corpusService.available()).thenReturn(true);
        when(corpusService.loadOwnerCorpus(anyLong(), anyInt()))
                .thenReturn(List.of(chunk("lex-1", 5L, 7L)));

        DocumentSearchTool tool = toolWith(vectorStore, corpusService);

        for (long userId : new long[]{1L, 2L, 3L}) {
            ReflectionTestUtils.invokeMethod(tool, "refreshDocumentCache", userId);
        }

        @SuppressWarnings("unchecked")
        Map<Long, Object> caches = (Map<Long, Object>) ReflectionTestUtils.getField(tool, "documentCaches");
        assertEquals(2, caches.size(), "缓存必须有容量上限，不能按 userId 只增不减");
    }
}
