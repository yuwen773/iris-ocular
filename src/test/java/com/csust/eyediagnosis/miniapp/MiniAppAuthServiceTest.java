package com.csust.eyediagnosis.miniapp;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.mapper.UserWechatMapper;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.entity.UserWechat;
import com.csust.eyediagnosis.security.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiniAppAuthServiceTest {

    private WeChatCode2SessionClient weChatClient;
    private UserWechatMapper bindingMapper;
    private UserMapper userMapper;
    private PasswordService passwordService;
    private MiniAppTokenService tokenService;
    private MiniAppAuthService authService;

    @BeforeEach
    void setUp() {
        WeChatMiniAppProperties properties = new WeChatMiniAppProperties();
        properties.setAppid("wx-app");
        properties.setSecret("secret");
        weChatClient = mock(WeChatCode2SessionClient.class);
        bindingMapper = mock(UserWechatMapper.class);
        userMapper = mock(UserMapper.class);
        passwordService = mock(PasswordService.class);
        tokenService = mock(MiniAppTokenService.class);

        when(passwordService.encode(anyString())).thenReturn("$2a$12$randomhash");
        when(tokenService.issue(anyLong()))
                .thenReturn(new MiniAppTokenService.IssuedToken("tok", 2592000L));
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        });
        when(bindingMapper.insert(any(UserWechat.class))).thenReturn(1);

        authService = new MiniAppAuthService(properties, weChatClient, bindingMapper, userMapper,
                passwordService, tokenService, noOpTransactions());
    }

    /** 不连数据库：只让 TransactionTemplate 的 execute 跑通回调，事务语义由 L2 门禁覆盖。 */
    private static PlatformTransactionManager noOpTransactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    private void stubBindingLookup(UserWechat binding) {
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(binding);
    }

    @Test
    void existingBindingReusesAccountAndIssuesToken() {
        UserWechat binding = new UserWechat();
        binding.setUserId(7L);
        stubBindingLookup(binding);
        User existing = user(7L, "user");
        when(userMapper.selectById(7L)).thenReturn(existing);
        when(weChatClient.exchange("code")).thenReturn(
                new WeChatCode2SessionClient.WeChatSession("open-1", null));

        MiniAppAuthService.LoginResult result = authService.login("code");

        assertSame(existing, result.user());
        assertEquals("tok", result.token());
        assertEquals(2592000L, result.expiresInSeconds());
        verify(userMapper, never()).insert(any(User.class));
        verify(bindingMapper, never()).insert(any(UserWechat.class));
    }

    @Test
    void firstLoginCreatesWechatAccountAndBinding() {
        stubBindingLookup(null);
        when(weChatClient.exchange("code")).thenReturn(
                new WeChatCode2SessionClient.WeChatSession("open-2", "union-2"));

        MiniAppAuthService.LoginResult result = authService.login("code");

        assertEquals(100L, result.user().getId());
        assertEquals("wx_open-2", result.user().getUserAccount());
        assertEquals("user", result.user().getUserRole());
        // 密码列 NOT NULL，写入的是随机 BCrypt 摘要，不是空串。
        assertEquals("$2a$12$randomhash", result.user().getUserPassword());

        ArgumentCaptor<UserWechat> captor = ArgumentCaptor.forClass(UserWechat.class);
        verify(bindingMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getUserId());
        assertEquals("wx-app", captor.getValue().getAppid());
        assertEquals("open-2", captor.getValue().getOpenid());
        assertEquals("union-2", captor.getValue().getUnionid());
    }

    @Test
    void bannedAccountCannotLogIn() {
        UserWechat binding = new UserWechat();
        binding.setUserId(8L);
        stubBindingLookup(binding);
        when(userMapper.selectById(8L)).thenReturn(user(8L, "ban"));
        when(weChatClient.exchange("code")).thenReturn(
                new WeChatCode2SessionClient.WeChatSession("open-3", null));

        BusinessException error = assertThrows(BusinessException.class, () -> authService.login("code"));

        assertEquals(40300, error.getCode());
        verify(tokenService, never()).issue(anyLong());
    }

    @Test
    void bindingToDeletedAccountRecreatesUserAndRebinds() {
        UserWechat binding = new UserWechat();
        binding.setUserId(5L);
        binding.setAppid("wx-app");
        binding.setOpenid("open-4");
        stubBindingLookup(binding);
        when(userMapper.selectById(5L)).thenReturn(null);
        when(weChatClient.exchange("code")).thenReturn(
                new WeChatCode2SessionClient.WeChatSession("open-4", null));

        MiniAppAuthService.LoginResult result = authService.login("code");

        assertEquals(100L, result.user().getId());
        verify(bindingMapper).updateById(binding);
        assertEquals(100L, binding.getUserId());
    }

    @Test
    void concurrentFirstLoginFallsBackToTheWinnerAfterRollback() {
        when(weChatClient.exchange("code")).thenReturn(
                new WeChatCode2SessionClient.WeChatSession("open-5", null));
        UserWechat winnerBinding = new UserWechat();
        winnerBinding.setUserId(99L);
        // 第一次查不到绑定 → 建号 → 写绑定撞唯一键；回滚后第二次查到赢家的绑定。
        when(bindingMapper.selectOne(any(Wrapper.class))).thenReturn(null, winnerBinding);
        when(userMapper.selectById(99L)).thenReturn(user(99L, "user"));
        when(bindingMapper.insert(any(UserWechat.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_appid_openid"));

        MiniAppAuthService.LoginResult result = authService.login("code");

        assertEquals(99L, result.user().getId());
        // 落败请求只写过一次绑定；它新建的账号随事务回滚，不留在库里。
        verify(bindingMapper).insert(any(UserWechat.class));
    }

    @Test
    void loginResultRequiresTokenAndUser() {
        User user = user(1L, "user");
        assertNotNull(new MiniAppAuthService.LoginResult("tok", 1L, user).token());
        assertThrows(NullPointerException.class,
                () -> new MiniAppAuthService.LoginResult(null, 1L, user));
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
