package com.csust.eyediagnosis.model.vo.notification;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 通知读模型。
 *
 * <p>文案（title / body）直接返回库里的定稿值，不做二次拼装：
 * 历史消息保持发送时的措辞，是这张表存在的理由之一。
 */
@Data
public class NotificationVO implements Serializable {

    /** 雪花 id，JSON 序列化为字符串 */
    private Long id;

    /** DIAGNOSIS_READY / DIAGNOSIS_RESTRICTED / REVIEW_DONE / FEEDBACK_REPLIED */
    private String type;

    /** 发送时定稿的标题 */
    private String title;

    /** 发送时定稿的正文（只给指针） */
    private String body;

    /** DIAGNOSIS_TASK / USER_FEEDBACK，前端据此决定深链去哪个列表页 */
    private String refType;

    /** taskId / 反馈 id */
    private String refId;

    /** 已读时间；null = 未读 */
    private Date readAt;

    /** 发送时间 */
    private Date createTime;
}
