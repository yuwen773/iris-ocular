package com.csust.eyediagnosis.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.model.dto.notification.NotificationQueryDTO;
import com.csust.eyediagnosis.model.dto.notification.NotificationReadAllDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.vo.notification.NotificationVO;
import com.csust.eyediagnosis.service.NotificationService;
import com.csust.eyediagnosis.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知控制层的归属边界：接收人只能是会话用户。
 *
 * <p>与 {@code UserFeedbackOwnershipTest} 同一手法：绕过拦截器与 AOP 直接打控制器，
 * 钉住「查询条件里没有 userId」「路径 id 不改变接收人」，注解本身单独用反射断言。
 */
class NotificationOwnershipTest {

    private NotificationService notifications;
    private UserService users;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private User loginUser;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationService.class);
        users = mock(UserService.class);
        loginUser = new User();
        loginUser.setId(10L);
        loginUser.setUserRole("user");
        when(users.getLoginUser(any())).thenReturn(loginUser);
        when(notifications.page(any(), any())).thenReturn(new Page<>(1, 20, 0));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(notifications, users))
                .build();
    }

    @Test
    void listUsesSessionUserAndIgnoresClientSuppliedOwner() throws Exception {
        mockMvc.perform(get("/notifications")
                        .param("current", "1")
                        .param("pageSize", "20")
                        .param("unreadOnly", "true")
                        .param("type", "FEEDBACK_REPLIED")
                        .param("userId", "999"))
                .andExpect(status().isOk());

        ArgumentCaptor<NotificationQueryDTO> captor = ArgumentCaptor.forClass(NotificationQueryDTO.class);
        verify(notifications).page(eq(10L), captor.capture());
        NotificationQueryDTO passed = captor.getValue();
        assertEquals("FEEDBACK_REPLIED", passed.getType());
        assertEquals(Boolean.TRUE, passed.getUnreadOnly());
        assertFalse(objectMapper.valueToTree(passed).has("userId"),
                "查询条件不得携带 userId：接收人恒取会话");
    }

    @Test
    void unreadCountUsesSessionUser() throws Exception {
        when(notifications.unreadCount(10L)).thenReturn(3L);
        mockMvc.perform(get("/notifications/unread-count")).andExpect(status().isOk());
        verify(notifications).unreadCount(10L);
    }

    @Test
    void markReadTakesSessionUserAndPathId() throws Exception {
        when(notifications.markRead(any(), any())).thenReturn(new NotificationVO());
        mockMvc.perform(post("/notifications/42/read")).andExpect(status().isOk());
        verify(notifications).markRead(10L, 42L);
    }

    @Test
    void markAllReadCarriesTheTypeFilterAndSessionUser() throws Exception {
        mockMvc.perform(post("/notifications/read-all")
                        .contentType("application/json")
                        .content("{\"type\":\"FEEDBACK_REPLIED\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<NotificationReadAllDTO> captor = ArgumentCaptor.forClass(NotificationReadAllDTO.class);
        verify(notifications).markAllRead(eq(10L), captor.capture());
        assertEquals("FEEDBACK_REPLIED", captor.getValue().getType());
    }

    @Test
    void markAllReadWorksWithoutABody() throws Exception {
        when(notifications.markAllRead(any(), any())).thenReturn(0);
        mockMvc.perform(post("/notifications/read-all")).andExpect(status().isOk());
        verify(notifications).markAllRead(10L, null);
    }

    @Test
    void everyEndpointRequiresLogin() throws Exception {
        for (Method method : NotificationController.class.getDeclaredMethods()) {
            if (!isEndpoint(method)) continue;
            assertNotNull(method.getAnnotation(AuthCheck.class),
                    method.getName() + " 缺少 @AuthCheck：通知是个人消息，任何入口都必须先登录");
            assertEquals("", method.getAnnotation(AuthCheck.class).mustRole());
        }
    }

    @Test
    void notificationResponsesAreNotCacheable() throws Exception {
        when(notifications.unreadCount(10L)).thenReturn(0L);
        mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                || method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class);
    }
}
