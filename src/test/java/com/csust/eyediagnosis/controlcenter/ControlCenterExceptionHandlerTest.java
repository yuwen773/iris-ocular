package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发失败语义（WP-01D）。
 *
 * <p>锁等待超时与死锁必须变成可重试的 40906，而不是 50000「系统错误」。这一层
 * 只看「异常类型 → 错误码」，真正的触发证据在
 * {@code ControlCenterMultiProcessTest#aLockWaitTimeoutLeavesNoPartialPublish}
 * （子进程会话把 {@code innodb_lock_wait_timeout} 压到 2 秒，父进程持锁）。
 */
class ControlCenterExceptionHandlerTest {

    private final ControlCenterExceptionHandler handler = new ControlCenterExceptionHandler();

    @Test
    void lockWaitTimeoutBecomesARetryableConflict() {
        BaseResponse<?> response = handler.handleLockConflict(
                new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction"));

        assertEquals(ErrorCode.CONFIG_CONCURRENT_CONFLICT.getCode(), response.getCode());
        assertEquals(40906, response.getCode());
        assertTrue(response.getMsg().contains("重试"), "提示必须告诉客户端可以重试：" + response.getMsg());
    }

    @Test
    void deadlockBecomesTheSameRetryableConflict() {
        BaseResponse<?> response = handler.handleLockConflict(
                new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null));

        assertEquals(40906, response.getCode());
    }

    @Test
    void bothConcreteTypesAreCoveredByTheSingleHandler() {
        // 处理器只声明了 PessimisticLockingFailureException：两个具体类型都必须能被它接住。
        assertTrue(PessimisticLockingFailureException.class.isAssignableFrom(CannotAcquireLockException.class));
        assertTrue(PessimisticLockingFailureException.class.isAssignableFrom(DeadlockLoserDataAccessException.class));
    }
}
