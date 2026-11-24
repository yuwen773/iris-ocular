package com.csust.eyediagnosis.model.dto.userfeedback;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端状态流转请求。
 *
 * <p>{@code reply} 可空；但迁移到 {@code RESOLVED} 时**必须已有回复**——
 * 既可以是本行已经存过的回复，也可以是这次一并提交的回复。
 */
@Data
public class UserFeedbackDecideDTO implements Serializable {

    /** 目标状态：PROCESSING / RESOLVED / IGNORED / PENDING */
    private String status;

    /** 可选：一并写下的回复正文（1–2000 字） */
    private String reply;

    private static final long serialVersionUID = 1L;
}
