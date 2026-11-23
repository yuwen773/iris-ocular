package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.model.dto.user.UserUpdateDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员账号的不可删除 / 不可降级契约。
 *
 * <p>批量通道（{@code deleteUsers} / {@code updateUserRole}）早就拒绝动管理员，
 * 单条通道如果放行，就会留下一个「批量拦得住、单条拦不住」的洞：任何管理员
 * 都能顺手把另一位管理员删掉或降级。这里把两条单条通道钉住，并验证两个管理员
 * 同时互删时谁都不会成功。
 */
class UserAdminProtectionTest {

    private UserMapper userMapper;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    private User user(long id, String role) {
        User account = new User();
        account.setId(id);
        account.setUserAccount("account-" + id);
        account.setUserRole(role);
        return account;
    }

    @Test
    void singleDeleteRefusesAdminAccount() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, AuthConstant.ADMIN_ROLE));

        BusinessException error = assertThrows(BusinessException.class, () -> userService.deleteUser(1L));

        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), error.getCode());
        verify(userMapper, never()).deleteById(anyLong());
    }

    @Test
    void singleDeleteStillRemovesOrdinaryAccount() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "user"));
        when(userMapper.deleteById(2L)).thenReturn(1);

        assertTrue(userService.deleteUser(2L));
        verify(userMapper).deleteById(2L);
    }

    @Test
    void singleUpdateRefusesAdminDemotion() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, AuthConstant.ADMIN_ROLE));
        UserUpdateDTO input = new UserUpdateDTO();
        input.setId(1L);
        input.setUserRole("user");

        BusinessException error = assertThrows(BusinessException.class, () -> userService.updateUser(input));

        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), error.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void singleUpdateStillAllowsOrdinaryRoleChange() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "user"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        UserUpdateDTO input = new UserUpdateDTO();
        input.setId(2L);
        input.setUserRole("ban");

        assertTrue(userService.updateUser(input));
    }

    /**
     * 两个管理员同时删对方：两条请求都必须被拒。这里不是在验证数据库隔离级别，
     * 而是验证「权限判断本身不依赖调用者是谁」——谁先谁后都不该有一个人删成。
     */
    @Test
    void twoAdminsDeletingEachOtherBothFail() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, AuthConstant.ADMIN_ROLE));
        when(userMapper.selectById(3L)).thenReturn(user(3L, AuthConstant.ADMIN_ROLE));
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> first = () -> deleteOutcome(1L, 3L);
            Callable<Integer> second = () -> deleteOutcome(3L, 1L);
            List<Integer> codes = pool.invokeAll(List.of(first, second), 15, TimeUnit.SECONDS)
                    .stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
            assertEquals(List.of(ErrorCode.FORBIDDEN_ERROR.getCode(), ErrorCode.FORBIDDEN_ERROR.getCode()), codes);
        } finally {
            pool.shutdownNow();
        }
        verify(userMapper, never()).deleteById(anyLong());
    }

    /** 返回业务错误码；能删成功则返回 0（本用例里不该出现）。 */
    private int deleteOutcome(long actor, long target) {
        try {
            userService.deleteUser(target);
            return 0;
        } catch (BusinessException e) {
            return e.getCode();
        }
    }
}
