package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.constant.UserConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.security.PasswordService;
import com.csust.eyediagnosis.service.impl.UserServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话生命周期：登录轮换会话标识、登出销毁会话、匿名读取不创建会话。
 *
 * <p>这三条是 Cookie 会话系统里应主动关闭的 Session Fixation 面：登录前后沿用同一
 * SessionId、登出只删属性不销毁容器、匿名请求顺手建会话。
 */
class UserSessionLifecycleTest {

    private UserMapper userMapper;
    private PasswordService passwordService;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordService = mock(PasswordService.class);
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "passwordService", passwordService);
    }

    private User account() {
        User user = new User();
        user.setId(7L);
        user.setUserAccount("doctor-7");
        user.setUserPassword("bcrypt-hash");
        user.setUserRole(AuthConstant.DEFAULT_ROLE);
        return user;
    }

    @Test
    void loginRotatesTheSessionIdBeforeStoringTheLoginState() {
        User user = account();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordService.verify("password1", "bcrypt-hash"))
                .thenReturn(new PasswordService.PasswordVerification(true, false));

        HttpSession session = mock(HttpSession.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(true)).thenReturn(session);
        when(request.getSession()).thenReturn(session);
        when(request.changeSessionId()).thenReturn("rotated-session-id");

        userService.userLogin("doctor-7", "password1", request);

        // 顺序也要钉住：先有会话、再换标识、最后写登录态。
        // 反过来则等于「先写登录态再用旧标识」，攻击者拿旧标识就能读到它。
        InOrder order = inOrder(request, session);
        order.verify(request).getSession(true);
        order.verify(request).changeSessionId();
        order.verify(session).setAttribute(eq(UserConstant.USER_LOGIN_STATE), any());
    }

    @Test
    void logoutDestroysTheSessionInsteadOfOnlyRemovingTheAttribute() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(UserConstant.USER_LOGIN_STATE)).thenReturn(account());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(session);

        userService.userLogout(request);

        verify(session).invalidate();
    }

    @Test
    void anonymousLoginStateReadDoesNotCreateASession() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(null);

        assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        // 无参 getSession() 会顺手建一个会话：匿名请求不该留下会话容器。
        verify(request, never()).getSession();
    }

    @Test
    void bannedAccountCannotLogIn() {
        User user = account();
        user.setUserRole(AuthConstant.BAN_ROLE);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordService.verify(anyString(), anyString()))
                .thenReturn(new PasswordService.PasswordVerification(true, false));

        HttpServletRequest request = mock(HttpServletRequest.class);
        assertThrows(BusinessException.class, () -> userService.userLogin("doctor-7", "password1", request));
        // 封禁账号连会话标识都不该轮换。
        verify(request, never()).changeSessionId();
    }
}
