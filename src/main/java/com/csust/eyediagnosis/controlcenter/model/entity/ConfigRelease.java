package com.csust.eyediagnosis.controlcenter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 不可变发布快照（V36）。
 *
 * <p>只插入，不更新、不删除：回滚同样是插入一条新 release（ADR D2 / PRD FR-02）。
 * 因此本实体没有 update 路径，服务层也不应出现 {@code UPDATE t_config_release}。
 */
@Data
@TableName("t_config_release")
public class ConfigRelease {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单调递增发布序号，唯一。 */
    private Long releaseVersion;

    /** 载荷 schema 版本。 */
    private Integer schemaVersion;

    private String payload;

    /** 规范化 JSON 的 SHA-256，十六进制。 */
    private String digest;

    /** 非空表示由回滚生成。 */
    private Long rollbackFromReleaseId;

    private Long createdBy;

    private Date createdTime;
}
