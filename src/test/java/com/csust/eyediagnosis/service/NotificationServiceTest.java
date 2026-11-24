package com.csust.eyediagnosis.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.constant.NotificationType;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.UserNotificationMapper;
import com.csust.eyediagnosis.model.dto.notification.NotificationQueryDTO;
import com.csust.eyediagnosis.model.dto.notification.NotificationReadAllDTO;
import com.csust.eyediagnosis.model.entity.UserNotification;
import com.csust.eyediagnosis.model.vo.notification.NotificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知写侧与读侧的纯单元测试（持久层 mock，不起 Spring 容器）。
 *
 * <p>钉住四件事：文案在发送时定稿、未知类型不写库、通知写失败不打断主流程、
 * 「提交后写入」真的等到提交。
 */
class NotificationServiceTest {

    private UserNotificationMapper mapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserNotificationMapper.class);
        service = new NotificationService(mapper);
    }

    /* ── 写侧 ─────────────────────────────────────────────── */

    @Test
    void notifyFreezesCopyAndPointerOnly() {
        service.notify(7L, NotificationType.DIAGNOSIS_READY, "2f6c1b7a-9d3e-4c55-8e21-0a1b2c3d4e5f");

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        verify(mapper).insert(captor.capture());
        UserNotification row = captor.getValue();
        assertEquals(7L, row.getUserId());
        assertEquals(NotificationType.DIAGNOSIS_READY, row.getType());
        assertEquals("判读结果已出", row.getTitle());
        // 正文只带任务短引用，不带结论 / 病名
        assertEquals("任务 3d4e5f 的影像分析已完成，等待医生复核。", row.getBody());
        assertEquals("DIAGNOSIS_TASK", row.getRefType());
        assertEquals("2f6c1b7a-9d3e-4c55-8e21-0a1b2c3d4e5f", row.getRefId());
        assertNotNull(row.getCreateTime());
    }

    @Test
    void unknownTypeIsIgnoredInsteadOfStored() {
        service.notify(7L, "SOMETHING_NEW", "ref-1");
        verifyNoInteractions(mapper);
    }

    @Test
    void notificationFailureNeverBreaksTheCaller() {
        when(mapper.insert(any(UserNotification.class))).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.notify(7L, NotificationType.FEEDBACK_REPLIED, "1"));
    }

    @Test
    void notifyAfterCommitWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyAfterCommit(7L, NotificationType.REVIEW_DONE, "task-1");
            verify(mapper, never()).insert(any(UserNotification.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(mapper).insert(any(UserNotification.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void notifyAfterCommitWithoutTransactionWritesImmediately() {
        service.notifyAfterCommit(7L, NotificationType.FEEDBACK_REPLIED, "1");
        verify(mapper).insert(any(UserNotification.class));
    }

    @Test
    void taskOwnerNotificationWaitsForTheCommit() {
        when(mapper.selectTaskOwner("task-1")).thenReturn(9L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyTaskOwnerAfterCommit("task-1", NotificationType.REVIEW_DONE);
            // 事务没提交前既不查归属也不写通知
            verify(mapper, never()).selectTaskOwner(any());
            verify(mapper, never()).insert(any(UserNotification.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
            verify(mapper).insert(captor.capture());
            assertEquals(9L, captor.getValue().getUserId());
            assertEquals(NotificationType.REVIEW_DONE, captor.getValue().getType());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void taskOwnerNotificationWithoutTransactionWritesImmediately() {
        when(mapper.selectTaskOwner("task-1")).thenReturn(9L);
        service.notifyTaskOwnerAfterCommit("task-1", NotificationType.REVIEW_DONE);
        verify(mapper).insert(any(UserNotification.class));
    }

    @Test
    void taskNotificationsGoToTheTaskOwner() {
        when(mapper.selectTaskOwner("task-1")).thenReturn(9L);
        service.notifyTaskOwner("task-1", NotificationType.REVIEW_DONE);

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        verify(mapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getUserId());
        assertEquals("task-1", captor.getValue().getRefId());
    }

    @Test
    void missingTaskOwnerIsSilentlySkipped() {
        when(mapper.selectTaskOwner("gone")).thenReturn(null);
        service.notifyTaskOwner("gone", NotificationType.REVIEW_DONE);
        verify(mapper, never()).insert(any(UserNotification.class));
    }

    /* ── 读侧 ─────────────────────────────────────────────── */

    @Test
    void pageRejectsUnknownTypeFilter() {
        NotificationQueryDTO query = new NotificationQueryDTO();
        query.setType("BOGUS");
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.page(7L, query));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pageClampsPageSizeToTheServerMaximum() {
        when(mapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationQueryDTO query = new NotificationQueryDTO();
        query.setPageSize(1000);

        service.page(7L, query);

        ArgumentCaptor<Page<UserNotification>> captor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(captor.capture(), any());
        assertEquals(50L, captor.getValue().getSize());
    }

    @Test
    void markReadRejectsSomebodyElsesNotification() {
        when(mapper.selectOne(any())).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.markRead(7L, 42L));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), error.getCode());
    }

    @Test
    void markReadIsIdempotentAndKeepsTheFirstReadTime() {
        Date firstRead = new Date(1_700_000_000_000L);
        UserNotification row = row(42L, 7L);
        row.setReadAt(firstRead);
        when(mapper.selectOne(any())).thenReturn(row);

        NotificationVO vo = service.markRead(7L, 42L);

        assertEquals(firstRead, vo.getReadAt());
        // 已读的行不再写库：重复点击不会改写「首次已读」
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void markReadStampsUnreadRow() {
        UserNotification row = row(42L, 7L);
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(any(), any())).thenReturn(1);

        NotificationVO vo = service.markRead(7L, 42L);

        assertNotNull(vo.getReadAt());
        verify(mapper).update(any(), any());
    }

    @Test
    void markAllReadRejectsUnknownType() {
        NotificationReadAllDTO dto = new NotificationReadAllDTO();
        dto.setType("BOGUS");
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.markAllRead(7L, dto));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    private static UserNotification row(long id, long userId) {
        UserNotification row = new UserNotification();
        row.setId(id);
        row.setUserId(userId);
        row.setType(NotificationType.FEEDBACK_REPLIED);
        row.setTitle("反馈有了回复");
        row.setBody("你提交的意见反馈收到了管理员回复。");
        row.setRefType("USER_FEEDBACK");
        row.setRefId("1");
        return row;
    }
}
