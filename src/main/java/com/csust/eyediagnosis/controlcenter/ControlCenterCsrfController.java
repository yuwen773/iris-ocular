package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.controlcenter.security.ControlCenterCsrfFilter;
import com.csust.eyediagnosis.security.CsrfTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制中心 CSRF token 取用端点。
 *
 * <p>管理端页面在进入控制中心时先调这个 GET（安全方法，不需要 token 本身），
 * 拿到 token 后对后续每个写请求带 {@value ControlCenterCsrfFilter#CSRF_HEADER}。
 * 这是标准 double-submit 的会话绑定变体：token 不经 Cookie 下发，攻击者
 * 无法从跨站页面读取。
 *
 * <p>仅 {@code eye-diagnosis.control-center.enabled=true} 时注册；
 * 与 {@link ControlCenterCsrfFilter} 同开关，避免出现"能取 token 但无人校验"
 * 或反向的空档。
 */
@RestController
@RequestMapping("/admin/control-center")
@ConditionalOnProperty(prefix = "eye-diagnosis.control-center", name = "enabled", havingValue = "true")
public class ControlCenterCsrfController {

    private final CsrfTokenService csrfTokenService;

    public ControlCenterCsrfController(CsrfTokenService csrfTokenService) {
        this.csrfTokenService = csrfTokenService;
    }

    /**
     * 返回（必要时生成）当前会话的 CSRF token。
     *
     * <p>写方法会创建/延长会话，这是刻意的：token 必须挂在会话上；未登录的
     * 请求在 {@link AuthCheck} 处就被拒，不会走到这里。
     */
    @GetMapping("/csrf")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<CsrfTokenVO> csrfToken(HttpServletRequest request, HttpServletResponse response) {
        // token 是会话凭据的一部分：任何中间缓存（浏览器、反代、CDN）都不能留存。
        // 没有这条头时，共享缓存理论上可能把一个管理员的 token 发给另一个会话。
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        String token = csrfTokenService.issue(request.getSession(true));
        return ResultUtils.success(new CsrfTokenVO(token, ControlCenterCsrfFilter.CSRF_HEADER));
    }
}
