package com.csust.eyediagnosis.upload.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上传补偿定时任务：周期性地把 COS 上的孤儿图片清理掉。
 *
 * <p>默认开启（{@code eye-diagnosis.upload.compensation.enabled}，缺省视为 true）。
 * 显式设为 {@code false} 可关闭，但那样「对象已写入、任务行没写成」的残留就只能靠人工排查，
 * {@link UploadCompensationService} 会在启动时打出警告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eye-diagnosis.upload.compensation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class UploadCompensationScheduler {

    private final UploadCompensationService compensationService;

    @Scheduled(
            fixedDelayString = "${eye-diagnosis.upload.compensation.interval-ms:3600000}",
            initialDelayString = "${eye-diagnosis.upload.compensation.initial-delay-ms:600000}")
    public void reconcile() {
        try {
            int removed = compensationService.reconcileAll();
            if (removed > 0) {
                log.info("上传补偿对账完成，清理孤儿对象 {} 个", removed);
            } else {
                log.debug("上传补偿对账完成，无需清理");
            }
        } catch (Exception e) {
            // 定时任务不能因为单次失败而中断调度
            log.error("上传补偿对账失败", e);
        }
    }
}
