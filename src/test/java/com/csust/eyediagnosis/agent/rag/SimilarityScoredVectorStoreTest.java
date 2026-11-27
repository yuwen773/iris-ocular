package com.csust.eyediagnosis.agent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分数口径的边界：PgVectorStore 返回 cosine distance（越小越近），应用侧按
 * Chroma 的相似度（1 - distance，越大越相关）理解 {@code Document.score}。
 * 这里锁住换算、截断、空分数与委托行为。
 */
class SimilarityScoredVectorStoreTest {

    private static Document scored(String id, Double score) {
        Document.Builder builder = Document.builder().id(id).text("chunk " + id);
        if (score != null) {
            builder.score(score);
        }
        return builder.build();
    }

    @Test
    void distanceBecomesSimilarity() {
        VectorStore delegate = mock(VectorStore.class);
        when(delegate.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(scored("near", 0.02), scored("far", 0.9)));

        List<Document> hits = new SimilarityScoredVectorStore(delegate)
                .similaritySearch(SearchRequest.builder().query("q").topK(2).build());

        assertEquals(0.98, hits.get(0).getScore(), 1e-9, "near chunk must become a high similarity");
        assertEquals(0.10, hits.get(1).getScore(), 1e-9);
    }

    @Test
    void scoresAreClampedAndNullScoresPassThrough() {
        VectorStore delegate = mock(VectorStore.class);
        when(delegate.similaritySearch("q"))
                .thenReturn(List.of(scored("negative", 1.7), scored("unscored", null)));

        List<Document> hits = new SimilarityScoredVectorStore(delegate).similaritySearch("q");

        assertEquals(0.0, hits.get(0).getScore(), 1e-9, "similarity never goes below 0");
        assertNull(hits.get(1).getScore(), "lexical hits carry no score and must stay unscored");
    }

    @Test
    void writesAndDeletesGoStraightThrough() {
        VectorStore delegate = mock(VectorStore.class);
        VectorStore store = new SimilarityScoredVectorStore(delegate);
        List<Document> documents = List.of(new Document("id-1", "text", Map.of("ownerUserId", 7)));
        Filter.Expression filter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                .eq("ownerUserId", 7).build();

        store.add(documents);
        store.delete(List.of("id-1"));
        store.delete(filter);

        verify(delegate).add(documents);
        verify(delegate).delete(List.of("id-1"));
        verify(delegate).delete(filter);
        assertSame(delegate.getName(), store.getName());
    }
}
