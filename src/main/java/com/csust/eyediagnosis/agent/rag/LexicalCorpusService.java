package com.csust.eyediagnosis.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法召回语料的读取口：按 owner 元数据从向量库<b>直取</b>该用户可见的全部分块。
 *
 * <p>这里替代的是旧实现里那句「用 {@code similaritySearch("*", topK=1000, threshold=0)}
 * 冒充全部文档」：那条路拿到的是<b>语义近邻的前 1000 条</b>，不是全量。片段数超过
 * 上限、或语料分布偏离查询语义时，词法召回会静默漏掉本该命中的片段，而且这种漏是
 * 看不出来——检索照常返回结果，只是少了一些证据。
 *
 * <p>Chroma 的 {@code get} 接口支持按 metadata 过滤并分页返回，因此这里可以拿到
 * owner 维度上的<b>完整</b>分块集合：既不受语义近邻数量限制，也不受查询向量影响。
 * 内置语料用哨兵 owner（{@link BuiltinCorpus#SYSTEM_OWNER_ID}）与用户私有文档一起取，
 * 与向量检索的 owner 过滤口径保持一致。
 *
 * <p>内存边界是显式的：{@code maxChunks} 之外的分块不会被取回，取满时记 WARN 而不是
 * 静默截断。宁可少召回并让运维看见，也不要让「证据不全」变成一件看不见的事。
 */
@Slf4j
@Component
public class LexicalCorpusService {

    /** 单页条数：Chroma 的 get 接口按 limit/offset 分页。 */
    static final int PAGE_SIZE = 500;

    @Autowired(required = false)
    private ChromaApi chromaApi;

    @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}")
    private String tenantName = "default_tenant";

    @Value("${spring.ai.vectorstore.chroma.database-name:default_database}")
    private String databaseName = "default_database";

    @Value("${spring.ai.vectorstore.chroma.collection-name:eye-diagnosis-knowledge}")
    private String collectionName = "eye-diagnosis-knowledge";

    /** 向量库不可用时词法通道降级为空（检索退回纯语义），不做假装成功的兜底。 */
    public boolean available() {
        return chromaApi != null;
    }

    /**
     * 取该用户可见的全部分块（含内置语料）。
     *
     * @param userId   当前登录用户
     * @param maxChunks 最多取多少分块；触顶时记 WARN，说明语料被截断
     */
    public List<Document> loadOwnerCorpus(Long userId, int maxChunks) {
        if (userId == null || chromaApi == null || maxChunks <= 0) {
            return List.of();
        }
        Map<String, Object> where = ownerWhere(userId);
        List<Document> corpus = new ArrayList<>();
        int offset = 0;
        try {
            // offset 按「已取回的条数」推进而不是按语料条数：空文本的分块不会进语料，
            // 用语料条数推进会在这种情况下原地打转。服务端返回空页即语料取完。
            while (offset < maxChunks) {
                int limit = Math.min(PAGE_SIZE, maxChunks - offset);
                ChromaApi.GetEmbeddingsRequest request = new ChromaApi.GetEmbeddingsRequest(
                        null, where, limit, offset,
                        List.of(ChromaApi.QueryRequest.Include.DOCUMENTS,
                                ChromaApi.QueryRequest.Include.METADATAS));
                ChromaApi.GetEmbeddingResponse response =
                        chromaApi.getEmbeddings(tenantName, databaseName, collectionName, request);
                if (response == null || response.ids() == null || response.ids().isEmpty()) {
                    break;
                }
                append(corpus, response);
                offset += response.ids().size();
            }
        } catch (Exception e) {
            log.warn("按 owner 直取词法语料失败，词法通道本轮降级为空: {}", e.getMessage());
            return List.of();
        }
        if (offset >= maxChunks) {
            log.warn("词法语料达到上限 {} 分块，剩余分块未纳入本轮词法召回（语料规模超出上限时召回不完整）",
                    maxChunks);
        }
        log.debug("词法语料已直取: userId={}, chunks={}", userId, corpus.size());
        return corpus;
    }

    /**
     * owner 过滤条件：当前用户的私有文档 + 公共内置语料。
     *
     * <p>用 {@code $in} 一次取回两类 owner，避免两次分页查询。值必须是数字：
     * 元数据里的 owner 是 Long，雪花 ID 超出 int 范围，转成字符串会匹配不上。
     */
    static Map<String, Object> ownerWhere(Long userId) {
        return Map.of("$and", List.of(Map.of("ownerUserId",
                Map.of("$in", List.of(userId, BuiltinCorpus.SYSTEM_OWNER_ID)))));
    }

    private void append(List<Document> corpus, ChromaApi.GetEmbeddingResponse response) {
        List<String> ids = response.ids();
        List<String> texts = response.documents();
        List<Map<String, String>> metadata = response.metadata();
        for (int i = 0; i < ids.size(); i++) {
            String text = texts != null && i < texts.size() ? texts.get(i) : null;
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> chunkMetadata = new HashMap<>();
            if (metadata != null && i < metadata.size() && metadata.get(i) != null) {
                metadata.get(i).forEach((key, value) -> chunkMetadata.put(key, normalizeValue(key, value)));
            }
            corpus.add(new Document(ids.get(i), text, chunkMetadata));
        }
    }

    /**
     * Chroma 的 metadata 取回来都是字符串。owner 与 documentId 必须还原成数字，
     * 否则 {@link SourceTypePolicy} 的「是否属于当前用户」判断会全部落空
     * （它只认 {@code Number}）。
     */
    static Object normalizeValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if ("ownerUserId".equals(key) || "documentId".equals(key)) {
            try {
                return Long.valueOf(value.trim());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if ("chunkIndex".equals(key)) {
            try {
                return Integer.valueOf(value.trim());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }
}
