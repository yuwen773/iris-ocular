package com.csust.eyediagnosis.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackQueryDTO;
import com.csust.eyediagnosis.model.entity.UserFeedback;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;

import java.util.List;

/**
 * 用户反馈的读侧：查询条件、分页与实体 → VO 转换。
 *
 * <p>写侧（提交 / 状态流转 / 回复）在 {@link UserFeedbackLifecycleService}，
 * 两者分开是为了让「谁能看见什么」与「谁能改什么」各自只有一处实现。
 * 用户侧分页由本接口的调用方强制带上当前用户 id，管理侧不加。
 */
public interface UserFeedbackService extends IService<UserFeedback> {

    /**
     * 组装查询条件。
     *
     * <p>{@code userIdFilter} 非空时恒加 {@code userId = ?}——用户侧传当前用户，
     * 管理侧传 {@code null}。排序字段来自请求体，只认白名单。
     */
    QueryWrapper<UserFeedback> getQueryWrapper(UserFeedbackQueryDTO dto, Long userIdFilter);

    /** 实体 → VO（含提交人昵称；查不到用户时昵称为 null） */
    UserFeedbackVO getVO(UserFeedback feedback);

    /** 批量转换，保持入参顺序 */
    List<UserFeedbackVO> getVOList(List<UserFeedback> list);

    /** 我的反馈：恒按当前用户过滤 */
    Page<UserFeedbackVO> pageMine(UserFeedbackQueryDTO dto, Long userId);

    /** 管理端台账：不按用户过滤 */
    Page<UserFeedbackVO> pageAdmin(UserFeedbackQueryDTO dto);

    /** 待处理数（管理端侧栏徽标 / 页面读数） */
    long pendingCount();
}
