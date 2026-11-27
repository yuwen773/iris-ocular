package com.csust.eyediagnosis.agent.rag;

import com.csust.eyediagnosis.common.LogAnonymizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>PostgreSQL 线直接按 metadata 查 {@code rag.iris_knowledge_vectors}：拿到的是
 * owner 维度上的<b>完整</b>分块集合，既不受语义近邻数量限制，也不受查询向量影响。
 * 内置语料用哨兵 owner（{@link BuiltinCorpus#SYSTEM_OWNER_ID}）与用户私有文档一起取，
 * 与向量检索的 owner 过滤口径保持一致。
 *
 * <p>owner 用文本比较（{@code ->>}）：JSON 里的数字既可能是 Number（新写入），也可能是
 * 字符串（Chroma 迁移来的旧数据按字符串保存），两边归一成十进制文本再比，
 * 不会出现「迁移后的旧分块词法通道一条都取不到」这种安静退化。
 *
 * <p>内存边界是显式的：{@code maxChunks} 之外的分块不会被取回，取满时记 WARN 而不是
 * 静默截断。宁可少召回并让运维看见，也不要让「证据不全」变成一件看不见的事。
 */
@Slf4j
@Component
public class LexicalCorpusService {

    /** 单页条数。 */
    static final int PAGE_SIZE = 500;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Value("${eye-diagnosis.rag.vector-store.schema:rag}")
    private String schemaName = "rag";

    @Value("${eye-diagnosis.rag.vector-store.table:iris_knowledge_vectors}")
    private String tableName = "iris_knowledge_vectors";

    /** 向量库不可用时词法通道降级为空（检索退回纯语义），不做假装成功的兜底。 */
    public boolean available() {
        return jdbcTemplate != null;
    }

    /**
     * 取该用户可见的全部分块（含内置语料）。
     *
     * @param userId   当前登录用户
     * @param maxChunks 最多取多少分块；触顶时记 WARN，说明语料被截断
     */
    public List<Document> loadOwnerCorpus(Long userId, int maxChunks) {
        if (userId == null || jdbcTemplate == null || maxChunks <= 0) {
            return List.of();
        }
        List<Document> corpus = new ArrayList<>();
        int offset = 0;
        String sql = "SELECT id, content, metadata::text AS metadata FROM "
                + qualifiedTable()
                + " WHERE (metadata::jsonb ->> 'ownerUserId') IN (?, ?)"
                + " ORDER BY id LIMIT ? OFFSET ?";
        try {
            while (offset < maxChunks) {
                int limit = Math.min(PAGE_SIZE, maxChunks - offset);
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        sql, String.valueOf(userId), String.valueOf(BuiltinCorpus.SYSTEM_OWNER_ID),
                        limit, offset);
                if (rows.isEmpty()) {
                    break;
                }
                append(corpus, rows);
                // offset 按「已取回的条数」推进而不是按语料条数：空文本的分块不会进语料。
                offset += rows.size();
            }
        } catch (Exception e) {
            log.warn("按 owner 直取词法语料失败，词法通道本轮降级为空: {}", e.getMessage());
            return List.of();
        }
        if (offset >= maxChunks) {
            log.warn("词法语料达到上限 {} 分块，剩余分块未纳入本轮词法召回（语料规模超出上限时召回不完整）",
                    maxChunks);
        }
        // 运行日志不得出现原始 userId（见 LogAnonymizer 的类注释）。
        log.debug("词法语料已直取: userIdHash={}, chunks={}",
                LogAnonymizer.userId(userId), corpus.size());
        return corpus;
    }

    private String qualifiedTable() {
        // schema/表名来自受控配置；仍然只允许标识符字符，杜绝把配置写进 SQL。
        if (!schemaName.matches("[a-zA-Z0-9_]{1,64}") || !tableName.matches("[a-zA-Z0-9_]{1,64}")) {
            throw new IllegalStateException("向量表名不合法: " + schemaName + "." + tableName);
        }
        return schemaName + "." + tableName;
    }

    private void append(List<Document> corpus, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String text = row.get("content") == null ? null : String.valueOf(row.get("content"));
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> chunkMetadata = new HashMap<>();
            Object rawMetadata = row.get("metadata");
            if (rawMetadata != null && objectMapper != null) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(
                            String.valueOf(rawMetadata), new TypeReference<Map<String, Object>>() { });
                    parsed.forEach((key, value) -> chunkMetadata.put(key, normalizeValue(key, value)));
                } catch (Exception e) {
                    log.warn("分块元数据解析失败，按空元数据继续: {}", e.getMessage());
                }
            }
            corpus.add(new Document(String.valueOf(row.get("id")), text, chunkMetadata));
        }
    }

    /**
     * 迁移来的旧分块元数据可能是字符串（Chroma 只存字符串）。owner 与 documentId
     * 必须还原成数字，否则 {@link SourceTypePolicy} 的「是否属于当前用户」判断会全部
     * 落空（它只认 {@code Number}）。
     */
    static Object normalizeValue(String key, Object value) {
        if (value == null || value instanceof Number) {
            return value;
        }
        String text = String.valueOf(value);
        if ("ownerUserId".equals(key) || "documentId".equals(key)) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if ("chunkIndex".equals(key)) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }
}
