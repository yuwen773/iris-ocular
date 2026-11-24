package com.csust.eyediagnosis.model.dto.miniapp;

import lombok.Data;

import java.io.Serializable;

/**
 * 小程序登录入参：wx.login 拿到的一次性 code。
 */
@Data
public class MiniAppLoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** wx.login 返回的 code，5 分钟有效且只能用一次。 */
    private String code;
}
