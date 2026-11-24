package com.csust.eyediagnosis.controlcenter.security;

import com.csust.eyediagnosis.constant.UserConstant;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterAuditService;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.security.SecurityEventSink;
import com.csust.eyediagnosis.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 前置安全拒绝（Origin / CSRF）的审计记录。
 *
 * <p>这两个过滤器跑在认证之前，拿不到「已认证的操作人」。这里的折中：
 * <ul>
 *   <li>只有<b>带已登录 Session</b> 的请求才解析操作人——匿名请求不查用户表，
 *       但仍会写一条 {@code actorId = NULL} 的审计行：WP-01D 审计发现原注释
 *       「匿名请求不落库」与实现不符，此处按实现更正。<b>这意味着匿名来源的
 *       Origin/CSRF 拒绝会按请求数增长审计表</b>，部署时需要限流或保留期策略
 *       （见 WP-01D 报告的风险接受清单）；</li>
 *   <li>解析失败（会话过期、账号封禁）按 actorId=null 记录，保留「发生过一次
 *       被拒绝的敏感操作」这一事实；</li>
 *   <li>任何异常都被吞掉：审计是旁路，绝不能把一次拒绝变成 500。</li>
 * </ul>
 */
@Slf4j
@Component
public class ControlCenterSecurityAudit implements SecurityEventSink {

    private final ControlCenterAuditService auditService;
    private final UserService userService;

    public ControlCenterSecurityAudit(ControlCenterAuditService auditService, UserService userService) {
        this.auditService = auditService;
        this.userService = userService;
    }

    @Override
    public void denied(HttpServletRequest request, String action, String reasonCode) {
        try {
            auditService.recordDenied(action, resolveActorId(request), null, null, reasonCode);
        } catch (RuntimeException e) {
            log.warn("安全拒绝审计写入失败（不影响拒绝本身）, action={}, reason={}", action, reasonCode, e);
        }
    }

    /**
     * 仅当 Session 里存在登录态时才解析用户。
     *
     * <p>匿名请求没有这个属性，直接返回 null——不查库。
     */
    private Long resolveActorId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(UserConstant.USER_LOGIN_STATE) == null) {
            return null;
        }
        try {
            User user = userService.getLoginUser(request);
            return user == null ? null : user.getId();
        } catch (BusinessException notLoggedIn) {
            return null;
        }
    }
}
