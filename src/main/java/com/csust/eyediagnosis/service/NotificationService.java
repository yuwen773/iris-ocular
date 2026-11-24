package com.csust.eyediagnosis.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.constant.NotificationType;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.mapper.UserNotificationMapper;
import com.csust.eyediagnosis.model.dto.notification.NotificationQueryDTO;
import com.csust.eyediagnosis.model.dto.notification.NotificationReadAllDTO;
import com.csust.eyediagnosis.model.entity.UserNotification;
import com.csust.eyediagnosis.model.vo.notification.NotificationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 用户通知：写侧（业务事件 → 一条站内消息）与读侧（列表、未读数、标记已读）。
 *
 * <p><b>写侧是旁路。</b>通知写失败只记日志、不抛异常：用户看不到一条「结果已出」
 * 并不会让判读失败，反过来因为通知表写不进去而回滚一次复核结论，才是严重得多的错误。
 *
 * <p><b>提交后写入。</b>业务事务里调用 {@link #notifyAfterCommit} 时，插入被推迟到
 * 事务提交成功之后——否则事务回滚了，用户却收到一条「已完成」的消息。
 *
 * <p>文案由 {@link NotificationType} 在发送时定稿存库，读侧原样返回，
 * 历史消息不随后续改名变化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 单页上限：通知是「最近发生了什么」，不需要一次拉几百条。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final UserNotificationMapper notificationMapper;

    /* ── 写侧 ─────────────────────────────────────────────── */

    /** 立即写入一条通知（调用方已经确认在事务外 / 事务已提交）。 */
    public void notify(Long userId, String type, String refId) {
        if (userId == null || StrUtil.isBlank(refId)) {
            return;
        }
        NotificationType.Copy copy = NotificationType.copyOf(type, refId);
        if (copy == null) {
            log.warn("忽略未知的通知类型: type={} refId={}", type, refId);
            return;
        }
        try {
            UserNotification row = new UserNotification();
            row.setUserId(userId);
            row.setType(type);
            row.setTitle(copy.title());
            row.setBody(copy.body());
            row.setRefType(copy.refType());
            row.setRefId(refId);
            row.setCreateTime(new Date());
            notificationMapper.insert(row);
        } catch (RuntimeException e) {
            // 旁路：通知丢了不影响业务，但要留得下排查线索
            log.warn("通知写入失败（不阻断主流程）: type={} refId={} {}", type, refId, e.getMessage());
        }
    }

    /**
     * 在业务事务提交成功后写入通知；没有事务时立即写入。
     *
     * <p>只注册 {@code afterCommit}，不注册 {@code afterCompletion}：
     * 回滚时什么都不该发。
     */
    public void notifyAfterCommit(Long userId, String type, String refId) {
        afterCommit(() -> notify(userId, type, refId));
    }

    /**
     * 发给判读任务的所有者（复核结论的接收人是患者，不是医生）。
     * 任务查不到时静默返回：通知是旁路，不制造「任务不存在」的错误面。
     */
    public void notifyTaskOwner(String taskId, String type) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        Long owner;
        try {
            owner = notificationMapper.selectTaskOwner(taskId);
        } catch (RuntimeException e) {
            log.warn("查询任务归属失败，跳过通知: taskId={} {}", taskId, e.getMessage());
            return;
        }
        notify(owner, type, taskId);
    }

    /**
     * 事务提交后通知任务所有者。
     *
     * <p>复核结论在事务里落定：调用方拿到返回值时事务已提交，但「决定写入」这件事
     * 必须与通知绑定在同一次提交上——回滚了就不该告诉患者「复核完成」。
     */
    public void notifyTaskOwnerAfterCommit(String taskId, String type) {
        afterCommit(() -> notifyTaskOwner(taskId, type));
    }

    /** 有事务就推迟到提交之后执行，没有事务（纯单元测试 / 非事务调用）立即执行。 */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /* ── 读侧 ─────────────────────────────────────────────── */

    /** 我的通知分页：恒按当前用户过滤，时间倒序。 */
    public Page<NotificationVO> page(Long userId, NotificationQueryDTO dto) {
        NotificationQueryDTO query = dto == null ? new NotificationQueryDTO() : dto;
        String type = validateType(query.getType());

        int current = Math.max(1, query.getCurrent());
        int pageSize = Math.min(Math.max(1, query.getPageSize()), MAX_PAGE_SIZE);

        QueryWrapper<UserNotification> wrapper = new QueryWrapper<UserNotification>()
                .eq("userId", userId)
                .eq(StrUtil.isNotBlank(type), "type", type)
                .isNull(Boolean.TRUE.equals(query.getUnreadOnly()), "readAt")
                .orderByDesc("createTime")
                // 同毫秒的两条（一次请求里连发）顺序稳定，翻页不会漏行
                .orderByDesc("id");

        Page<UserNotification> source = notificationMapper.selectPage(new Page<>(current, pageSize), wrapper);
        Page<NotificationVO> target = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        target.setRecords(toVOList(source.getRecords()));
        return target;
    }

    /** 未读数：侧栏 / 顶栏铃铛的唯一读数。 */
    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new QueryWrapper<UserNotification>()
                .eq("userId", userId)
                .isNull("readAt"));
    }

    /**
     * 标记一条已读（幂等）。不属于自己的 id 回 404 而不是 403：
     * 不让人拿 id 试探「这条通知存不存在」。
     */
    public NotificationVO markRead(Long userId, Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "通知 id 不能为空");
        UserNotification row = notificationMapper.selectOne(new QueryWrapper<UserNotification>()
                .eq("id", id)
                .eq("userId", userId));
        ThrowUtils.throwIf(row == null, ErrorCode.NOT_FOUND_ERROR, "通知不存在");
        if (row.getReadAt() != null) {
            return toVO(row);
        }
        Date now = new Date();
        // 条件里带 readAt IS NULL：并发重复点击只有一次生效，已读时间不被后到的请求改写
        int rows = notificationMapper.update(null, new UpdateWrapper<UserNotification>()
                .eq("id", id)
                .eq("userId", userId)
                .isNull("readAt")
                .set("readAt", now));
        if (rows == 1) {
            row.setReadAt(now);
        }
        return toVO(row);
    }

    /**
     * 全部已读：只清当前筛选下的未读，不是全账号一键清空。
     *
     * @return 本次真正被标记的条数
     */
    public int markAllRead(Long userId, NotificationReadAllDTO dto) {
        String type = validateType(dto == null ? null : dto.getType());
        return notificationMapper.update(null, new UpdateWrapper<UserNotification>()
                .eq("userId", userId)
                .eq(StrUtil.isNotBlank(type), "type", type)
                .isNull("readAt")
                .set("readAt", new Date()));
    }

    /**
     * 物理删除已读且早于 {@code before} 的通知，单次最多 {@code limit} 条
     * （调用方循环，避免一次删表级数据把库锁住）。未读通知永不清理。
     */
    public int purgeRead(Date before, int limit) {
        if (before == null || limit <= 0) {
            return 0;
        }
        return notificationMapper.delete(new QueryWrapper<UserNotification>()
                .isNotNull("readAt")
                .lt("readAt", before)
                .last("LIMIT " + limit));
    }

    /* ── 内部 ─────────────────────────────────────────────── */

    private String validateType(String type) {
        if (StrUtil.isBlank(type)) {
            return null;
        }
        ThrowUtils.throwIf(!NotificationType.isValid(type), ErrorCode.PARAMS_ERROR,
                "未知的通知类型：" + type);
        return type;
    }

    private List<NotificationVO> toVOList(List<UserNotification> rows) {
        List<NotificationVO> result = new ArrayList<>(rows.size());
        for (UserNotification row : rows) {
            result.add(toVO(row));
        }
        return result;
    }

    private NotificationVO toVO(UserNotification row) {
        NotificationVO vo = new NotificationVO();
        vo.setId(row.getId());
        vo.setType(row.getType());
        vo.setTitle(row.getTitle());
        vo.setBody(row.getBody());
        vo.setRefType(row.getRefType());
        vo.setRefId(row.getRefId());
        vo.setReadAt(row.getReadAt());
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }
}
