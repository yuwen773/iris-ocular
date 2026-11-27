package com.csust.eyediagnosis.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Objects;

/**
 * 把 pgvector 的「距离」分数换算回 Chroma 时代的「相似度」分数。
 *
 * <p>为什么需要这一层：{@code Document.score} 在两个存储里口径相反。
 * <ul>
 *   <li>Chroma（迁移前）：写入的是 {@code 1 - distance}，越大越相关，0..1；</li>
 *   <li>Spring AI {@code PgVectorStore}：直接写 SQL 里的 cosine distance，
 *       越小越相关，0..2。</li>
 * </ul>
 * 应用侧（侧栏「相关度」、{@code retrievalScore}、重排序回退）都按相似度理解这个数字。
 * 不在存储边界换算，最好的命中会显示成「相关度 0.02」，最差的显示成 1.0——方向完全反了。
 *
 * <p>换算只对 {@code COSINE_DISTANCE} 成立，与 {@code VectorStoreConfig} 的配置绑定；
 * 换距离类型时必须同时改这里。相似度截断到 [0,1]，与 Chroma 的展示范围一致。
 * 没有分数的文档（例如词法通道命中）原样返回。
 */
public class SimilarityScoredVectorStore implements VectorStore {

    private final VectorStore delegate;

    public SimilarityScoredVectorStore(VectorStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> ids) {
        delegate.delete(ids);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public List<Document> similaritySearch(String query) {
        return toSimilarity(delegate.similaritySearch(query));
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return toSimilarity(delegate.similaritySearch(request));
    }

    private static List<Document> toSimilarity(List<Document> documents) {
        return documents == null ? null : documents.stream().map(SimilarityScoredVectorStore::toSimilarity).toList();
    }

    private static Document toSimilarity(Document document) {
        if (document == null || document.getScore() == null) {
            return document;
        }
        double similarity = Math.max(0.0, Math.min(1.0, 1.0 - document.getScore()));
        return document.mutate().score(similarity).build();
    }
}
