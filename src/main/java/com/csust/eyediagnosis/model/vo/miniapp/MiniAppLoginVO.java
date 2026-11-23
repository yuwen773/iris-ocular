package com.csust.eyediagnosis.model.vo.miniapp;

import com.csust.eyediagnosis.model.vo.user.LoginUserVO;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序登录结果：令牌 + 有效期 + 脱敏后的当前用户。
 */
@Data
public class MiniAppLoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 后续请求放在 {@code Authorization: Bearer <token>}。 */
    private String token;

    /** 有效期（秒）。每次校验滑动续期。 */
    private long expiresIn;

    private LoginUserVO user;
}
