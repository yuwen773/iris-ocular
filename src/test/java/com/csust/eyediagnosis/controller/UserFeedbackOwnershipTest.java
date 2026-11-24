package com.csust.eyediagnosis.controller;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackDecideDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackQueryDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackSubmitDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;
import com.csust.eyediagnosis.service.UserFeedbackAttachmentService;
import com.csust.eyediagnosis.service.UserFeedbackLifecycleService;
import com.csust.eyediagnosis.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制层的归属边界：会话用户是唯一身份来源，请求体与路径参数都不得改写「这条反馈属于谁」。
 *
 * <p>服务层的裁决（谁能看哪一条、别人的 taskId 会被拒）由
 * {@code UserFeedbackLifecycleServiceTest} 覆盖；这里只钉住控制层不能提前放权：
 * 一旦有人在 DTO 里加 {@code userId}、或把 {@code @AuthCheck} 摘掉，本类先红。
 */
class UserFeedbackOwnershipTest {

    private UserFeedbackLifecycleService feedbacks;
    private UserFeedbackAttachmentService attachments;
    private UserService users;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private User loginUser;

    @BeforeEach
    void setUp() {
        feedbacks = mock(UserFeedbackLifecycleService.class);
        attachments = mock(UserFeedbackAttachmentService.class);
        users = mock(UserService.class);
        loginUser = new User();
        loginUser.setId(10L);
        loginUser.setUserRole("user");
        when(users.getLoginUser(any())).thenReturn(loginUser);

        // 绕过 AuthInterceptor 与 @AuthCheck AOP：本类测的是控制器如何取用会话用户，
        // 注解本身用反射单独断言（见 adminEndpointsRequireAdminRole）。
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserFeedbackController(feedbacks, attachments, users),
                        new AdminUserFeedbackController(feedbacks, users))
                .build();
    }

    @Test
    void submitUsesSessionUserAndIgnoresClientSuppliedOwner() throws Exception {
        when(feedbacks.submit(any(), any())).thenReturn(new UserFeedbackVO());

        // 恶意请求体：想自己指定提交人，甚至直接写一个已解决状态
        String body = "{\"category\":\"SUGGESTION\",\"content\":\"希望支持批量导出\","
                + "\"userId\":999,\"status\":\"RESOLVED\",\"handledById\":999}";
        mockMvc.perform(post("/feedback").contentType("application/json").content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<UserFeedbackSubmitDTO> captor = ArgumentCaptor.forClass(UserFeedbackSubmitDTO.class);
        verify(feedbacks).submit(captor.capture(), eq(loginUser));
        UserFeedbackSubmitDTO passed = captor.getValue();
        assertEquals("SUGGESTION", passed.getCategory());
        assertEquals("希望支持批量导出", passed.getContent());
        assertFalse(objectMapper.valueToTree(passed).has("userId"), "提交人只能来自会话");
        assertFalse(objectMapper.valueToTree(passed).has("status"), "状态只能由管理端流转");
    }

    @Test
    void mineAlwaysScopesToSessionUser() throws Exception {
        mockMvc.perform(post("/feedback/mine")
                        .contentType("application/json")
                        .content("{\"current\":1,\"pageSize\":5,\"userId\":999}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserFeedbackQueryDTO> captor = ArgumentCaptor.forClass(UserFeedbackQueryDTO.class);
        verify(feedbacks).pageMine(captor.capture(), eq(loginUser));
        assertFalse(objectMapper.valueToTree(captor.getValue()).has("userId"),
                "查询条件不得携带 userId：用户侧过滤恒由服务层按会话注入");
    }

    @Test
    void detailAndAttachmentReadsPassSessionUser() throws Exception {
        when(feedbacks.detail(eq(42L), any())).thenReturn(new UserFeedbackVO());
        when(feedbacks.attachmentUrl(eq(42L), any())).thenReturn("https://cos/signed");

        mockMvc.perform(get("/feedback/42")).andExpect(status().isOk());
        mockMvc.perform(get("/feedback/42/attachment")).andExpect(status().isOk());

        verify(feedbacks).detail(42L, loginUser);
        verify(feedbacks).attachmentUrl(42L, loginUser);
    }

    @Test
    void uploadAttachmentUsesSessionUserId() throws Exception {
        when(attachments.upload(any(), eq(10L))).thenReturn("feedback/10/abc.png");
        MockMultipartFile file = new MockMultipartFile("file", "shot.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/feedback/attachment").file(file))
                .andExpect(status().isOk());

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(attachments).upload(captor.capture(), eq(10L));
        assertEquals("shot.png", captor.getValue().getOriginalFilename());
    }

    @Test
    void adminWritesPassSessionUserAndBodyPayload() throws Exception {
        when(feedbacks.pendingCount(any())).thenReturn(3L);
        when(feedbacks.changeStatus(eq(7L), any(), any())).thenReturn(new UserFeedbackVO());
        when(feedbacks.reply(eq(7L), any(), any())).thenReturn(new UserFeedbackVO());

        mockMvc.perform(get("/admin/feedback/pending-count"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/feedback/7/status")
                        .contentType("application/json")
                        .content("{\"status\":\"RESOLVED\",\"reply\":\"已修复\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/feedback/7/reply")
                        .contentType("application/json")
                        .content("{\"reply\":\"已修复\"}"))
                .andExpect(status().isOk());

        verify(feedbacks).pendingCount(loginUser);
        ArgumentCaptor<UserFeedbackDecideDTO> decide = ArgumentCaptor.forClass(UserFeedbackDecideDTO.class);
        verify(feedbacks).changeStatus(eq(7L), decide.capture(), eq(loginUser));
        assertEquals("RESOLVED", decide.getValue().getStatus());
        assertEquals("已修复", decide.getValue().getReply());
        verify(feedbacks).reply(7L, "已修复", loginUser);
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        assertEquals(AuthConstant.ADMIN_ROLE, adminRoleOf("page", UserFeedbackQueryDTO.class, jakarta.servlet.http.HttpServletRequest.class));
        assertEquals(AuthConstant.ADMIN_ROLE, adminRoleOf("pendingCount", jakarta.servlet.http.HttpServletRequest.class));
        assertEquals(AuthConstant.ADMIN_ROLE, adminRoleOf("detail", Long.class, jakarta.servlet.http.HttpServletRequest.class));
        assertEquals(AuthConstant.ADMIN_ROLE, adminRoleOf("changeStatus", Long.class, UserFeedbackDecideDTO.class, jakarta.servlet.http.HttpServletRequest.class));
        assertEquals(AuthConstant.ADMIN_ROLE, adminRoleOf("reply", Long.class, com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackReplyDTO.class, jakarta.servlet.http.HttpServletRequest.class));
        assertEquals(AuthConstant.ADMIN_ROLE, adminRoleOf("attachment", Long.class, jakarta.servlet.http.HttpServletRequest.class));
    }

    @Test
    void feedbackResponsesAreNotCacheable() throws Exception {
        when(feedbacks.detail(eq(42L), any())).thenReturn(new UserFeedbackVO());
        mockMvc.perform(get("/feedback/42"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/admin/feedback/pending-count"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static String adminRoleOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AdminUserFeedbackController.class.getMethod(methodName, parameterTypes);
        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
        assertNotNull(authCheck, methodName + " 缺少 @AuthCheck");
        return authCheck.mustRole();
    }
}
