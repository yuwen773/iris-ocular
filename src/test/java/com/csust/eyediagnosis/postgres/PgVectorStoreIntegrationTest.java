package com.csust.eyediagnosis.postgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pgvector 检索契约（真实 PostgreSQL + pgvector，不打模型）。
 *
 * <p>嵌入由确定性桩模型给出（同一文本 → 同一向量），因此可以断言「同一文本必须
 * 排第一」、阈值、过滤与删除语义，而不依赖付费模型调用或网络。
 *
 * <p>门槛：{@code PG_IT_URL} 环境变量指向一个已跑过 db/postgresql 迁移的数据库；
 * 未设置时整类跳过（CI 必须设置，见 .github/workflows/verify.yml 的 postgres job）。
 * 所有测试数据 id 带 {@code it_pgv_} 前缀，收发只碰自己写入的行。
 */
class PgVectorStoreIntegrationTest {

    private static final String PREFIX = "it_pgv_";
    private static final int DIMENSIONS = 1024;

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PgVectorStore store;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(env("PG_IT_URL") != null, "PG_IT_URL not set");
        dataSource = new DriverManagerDataSource(env("PG_IT_URL"), envOrDefault("PG_IT_APP_USER", "iris_app"), env("PG_IT_APP_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        store = PgVectorStore.builder(jdbc, new DeterministicEmbeddingModel())
                .schemaName("rag")
                .vectorTableName("iris_knowledge_vectors")
                .idType(PgVectorStore.PgIdType.TEXT)
                .dimensions(DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .removeExistingVectorStoreTable(false)
                .vectorTableValidationsEnabled(true)
                .build();
        store.afterPropertiesSet();
        clean();
    }

    @AfterAll
    static void tearDown() {
        if (jdbc != null) clean();
    }

    private static void clean() {
        jdbc.update("DELETE FROM rag.iris_knowledge_vectors WHERE id LIKE ?", PREFIX + "%");
    }

    private static Document doc(String suffix, String text, Map<String, Object> metadata) {
        return new Document(PREFIX + suffix, text, metadata);
    }

    @Test
    void sameTextRanksFirstWithCosineDistance() {
        store.add(List.of(
                doc("alpha", "alpha lesion description", Map.of("ownerUserId", 7, "documentId", "d1", "chunkIndex", 0)),
                doc("beta", "beta retinal scan", Map.of("ownerUserId", 7, "documentId", "d2", "chunkIndex", 0)),
                doc("gamma", "gamma optic disc", Map.of("ownerUserId", 8, "documentId", "d3", "chunkIndex", 0))));

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("alpha lesion description").topK(3).build());

        assertEquals(PREFIX + "alpha", hits.get(0).getId(),
                "identical text must rank first under cosine distance: " + hits.stream().map(Document::getId).toList());
    }

    @Test
    void metadataFilterIsolatesOwners() {
        store.add(List.of(
                doc("own7", "shared vocabulary one", Map.of("ownerUserId", 7, "documentId", "d7", "chunkIndex", 0)),
                doc("own8", "shared vocabulary one", Map.of("ownerUserId", 8, "documentId", "d8", "chunkIndex", 0)),
                doc("sys0", "shared vocabulary one", Map.of("ownerUserId", 0, "documentId", "d0", "chunkIndex", 0))));

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("shared vocabulary one").topK(10)
                .filterExpression(b.eq("ownerUserId", 8).build())
                .build());

        assertFalse(hits.isEmpty(), "owner 8 must see its own chunk");
        assertTrue(hits.stream().allMatch(d -> String.valueOf(d.getMetadata().get("ownerUserId")).equals("8")),
                "cross-owner leakage: " + hits.stream().map(d -> d.getMetadata().get("ownerUserId")).toList());
    }

    @Test
    void similarityThresholdFiltersUnrelatedText() {
        store.add(List.of(doc("thr", "threshold document", Map.of("ownerUserId", 7, "documentId", "dt", "chunkIndex", 0))));

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("completely unrelated query text").topK(10).similarityThreshold(0.99).build());

