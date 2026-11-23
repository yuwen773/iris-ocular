package com.csust.eyediagnosis.upload.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code public/} 前缀的孤儿对象审计定时任务（只读）。
 *
 * <p>默认开启，一天一次：判读原图与头像所在的前缀过去没有任何对账入口，
 * 出了孤儿对象只能靠人凭记忆翻 COS 控制台。这里不改这个现状的删除策略——
 * 删除仍然是人工决定——只保证「无引用的对象会被列出来」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eye-diagnosis.upload.orphan-audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PublicImageOrphanAuditScheduler {

    private final PublicImageOrphanAuditor auditor;

    @Scheduled(
            fixedDelayString = "${eye-diagnosis.upload.orphan-audit.interval-ms:86400000}",
            initialDelayString = "${eye-diagnosis.upload.orphan-audit.initial-delay-ms:1800000}")
    public void audit() {
        try {
            auditor.audit();
        } catch (Exception e) {
            // 定时任务不能因为单次失败而中断调度
            log.error("判读影像孤儿对象审计失败", e);
        }
    }
}
