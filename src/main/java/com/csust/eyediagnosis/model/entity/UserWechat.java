package com.csust.eyediagnosis.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 小程序微信账号绑定（t_user_wechat）。
 *
 * <p>与 t_user 一对多：同一个用户在换 AppID 后会拿到不同 openid，因此绑定按
 * (appid, openid) 唯一，而不是按 openid 唯一。
 */
@TableName("t_user_wechat")
@Data
public class UserWechat implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 与 V35 的 {@code BIGINT AUTO_INCREMENT} 对齐：插入时由数据库发号。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** t_user.id */
    private Long userId;

    /** 小程序 AppID */
    private String appid;

    /** 微信 openid，同一 AppID 内唯一 */
    private String openid;

    /** 微信 unionid，绑定开放平台时才有 */
    private String unionid;

    private Date createTime;

    private Date updateTime;
}
