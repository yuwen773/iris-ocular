package com.csust.eyediagnosis.newsworkbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-07G：阻塞原因的口径单测（不需要数据库）。
 *
 * <p>规则：先说本窗口自己的问题，再说被谁挡住；两处都没有原因时不给理由，
 * 免得台账里出现一堆「无」的噪声。
 */
class NewsWorkbenchBlockingReasonTest {

    @Test
    void failedWindowStatesThatItMustBeReopenedAndNotSkipped() {
        String reason = NewsWorkbenchService.describeBlockingReason(
                "FAILED", "SOURCE_TIMEOUT", null, null);

        assertTrue(reason.contains("SOURCE_TIMEOUT"), reason);
        assertTrue(reason.contains("失败窗口重试入口"), reason);
        assertTrue(reason.contains("不会自动跳过"), reason);
    }

    @Test
    void blockedWindowNamesTheEarlierTask() {
        String reason = NewsWorkbenchService.describeBlockingReason(
                "PENDING", null, "task-early-1", "FAILED");

        assertTrue(reason.contains("task-early-1"), reason);
        assertTrue(reason.contains("FAILED"), reason);
        assertTrue(reason.contains("需先处理该窗口"), reason);
    }

    @Test
    void failedAndBlockedAreBothStated() {
        String reason = NewsWorkbenchService.describeBlockingReason(
                "FAILED", "SOURCE_TIMEOUT", "task-early-1", "FAILED");

        assertTrue(reason.contains("SOURCE_TIMEOUT"), reason);
        assertTrue(reason.contains("task-early-1"), reason);
    }

    @Test
    void healthyTaskHasNoBlockingReason() {
        assertNull(NewsWorkbenchService.describeBlockingReason("PENDING", null, null, null));
        assertNull(NewsWorkbenchService.describeBlockingReason("LEASED", null, null, null));
        assertNull(NewsWorkbenchService.describeBlockingReason("COMPLETED", null, null, null));
    }
}