        assertTrue(hits.isEmpty(), "threshold 0.99 must reject unrelated content, got " + hits.size());
    }

    @Test
    void hnswResultMatchesExactBaseline() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            docs.add(doc("cmp" + i, "comparison document " + i, Map.of("ownerUserId", 7, "documentId", "dc" + i, "chunkIndex", 0)));
        }
        store.add(docs);
        String query = "comparison document 7";

        List<String> approximate = store.similaritySearch(SearchRequest.builder()
                        .query(query).topK(5).build())
                .stream().map(Document::getId).toList();
        List<String> exact = jdbc.queryForList(
                "SELECT id FROM rag.iris_knowledge_vectors ORDER BY embedding <=> CAST(? AS vector) LIMIT 5",
                String.class, vectorLiteral(embed(query)));

        assertEquals(exact, approximate, "HNSW top-k must agree with the exact <=> ordering on this corpus");
    }

    @Test
    void upsertKeepsOneRowPerId() {
        store.add(List.of(doc("upsert", "first version", Map.of("ownerUserId", 7, "documentId", "du", "chunkIndex", 0))));
        store.add(List.of(doc("upsert", "second version", Map.of("ownerUserId", 7, "documentId", "du", "chunkIndex", 0))));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag.iris_knowledge_vectors WHERE id = ?", Integer.class, PREFIX + "upsert");
        assertEquals(1, rows);
        String content = jdbc.queryForObject(
                "SELECT content FROM rag.iris_knowledge_vectors WHERE id = ?", String.class, PREFIX + "upsert");
        assertEquals("second version", content);
    }

    @Test
    void deleteRemovesChunkFromRetrieval() {
        store.add(List.of(doc("del", "delete me please", Map.of("ownerUserId", 7, "documentId", "dd", "chunkIndex", 0))));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag.iris_knowledge_vectors WHERE id = ?", Integer.class, PREFIX + "del"));

        store.delete(List.of(PREFIX + "del"));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag.iris_knowledge_vectors WHERE id = ?", Integer.class, PREFIX + "del"));
        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("delete me please").topK(10).similarityThreshold(0.99).build());
        assertTrue(hits.stream().noneMatch(d -> (PREFIX + "del").equals(d.getId())));
    }

    @Test
    void deleteByDocumentFilterRemovesOnlyThatDocument() {
        store.add(List.of(
                doc("docA-0", "document A chunk zero", Map.of("ownerUserId", 7, "documentId", "docA", "chunkIndex", 0)),
                doc("docA-1", "document A chunk one", Map.of("ownerUserId", 7, "documentId", "docA", "chunkIndex", 1)),
                doc("docB-0", "document B chunk zero", Map.of("ownerUserId", 7, "documentId", "docB", "chunkIndex", 0))));

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        store.delete(b.eq("documentId", "docA").build());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag.iris_knowledge_vectors WHERE id LIKE ?", Integer.class, PREFIX + "docA-%"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag.iris_knowledge_vectors WHERE id LIKE ?", Integer.class, PREFIX + "docB-%"));
    }

    @Test
    void legacyStringMetadataIsNormalizedAtWriteTime() {
        // Chroma 只存字符串元数据；迁移或旧写入可能把 ownerUserId 写成 "7"。
        // V3 的触发器在写入时把数字字符串改写成 JSON number：数字过滤必须命中，
        // 否则迁移后用户看不到自己的知识库（医学知识静默丢失）。
        jdbc.update("INSERT INTO rag.iris_knowledge_vectors (id, content, metadata, embedding) VALUES (?,?,CAST(? AS json),CAST(? AS vector))",
                PREFIX + "legacy", "legacy string metadata chunk",
                "{\"ownerUserId\":\"7\",\"documentId\":\"legacy\",\"chunkIndex\":\"0\"}",
                vectorLiteral(embed("legacy string metadata chunk")));

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("legacy string metadata chunk").topK(10)
                .filterExpression(b.eq("ownerUserId", 7).build())
                .build());

        assertTrue(hits.stream().anyMatch(d -> (PREFIX + "legacy").equals(d.getId())),
                "numeric filter must match normalized metadata");
        String stored = jdbc.queryForObject(
                "SELECT metadata::text FROM rag.iris_knowledge_vectors WHERE id = ?", String.class, PREFIX + "legacy");
        assertTrue(stored.contains("\"ownerUserId\": 7") || stored.contains("\"ownerUserId\":7"),
                "ownerUserId must be stored as a JSON number, got " + stored);
        assertNotNull(hits.get(0).getMetadata().get("documentId"));
    }

    // ---- deterministic stub embedding, no network ----

    private static float[] embed(String text) {
        return DeterministicEmbeddingModel.vector(text);
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String envOrDefault(String name, String fallback) {
        String v = env(name);
        return v == null ? fallback : v;
    }

    /** 同一文本 → 同一单位向量；不同文本近似正交。 */
    static final class DeterministicEmbeddingModel implements EmbeddingModel {

        static float[] vector(String text) {
            byte[] seed;
            try {
                seed = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Random random = new Random(Arrays.hashCode(seed));
            float[] v = new float[DIMENSIONS];
            double norm = 0;
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) random.nextGaussian();
                norm += (double) v[i] * v[i];
            }
            double n = Math.sqrt(norm);
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / n);
            return v;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) out.add(new Embedding(vector(texts.get(i)), i));
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }
    }
}
