package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.constant.UserConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.vo.user.LoginUserVO;
import com.csust.eyediagnosis.security.PasswordService;
import com.csust.eyediagnosis.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 封禁账号（userRole = "ban"）的登录与会话拦截契约。
 *
 * <p>文档口径见 {@link UserServiceImpl}（{@code ASSIGNABLE_ROLES} 注释）：
 * {@code ban} 表示账号保留但不能登录。本用例验证两条路径：
 * 1) 被封禁账号不能凭正确密码登录或建立会话；
 * 2) 已登录账号被管理员封禁后，下一次 {@code getLoginUser} 立即拒绝并清掉会话态。
 */
class UserBanEnforcementTest {

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

    @Test
    void bannedUserCannotLoginAndDoesNotEstablishSession() {
        User banned = new User();
        banned.setId(42L);
        banned.setUserAccount("banneduser");
        banned.setUserPassword("hashed");
        banned.setUserRole("ban");

        when(userMapper.selectOne(any())).thenReturn(banned);
        when(passwordService.verify("Password123", "hashed"))
                .thenReturn(new PasswordService.PasswordVerification(true, false));

        MockHttpServletRequest request = new MockHttpServletRequest();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.userLogin("banneduser", "Password123", request));

        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), ex.getCode());
        assertNull(request.getSession(false));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void activeSessionIsRejectedAndClearedOnceUserIsBanned() {
        User cachedInSession = new User();
        cachedInSession.setId(42L);
        cachedInSession.setUserRole(AuthConstant.DEFAULT_ROLE);

        User bannedInDb = new User();
        bannedInDb.setId(42L);
        bannedInDb.setUserRole("ban");

        when(userMapper.selectById(42L)).thenReturn(bannedInDb);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, cachedInSession);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.getLoginUser(request));

        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), ex.getCode());
        assertNull(request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE));
    }

    @Test
    void activeUserCanStillLoginAndResolveSession() {
        User active = new User();
        active.setId(7L);
        active.setUserAccount("normaluser");
        active.setUserPassword("hashed");
        active.setUserRole(AuthConstant.DEFAULT_ROLE);

        when(userMapper.selectOne(any())).thenReturn(active);
        when(userMapper.selectById(7L)).thenReturn(active);
        when(passwordService.verify("Password123", "hashed"))
                .thenReturn(new PasswordService.PasswordVerification(true, false));

        MockHttpServletRequest request = new MockHttpServletRequest();
        LoginUserVO vo = userService.userLogin("normaluser", "Password123", request);
        assertNotNull(vo);
        assertEquals(7L, vo.getId());

        User resolved = userService.getLoginUser(request);
        assertEquals(7L, resolved.getId());
    }
}
