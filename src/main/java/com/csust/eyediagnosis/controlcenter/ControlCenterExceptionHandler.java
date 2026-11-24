package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 配置中心的并发失败语义（WP-01D）。
 *
 * <p>为什么需要这一层：发布事务靠 {@code SELECT … FOR UPDATE} 串行化，竞争一旦超过
 * {@code innodb_lock_wait_timeout}（默认 50s）或 InnoDB 判定死锁，Spring 抛出的是
 * {@link PessimisticLockingFailureException} 的子类（{@code CannotAcquireLockException} /
 * {@code DeadlockLoserDataAccessException}）。它会一路落到全局兜底处理器，变成
 * {@code 50000 系统错误}——客户端拿到这个码只能当作「服务坏了」，实际上事务已经
 * 完整回滚、原样重试就是正确处置。
 *
 * <p>范围只限配置中心的两个控制器（{@code @RestControllerAdvice(assignableTypes=…)}）：
 * 不改全站其它接口的异常语义。{@code @Order(HIGHEST_PRECEDENCE)} 是必需的——
 * 全局 {@code GlobalExceptionHandler} 的 {@code Exception} 兜底同样能匹配这个异常，
 * 谁先被咨询取决于顺序。
 *
 * <p>前提：事务在异常穿出代理时已经回滚，因此这里不需要（也不能）回滚什么。
 * 本处理器只翻译语义，不捕获后继续执行。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ControlCenterController.class, ControlCenterCsrfController.class})
public class ControlCenterExceptionHandler {

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public BaseResponse<?> handleLockConflict(PessimisticLockingFailureException failure) {
        log.warn("配置中心并发冲突（锁等待超时或死锁），事务已回滚: {}", failure.getMessage());
        return ResultUtils.error(ErrorCode.CONFIG_CONCURRENT_CONFLICT,
                "并发冲突（锁等待超时或死锁），本次操作未生效，请原样重试");
    }
}
