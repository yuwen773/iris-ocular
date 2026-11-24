package com.csust.eyediagnosis.controlcenter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 发布审批请求（V36）。
 *
 * <p>这张表存在的唯一理由：把「某一刻的草稿内容」钉死，让审批通过时能证明
 * 发布的正是被批准的那一份（见 WP-01C 设计 §2）。因此三个摘要列在插入后
 * <b>永不修改</b>；只有 status / decidedBy / decidedTime / decisionReason /
 * releaseId 这几个"决定"列会随状态机变化一次。
 */
@Data
@TableName("t_config_publish_request")
public class ConfigPublishRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_STALE = "STALE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人的客户端幂等键；与 (actorId, requestId) 一起构成二道闸。 */
    private String requestId;

    /** 申请人 userId。 */
    private Long actorId;

    private Long draftRevision;

    /** 申请时草稿载荷的 SHA-256。 */
    private String payloadDigest;

    /** 申请时「校验 revision + 校验结论」的 SHA-256。 */
    private String validationDigest;

    /** 申请时的活跃 release id；null 表示尚未发布过。 */
    private Long expectedActiveReleaseId;

    private String note;

    private String status;

    private Date requestedTime;

    private Long decidedBy;

    private Date decidedTime;

    private String decisionReason;

    /** 批准后生成的 release id；其余状态为 null。 */
    private Long releaseId;
}
