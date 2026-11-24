package com.csust.eyediagnosis.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.constant.NotificationType;
import com.csust.eyediagnosis.constant.UserFeedbackCategory;
import com.csust.eyediagnosis.constant.UserFeedbackStatus;
import com.csust.eyediagnosis.config.UserFeedbackSettings;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.mapper.UserFeedbackMapper;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackDecideDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackQueryDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackSubmitDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.entity.UserFeedback;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Date;
import java.util.Set;

/**
 * 用户反馈的**唯一写入口与唯一鉴权面**：提交、状态流转、回复、详情、截图读取。
 *
 * <p>为什么写入口收敛在这里：状态迁移必须与「谁在什么状态下能做什么」一起读才安全。
 * 迁移用条件更新（{@code WHERE id = ? AND status = 旧值}），影响行数不是 1 就是
 * 「有人先改过」——那时宁可让管理员刷新重试，也不覆盖别人的处理结果。
 *
 * <p>合规边界（见 {@code GLOSSARY.md}）：反馈是产品意见通道，不改判读结论、
 * 不进复核队列、不影响任何医疗口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFeedbackLifecycleService {

    /** 正文与回复的上限：TEXT 列装得下，但产品上不需要一篇长文 */
    private static final int MAX_TEXT = 2000;
    private static final Set<String> TARGET_TYPES = Set.of("DIAGNOSIS_TASK", "AGENT_SESSION");

    private final UserFeedbackMapper feedbackMapper;
    private final UserFeedbackService feedbackService;
    private final UserFeedbackAttachmentService attachments;
    private final StringRedisTemplate redisTemplate;
    private final UserFeedbackSettings settings;
    /** 通知是旁路：为 null 时（纯单元测试）不发送，处理逻辑不受影响。 */
    private final NotificationService notifications;

    /* ── 读侧（带鉴权） ───────────────────────────────────── */

    /** 我的反馈：恒按当前用户过滤。 */
    public Page<UserFeedbackVO> pageMine(UserFeedbackQueryDTO dto, User actor) {
        requireEnabled();
        requireUser(actor);
        return feedbackService.pageMine(dto, actor.getId());
    }

    /** 管理端台账：只有管理员能看全部。 */
    public Page<UserFeedbackVO> pageAdmin(UserFeedbackQueryDTO dto, User actor) {
        requireEnabled();
        requireAdmin(actor);
        return feedbackService.pageAdmin(dto);
    }

    /** 待处理数（管理端侧栏徽标 / 页面读数）。 */
    public long pendingCount(User actor) {
        requireEnabled();
        requireAdmin(actor);
        return feedbackService.pendingCount();
    }

    /** 详情：本人或管理员。 */
    public UserFeedbackVO detail(Long id, User actor) {
        requireEnabled();
        requireUser(actor);
        UserFeedback row = requireRow(id);
        requireVisible(row, actor);
        return feedbackService.getVO(row);
    }

    /** 截图的限时链接：本人或管理员；没有截图时按「不存在」处理。 */
    public String attachmentUrl(Long id, User actor) {
        requireEnabled();
        requireUser(actor);
        UserFeedback row = requireRow(id);
        requireVisible(row, actor);
        ThrowUtils.throwIf(StrUtil.isBlank(row.getAttachmentObjectKey()),
                ErrorCode.NOT_FOUND_ERROR, "该反馈没有截图");
        return attachments.presignedUrl(row.getAttachmentObjectKey());
    }

    /* ── 写侧 ─────────────────────────────────────────────── */

    /**
     * 提交反馈。
     *
     * <p>落库失败时把本次刚上传的截图删掉：对象已经写进 COS，但没有任何记录引用它，
     * 留着就是一张只能人工排查的孤儿图（{@code feedback/} 前缀没有对账器）。
     */
    @Transactional
    public UserFeedbackVO submit(UserFeedbackSubmitDTO dto, User actor) {
        requireEnabled();
        requireUser(actor);
        ThrowUtils.throwIf(dto == null, ErrorCode.PARAMS_ERROR, "请求体不能为空");
        requireRateLimit(actor.getId());

        UserFeedback feedback = new UserFeedback();
        feedback.setUserId(actor.getId());
        ThrowUtils.throwIf(!UserFeedbackCategory.isValid(dto.getCategory()),
                ErrorCode.PARAMS_ERROR, "请选择反馈分类");
        feedback.setCategory(dto.getCategory());
        feedback.setContent(validateText(dto.getContent(), "反馈正文"));
        feedback.setStatus(UserFeedbackStatus.PENDING);
        applyTarget(feedback, dto.getTargetType(), dto.getTargetId(), actor.getId());
        applyAttachment(feedback, dto.getAttachmentObjectKey(), actor.getId());

        try {
            int rows = feedbackMapper.insert(feedback);
            ThrowUtils.throwIf(rows != 1, ErrorCode.OPERATION_ERROR, "反馈保存失败");
        } catch (RuntimeException e) {
            if (feedback.getAttachmentObjectKey() != null) {
                attachments.discardQuietly(feedback.getAttachmentObjectKey());
            }
            throw e;
        }
        // DB 默认值负责 createTime / updateTime，插完回读一次再转 VO
        return feedbackService.getVO(requireRow(feedback.getId()));
    }

    /**
     * 状态流转（管理端）。可顺带写入一条回复。
     *
     * <p>标记 {@code RESOLVED} 前必须已有回复：结论不能只有一个状态码，
     * 用户点开「我的反馈」要能读到一句人话。
     */
    @Transactional
    public UserFeedbackVO changeStatus(Long id, UserFeedbackDecideDTO dto, User actor) {
        requireEnabled();
        requireAdmin(actor);
        ThrowUtils.throwIf(dto == null, ErrorCode.PARAMS_ERROR, "请求体不能为空");

        String target = dto.getStatus();
        ThrowUtils.throwIf(!UserFeedbackStatus.isValid(target),
                ErrorCode.PARAMS_ERROR, "非法目标状态：" + UserFeedbackStatus.orUnknown(target));
        String reply = StrUtil.isBlank(dto.getReply()) ? null
                : validateText(dto.getReply(), "回复正文");

        UserFeedback current = requireRow(id);
        ThrowUtils.throwIf(!UserFeedbackStatus.canMove(current.getStatus(), target),
                ErrorCode.USER_FEEDBACK_CONFLICT,
                "非法状态迁移：" + UserFeedbackStatus.orUnknown(current.getStatus())
                        + " → " + UserFeedbackStatus.orUnknown(target));
        ThrowUtils.throwIf(UserFeedbackStatus.RESOLVED.equals(target)
                        && reply == null && StrUtil.isBlank(current.getReply()),
                ErrorCode.PARAMS_ERROR, "标记已解决前需要先回复用户");

        Date now = new Date();
        // 用字符串列名的 UpdateWrapper 而不是 LambdaUpdateWrapper：列名与实体字段同名
        // （map-underscore-to-camel-case: false），而 Lambda 形式要求 MyBatis-Plus 的
        // 反射缓存，纯单元测试里没有 Spring 容器就解析不了方法引用。
        UpdateWrapper<UserFeedback> update = new UpdateWrapper<UserFeedback>()
                .eq("id", id)
                // 条件里带旧状态：这一步保证「迁移只在观察到的状态上发生」
                .eq("status", current.getStatus())
                .set("status", target)
                .set("handledById", actor.getId())
                .set("handledAt", now);
        if (reply != null) {
            update.set("reply", reply)
                    .set("repliedById", actor.getId())
                    .set("repliedAt", now);
        }
        int rows = feedbackMapper.update(null, update);
        ThrowUtils.throwIf(rows != 1, ErrorCode.USER_FEEDBACK_CONFLICT,
                "反馈状态已变化，请刷新后重试");
        // 只有真的写了回复才通知：结案 / 改状态本身不是对用户说的话
        notifyReplied(current.getUserId(), id, reply != null);
        return feedbackService.getVO(requireRow(id));
    }

    /**
     * 回复用户（管理端）。已结案的反馈先重新打开才能回复——
     * 结案之后再追加答复会让「已解决」这一状态失去意义。
     */
    @Transactional
    public UserFeedbackVO reply(Long id, String reply, User actor) {
        requireEnabled();
        requireAdmin(actor);
        String text = validateText(reply, "回复正文");

        UserFeedback current = requireRow(id);
        ThrowUtils.throwIf(UserFeedbackStatus.RESOLVED.equals(current.getStatus())
                        || UserFeedbackStatus.IGNORED.equals(current.getStatus()),
                ErrorCode.USER_FEEDBACK_CONFLICT, "该反馈已结案，请先重新打开再回复");

        // 待处理 → 处理中：回复本身就是「已受理」的证据
        String next = UserFeedbackStatus.PENDING.equals(current.getStatus())
                ? UserFeedbackStatus.PROCESSING : current.getStatus();
        Date now = new Date();
        int rows = feedbackMapper.update(null, new UpdateWrapper<UserFeedback>()
                .eq("id", id)
                .eq("status", current.getStatus())
                .set("status", next)
                .set("reply", text)
                .set("repliedById", actor.getId())
                .set("repliedAt", now)
                .set("handledById", actor.getId())
                .set("handledAt", now));
        ThrowUtils.throwIf(rows != 1, ErrorCode.USER_FEEDBACK_CONFLICT,
                "反馈状态已变化，请刷新后重试");
        notifyReplied(current.getUserId(), id, true);
        return feedbackService.getVO(requireRow(id));
    }

    /**
     * 回复落库后通知提交人。
     *
     * <p>注册在事务提交之后：回滚了就不该有「收到回复」的消息。
     * 一次请求里「回复 + 改状态」同发时只产生一条——{@code changeStatus} 只在
     * 真写了回复时通知，所以两条路径不会各发一条。
     */
    private void notifyReplied(Long authorId, Long feedbackId, boolean replied) {
        if (notifications == null || !replied) {
            return;
        }
        notifications.notifyAfterCommit(authorId, NotificationType.FEEDBACK_REPLIED,
                String.valueOf(feedbackId));
    }

    /* ── 校验 ─────────────────────────────────────────────── */

    private void requireEnabled() {
        ThrowUtils.throwIf(!settings.isEnabled(), ErrorCode.FORBIDDEN_ERROR, "反馈功能暂未启用");
    }

    private void requireUser(User actor) {
        ThrowUtils.throwIf(actor == null || actor.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "未登录");
    }

    private void requireAdmin(User actor) {
        requireUser(actor);
        ThrowUtils.throwIf(!AuthConstant.ADMIN_ROLE.equals(actor.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "仅管理员可处理反馈");
    }

    /**
     * 可见性：管理员看全部，普通用户只看自己的。
     *
     * <p>别人的反馈回 NO_AUTH 而不是 404：与仓库里「越权读别人会话」的口径一致，
     * 前端据此分辨「没权限」与「不存在」。
     */
    private void requireVisible(UserFeedback row, User actor) {
        if (AuthConstant.ADMIN_ROLE.equals(actor.getUserRole())) {
            return;
        }
        ThrowUtils.throwIf(!row.getUserId().equals(actor.getId()),
                ErrorCode.NO_AUTH_ERROR, "只能查看自己提交的反馈");
    }

    private UserFeedback requireRow(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "反馈 id 不能为空");
        UserFeedback row = feedbackMapper.selectById(id);
        ThrowUtils.throwIf(row == null, ErrorCode.NOT_FOUND_ERROR, "反馈不存在");
        return row;
    }

    private String validateText(String value, String label) {
        ThrowUtils.throwIf(StrUtil.isBlank(value), ErrorCode.PARAMS_ERROR, label + "不能为空");
        String text = value.trim();
        ThrowUtils.throwIf(text.length() > MAX_TEXT, ErrorCode.PARAMS_ERROR,
                label + "不能超过 " + MAX_TEXT + " 字");
        return text;
    }

    /**
     * 关联对象校验：类型与 id 同时出现或同时为空；对象必须属于提交人。
     *
     * <p>归属不成立时回 404：不能让人拿别人的 taskId 试探「这个任务存不存在」。
     */
    private void applyTarget(UserFeedback feedback, String targetType, String targetId, Long userId) {
        boolean hasType = StrUtil.isNotBlank(targetType);
        boolean hasId = StrUtil.isNotBlank(targetId);
        ThrowUtils.throwIf(hasType != hasId, ErrorCode.PARAMS_ERROR,
                "关联对象类型与 id 必须同时提供");
        if (!hasType) {
            return;
        }
        ThrowUtils.throwIf(!TARGET_TYPES.contains(targetType), ErrorCode.PARAMS_ERROR,
                "不支持的关联对象类型");
        ThrowUtils.throwIf(targetId.length() > 64, ErrorCode.PARAMS_ERROR, "关联对象 id 过长");
        int owned = switch (targetType) {
            case "DIAGNOSIS_TASK" -> feedbackMapper.countOwnedDiagnosisTask(targetId, userId);
            case "AGENT_SESSION" -> feedbackMapper.countOwnedAgentSession(targetId, userId);
            default -> 0;
        };
        ThrowUtils.throwIf(owned != 1, ErrorCode.NOT_FOUND_ERROR, "关联对象不存在");
        feedback.setTargetType(targetType);
        feedback.setTargetId(targetId);
    }

    /**
     * 截图归属校验：object key 必须落在当前用户的前缀下，且对象真的存在。
     *
     * <p>{@code ..} 一律拒绝：前缀是字符串比较，允许路径穿越等于把前缀校验作废。
     * 只做前缀校验会接受一个从未上传过的 key，管理端详情页就会显示一张裂图
     * （手法同 {@code AgentImageUploadService.resolveOwnedImageId}：归属 + 存在性）。
     */
    private void applyAttachment(UserFeedback feedback, String objectKey, Long userId) {
        if (StrUtil.isBlank(objectKey)) {
            return;
        }
        String prefix = UserFeedbackAttachmentService.keyPrefix(userId);
        ThrowUtils.throwIf(!objectKey.startsWith(prefix)
                        || objectKey.length() > 512
                        || objectKey.contains(".."),
                ErrorCode.NO_AUTH_ERROR, "截图引用未获授权");
        String format = UserFeedbackAttachmentService.formatOf(objectKey);
        ThrowUtils.throwIf(format == null, ErrorCode.PARAMS_ERROR, "仅支持 png / jpg 截图");
        ThrowUtils.throwIf(!attachments.exists(objectKey),
                ErrorCode.PARAMS_ERROR, "截图不存在，请重新上传");
        feedback.setAttachmentObjectKey(objectKey);
        feedback.setAttachmentFormat(format);
    }

    /**
     * 提交限流：每人每分钟 N 条。
     *
     * <p>Redis 不可用时**放行**并记日志：限流是防刷手段，不是业务前提，
     * 因为缓存故障把正常用户的意见挡在门外，代价比偶尔几条刷屏更大。
     */
    private void requireRateLimit(Long userId) {
        int limit = settings.getRateLimitPerMinute();
        if (limit <= 0) {
            return;
        }
        String key = "user-feedback:rate:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            ThrowUtils.throwIf(count != null && count > limit,
                    ErrorCode.TOO_MANY_REQUESTS, "提交过于频繁，请稍后再试");
        } catch (BusinessException e) {
            // 超限是业务结论，照常抛给调用方
            throw e;
        } catch (Exception e) {
            // 缓存故障不该把正常用户的意见挡在门外
            log.warn("反馈限流计数失败，本次放行: {}", e.getMessage());
        }
    }
}
