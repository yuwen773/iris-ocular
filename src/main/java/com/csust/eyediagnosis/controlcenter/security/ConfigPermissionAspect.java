package com.csust.eyediagnosis.controlcenter.security;

import com.csust.eyediagnosis.config.ControlCenterProperties;
import com.csust.eyediagnosis.controlcenter.model.enums.ConfigErrorReason;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterAuditService;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.enums.UserRole;
import com.csust.eyediagnosis.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 执行 {@link ConfigPermission} 声明式校验。
 *
 * <p>刻意不复用 {@code AuthInterceptor}：
 * <ul>
 *   <li>{@code @AuthCheck(mustRole)} 只有 ADMIN 一个分支真正生效，把
 *       "publisher / rollbacker"塞进去会污染它的角色语义；</li>
 *   <li>配置中心的授权事实来自部署配置（发布人白名单），不属于用户角色表。</li>
 * </ul>
 *
 * <p>失败一律 fail-closed：未登录沿用 40100，登录了但动作不被允许返回
 * {@code 40301 CONFIG_PERMISSION_DENIED}。
 */
@Aspect
@Component
public class ConfigPermissionAspect {

    /**
     * 请求属性名：本次已解析的操作人。
     *
     * <p>切面已经查过一次用户（含封禁校验），控制器再查一次是重复的数据库读。
     * 把结果挂在请求上，控制器用 {@link #actorOf} 取；这也顺带说明了一个边界：
     * 这个值只在**被本切面拦截的方法**里存在，控制器不能假设它永远有。
     */
    public static final String ACTOR_ATTRIBUTE = "iris.controlCenter.actor";

    @Resource
    private UserService userService;

    @Resource
    private ControlCenterAuditService auditService;

    private final ControlCenterProperties controlCenterProperties;

    public ConfigPermissionAspect(ControlCenterProperties controlCenterProperties) {
        this.controlCenterProperties = controlCenterProperties;
    }

    @Around("@annotation(configPermission)")
    public Object check(ProceedingJoinPoint joinPoint, ConfigPermission configPermission) throws Throwable {
        ConfigAction action = configPermission.value();
        HttpServletRequest request = currentRequest();
        // 未登录时 getLoginUser 自己抛 40100，这里不捕获，保持"未登录"与"无权限"可区分。
        User loginUser = userService.getLoginUser(request);

        if (!UserRole.ADMIN.equals(UserRole.getEnumByValue(loginUser.getUserRole()))) {
            deny(action, loginUser);
            throw new BusinessException(ErrorCode.CONFIG_PERMISSION_DENIED, "非管理员不能访问配置中心");
        }

        if (action.requiresPublisher() && !isPublisher(loginUser)) {
            deny(action, loginUser);
            throw new BusinessException(ErrorCode.CONFIG_PERMISSION_DENIED, "当前账号不在配置发布人名单内");
        }
        request.setAttribute(ACTOR_ATTRIBUTE, loginUser);
        return joinPoint.proceed();
    }

    /**
     * 权限拒绝留痕（PRD FR-03「未授权的敏感操作尝试」）。
     *
     * <p>独立事务写入且吞掉写失败：审计写不进去不能改变拒绝本身，
     * 否则攻击者可以用刷审计制造 500。
     */
    private void deny(ConfigAction action, User loginUser) {
        auditService.recordDenied(action.name(), loginUser.getId(), null, null,
                ConfigErrorReason.PERMISSION_DENIED.name());
    }

    /** 取本切面已解析的操作人；没有时返回 null（调用方回落到 userService）。 */
    public static User actorOf(HttpServletRequest request) {
        Object actor = request.getAttribute(ACTOR_ATTRIBUTE);
        return actor instanceof User user ? user : null;
    }

    private boolean isPublisher(User loginUser) {
        if (loginUser.getId() == null) {
            return false;
        }
        return controlCenterProperties.getPublisherUserIds() != null
                && controlCenterProperties.getPublisherUserIds().contains(loginUser.getId());
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            throw new IllegalStateException("@ConfigPermission 只能用于处理 HTTP 请求的端点");
        }
        return servletAttributes.getRequest();
    }
}
