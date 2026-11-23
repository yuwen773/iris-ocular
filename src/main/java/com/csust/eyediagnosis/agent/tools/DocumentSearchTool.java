package com.csust.eyediagnosis.agent.tools;

import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.csust.eyediagnosis.agent.observability.EvidenceContextViolation;
import com.csust.eyediagnosis.agent.observability.ToolResourceContext;
import com.csust.eyediagnosis.agent.rag.Bm25Segmenter;
import com.csust.eyediagnosis.agent.rag.BuiltinCorpus;
import com.csust.eyediagnosis.agent.rag.LexicalCorpusService;
import com.csust.eyediagnosis.agent.rag.SourceTypePolicy;
import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.dto.agent.ResourceRef;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DocumentSearchTool {

    @Value("${eye-diagnosis.rag.search.top-k:20}")
    private int searchTopK;

    @Value("${eye-diagnosis.rag.search.final-top-k:5}")
    private int finalTopK;

    @Value("${eye-diagnosis.rag.search.similarity-threshold:0.5}")
    private double similarityThreshold;

    @Value("${eye-diagnosis.rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${eye-diagnosis.rag.hybrid-search.enabled:false}")
    private boolean hybridSearchEnabled;

    @Value("${eye-diagnosis.rag.hybrid-search.rrf-k:60}")
    private int rrfK;

    /**
     * 词法通道一次最多取多少分块。
     *
     * <p>这是内存边界，不是召回目标：语料超过这个规模时剩余分块本轮不参与词法召回，
     * 取满时服务会记 WARN。静默截断会让「证据变少」看起来像「本来就没有」。
     */
    @Value("${eye-diagnosis.rag.hybrid-search.max-corpus-chunks:5000}")
    private int maxCorpusChunks = 5000;

    /**
     * 缓存的用户数上限；超出按 LRU 驱逐。
     *
     * <p>语料缓存按用户只增不减会把内存吃光（每个用户一份全量分块），
     * 因此缓存必须有容量上限，且驱逐策略必须是 LRU。
     */
    @Value("${eye-diagnosis.rag.hybrid-search.cache-max-users:20}")
    private int cacheMaxUsers = 20;

    /**
     * B2 source-type restriction. Default OFF: turning it on can only REMOVE
     * candidates (BUILTIN / USER_UPLOAD survive; UNKNOWN / AUTHORIZED_EXTERNAL
     * are fail-closed). It never changes the existing owner OR filter.
     */
    @Value("${eye-diagnosis.rag.source-type.enabled:false}")
    private boolean sourceTypeRestrictionEnabled;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private RerankModel rerankModel;

    /** 上传文档的发布版本准入；不可查询时不放行上传片段。 */
    @Autowired(required = false)
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    /** 词法召回语料（按 owner 元数据直取的全量分块）。 */
    @Autowired(required = false)
    private LexicalCorpusService lexicalCorpusService;

    private final Bm25Segmenter segmenter = new Bm25Segmenter();

    /** 文档缓存的最大用户数由 {@code cacheMaxUsers} 控制；超出按 LRU 驱逐。 */
    private final Map<Long, UserDocumentCache> documentCaches =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, UserDocumentCache> eldest) {
                    return size() > cacheMaxUsers;
                }
            };
    private static final long CACHE_REFRESH_INTERVAL = 60_000; // 1 minute

    /**
     * 模型可见的检索入口；必须绑定服务端 currentUserId 上下文。
     * 不要把 userId 暴露为 @ToolParam，避免模型伪造。
     */
    @Tool(name = "searchDocuments", description = "从眼科医学知识库中搜索相关文档，用于回答专业眼科问题。当用户询问医学知识、疾病解释、治疗方法等专业问题时使用此工具。")
    public String searchDocuments(@ToolParam(description = "搜索查询文本，应包含要查找的医学主题或关键词") String query) {
        // 没有绑定可信 userId 时拒绝检索；调用方（Agent 入口）应通过
        // ToolContext 传入或直接使用 searchDocuments(query, currentUserId)。
        return searchDocuments(query, null);
    }

    /**
     * 真正按当前用户过滤的检索入口。currentUserId 来自服务端当前会话，
     * 不得由模型输入控制。
     */
    public String searchDocuments(String query, Long currentUserId) {
        // 这两种「没做成」不能写成一句正常返回值：观察者会记 success，
        // 界面显示「完成」，而实际上一次检索都没发生。
        if (currentUserId == null) {
            throw new IllegalStateException("知识库检索需要登录用户上下文。");
        }
        if (vectorStore == null) {
            throw new IllegalStateException("知识库暂未启用，请稍后再试。");
        }
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(searchTopK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(BuiltinCorpus.ownerFilter(currentUserId))
                    .build();

            List<Document> candidates;
            if (hybridSearchEnabled) {
                List<Document> userDocuments = refreshDocumentCache(currentUserId);
                List<Document> bm25Results = bm25Search(query, userDocuments, searchTopK);
                List<Document> vectorResults = vectorStore.similaritySearch(searchRequest);
                // 词法命中没有向量分数，必须回向量库核对相似度阈值，否则词法通道
                // 会成为绕过阈值的旁路。核对不通过的命中一律丢弃并记账。
                bm25Results = dropUnconfirmedLexicalHits(query, bm25Results, vectorResults, currentUserId);
                candidates = rrfFusion(vectorResults, bm25Results, searchTopK);
            } else {
                candidates = vectorStore.similaritySearch(searchRequest);
            }

            // Restrictive only: never widens the owner-filtered result set.
            // Applied to the fused result so the BM25 cache path cannot bypass it.
            if (sourceTypeRestrictionEnabled) {
                candidates = SourceTypePolicy.restrict(candidates, currentUserId);
            }

            // 证据准入：只接受「整份文档已完成入库」的片段。
            candidates = readyOnly(candidates);

            if (candidates.isEmpty()) {
                return "未找到相关文档。";
            }

            // 先定「最终交给模型的片段」，再从同一份结果登记结构化来源。
            // 侧栏的知识来源必须与模型真正拿到的片段一致：同集合、同顺序、同分数。
            // 早于 rerank 与 finalTopK 登记，会把没交给模型的候选也写成「证据」。
            boolean reranked = rerankEnabled && rerankModel != null;
            List<Hit> hits = reranked ? rerank(query, candidates) : topHits(candidates);
            recordCitations(hits);
            return formatHits(hits);
        } catch (EvidenceContextViolation e) {
            // 证据登记的线程前提被破坏：这不是「检索暂时不可用」，包装成检索故障
            // 会让真正的原因（线程前提）只剩一个 cause。原样抛出。
            throw e;
        } catch (Exception e) {
            log.error("文档检索失败: {}", e.getMessage(), e);
            // 不能把失败包装成一句正常返回值：那样工具生命周期是「成功」，
            // 模型与用户都拿不到「这次检索没做成」的事实。异常交给框架转成
            // 错误结果，观察者据此标 error，页面上如实显示「失败」。
            throw new IllegalStateException("知识库检索暂时不可用，请稍后重试。", e);
        }
    }

    /**
     * 只有 READY 且 indexVersion 与当前发布版本相等的上传片段可以作为证据。
     * 失败清理和 BM25 缓存均可能留下旧代，不能仅凭 documentId 对应行已经 READY 放行。
     * 旧上传无版本不能证明处理完整，须重新处理；不带 documentId 的内置语料保持原契约。
     */
    List<Document> readyOnly(List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        Set<Long> docIds = new HashSet<>();
        for (Document doc : candidates) {
            Long id = documentIdOf(doc);
            if (id != null) docIds.add(id);
        }
        if (docIds.isEmpty()) return candidates;

        Map<Long, String> ready;
        try {
            if (knowledgeDocumentMapper == null) throw new IllegalStateException("文档发布状态不可用");
            ready = knowledgeDocumentMapper.selectBatchIds(docIds).stream()
                    .filter(doc -> Integer.valueOf(1).equals(doc.getStatus()))
                    .filter(doc -> doc.getIndexVersion() != null && !doc.getIndexVersion().isBlank())
                    .collect(Collectors.toMap(KnowledgeDocument::getId, KnowledgeDocument::getIndexVersion));
        } catch (Exception e) {
            log.warn("知识库文档状态校验失败，本轮丢弃带 documentId 的片段: {}", e.getMessage());
            return candidates.stream().filter(doc -> documentIdOf(doc) == null).toList();
        }
        return candidates.stream()
                .filter(doc -> {
                    Long id = documentIdOf(doc);
                    return id == null || ready.containsKey(id)
                            && ready.get(id).equals(doc.getMetadata().get("indexVersion"));
                })
                .toList();
    }

    /** 历史来源沿用实时检索的准入：旧代、失败代、已删除或越权引用不再暴露片段。 */
    public List<ResourceRef> validateResourceRefs(List<ResourceRef> resources, Long currentUserId) {
        if (resources == null || resources.isEmpty()) return resources;
        return resources.stream().filter(ref -> {
            if (!"document".equals(ref.kind()) || !"knowledge-base".equals(ref.source())) return true;
            Long id;
            try {
                id = Long.valueOf(ref.documentId());
            } catch (RuntimeException e) {
                // 非上传的内置语料不使用数据库文档 id。
                return ref.documentId() != null && ref.documentId().startsWith("builtin:");
            }
            if (knowledgeDocumentMapper == null || currentUserId == null || ref.indexVersion() == null) return false;
            try {
                KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
                return doc != null && currentUserId.equals(doc.getUserId())
                        && Integer.valueOf(1).equals(doc.getStatus())
                        && ref.indexVersion().equals(doc.getIndexVersion());
            } catch (Exception e) {
                log.warn("历史文档来源状态不可确认: docId={}", id);
                return false;
            }
        }).toList();
    }

    /** 分片元数据里的 documentId；没有或不是数字时返回 null（不猜、不判死）。 */
    private static Long documentIdOf(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        Object raw = metadata == null ? null : metadata.get("documentId");
        if (raw instanceof Number number) return number.longValue();
        if (raw instanceof String text) {
            try {
                String trimmed = text.trim();
                return trimmed.isEmpty() ? null : Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 最终结果的一条命中。
     *
     * <p>两种分数的语义必须分清：
     * <ul>
     *   <li>{@code score} —— <b>最终排序分</b>。rerank 生效时是重排序分；
     *       否则是文档自带的检索分（向量相似度；BM25-only 的片段可能没有分数）。</li>
     *   <li>{@code retrievalScore} —— <b>原始检索分</b>。只在 rerank 成功且这一条拿到了
     *       重排序分时单独记录；没有 rerank（含回退、或无重排序分）时它与 {@code score}
     *       同源，置 null，避免制造「两个不同分数」的错觉。</li>
     * </ul>
     * {@code reranked} 表示这一条的 {@code score} 是否真的来自重排序模型——回退路径
     * 即使走的是 rerank 开关打开的分支，也必须是 false，不把检索分说成重排序分。
     */
    private record Hit(Document document, Double score, Double retrievalScore, boolean reranked) {
    }

    /** 没有 rerank 时的最终结果：前 finalTopK 条，分数即检索分。 */
    private List<Hit> topHits(List<Document> candidates) {
        return candidates.stream()
                .limit(finalTopK)
                .map(doc -> new Hit(doc, doc.getScore(), null, false))
                .toList();
    }

    /**
     * 重排序后的最终结果。失败或空响应都回退到原始检索：
     * 回退只影响排序质量，不影响「这次工具调用是成功的」——但回退后的分数
     * 必须如实标成检索分，不冒充重排序分。
     */
    private List<Hit> rerank(String query, List<Document> candidates) {
        try {
            RerankResponse response = rerankModel.call(new RerankRequest(query, candidates));
            List<Hit> hits = response == null || response.getResults() == null
                    ? List.of()
                    : response.getResults().stream()
                            .filter(result -> result.getOutput() != null)
                            .limit(finalTopK)
                            .map(result -> {
                                Document doc = result.getOutput();
                                Double rerankScore = result.getScore();
                                // rerank 没给分时 score 回退成检索分；这时原始检索分不再单独记录，
                                // 否则同一个数字会被展示成两个「不一样的分数」。
                                return new Hit(doc,
                                        rerankScore != null ? rerankScore : doc.getScore(),
                                        rerankScore != null ? retrievalScoreOf(candidates, doc) : null,
                                        rerankScore != null);
                            })
                            .toList();
            if (hits.isEmpty()) {
                log.warn("Rerank 返回空结果，使用原始检索结果");
                return topHits(candidates);
            }
            return hits;
        } catch (Exception e) {
            log.warn("Rerank 失败，回退到原始检索: {}", e.getMessage());
            return topHits(candidates);
        }
    }

    /** 在原始候选里找同一条片段，取它进 rerank 之前的分数。 */
    private static Double retrievalScoreOf(List<Document> candidates, Document target) {
        if (target == null) {
            return null;
        }
        for (Document candidate : candidates) {
            if (candidate == target) {
                return candidate.getScore();
            }
        }
        for (Document candidate : candidates) {
            if (Objects.equals(candidate.getId(), target.getId())
                    && Objects.equals(candidate.getText(), target.getText())) {
                return candidate.getScore();
            }
        }
        return null;
    }

    /** 把最终交给模型的片段登记为会话资源（同 id 去重，由上下文负责）。 */
    private void recordCitations(List<Hit> hits) {
        for (Hit hit : hits) {
            Document doc = hit.document();
            if (doc == null) {
                continue;
            }
            String docId = doc.getMetadata().get("docId") == null
                    ? null : String.valueOf(doc.getMetadata().get("docId"));
            if (docId == null || docId.isBlank()) {
                // 没有稳定文档标识的片段不能当「可核验来源」展示。
                continue;
            }
            String title = doc.getMetadata().get("title") == null
                    ? snippetOf(doc.getText()) : String.valueOf(doc.getMetadata().get("title"));
            ToolResourceContext.record(ResourceRef.documentChunk(
                    docId, doc.getId(), chunkIndexOf(doc), title,
                    hit.score(), hit.retrievalScore(), snippetOf(doc.getText()), null)
                    .withIndexVersion((String) doc.getMetadata().get("indexVersion")));
        }
    }

    /**
     * 片段序号：优先读元数据，缺失时从稳定的分块 id 末尾解析。
     *
     * <p>两种分块 id 都由服务端生成且以序号结尾：
     * 上传文档 {@code doc_<docId>_<indexVersion>_chunk_<i>}、
     * 内置语料 {@code builtin_<slug>_chunk_<i>}。已入索引的旧数据没有
     * {@code chunkIndex} 元数据，不能为此要求重灌；id 是同一事实的另一份来源。
     * 两处都拿不到就返回 {@code null}——宁可不给片段粒度，也不猜一个序号。
     */
    static Integer chunkIndexOf(Document doc) {
        if (doc == null) {
            return null;
        }
        Object fromMetadata = doc.getMetadata().get("chunkIndex");
        if (fromMetadata instanceof Number number) {
            return number.intValue();
        }
        if (fromMetadata != null) {
            try {
                return Integer.valueOf(String.valueOf(fromMetadata).trim());
            } catch (NumberFormatException ignored) {
                // 元数据不是数字时不据此下结论，继续按 id 解析。
            }
        }
        String id = doc.getId();
        if (id == null) {
            return null;
        }
        Matcher matcher = CHUNK_SUFFIX.matcher(id);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static final Pattern CHUNK_SUFFIX = Pattern.compile("_chunk_(\\d+)$");

    private static String snippetOf(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip().replaceAll("\\s+", " ");
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    /**
     * 与模型看到的文本保持一致：同一份 hits、同一顺序。
     *
     * <p>只在分数<b>确实来自重排序</b>时才印「相关度」——回退路径的检索分不往回印，
     * 免得模型把检索分当成「重排序也认可」。
     */
    private String formatHits(List<Hit> hits) {
        return hits.stream()
                .map(hit -> {
                    Document doc = hit.document();
                    StringBuilder sb = new StringBuilder("【文档片段】").append(doc.getText());
                    Object docId = doc.getMetadata().get("docId");
                    if (docId != null) {
                        sb.append("\n(文档ID: ").append(docId).append(")");
                    }
                    // 可引用句柄：只有它能变成正文角标。文档级 ID 不唯一到片段，
                    // 同一文档命中两段时模型无法说清引的是哪一段。
                    Integer chunkIndex = chunkIndexOf(doc);
                    if (docId != null && chunkIndex != null) {
                        sb.append("\n(证据: ").append(docId).append("#").append(chunkIndex).append(")");
                    }
                    if (hit.reranked() && hit.score() != null && hit.score() > 0) {
                        sb.append(String.format("\n(相关度: %.2f)", hit.score()));
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @PostConstruct
    public void init() {
        log.info("DocumentSearchTool initialized, hybrid-search: {}, rerank: {}",
                hybridSearchEnabled, rerankEnabled);
        if (hybridSearchEnabled) {
            // 说清楚这条通道到底是什么：词法语料按 owner 元数据从向量库直取，
            // 是该用户可见的全部分块（不是语义近邻的子集）；命中后仍要回向量库
            // 核对相似度阈值，核对不通过的一律丢弃并记账。
            log.warn("混合检索已开启：词法通道按 owner 元数据直取全量分块做 BM25，"
                    + "语料上限 {} 分块（触顶会记 WARN，召回随之不完整）；"
                    + "词法命中需回向量库核对相似度阈值，未通过的会被丢弃。",
                    maxCorpusChunks);
        }
    }

    private synchronized List<Document> refreshDocumentCache(Long userId) {
        UserDocumentCache cached = documentCaches.get(userId);
        if (cached != null && System.currentTimeMillis() - cached.refreshedAt() < CACHE_REFRESH_INTERVAL) {
            return cached.documents();
        }
        List<Document> documents = List.of();
        if (lexicalCorpusService != null && lexicalCorpusService.available()) {
            // 按 owner 元数据直取全量分块：这是真正的词法语料，不再是
            // 「语义近邻前 1000 条」那种语义偏置的子集。取不到就退化为空列表，
            // 词法通道本轮没有候选——而不是拿一份不完整的语料假装完整。
            documents = lexicalCorpusService.loadOwnerCorpus(userId, maxCorpusChunks);
        }
        documentCaches.put(userId, new UserDocumentCache(documents, System.currentTimeMillis()));
        log.debug("Document cache refreshed for user {}, size: {}", userId, documents.size());
        return documents;
    }

    /**
     * 词法命中的相似度阈值核对。
     *
     * <p>词法通道命中的是「词面相似」，本身没有向量分数；旧的实现直接把它并进
     * 证据，等于给 {@code similarity-threshold} 开了一个旁路：一条只有词面沾边、
     * 语义上并不相关的片段也能成为证据。现在每条词法命中都要回向量库核对——
     * 已在向量候选里的（自然带分数）直接放行，其余按文档分组做一次带过滤的
     * 向量检索，只有同样通过阈值的才留下。核对不通过的丢弃并记账。
     *
     * <p>核对失败（向量库异常）时按不通过处理：阈值是安全边界，宁可少召回。
     */
    List<Document> dropUnconfirmedLexicalHits(String query, List<Document> lexicalHits,
                                              List<Document> vectorResults, Long currentUserId) {
        if (lexicalHits == null || lexicalHits.isEmpty()) {
            return List.of();
        }
        Set<String> vectorIds = new HashSet<>();
        if (vectorResults != null) {
            for (Document doc : vectorResults) {
                if (doc != null && doc.getId() != null) {
                    vectorIds.add(doc.getId());
                }
            }
        }
        List<Document> lexicalOnly = lexicalHits.stream()
                .filter(doc -> doc != null && doc.getId() != null && !vectorIds.contains(doc.getId()))
                .toList();
        if (lexicalOnly.isEmpty()) {
            return lexicalHits;
        }

        Map<Long, List<Document>> byDocument = new LinkedHashMap<>();
        List<Document> withoutDocument = new ArrayList<>();
        for (Document doc : lexicalOnly) {
            Long documentId = documentIdOf(doc);
            if (documentId == null) {
                withoutDocument.add(doc);
            } else {
                byDocument.computeIfAbsent(documentId, key -> new ArrayList<>()).add(doc);
            }
        }

        Set<String> confirmed = new HashSet<>();
        for (Map.Entry<Long, List<Document>> entry : byDocument.entrySet()) {
            List<Document> group = entry.getValue();
            confirmed.addAll(confirmByVectorStore(query, group, ownerOf(group), currentUserId));
        }
        if (!withoutDocument.isEmpty()) {
            // 内置语料片段没有 documentId：按 owner 口径整体核对一次。
            confirmed.addAll(confirmByVectorStore(query, withoutDocument, null, currentUserId));
        }

        List<Document> kept = lexicalHits.stream()
                .filter(doc -> doc == null || doc.getId() == null || confirmed.contains(doc.getId()))
                .toList();
        int dropped = lexicalHits.size() - kept.size();
        if (dropped > 0) {
            log.warn("词法命中未通过相似度阈值核对，已丢弃 {} 条（阈值旁路已拦截）", dropped);
        }
        return kept;
    }

    /**
     * 带过滤的向量检索，确认这批词法命中在语义上确实达到阈值。
     *
     * @param owner 命中片段的 owner；为 null 时按当前用户 + 内置语料核对
     */
    private Set<String> confirmByVectorStore(String query, List<Document> group, Long owner,
                                             Long currentUserId) {
        if (vectorStore == null || group.isEmpty()) {
            return Set.of();
        }
        // 与 purgeVectors 同一套 Filter 写法：值直接用 Long，不走文本表达式
        // （文本解析器把数字按 Integer 解析，雪花 ID 会直接抛 NumberFormatException）。
        Filter.Expression ownerExpression = owner == null
                ? BuiltinCorpus.ownerFilter(currentUserId)
                : new Filter.Expression(Filter.ExpressionType.EQ,
                        new Filter.Key("ownerUserId"), new Filter.Value(owner));
        List<Document> groupList = group;
        Long documentId = groupList.isEmpty() ? null : documentIdOf(groupList.get(0));
        Filter.Expression expression = documentId == null
                ? ownerExpression
                : new Filter.Expression(Filter.ExpressionType.AND,
                        ownerExpression,
                        new Filter.Expression(Filter.ExpressionType.EQ,
                                new Filter.Key("documentId"), new Filter.Value(documentId)));
        try {
            int topK = Math.min(100, Math.max(searchTopK, groupList.size() + searchTopK));
            List<Document> confirmed = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(expression)
                    .build());
            if (confirmed == null || confirmed.isEmpty()) {
                return Set.of();
            }
            return confirmed.stream()
                    .filter(doc -> doc != null && doc.getId() != null)
                    .map(Document::getId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            // 核对失败按不通过处理：阈值是安全边界。
            log.warn("词法命中的向量核对失败，本批按未通过处理: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 命中片段的 owner；取不到就按当前用户核对。 */
    private Long ownerOf(List<Document> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }
        Object owner = group.get(0).getMetadata().get("ownerUserId");
        if (owner instanceof Number number) {
            return number.longValue();
        }
        if (owner instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<Document> bm25Search(String query, List<Document> documents, int topK) {
        if (documents.isEmpty()) {
            return List.of();
        }
        try {
            String[] tokenizedQuery = segmenter.tokenize(query).toArray(new String[0]);
            List<String[]> corpus = documents.stream()
                    .map(doc -> segmenter.tokenize(doc.getText()).toArray(new String[0]))
                    .toList();

            // Custom BM25 implementation
            double[] scores = bm25Scores(tokenizedQuery, corpus);

            List<Map.Entry<Integer, Double>> scoredDocs = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] > 0) {
                    scoredDocs.add(Map.entry(i, scores[i]));
                }
            }
            scoredDocs.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            return scoredDocs.stream()
                    .limit(topK)
                    .map(entry -> documents.get(entry.getKey()))
                    .toList();
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private double[] bm25Scores(String[] query, List<String[]> corpus) {
        double k1 = 1.5;
        double b = 0.75;
        int docCount = corpus.size();
        double[] scores = new double[docCount];

        // Calculate average document length
        double avgDocLen = corpus.stream().mapToInt(doc -> doc.length).average().orElse(1);

        // Calculate document frequencies for query terms
        Map<String, Integer> docFreq = new HashMap<>();
        for (String[] doc : corpus) {
            Set<String> uniqueTerms = new HashSet<>(Arrays.asList(doc));
            for (String term : query) {
                if (uniqueTerms.contains(term)) {
                    docFreq.merge(term, 1, Integer::sum);
                }
            }
        }

        // Calculate BM25 score for each document
        for (int i = 0; i < docCount; i++) {
            String[] doc = corpus.get(i);
            double docLen = doc.length;
            double score = 0.0;

            // Pre-compute term frequencies for this document
            Map<String, Integer> termFreq = new HashMap<>();
            for (String term : doc) {
                termFreq.merge(term, 1, Integer::sum);
            }

            for (String term : query) {
                Integer tf = termFreq.get(term);
                if (tf == null || tf == 0) continue;

                int df = docFreq.getOrDefault(term, 1);
                double idf = Math.log((docCount - df + 0.5) / (df + 0.5) + 1);

                double tfComponent = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLen / avgDocLen));
                score += idf * tfComponent;
            }
            scores[i] = score;
        }
        return scores;
    }

    List<Document> rrfFusion(List<Document> vectorResults, List<Document> bm25Results, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // 修复：每组结果的 rank 必须独立从 1 开始，避免 BM25 因 vectorResults 长度而被压低分数。
        for (List<Document> results : List.of(vectorResults, bm25Results)) {
            int rank = 1;
            for (Document doc : results) {
                String id = doc.getId();
                docMap.putIfAbsent(id, doc);
                rrfScores.merge(id, 1.0 / (rrfK + rank), Double::sum);
                rank++;
            }
        }

        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(entry -> docMap.get(entry.getKey()))
                .toList();
    }

    private record UserDocumentCache(List<Document> documents, long refreshedAt) {
    }
}
