package com.csust.eyediagnosis.controlcenter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 请求级幂等记录（V35）。
 *
 * <p>唯一键 {@code (actorId, requestId)} 是判重点；{@code requestDigest} 区分
 * 「同一请求重放」与「同一 requestId 换了内容」。后者必须拒绝（40908），
 * 不能把旧结果回放给一个语义不同的请求。
 */
@Data
@TableName("t_config_request")
public class ConfigRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long actorId;

    private String requestId;

    private String requestDigest;

    private String action;

    private Integer resultCode;

    private String resultPayload;

    private Date createdTime;
}
