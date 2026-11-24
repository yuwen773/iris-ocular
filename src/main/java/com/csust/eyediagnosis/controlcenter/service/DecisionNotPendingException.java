package com.csust.eyediagnosis.controlcenter.service;

/**
 * 审批单已不在 PENDING（内部信号，对外映射 40909）。
 *
 * <p>与 {@link StaleApprovalException} 分开的原因：这不是「内容变了」，
 * 而是「别人已经决定了」，重试没有意义，也不该把审批状态改写成 STALE。
 */
class DecisionNotPendingException extends RuntimeException {

    private final String currentStatus;

    DecisionNotPendingException(String currentStatus) {
        super("发布申请当前状态是 " + currentStatus);
        this.currentStatus = currentStatus;
    }

    String getCurrentStatus() {
        return currentStatus;
    }
}
