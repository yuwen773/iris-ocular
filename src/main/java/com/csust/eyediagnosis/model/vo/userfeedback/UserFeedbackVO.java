package com.csust.eyediagnosis.model.vo.userfeedback;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户反馈的读模型。
 *
 * <p>不暴露 {@code attachmentObjectKey}：对象 key 是私有存储里的内部定位符，
 * 前端需要的是一条限时链接，由 {@code GET /feedback/{id}/attachment} 现签现发。
 * 这里只给 {@code hasAttachment} 与 {@code attachmentFormat} 供界面决定要不要显示预览位。
 *
 * <p>{@code userName} 是提交人的展示名（管理员台账需要）；查不到用户时置 null，
 * 界面显示「未知用户」而不是编一个名字。
 */
@Data
public class UserFeedbackVO implements Serializable {

    /** 雪花 id，JSON 序列化为字符串 */
    private Long id;

    /** 提交人 id */
    private Long userId;

    /** 提交人昵称（可空） */
    private String userName;

    /** 分类：SUGGESTION / ISSUE / RESULT_DOUBT / OTHER */
    private String category;

    /** 正文 */
    private String content;

    /** 关联对象类型；空表示不关联 */
    private String targetType;

    /** 关联对象 id；空表示不关联 */
    private String targetId;

    /** 状态：PENDING / PROCESSING / RESOLVED / IGNORED */
    private String status;

    /** 管理员回复正文；未回复时为 null（不是空串，界面要能区分） */
    private String reply;

    /** 回复人 */
    private Long repliedById;

    /** 回复时间 */
    private Date repliedAt;

    /** 最后处理人 */
    private Long handledById;

    /** 最后处理时间 */
    private Date handledAt;

    /** 是否带截图 */
    private Boolean hasAttachment;

    /** 截图格式：png / jpg */
    private String attachmentFormat;

    /** 提交时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
