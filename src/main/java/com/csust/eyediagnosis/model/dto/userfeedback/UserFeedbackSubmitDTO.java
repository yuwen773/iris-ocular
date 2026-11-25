package com.csust.eyediagnosis.model.dto.userfeedback;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户提交反馈的请求体。
 *
 * <p>{@code targetType} / {@code targetId} / {@code attachmentObjectKey} 都可空：
 * 反馈既可以针对具体对象，也可以只是一句泛泛的意见。
 */
@Data
public class UserFeedbackSubmitDTO implements Serializable {

    /** 分类：SUGGESTION / ISSUE / RESULT_DOUBT / OTHER */
    private String category;

    /** 正文，1–2000 字 */
    private String content;

    /** 关联对象类型：DIAGNOSIS_TASK / AGENT_SESSION；空表示不关联 */
    private String targetType;

    /** 关联对象 id：taskId 或 sessionId；与 targetType 必须同时出现或同时为空 */
    private String targetId;

    /** 截图 object key（由 /feedback/attachment 上传返回）；空表示无截图 */
    private String attachmentObjectKey;

    private static final long serialVersionUID = 1L;
}
