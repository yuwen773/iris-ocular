package com.csust.eyediagnosis.controlcenter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 配置中心单例草稿（V35）。
 *
 * <p>全局只有一行（{@code id = 1}）：这是 ADR D3 的结构化表达，服务层不提供
 * 多草稿语义。{@code revision} 是乐观并发判据，任何更新都必须带
 * {@code WHERE revision = ?}。
 *
 * <p>{@code payload}/{@code validationResult} 在 Java 侧保持 JSON 字符串：
 * 列类型是 MySQL {@code JSON}，映射到 String 后由业务层用
 * {@code ConfigPayloadCodec} 做规范化与摘要，避免两套序列化口径。
 */
@Data
@TableName("t_config_draft")
public class ConfigDraft {

    public static final long SINGLETON_ID = 1L;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_VALIDATED = "VALIDATED";

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long revision;

    private String status;

    /** 键值对 JSON。 */
    private String payload;

    private Long validationRevision;

    private String validationResult;

    private Long updatedBy;

    private Date createTime;

    private Date updateTime;
}
