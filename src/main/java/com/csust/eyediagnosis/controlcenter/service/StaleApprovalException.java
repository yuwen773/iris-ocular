package com.csust.eyediagnosis.controlcenter.service;

/**
 * 审批单绑定的内容已经变化（内部信号，不直接映射 HTTP）。
 *
 * <p>为什么需要这个类型：STALE 标记必须落在<b>发布事务回滚之后</b>——
 * 发布事务持有审批行锁，若在事务内用 REQUIRES_NEW 更新同一行，内层会等
 * 外层释放锁、外层又在内层完成前不提交，直接死锁。因此发布事务只抛出本异常
 * 让整个事务回滚（锁随之释放），由 {@link ControlCenterPublishService} 捕获后
 * 再开新事务写 STALE 与审计。
 */
class StaleApprovalException extends RuntimeException {

    private final String reasonCode;

    StaleApprovalException(String reasonCode) {
        super("发布申请绑定的内容已变化: " + reasonCode);
        this.reasonCode = reasonCode;
    }

    String getReasonCode() {
        return reasonCode;
    }
}
