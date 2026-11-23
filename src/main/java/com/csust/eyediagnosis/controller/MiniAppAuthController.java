package com.csust.eyediagnosis.controller;

import cn.hutool.core.util.StrUtil;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.constant.MiniAppConstant;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.miniapp.MiniAppAuthService;
import com.csust.eyediagnosis.miniapp.MiniAppTokenService;
import com.csust.eyediagnosis.model.dto.miniapp.MiniAppLoginDTO;
import com.csust.eyediagnosis.model.vo.miniapp.MiniAppLoginVO;
import com.csust.eyediagnosis.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信小程序登录入口。
 *
 * <p>登录本身没有可携带的令牌（正是它要换一个），因此路径被 {@code SameOriginFilter}
 * 显式豁免；除登录外的所有接口都复用现有 Controller，凭 Bearer 令牌通过
 * {@code MiniAppTokenFilter} 认证。
 */
@RestController
@RequestMapping("/mp/auth")
public class MiniAppAuthController {

    @Resource
    private MiniAppAuthService miniAppAuthService;
    @Resource
    private MiniAppTokenService miniAppTokenService;
    @Resource
    private UserService userService;

    /**
     * wx.login → code 换令牌。
     */
    @PostMapping("/login")
    @Operation(summary = "小程序登录")
    public BaseResponse<MiniAppLoginVO> login(@RequestBody MiniAppLoginDTO loginDTO) {
        ThrowUtils.throwIf(loginDTO == null || StrUtil.isBlank(loginDTO.getCode()),
                ErrorCode.PARAMS_ERROR, "缺少微信登录凭证");
        MiniAppAuthService.LoginResult result = miniAppAuthService.login(loginDTO.getCode().trim());
        MiniAppLoginVO vo = new MiniAppLoginVO();
        vo.setToken(result.token());
        vo.setExpiresIn(result.expiresInSeconds());
        vo.setUser(userService.getLoginUserVO(result.user()));
        return ResultUtils.success(vo);
    }

    /**
     * 退出登录：吊销当前令牌。令牌无效时也返回成功，客户端无论如何都应该清本地状态。
     */
    @PostMapping("/logout")
    @Operation(summary = "小程序退出登录")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        String token = MiniAppTokenService.parseBearer(
                request.getHeader(MiniAppConstant.AUTHORIZATION_HEADER));
        miniAppTokenService.revoke(token);
        return ResultUtils.success(true);
    }
}
