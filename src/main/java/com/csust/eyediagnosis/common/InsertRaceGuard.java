package com.csust.eyediagnosis.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 「插入，撞唯一键就当没插」的跨引擎写法。
 *
 * <p>MySQL 时代在事务里 {@code try { insert } catch (DuplicateKeyException) { 再查 } }
 * 是安全的：失败语句不污染连接，后续语句照常执行。PostgreSQL 不是——
 * 事务内任何语句报错后，直到事务结束前后续语句一律
 * {@code 25P02 current transaction is aborted}。于是「先插入、撞键、再 SELECT 赢家」
 * 这条幂等路径会在 SELECT 处炸掉，异常类型还从预期的 409 变成数据库错误。
 *
 * <p>这里把插入放进 savepoint（{@code PROPAGATION_NESTED}）：撞键只回滚到 savepoint，
 * 外层事务继续可用，语义与 MySQL 上的 try/catch 完全一致。SQL 仍是原来的方言
 * （PG 侧靠函数补丁兼容 MySQL 函数），不引入 {@code ON CONFLICT} 这类单引擎语法，
 * 所以两种数据库上行为相同。
 *
 * <p>没有活动事务时（例如单测直接调 service、或 controller 里的一次性写入），
 * savepoint 无处可挂，直接执行——此时 PG 的单语句隐式事务已经保证了
 * 「失败语句不污染下一个语句」，不需要额外包裹。
 */
@Component
public class InsertRaceGuard {

    private final TransactionTemplate nested;

    /**
     * @param transactionManager 生产环境由 Spring 注入；没有事务管理器的单测可传 {@code null}，
     *                           此时 {@link #tryInsert(Runnable)} 只会走无事务分支
     */
    public InsertRaceGuard(PlatformTransactionManager transactionManager) {
        this.nested = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.nested != null) {
            this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        }
    }

    /**
     * 执行一次可能撞唯一键的插入。
     *
     * @return {@code true} 插入成功；{@code false} 唯一键冲突（savepoint 已回滚，外层事务仍可用）
     * @throws org.springframework.dao.DataAccessException 其他数据库错误原样抛出，调用方语义不变
     */
    public boolean tryInsert(Runnable insert) {
        if (nested == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                insert.run();
                return true;
            } catch (DuplicateKeyException duplicate) {
                return false;
            }
        }
        try {
            nested.executeWithoutResult(status -> insert.run());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
