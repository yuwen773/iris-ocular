package com.csust.eyediagnosis.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户通知：一条「有东西变了，去哪里看」的站内消息。
 *
 * <p>它不是第二份业务台账：真正的待办仍在 {@code t_diagnosis_task} /
 * {@code t_user_feedback} 里，删掉通知不影响任何业务状态。
 * 文案（title / body）在发送时定稿存库，历史消息不随后续改名变化。
 *
 * <p>没有 {@code isDelete}：已读且超过保留期的行由
 * {@code NotificationCleanupScheduler} 物理删除（见 {@code V38__user_notification.sql}）。
 *
 * @TableName t_user_notification
 */
@TableName(value = "t_user_notification")
@Data
public class UserNotification {

    /** 主键（雪花 id，JSON 序列化成字符串，避免前端精度丢失） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 接收人 */
    private Long userId;

    /** 类型，取值见 {@link com.csust.eyediagnosis.constant.NotificationType} */
    private String type;

    /** 发送时定稿的标题 */
    private String title;

    /** 发送时定稿的正文：只给指针，不放结论、病名、严重度 */
    private String body;

    /** 关联对象类型：DIAGNOSIS_TASK / USER_FEEDBACK */
    private String refType;

    /** 关联对象 id（taskId / 反馈 id） */
    private String refId;

    /** 首次已读时间；null = 未读（重复标记已读不覆盖它） */
    private Date readAt;

    /** 发送时间 */
    private Date createTime;
}
