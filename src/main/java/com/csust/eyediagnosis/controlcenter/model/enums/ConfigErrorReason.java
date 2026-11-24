package com.csust.eyediagnosis.controlcenter.model.enums;

import com.csust.eyediagnosis.exception.ErrorCode;

/**
 * 配置中心的失败原因码。
 *
 * <p>一个原因码同时出现在两个地方：错误信封的 {@code msg} 里可读，审计事件的
 * {@code reasonCode} 里可查。二者共用本枚举，避免「日志写一个词、接口写另一个词」。
 */
public enum ConfigErrorReason {

    /** 载荷里出现未注册的键。 */
    UNKNOWN_KEY(ErrorCode.CONFIG_UNKNOWN_KEY),
    /** 命中部署保护前缀（eye-diagnosis.* / spring.* / cos.* …）。 */
    DEPLOY_PROTECTED(ErrorCode.CONFIG_DEPLOY_PROTECTED),
    /** 载荷 schema 版本与代码不一致。 */
    SCHEMA_UNSUPPORTED(ErrorCode.CONFIG_SCHEMA_UNSUPPORTED),
    /** revision / expectedActiveReleaseId 与当前状态不符。 */
    VERSION_CONFLICT(ErrorCode.CONFIG_VERSION_CONFLICT),
    /** 未经校验就尝试发布（WP-01C 使用）。 */
    NOT_VALIDATED(ErrorCode.CONFIG_NOT_VALIDATED),
    /** 校验结论对应的 revision 已过期。 */
    STALE_VALIDATION(ErrorCode.CONFIG_STALE_VALIDATION),
    /** 幂等 requestId 已被不同内容使用过。 */
    IDEMPOTENCY_MISMATCH(ErrorCode.CONFIG_IDEMPOTENCY_MISMATCH),
    /** 对已批准 / 已拒绝 / 已失效的审批再做决定（WP-01C）。 */
    APPROVAL_NOT_PENDING(ErrorCode.CONFIG_APPROVAL_NOT_PENDING),
    /** 审批绑定的草稿内容或验证摘要已经变化（WP-01C）。 */
    APPROVAL_STALE(ErrorCode.CONFIG_APPROVAL_STALE),
    /** 载荷引用 Provider 命名空间，但 WP-03 尚未实现（WP-01C）。 */
    PROVIDER_NOT_SUPPORTED(ErrorCode.CONFIG_PROVIDER_NOT_SUPPORTED),
    /** 值类型不符。 */
    TYPE_MISMATCH(ErrorCode.PARAMS_ERROR),
    /** 文本长度越界（按 Unicode 码点）。 */
    LENGTH_OUT_OF_RANGE(ErrorCode.PARAMS_ERROR),
    /** 含控制字符。 */
    CONTROL_CHARACTER(ErrorCode.PARAMS_ERROR),
    /** 必填值为空。 */
    REQUIRED(ErrorCode.PARAMS_ERROR),
    /** 资产引用不是服务端签发的 Asset ID。 */
    ASSET_REF_INVALID(ErrorCode.PARAMS_ERROR),
    /** 动作权限不足。 */
    PERMISSION_DENIED(ErrorCode.CONFIG_PERMISSION_DENIED);

    private final ErrorCode errorCode;

    ConfigErrorReason(ErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 按名字查原因码；未知名字返回 null（用于把校验器的字符串结果转回枚举）。 */
    public static ConfigErrorReason fromName(String name) {
        if (name == null) {
            return null;
        }
        for (ConfigErrorReason reason : values()) {
            if (reason.name().equals(name)) {
                return reason;
            }
        }
        return null;
    }
}
