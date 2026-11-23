package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import com.csust.eyediagnosis.service.impl.DocumentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识索引的持久恢复：把 ETL 没能收口的记录收口成明确终态。
 *
 * <p>ETL 跑在进程内的线程池里，文档状态却写在数据库里。进程重启、容器被踢掉、
 * 队列任务丢失之后，{@code status=0}（处理中）与 {@code pendingIndexVersion}
 * 都没有任何东西会再推进：前者让管理端永远转圈，后者让「再更新一次」永远被当成
 * 已有候选而作废。两种残留都由这里按「超过阈值仍未完成」收口。
 *
 * <p>收口只改数据库记录，不重跑 ETL：源文件还在 COS 上，用户重新上传/更新即可；
 * 这里保证的是「不留悬空状态」，而不是替用户重试。阈值取得保守也有补偿作用：
 * 真有超过阈值的超长任务，它跑完时的 {@code finishIndex} 仍按 {@code indexVersion}
 * 命中同一行并置回 READY，所以误判是可自愈的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eye-diagnosis.rag.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexRecoveryScheduler {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final DocumentProcessingService documentProcessingService;

    /**
     * 判定「卡住」的阈值（分钟）。默认 60 分钟：大 PDF 的解析 + 向量化在慢机器上
     * 可能要十几分钟，阈值取得太短会把正常任务误判成失败。
     */
    @Value("${eye-diagnosis.rag.recovery.stale-minutes:60}")
    private int staleMinutes = 60;

    @Scheduled(
            fixedDelayString = "${eye-diagnosis.rag.recovery.interval-ms:600000}",
            initialDelayString = "${eye-diagnosis.rag.recovery.initial-delay-ms:120000}")
    public void recover() {
        try {
            int failed = knowledgeDocumentMapper.failStuckIndexing(staleMinutes);
            int cleared = clearStaleCandidates();
            if (failed > 0 || cleared > 0) {
                log.warn("知识索引恢复：{} 份卡在「处理中」的文档置为失败，{} 个超时候选版本已清除（阈值 {} 分钟）",
                        failed, cleared, staleMinutes);
            }
        } catch (Exception e) {
            // 定时任务不能因为单次失败中断调度
            log.error("知识索引恢复扫描失败", e);
        }
    }

    /**
     * 清掉超时候选：先清登记指针，再按 (docId, 候选版本) 删掉这次候选写进向量库的分块。
     * 只清指针会把分块永久留在向量库里——它们既不参与检索（版本不等），也不会再被覆盖。
     */
    private int clearStaleCandidates() {
        int cleared = 0;
        for (KnowledgeDocument stale : knowledgeDocumentMapper.selectStaleCandidates(staleMinutes)) {
            String version = stale.getPendingIndexVersion();
            if (knowledgeDocumentMapper.clearCandidate(stale.getId(), version) != 1) {
                continue;
            }
            documentProcessingService.purgeIndexVersion(stale.getId(), version);
            cleared++;
        }
        return cleared;
    }
}
