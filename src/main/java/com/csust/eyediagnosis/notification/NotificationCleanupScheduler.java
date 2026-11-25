package com.csust.eyediagnosis.notification;

import com.csust.eyediagnosis.config.NotificationSettings;
import com.csust.eyediagnosis.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 通知保留期清理：物理删除「已读且超过保留期」的行。
 *
 * <p>为什么单独一个包：仓库里 {@code @Scheduled} 的 worker 都住在自己领域的包里
 * （{@code diagnosistask} / {@code patientdraft} / {@code upload.state}），
 * 一个「通知的定时动作」既不属于 upload 也不属于 feedback。这里只放这一个类，
 * 业务逻辑仍全部在 {@link NotificationService}。
 *
 * <p>只删已读：未读消息是用户还没看到的东西，不能因为时间到了就消失。
 * 分批删除并在本方法内循环，避免一条 DELETE 扫全表把库锁住。
 * 多实例同时跑是幂等的（删除条件相同），重复执行只会得到 0 行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    /** 单批删除上限：够小到不锁表，够大到一次日常清理能收敛。 */
    private static final int BATCH = 1000;
    /** 单次调度的最大批次数：防止异常积压时把这一轮拖成小时级任务。 */
    private static final int MAX_BATCHES = 20;

    private final NotificationService notifications;
    private final NotificationSettings settings;

    /** 每天 03:40（低峰，避开 03:00 的备份窗口）。 */
    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        int retentionDays = settings.getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        Date before = Date.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int removed = notifications.purgeRead(before, BATCH);
            total += removed;
            if (removed < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("清理已读通知 {} 条（保留 {} 天）", total, retentionDays);
        }
    }
}
