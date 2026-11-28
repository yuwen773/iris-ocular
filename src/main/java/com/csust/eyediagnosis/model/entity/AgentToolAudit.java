package com.csust.eyediagnosis.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * Structured, owner-scoped audit row for one tool-call state transition.
 * Deliberately separate from {@link AgentMessage}: history is user-visible,
 * this table is a security audit record. No raw arguments / prompts here.
 */
@Data
@TableName("t_agent_tool_audit")
public class AgentToolAudit implements Serializable {
    // 列名写成迁移里的实际小写：MyBatis-Plus 把 keyColumn 交给 pgjdbc 时会拼成
    // RETURNING "auditId"（带引号 → 大小写敏感），而 PG 迁移里的列是未加引号的
    // auditid。写成同一字符串后，引号里的名字才对得上。
    @TableId(value = "auditid", type = IdType.AUTO)
    private Long auditId;
    private String sessionId;
    private Long userId;
    private String toolCallId;
    private String toolName;
    private String toolStatus;
    private String errorCategory;
    private String argsDigest;
    private String argsKeyNames;
    private Date startedAt;
    private Date finishedAt;
}
