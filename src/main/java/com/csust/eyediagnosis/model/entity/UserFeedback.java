package com.csust.eyediagnosis.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户反馈：登录用户对产品、判读体验或 Agent 回答提出的意见。
 *
 * <p>它是**产品意见通道**，不是医疗记录：不参与判读统计、复核队列与数据分析口径。
 * 与 Critic 反馈（{@code agent.feedback.requested}）、复核意见（{@code t_doctor_review.note}）、
 * 审核意见（{@code t_news.reviewNote}）是四个不同概念，见 {@code GLOSSARY.md}。
 *
 * @TableName t_user_feedback
 */
@TableName(value = "t_user_feedback")
@Data
public class UserFeedback {

    /** 主键（雪花 id，JSON 序列化成字符串，避免前端精度丢失） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 提交人 */
    private Long userId;

    /** 分类，取值见 {@link com.csust.eyediagnosis.constant.UserFeedbackCategory} */
    private String category;

    /** 正文（1–2000 字，长度由 Service 守卫） */
    private String content;

    /** 关联对象类型：DIAGNOSIS_TASK / AGENT_SESSION；空表示与具体对象无关 */
    private String targetType;

    /** 关联对象 id（taskId / sessionId） */
    private String targetId;

    /**
     * 处理状态，取值见 {@link com.csust.eyediagnosis.constant.UserFeedbackStatus}。
     * 迁移由 {@code UserFeedbackLifecycleService} 用条件更新完成，DB 不设 CHECK（3306 为 5.7）。
     */
    private String status;

    /** 管理员回复正文（用户可见） */
    private String reply;

    /** 回复人（管理员 id） */
    private Long repliedById;

    /** 回复时间 */
    private Date repliedAt;

    /** 最后处理人 */
    private Long handledById;

    /** 最后处理时间 */
    private Date handledAt;

    /** 截图在 COS 的 object key；不存公开 URL，读取时换限时链接 */
    private String attachmentObjectKey;

    /** 截图格式：png / jpg */
    private String attachmentFormat;

    /** 提交时间（DB 默认值） */
    private Date createTime;

    /** 更新时间（DB ON UPDATE） */
    private Date updateTime;

    /** 逻辑删除，与全局 logic-delete-field: isDelete 一致 */
    @TableLogic
    private Integer isDelete;
}
