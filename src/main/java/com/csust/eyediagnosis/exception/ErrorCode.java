package com.csust.eyediagnosis.exception;

import lombok.Getter;

/**
 * &#064;ClassNameErrorCode
 * &#064;Description  错误枚举类
 * &#064;Author钰玟
 * &#064;Date  2025/4/10 下午8:50
 * &#064;Version  1.0
 **/
@Getter
public enum ErrorCode {

    SUCCESS(0,"ok"),
    PARAMS_ERROR(40000,"请求参数错误"),
    NOT_LOGIN_ERROR(40100,"未登录"),
    NO_AUTH_ERROR(40101,"无权限"),
    /**
     * 配置中心动作权限不足。
     *
     * 与 40101 分开：40101 是"这个角色根本不开放该端点"，
     * 40301 是"是管理员，但当前动作（发布 / 回滚）不在你的授权范围内"。
     * 前端据此提示"请联系配置发布人"，而不是把人踢去登录。
     */
    CONFIG_PERMISSION_DENIED(40301,"配置操作权限不足"),
    /**
     * 配置中心 409 段：全部是「请求合法，但与当前状态冲突」。
     *
     * 与 40000 分开，前端才能把「你该刷新重试」和「你填错了」分开提示。
     */
    CONFIG_VERSION_CONFLICT(40900,"配置版本冲突，请刷新后重试"),
    CONFIG_NOT_VALIDATED(40901,"配置尚未通过校验，不能发布"),
    CONFIG_STALE_VALIDATION(40902,"校验结果已过期，请重新校验"),
    CONFIG_SCHEMA_UNSUPPORTED(40903,"不支持的配置载荷版本"),
    CONFIG_UNKNOWN_KEY(40904,"存在未注册的配置键"),
    CONFIG_DEPLOY_PROTECTED(40905,"该配置属于部署事实，不允许在线修改"),
    /**
     * 配置中心并发冲突：行锁等待超时或死锁。
     *
     * <p>与 40900 分开：40900 是「你手里的版本旧了」，要刷新后重来；本码是
     * 「同一份内容撞上了并发」，事务已经完整回滚、{@code requestId} 未被消耗，
     * 原样重试即可。没有这个码时，锁等待超时与死锁会落到 50000「系统错误」，
     * 客户端无法判断该不该重试。
     */
    CONFIG_CONCURRENT_CONFLICT(40906,"并发冲突，请稍后重试"),
    CONFIG_IDEMPOTENCY_MISMATCH(40908,"同一请求号的内容不一致"),
    /**
     * WP-01C：审批闸口与 Provider 边界。
     *
     * 40909/40910 分开是必要的：一个说"这条审批已经决定过了"，另一个说
     * "审批绑定的内容已经变了"——前者要刷新列表，后者要重新申请。
     * 40911 是 Provider 尚未实现的显式边界：宁可拒绝，也不要在 WP-03 之前
     * 让一个半成品引用进入发布快照。
     */
    CONFIG_APPROVAL_NOT_PENDING(40909,"该发布申请已处理，不能重复决定"),
    CONFIG_APPROVAL_STALE(40910,"发布申请绑定的草稿内容已变化，请重新申请"),
    CONFIG_PROVIDER_NOT_SUPPORTED(40911,"当前版本未实现配置中心 Provider，相关配置不允许发布"),
    NOT_FOUND_ERROR(40400,"请求数据不存在"),
    FORBIDDEN_ERROR(40300,"禁止访问"),
    SYSTEM_ERROR(50000,"系统内部异常"),
    OPERATION_ERROR(50001,"操作失败"),
    /**
     * 影像存储失败（对象存储超时 / 鉴权 / 服务端异常）。
     *
     * 单独一个码，而不是复用 SYSTEM_ERROR：前端要能把「影像没存下来、
     * 本次判读未归档、可以重试」和笼统的「服务没响应」分开说。
     */
    STORAGE_ERROR(50002,"影像存储失败"),

    // 模型服务（DashScope）侧的失败：与「服务端自己坏了」分开，
    // 前端据此说清是配置/额度问题还是临时故障。
    MODEL_SERVICE_UNAUTHORIZED(50301,"模型服务鉴权失败"),
    MODEL_SERVICE_QUOTA_EXCEEDED(50302,"模型服务额度不足"),
    MODEL_SERVICE_UNAVAILABLE(50303,"模型服务不可用"),

    DIAGNOSIS_REJECTED_QUALITY(40010,"图片质量不合格"),
    DIAGNOSIS_REJECTED_OOD(40011,"图像分布外"),
    DIAGNOSIS_NEEDS_REVIEW(40012,"诊断结果需人工复核"),
    /**
     * 用户在判读进行中取消了本次运行。
     *
     * 单独一个码而不是复用一个笼统的错误：前端要能把它识别成「按你的要求停下了」，
     * 既不当失败提示，也不重试。服务端保证此时没有落库、没有留下原图。
     */
    DIAGNOSIS_CANCELLED(40013,"判读已取消"),

    /**
     * 用户反馈的状态在「读到」与「写入」之间被别人改过。
     *
     * 单独一个码而不是复用 OPERATION_ERROR：前端要能把它识别成「你慢了一步，
     * 刷新后重试」，而不是「服务出错了」。
     */
    USER_FEEDBACK_CONFLICT(40900,"反馈状态已变化，请刷新后重试"),

    /**
     * 提交频率超限（用户反馈限流）。
     *
     * 单独一个 429 语义的码而不是复用 OPERATION_ERROR：这是「稍后再试」，
     * 不是「操作失败」，前端据此提示而不当成服务故障。
     */
    TOO_MANY_REQUESTS(42900,"提交过于频繁，请稍后再试")
    ;
    /**
     * 状态码
     */
    private final int code;

    /**
     * 返回信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
