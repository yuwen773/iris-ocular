package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.config.UserFeedbackSettings;
import com.csust.eyediagnosis.constant.NotificationType;
import com.csust.eyediagnosis.constant.UserFeedbackStatus;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.UserFeedbackMapper;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackDecideDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackSubmitDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.entity.UserFeedback;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户反馈写侧的纯单元测试：校验、归属与状态迁移的判定全部不依赖 Spring 容器。
 *
 * <p>被测的本体是「在什么状态下允许写什么」，因此把持久层 mock 掉，
 * 只断言调用方看得到的行为（写入什么、报哪个错误码、有没有触发补偿删除）。
 */
class UserFeedbackLifecycleServiceTest {

    private UserFeedbackMapper mapper;
    private UserFeedbackService feedbackService;
    private UserFeedbackAttachmentService attachments;
    private ValueOperations<String, String> values;
    private UserFeedbackSettings settings;
    private NotificationService notifications;
    private UserFeedbackLifecycleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(UserFeedbackMapper.class);
        feedbackService = mock(UserFeedbackService.class);
        attachments = mock(UserFeedbackAttachmentService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);

        // 配置对象的默认值与 application.yml 一致；要关掉开关的用例改 settings 再建服务。
        settings = new UserFeedbackSettings();
        notifications = mock(NotificationService.class);
        service = new UserFeedbackLifecycleService(mapper, feedbackService, attachments, redis, settings,
                notifications);
    }

    /* ── 提交 ─────────────────────────────────────────────── */

    @Test
    void submitAcceptsOwnTaskAndInsertsPendingRow() {
        when(mapper.countOwnedDiagnosisTask("task-1", 7L)).thenReturn(1);
        // 真实插入会回填雪花 id：这里模拟同样的效果，否则回读那一步拿不到 id
        when(mapper.insert(any(UserFeedback.class))).thenAnswer(invocation -> {
            ((UserFeedback) invocation.getArgument(0)).setId(1L);
            return 1;
        });
        when(mapper.selectById(any())).thenReturn(new UserFeedback());
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        UserFeedbackVO vo = service.submit(submit("SUGGESTION", "希望支持批量导出",
                "DIAGNOSIS_TASK", "task-1", null), user(7L, "user"));

        assertNotNull(vo);
        var captor = org.mockito.ArgumentCaptor.forClass(UserFeedback.class);
        verify(mapper).insert(captor.capture());
        UserFeedback saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertEquals(UserFeedbackStatus.PENDING, saved.getStatus());
        assertEquals("DIAGNOSIS_TASK", saved.getTargetType());
        assertEquals("task-1", saved.getTargetId());
        assertNull(saved.getReply());
    }

    @Test
    void submitRejectsSomebodyElsesTask() {
        when(mapper.countOwnedDiagnosisTask("task-1", 7L)).thenReturn(0);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("ISSUE", "看不到记录", "DIAGNOSIS_TASK", "task-1", null),
                        user(7L, "user")));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), error.getCode());
        verify(mapper, never()).insert(any(UserFeedback.class));
    }

    @Test
    void submitRejectsUnknownCategory() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("COMPLAINT", "随便说说", null, null, null),
                        user(7L, "user")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    void submitRejectsOverlongContent() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "字".repeat(2001), null, null, null),
                        user(7L, "user")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    void submitRejectsTargetTypeWithoutId() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "关联了但没给 id", "AGENT_SESSION", null, null),
                        user(7L, "user")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    void submitRejectsAttachmentOutsideOwnPrefix() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "截图", null, null,
                        "feedback/99/abc.png"), user(7L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
    }

    @Test
    void submitRejectsPathTraversalInAttachmentKey() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "截图", null, null,
                        "feedback/7/../99/abc.png"), user(7L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
    }

    @Test
    void submitRejectsScreenshotThatWasNeverUploaded() {
        // 前缀对、格式对，但对象不在存储里：只比前缀会存下一个指向空对象的引用，
        // 管理端详情页就只剩一张裂图。
        when(attachments.exists("feedback/7/abc.png")).thenReturn(false);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "截图", null, null,
                        "feedback/7/abc.png"), user(7L, "user")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        verify(mapper, never()).insert(any(UserFeedback.class));
    }

    @Test
    void submitAcceptsScreenshotThatExistsInStorage() {
        when(attachments.exists("feedback/7/abc.png")).thenReturn(true);
        when(mapper.insert(any(UserFeedback.class))).thenAnswer(invocation -> {
            ((UserFeedback) invocation.getArgument(0)).setId(1L);
            return 1;
        });
        when(mapper.selectById(any())).thenReturn(new UserFeedback());
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        service.submit(submit("OTHER", "截图", null, null, "feedback/7/abc.png"), user(7L, "user"));

        var captor = org.mockito.ArgumentCaptor.forClass(UserFeedback.class);
        verify(mapper).insert(captor.capture());
        assertEquals("feedback/7/abc.png", captor.getValue().getAttachmentObjectKey());
        assertEquals("png", captor.getValue().getAttachmentFormat());
    }

    @Test
    void submitDiscardsUploadedScreenshotWhenInsertFails() {
        when(attachments.exists("feedback/7/abc.png")).thenReturn(true);
        when(mapper.insert(any(UserFeedback.class))).thenReturn(0);
        assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "截图", null, null,
                        "feedback/7/abc.png"), user(7L, "user")));
        verify(attachments).discardQuietly("feedback/7/abc.png");
    }

    @Test
    void submitRejectsRateLimitExceeded() {
        when(values.increment(anyString())).thenReturn(6L);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "刷屏", null, null, null), user(7L, "user")));
        // 429 语义的独立码：前端据此提示「稍后再试」，不当作服务故障
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), error.getCode());
        verify(mapper, never()).insert(any(UserFeedback.class));
    }

    @Test
    void submitIsAllowedWhenRateLimitIsDisabled() {
        settings.setRateLimitPerMinute(0);
        when(mapper.insert(any(UserFeedback.class))).thenAnswer(invocation -> {
            ((UserFeedback) invocation.getArgument(0)).setId(1L);
            return 1;
        });
        when(mapper.selectById(any())).thenReturn(new UserFeedback());
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        service.submit(submit("OTHER", "不限流", null, null, null), user(7L, "user"));

        verify(values, never()).increment(anyString());
    }

    @Test
    void submitRejectsAnonymous() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "匿名", null, null, null), null));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), error.getCode());
    }

    @Test
    void submitIsBlockedWhenFeatureDisabled() {
        settings.setEnabled(false);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(submit("OTHER", "关掉了", null, null, null), user(7L, "user")));
        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), error.getCode());
    }

    /* ── 状态流转 ─────────────────────────────────────────── */

    @Test
    void changeStatusRejectsIllegalTransition() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.RESOLVED, "已回复"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeStatus(1L, decide("IGNORED", null), user(1L, "admin")));
        assertEquals(ErrorCode.USER_FEEDBACK_CONFLICT.getCode(), error.getCode());
    }

    @Test
    void changeStatusRequiresReplyBeforeResolving() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeStatus(1L, decide("RESOLVED", null), user(1L, "admin")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    void changeStatusReportsConflictWhenAnotherAdminMovedItFirst() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        // 条件更新影响 0 行：读到的状态已经不是当前状态
        when(mapper.update(isNull(), any())).thenReturn(0);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeStatus(1L, decide("PROCESSING", null), user(1L, "admin")));
        assertEquals(ErrorCode.USER_FEEDBACK_CONFLICT.getCode(), error.getCode());
    }

    @Test
    void changeStatusResolvesWithReply() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PROCESSING, null));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        assertNotNull(service.changeStatus(1L, decide("RESOLVED", "已修复，感谢反馈"),
                user(1L, "admin")));
        verify(mapper).update(isNull(), any());
    }

    @Test
    void changeStatusRejectsNonAdmin() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeStatus(1L, decide("PROCESSING", null), user(7L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
    }

    /* ── 回复与可见性 ─────────────────────────────────────── */

    @Test
    void replyMovesPendingFeedbackIntoProcessing() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        assertNotNull(service.reply(1L, "我们看到了，正在排查", user(1L, "admin")));
        verify(mapper).update(isNull(), any());
    }

    @Test
    void replyRejectsClosedFeedback() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.RESOLVED, "已回复"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.reply(1L, "补充一句", user(1L, "admin")));
        assertEquals(ErrorCode.USER_FEEDBACK_CONFLICT.getCode(), error.getCode());
    }

    /* ── 通知（旁路） ─────────────────────────────────────── */

    @Test
    void replyNotifiesTheAuthorAfterCommit() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        service.reply(1L, "我们看到了，正在排查", user(1L, "admin"));

        // 接收人是提交人（7），不是处理的管理员（1）；事务提交后才写入
        verify(notifications).notifyAfterCommit(7L, NotificationType.FEEDBACK_REPLIED, "1");
    }

    @Test
    void statusChangeWithReplyNotifiesOnce() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PROCESSING, null));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        service.changeStatus(1L, decide("RESOLVED", "已修复，感谢反馈"), user(1L, "admin"));

        verify(notifications, times(1))
                .notifyAfterCommit(7L, NotificationType.FEEDBACK_REPLIED, "1");
    }

    @Test
    void statusChangeWithoutReplyDoesNotNotify() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());

        // 结案没有写回复：改状态本身不是对用户说的话，不该发通知
        service.changeStatus(1L, decide("IGNORED", null), user(1L, "admin"));

        verify(notifications, never()).notifyAfterCommit(any(), any(), any());
    }

    @Test
    void detailRejectsReadingSomebodyElsesFeedback() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.detail(1L, user(8L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
    }

    @Test
    void detailAllowsOwnerAndAdmin() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        when(feedbackService.getVO(any())).thenReturn(new UserFeedbackVO());
        assertNotNull(service.detail(1L, user(7L, "user")));
        assertNotNull(service.detail(1L, user(99L, "admin")));
    }

    @Test
    void attachmentUrlRejectsFeedbackWithoutScreenshot() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 7L, UserFeedbackStatus.PENDING, null));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.attachmentUrl(1L, user(7L, "user")));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), error.getCode());
    }

    @Test
    void attachmentUrlSignsLinkForOwner() {
        UserFeedback row = row(1L, 7L, UserFeedbackStatus.PENDING, null);
        row.setAttachmentObjectKey("feedback/7/abc.png");
        when(mapper.selectById(1L)).thenReturn(row);
        when(attachments.presignedUrl("feedback/7/abc.png")).thenReturn("https://signed");
        assertEquals("https://signed", service.attachmentUrl(1L, user(7L, "user")));
    }

    /* ── 夹具 ─────────────────────────────────────────────── */

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private static UserFeedback row(long id, long userId, String status, String reply) {
        UserFeedback row = new UserFeedback();
        row.setId(id);
        row.setUserId(userId);
        row.setStatus(status);
        row.setReply(reply);
        return row;
    }

    private static UserFeedbackSubmitDTO submit(String category, String content,
                                                String targetType, String targetId,
                                                String attachmentKey) {
        UserFeedbackSubmitDTO dto = new UserFeedbackSubmitDTO();
        dto.setCategory(category);
        dto.setContent(content);
        dto.setTargetType(targetType);
        dto.setTargetId(targetId);
        dto.setAttachmentObjectKey(attachmentKey);
        return dto;
    }

    private static UserFeedbackDecideDTO decide(String status, String reply) {
        UserFeedbackDecideDTO dto = new UserFeedbackDecideDTO();
        dto.setStatus(status);
        dto.setReply(reply);
        return dto;
    }
}
