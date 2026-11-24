package com.csust.eyediagnosis.controlcenter.security;

import com.csust.eyediagnosis.config.ControlCenterProperties;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterAuditService;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 配置中心细粒度权限（WP-01A）：不依赖、也不改变 @AuthCheck 的角色语义。 */
class ConfigPermissionAspectTest {

    private UserService userService;
    private ControlCenterAuditService auditService;
    private ControlCenterProperties properties;
    private ConfigPermissionAspect aspect;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        auditService = mock(ControlCenterAuditService.class);
        properties = new ControlCenterProperties();
        aspect = new ConfigPermissionAspect(properties);
        ReflectionTestUtils.setField(aspect, "userService", userService);
        ReflectionTestUtils.setField(aspect, "auditService", auditService);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/admin/control-center")));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private <T> T proxy(Class<T> type, T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private void loginAs(User user) {
        when(userService.getLoginUser(any())).thenReturn(user);
    }

    @Test
    void adminCanReadEditAndValidate() {
        loginAs(user(7L, "admin"));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        assertEquals("read", fixture.read());
        assertEquals("edit", fixture.edit());
        assertEquals("validate", fixture.validate());
    }

    @Test
    void nonAdminIsRejectedWithConfigPermissionDenied() {
        loginAs(user(9L, "user"));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        BusinessException error = assertThrows(BusinessException.class, fixture::read);
        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(), error.getCode());
    }

    @Test
    void doctorRoleIsNotAdminForConfigCenter() {
        // DOCTOR 不是管理员；配置中心不因临床角色放行。
        loginAs(user(11L, "doctor"));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        BusinessException error = assertThrows(BusinessException.class, fixture::read);
        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(), error.getCode());
    }

    @Test
    void unknownRoleIsRejected() {
        loginAs(user(12L, null));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        BusinessException error = assertThrows(BusinessException.class, fixture::read);
        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(), error.getCode());
    }

    @Test
    void adminNotInPublisherListCannotPublish() {
        loginAs(user(7L, "admin"));
        properties.setPublisherUserIds(List.of(42L));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        BusinessException error = assertThrows(BusinessException.class, fixture::publish);
        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("发布人"));
    }

    @Test
    void emptyPublisherListDeniesPublishToEveryone() {
        loginAs(user(7L, "admin"));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(),
                assertThrows(BusinessException.class, fixture::publish).getCode());
        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(),
                assertThrows(BusinessException.class, fixture::rollback).getCode());
    }

    @Test
    void whitelistedPublisherCanPublishAndRollback() {
        loginAs(user(42L, "admin"));
        properties.setPublisherUserIds(List.of(42L, 43L));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        assertEquals("publish", fixture.publish());
        assertEquals("rollback", fixture.rollback());
    }

    @Test
    void publisherWhitelistDoesNotGrantReadToNonAdmin() {
        // 白名单是"额外要求"，不是"额外授权"。
        loginAs(user(42L, "user"));
        properties.setPublisherUserIds(List.of(42L));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(),
                assertThrows(BusinessException.class, fixture::read).getCode());
    }

    @Test
    void notLoggedInKeepsExistingLoginError() {
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        Fixture fixture = proxy(Fixture.class, new Fixture());

        BusinessException error = assertThrows(BusinessException.class, fixture::read);
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), error.getCode());
    }

    @Test
    void missingRequestContextFailsClosed() {
        RequestContextHolder.resetRequestAttributes();
        Fixture fixture = proxy(Fixture.class, new Fixture());

        assertThrows(IllegalStateException.class, fixture::read);
    }

    @Test
    void requiresPublisherOnlyForMutatingReleaseActions() {
        assertTrue(ConfigAction.PUBLISH.requiresPublisher());
        assertTrue(ConfigAction.ROLLBACK.requiresPublisher());
        assertFalse(ConfigAction.READ.requiresPublisher());
        assertFalse(ConfigAction.EDIT.requiresPublisher());
        assertFalse(ConfigAction.VALIDATE.requiresPublisher());
    }

    /**
     * AOP 边界：类内自调用<b>不</b>经过代理，注解不生效。
     *
     * <p>这不是缺陷，是需要被记住的边界：权限必须标在<b>控制器入口方法</b>上
     * （Spring MVC 调用的是代理），而不是标在 service 内部互相调用的方法上。
     * 这里把行为固定下来，防止有人把 {@code @ConfigPermission} 挪到 service
     * 内部还误以为它仍然生效。
     */
    @Test
    void selfInvocationBypassesTheAspect() {
        loginAs(user(9L, "user"));
        SelfCalling target = new SelfCalling();
        SelfCalling proxy = proxy(SelfCalling.class, target);

        // 外部调用带注解的方法 → 被拦截。
        assertEquals(ErrorCode.CONFIG_PERMISSION_DENIED.getCode(),
                assertThrows(BusinessException.class, proxy::annotated).getCode());

        // 外部调用未注解的方法，它内部再调注解方法 → 不经过代理，不被拦截。
        assertEquals("inner", proxy.outer());
    }

    /** 注解需落在真实方法上，AspectJ 才能绑定 @annotation。 */
    static class Fixture {

        @ConfigPermission(ConfigAction.READ)
        public String read() {
            return "read";
        }

        @ConfigPermission(ConfigAction.EDIT)
        public String edit() {
            return "edit";
        }

        @ConfigPermission(ConfigAction.VALIDATE)
        public String validate() {
            return "validate";
        }

        @ConfigPermission(ConfigAction.PUBLISH)
        public String publish() {
            return "publish";
        }

        @ConfigPermission(ConfigAction.ROLLBACK)
        public String rollback() {
            return "rollback";
        }
    }

    /** 用于固定「自调用不经过代理」这一 AOP 边界。 */
    static class SelfCalling {

        public String outer() {
            return inner();
        }

        @ConfigPermission(ConfigAction.READ)
        public String annotated() {
            return inner();
        }

        @ConfigPermission(ConfigAction.READ)
        public String inner() {
            return "inner";
        }
    }
}
