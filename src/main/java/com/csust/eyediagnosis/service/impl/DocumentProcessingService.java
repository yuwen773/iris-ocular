package com.csust.eyediagnosis.service.impl;

import com.csust.eyediagnosis.agent.rag.DocumentParser;
import com.csust.eyediagnosis.agent.rag.EmbeddingBatchWriter;
import com.csust.eyediagnosis.agent.rag.SourceTypePolicy;
import com.csust.eyediagnosis.agent.rag.TextChunkerService;
import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class DocumentProcessingService {

    @Resource
    private DocumentParser documentParser;

    @Resource
    private TextChunkerService textChunkerService;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Resource
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    /** 候选作废时清理对象存储；测试未注入时退化为只记日志。 */
    @Autowired(required = false)
    private com.csust.eyediagnosis.manager.CosManager cosManager;

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public String computeContentHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(Files.readAllBytes(file.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("计算文件哈希失败", e);
        }
    }

    @Async("documentProcessingExecutor")
    public void processDocument(Long docId, File file, String fileType, String title) {
        String version = UUID.randomUUID().toString();
        if (knowledgeDocumentMapper.beginIndex(docId, version) != 1) {
            if (file != null) file.delete();
            return;
        }
        processDocument(docId, file, fileType, title, version);
    }

    @Async("documentProcessingExecutor")
    public void processDocument(Long docId, File file, String fileType, String title, String version) {
        runEtl(docId, file, fileType, title, version, false);
    }

    /**
     * 处理候选索引版本。旧版本在 {@link #promoteCandidate} 成功之前保持 READY 且可检索；
     * 候选失败只清理候选自己的分块，不影响活动版本。
     *
     * <p>{@code metadata} 是新文件的元数据：它在发布成功时才写进文档行。候选失败或
     * 被更新的候选取代时，文档行仍描述正在被检索的那份旧文件，同时这次上传的对象
     * 会被删掉，不留下「行里写着新文件、索引里是旧内容」的错位。
     */
    @Async("documentProcessingExecutor")
    public void processDocumentCandidate(Long docId, File file, String fileType, String title, String version,
                                         CandidateMetadata metadata) {
        if (knowledgeDocumentMapper.beginCandidate(docId, version) != 1) {
            discardCandidateObject(metadata);
            if (file != null) file.delete();
            return;
        }
        runEtl(docId, file, fileType, title, version, true, metadata);
    }

    /**
     * 候选版本发布时随发布指针一起落库的文件元数据。
     *
     * @param fileUrl         候选版本自己的对象键（发布后成为文档的 fileUrl）
     * @param previousFileUrl 发布前文档指向的旧对象键；切换成功后它没有任何引用，
     *                        属于应该清掉的旧版本对象
     */
    public record CandidateMetadata(String fileName, String fileUrl, Long fileSize, String fileType,
                                    String title, String previousFileUrl) { }

    private void runEtl(Long docId, File file, String fileType, String title, String version, boolean candidate) {
        runEtl(docId, file, fileType, title, version, candidate, null);
    }

    private void runEtl(Long docId, File file, String fileType, String title, String version, boolean candidate,
                        CandidateMetadata candidateMetadata) {
        log.info("[ETL] 开始处理文档: docId={}, file={}, fileType={}, title={}, candidate={}",
                docId, file.getAbsolutePath(), fileType, title, candidate);
        long startTime = System.currentTimeMillis();
        try {
            KnowledgeDocument knowledgeDocument = knowledgeDocumentMapper.selectById(docId);
            String expectedVersion = candidate
                    ? (knowledgeDocument == null ? null : knowledgeDocument.getPendingIndexVersion())
                    : (knowledgeDocument == null ? null : knowledgeDocument.getIndexVersion());
            if (knowledgeDocument == null || !version.equals(expectedVersion)) {
                // 行已删或被更新的候选取代：本次上传的对象没有任何引用，跟着作废。
                if (candidate) discardCandidateObject(candidateMetadata);
                return;
            }
            // ========== 1. Extract ==========
            String fileUrl = file.getAbsolutePath();
            log.info("[ETL-Extract] 开始解析文档: fileUrl={}", fileUrl);
            long extractStart = System.currentTimeMillis();
            List<String> paragraphs = documentParser.parse(fileUrl, fileType);
            log.info("[ETL-Extract] 文档解析完成: docId={}, 段落数={}, 耗时={}ms", docId, paragraphs.size(), System.currentTimeMillis() - extractStart);
            if (paragraphs.isEmpty()) throw new RuntimeException("文档内容为空");

            // ========== 2. Transform ==========
            log.info("[ETL-Transform] 开始合并文本并构建 metadata");
            long transformStart = System.currentTimeMillis();
            String rawText = paragraphs.stream().reduce("", (a, b) -> a + "\n\n" + b).trim();
            log.info("[ETL-Transform] 文本合并完成: docId={}, 原始文本长度={}字符", docId, rawText.length());

            // 查询文档的 owner（上传用户 id），用于写入向量元数据实现检索隔离
            // 构建 metadata（含可信 ownerUserId / documentId）
            Map<String, Object> metadata = documentParser.buildMetadata(rawText, fileUrl, title, fileType);
            metadata.put("docId", docId.toString());
            metadata.put("documentId", docId);
            metadata.put("ownerUserId", knowledgeDocument.getUserId());
            metadata.put("indexVersion", version);
            // Server-derived source marker. Never accepts a client-supplied value.
            metadata.put(SourceTypePolicy.METADATA_KEY, SourceTypePolicy.SourceType.USER_UPLOAD.wire());
            log.info("[ETL-Transform] Metadata 构建完成: docId={}, metadata={}", docId, metadata);

            // 文本分块
            log.info("[ETL-Transform] 开始文本分块: docId={}, 策略=512词滑动窗口", docId);
            List<Document> chunks = textChunkerService.chunk(rawText, metadata);
            log.info("[ETL-Transform] 文本分块完成: docId={}, chunk数量={}, 耗时={}ms", docId, chunks.size(), System.currentTimeMillis() - transformStart);

            // 为每个 chunk 设置唯一 ID 并确保 metadata 包含 ownerUserId。
            // 这里构建新列表而不是原地 set()：chunk() 的返回值可能是不可变列表
            // （List.of / stream().toList()），原地修改会抛 UnsupportedOperationException，
            // 再被下面的 catch 吞成 error=null，整份文档静默处理失败。
            List<Document> ownedChunks = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                String chunkId = "doc_" + docId + "_" + version + "_chunk_" + i;
                Map<String, Object> chunkMetadata = new HashMap<>(chunk.getMetadata());
                chunkMetadata.put("ownerUserId", knowledgeDocument.getUserId());
                chunkMetadata.put("documentId", docId);
                chunkMetadata.put("indexVersion", version);
                // 片段序号显式落元数据：正文引用的证据键是 documentId#chunkIndex，
                // 检索侧不必再从 id 字符串反解（id 解析只作为旧数据的兜底）。
                chunkMetadata.put("chunkIndex", i);
                // Re-stamp on every rebuilt chunk so no chunk can miss the marker.
                chunkMetadata.put(SourceTypePolicy.METADATA_KEY,
                        SourceTypePolicy.SourceType.USER_UPLOAD.wire());
                ownedChunks.add(new Document(chunkId, chunk.getText(), chunkMetadata));
                log.debug("[ETL-Transform] Chunk[{}]: id={}, text长度={}", i, chunkId, chunk.getText().length());
            }
            chunks = ownedChunks;

            // ========== 3. Load ==========
            log.info("[ETL-Load] 开始存储到向量数据库: docId={}, chunk数量={}", docId, chunks.size());
            long loadStart = System.currentTimeMillis();
            if (vectorStore != null) {
                EmbeddingBatchWriter.add(vectorStore, chunks);
                log.info("[ETL-Load] 向量存储完成: docId={}, 耗时={}ms", docId, System.currentTimeMillis() - loadStart);
            } else {
                throw new IllegalStateException("VectorStore 未配置");
            }

            if (candidate) {
                // 原子切换发布指针；只有仍持有候选登记的任务才能切换。
                int promoted = knowledgeDocumentMapper.promoteCandidate(docId, version, chunks.size(),
                        candidateMetadata == null ? null : candidateMetadata.fileName(),
                        candidateMetadata == null ? null : candidateMetadata.fileUrl(),
                        candidateMetadata == null ? null : candidateMetadata.fileSize(),
                        candidateMetadata == null ? null : candidateMetadata.fileType(),
                        candidateMetadata == null ? null : candidateMetadata.title());
                if (promoted == 1) {
                    purgeStaleVersions(docId, version);
                    // 切换成功后旧对象既没有行引用、也没有向量引用，留着就是孤儿。
                    // 放在发布成功之后清：候选失败绝不碰旧对象。
                    deleteSupersededObject(candidateMetadata);
                } else {
                    // 已被更新的候选取代：本次候选作废，活动版本保持不变，对象也不再被引用。
                    purgeVectors(docId, version);
                    discardCandidateObject(candidateMetadata);
                }
            } else {
                knowledgeDocumentMapper.finishIndex(docId, version, 1, chunks.size());
            }
            log.info("[ETL] 文档处理完成: docId={}, chunkCount={}, candidate={}, 总耗时={}ms",
                    docId, chunks.size(), candidate, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("[ETL] 文档处理失败: docId={}, candidate={}, 耗时={}ms, error={}",
                    docId, candidate, System.currentTimeMillis() - startTime, e.getMessage(), e);
            // 只清理本次失败代；迟到任务不能删除或覆盖已经发布的新版本。
            purgeVectors(docId, version);
            if (candidate) {
                knowledgeDocumentMapper.clearCandidate(docId, version);
                discardCandidateObject(candidateMetadata);
            } else {
                knowledgeDocumentMapper.finishIndex(docId, version, 2, null);
            }
        } finally {
            if (file != null && file.exists()) {
                if (!file.delete()) {
                    log.warn("[ETL] 临时文件删除失败: {}", file.getAbsolutePath());
                } else {
                    log.debug("[ETL] 临时文件已删除: {}", file.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 候选作废时删掉这次上传的对象。
     *
     * <p>发布前文档行的 fileUrl 仍指向旧文件，新对象没有任何行引用；不删就是对象
     * 存储里的孤儿。清理失败只记日志——索引侧已经一致，不值得为一次清理失败回滚。
     */
    private void discardCandidateObject(CandidateMetadata metadata) {
        if (metadata == null) {
            return;
        }
        deleteObjectQuietly(metadata.fileUrl(), "候选对象");
    }

    /**
     * 发布切换后清掉被取代的旧对象。
     *
     * <p>旧对象在切换成功的那一刻起就没有任何行或向量引用它了；不清就是对象存储里的
     * 长期孤儿（每次编辑文档多一份）。清理失败只记日志：索引侧已经一致，不值得为一次
     * 清理失败回滚一次已经生效的发布。
     */
    private void deleteSupersededObject(CandidateMetadata metadata) {
        if (metadata == null) {
            return;
        }
        deleteObjectQuietly(metadata.previousFileUrl(), "旧版本对象");
    }

    private void deleteObjectQuietly(String fileUrl, String what) {
        if (fileUrl == null) {
            return;
        }
        if (cosManager == null) {
            log.warn("[ETL] 未配置 CosManager，{}未清理: key={}", what, fileUrl);
            return;
        }
        try {
            cosManager.deleteObject(fileUrl);
        } catch (Exception e) {
            log.warn("[ETL] {}清理失败，可能残留孤儿对象: key={}", what, fileUrl, e);
        }
    }

    /** 清掉某个文档某一代的向量分块（调度器回收超时候选版本时用）。 */
    public void purgeIndexVersion(Long docId, String version) {
        purgeVectors(docId, version);
    }

    /**
     * 按 documentId 和 indexVersion 删除本次失败处理的分块。
     *
     * <p>不能靠 {@code chunkCount}：失败文档恰恰没有写这个字段，用计数拼 chunkId
     * 会一个也删不掉。这里按元数据删除，重试一次；仍失败也不抛——正确性由
     * 检索侧的「就绪且版本匹配」校验保证，这里的清理只是为了不留垃圾。
     */
    void purgeVectors(Long docId, String version) {
        if (vectorStore == null || docId == null) return;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                vectorStore.delete(new Filter.Expression(
                        Filter.ExpressionType.AND,
                        new Filter.Expression(Filter.ExpressionType.EQ,
                                new Filter.Key("documentId"), new Filter.Value(docId)),
                        new Filter.Expression(Filter.ExpressionType.EQ,
                                new Filter.Key("indexVersion"), new Filter.Value(version))));
                log.info("[ETL] 已清理文档残留向量: docId={}, attempt={}", docId, attempt);
                return;
            } catch (Exception e) {
                log.warn("[ETL] 残留向量清理失败（第 {} 次）: docId={}", attempt, docId, e.getMessage());
            }
        }
    }

    /** 候选版本发布后清理旧代分块；失败只留垃圾，不影响检索正确性。 */
    private void purgeStaleVersions(Long docId, String activeVersion) {
        if (vectorStore == null || docId == null || activeVersion == null) return;
        try {
            vectorStore.delete(new Filter.Expression(
                    Filter.ExpressionType.AND,
                    new Filter.Expression(Filter.ExpressionType.EQ,
                            new Filter.Key("documentId"), new Filter.Value(docId)),
                    new Filter.Expression(Filter.ExpressionType.NE,
                            new Filter.Key("indexVersion"), new Filter.Value(activeVersion))));
            log.info("[ETL] 已清理旧索引版本: docId={}, activeVersion={}", docId, activeVersion);
        } catch (Exception e) {
            log.warn("[ETL] 旧索引版本清理失败: docId={}, error={}", docId, e.getMessage());
        }
    }

}
