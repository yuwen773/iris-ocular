package com.csust.eyediagnosis.controlcenter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 配置中心审计事件（V36）。
 *
 * <p>只追加：没有 update/delete 路径。与业务写在同一事务内落库（ADR D4），
 * 因此「已发布但没有审计」在结构上不可能出现。
 *
 * <p>落库内容不得包含 Session、CSRF token、密钥或 Provider 凭据——
 * 只记动作、主体、结果、原因码与字段级摘要。
 */
@Data
@TableName("t_config_audit_event")
public class ConfigAuditEvent {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_DENIED = "DENIED";
    public static final String RESULT_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private Long actorId;

    private String action;

    private String targetId;

    private String requestId;

    private String beforeVersion;

    private String afterVersion;

    private String result;

    private String reasonCode;

    private String payload;

    private Date occurredAt;
}
