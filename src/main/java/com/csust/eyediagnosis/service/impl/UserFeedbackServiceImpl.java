package com.csust.eyediagnosis.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.csust.eyediagnosis.constant.UserFeedbackStatus;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.UserFeedbackMapper;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackQueryDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.entity.UserFeedback;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;
import com.csust.eyediagnosis.service.UserFeedbackService;
import com.csust.eyediagnosis.utils.DateRanges;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用户反馈查询实现。
 *
 * <p>排序字段白名单与 News 同款：{@code sortField} 来自请求体，直接拼进 ORDER BY
 * 等于把 SQL 片段交给客户端。
 */
@Service
@RequiredArgsConstructor
public class UserFeedbackServiceImpl extends ServiceImpl<UserFeedbackMapper, UserFeedback>
        implements UserFeedbackService {

    /** 允许的服务端排序字段 */
    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "status", "createTime", "updateTime");

    private final UserMapper userMapper;

    @Override
    public QueryWrapper<UserFeedback> getQueryWrapper(UserFeedbackQueryDTO dto, Long userIdFilter) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        }
        QueryWrapper<UserFeedback> wrapper = new QueryWrapper<>();
        wrapper.eq(ObjUtil.isNotNull(userIdFilter), "userId", userIdFilter);
        wrapper.eq(StrUtil.isNotBlank(dto.getCategory()), "category", dto.getCategory());
        wrapper.eq(StrUtil.isNotBlank(dto.getStatus()), "status", dto.getStatus());
        // 关键词只搜正文：回复正文属于管理员对某个用户的答复，不该成为全站检索面
        wrapper.like(StrUtil.isNotBlank(dto.getKeyword()), "content", dto.getKeyword());
        wrapper.ge(DateRanges.start(dto.getStartDate()) != null, "createTime",
                DateRanges.start(dto.getStartDate()));
        wrapper.le(DateRanges.end(dto.getEndDate()) != null, "createTime",
                DateRanges.end(dto.getEndDate()));

        String sortField = dto.getSortField();
        if (StrUtil.isNotBlank(sortField) && SORT_FIELDS.contains(sortField)) {
            wrapper.orderBy(true, "ascend".equalsIgnoreCase(dto.getSortOrder()), sortField);
        } else {
            // 默认最新提交在前；id 兜底保证同毫秒提交的行顺序稳定
            wrapper.orderByDesc("createTime").orderByDesc("id");
        }
        return wrapper;
    }

    @Override
    public UserFeedbackVO getVO(UserFeedback feedback) {
        if (feedback == null) {
            return null;
        }
        UserFeedbackVO vo = new UserFeedbackVO();
        vo.setId(feedback.getId());
        vo.setUserId(feedback.getUserId());
        // 孤儿 userId 查不到时留 null，界面显示「未知用户」，不编造名字
        User author = feedback.getUserId() == null ? null
                : userMapper.selectUserNameAndUserAvatarByUserId(feedback.getUserId());
        vo.setUserName(author == null ? null : author.getUserName());
        vo.setCategory(feedback.getCategory());
        vo.setContent(feedback.getContent());
        vo.setTargetType(feedback.getTargetType());
        vo.setTargetId(feedback.getTargetId());
        vo.setStatus(feedback.getStatus());
        vo.setReply(feedback.getReply());
        vo.setRepliedById(feedback.getRepliedById());
        vo.setRepliedAt(feedback.getRepliedAt());
        vo.setHandledById(feedback.getHandledById());
        vo.setHandledAt(feedback.getHandledAt());
        vo.setHasAttachment(StrUtil.isNotBlank(feedback.getAttachmentObjectKey()));
        vo.setAttachmentFormat(feedback.getAttachmentFormat());
        vo.setCreateTime(feedback.getCreateTime());
        vo.setUpdateTime(feedback.getUpdateTime());
        return vo;
    }

    @Override
    public List<UserFeedbackVO> getVOList(List<UserFeedback> list) {
        if (CollUtil.isEmpty(list)) {
            return new ArrayList<>();
        }
        List<UserFeedbackVO> result = new ArrayList<>(list.size());
        for (UserFeedback feedback : list) {
            result.add(getVO(feedback));
        }
        return result;
    }

    @Override
    public Page<UserFeedbackVO> pageMine(UserFeedbackQueryDTO dto, Long userId) {
        return toVOPage(page(new Page<>(dto.getCurrent(), dto.getPageSize()),
                getQueryWrapper(dto, userId)));
    }

    @Override
    public Page<UserFeedbackVO> pageAdmin(UserFeedbackQueryDTO dto) {
        return toVOPage(page(new Page<>(dto.getCurrent(), dto.getPageSize()),
                getQueryWrapper(dto, null)));
    }

    /**
     * 实体分页 → VO 分页。不直接用 MP 的 {@code page.convert}：它返回
     * {@code IPage}，与接口声明的 {@code Page} 不是同一个类型；手工建同名
     * {@code Page} 与 NewsController 的做法一致，分页条上的字段一个不少。
     */
    private Page<UserFeedbackVO> toVOPage(Page<UserFeedback> source) {
        Page<UserFeedbackVO> target = new Page<>(source.getCurrent(), source.getSize(),
                source.getTotal());
        target.setRecords(getVOList(source.getRecords()));
        return target;
    }

    @Override
    public long pendingCount() {
        return count(new QueryWrapper<UserFeedback>().eq("status", UserFeedbackStatus.PENDING));
    }
}
