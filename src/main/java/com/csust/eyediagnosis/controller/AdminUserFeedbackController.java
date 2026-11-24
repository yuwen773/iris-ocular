package com.csust.eyediagnosis.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackDecideDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackQueryDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackReplyDTO;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;
import com.csust.eyediagnosis.service.UserFeedbackLifecycleService;
import com.csust.eyediagnosis.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端反馈工作台：台账、详情、状态流转、回复、待处理数、截图读取。
 *
 * <p>权限两层：{@code @AuthCheck(mustRole = admin)} 在 AOP 层先挡一道，
 * {@link UserFeedbackLifecycleService} 里再各自 {@code requireAdmin}——
 * 注解是入口守卫，服务层的检查才是「换个调用方也不会漏」的那一道。
 */
@RestController
@RequestMapping("/admin/feedback")
public class AdminUserFeedbackController {

    private final UserFeedbackLifecycleService feedbacks;
    private final UserService users;

    public AdminUserFeedbackController(UserFeedbackLifecycleService feedbacks, UserService users) {
        this.feedbacks = feedbacks;
        this.users = users;
    }

    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/page")
    @Operation(summary = "管理端反馈台账分页")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserFeedbackVO>> page(@RequestBody UserFeedbackQueryDTO query,
                                                   HttpServletRequest request) {
        return ResultUtils.success(feedbacks.pageAdmin(query, users.getLoginUser(request)));
    }

    @GetMapping("/pending-count")
    @Operation(summary = "待处理反馈数")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<Long> pendingCount(HttpServletRequest request) {
        return ResultUtils.success(feedbacks.pendingCount(users.getLoginUser(request)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "管理端反馈详情")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<UserFeedbackVO> detail(@PathVariable Long id, HttpServletRequest request) {
        return ResultUtils.success(feedbacks.detail(id, users.getLoginUser(request)));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "反馈状态流转（可顺带回复）")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<UserFeedbackVO> changeStatus(@PathVariable Long id,
                                                     @RequestBody UserFeedbackDecideDTO decide,
                                                     HttpServletRequest request) {
        return ResultUtils.success(feedbacks.changeStatus(id, decide, users.getLoginUser(request)));
    }

    @PostMapping("/{id}/reply")
    @Operation(summary = "回复反馈用户")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<UserFeedbackVO> reply(@PathVariable Long id,
                                              @RequestBody UserFeedbackReplyDTO reply,
                                              HttpServletRequest request) {
        String text = reply == null ? null : reply.getReply();
        return ResultUtils.success(feedbacks.reply(id, text, users.getLoginUser(request)));
    }

    @GetMapping("/{id}/attachment")
    @Operation(summary = "反馈截图的限时链接")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    public BaseResponse<String> attachment(@PathVariable Long id, HttpServletRequest request) {
        return ResultUtils.success(feedbacks.attachmentUrl(id, users.getLoginUser(request)));
    }
}
