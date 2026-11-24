package com.csust.eyediagnosis.model.dto.userfeedback;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端回复请求。回复本身不改变状态由调用方决定（见 {@link UserFeedbackDecideDTO}），
 * 但回复一个 PENDING 的反馈会把它推进到 PROCESSING。
 */
@Data
public class UserFeedbackReplyDTO implements Serializable {

    /** 回复正文，1–2000 字 */
    private String reply;

    private static final long serialVersionUID = 1L;
}
