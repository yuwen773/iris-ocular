package com.csust.eyediagnosis.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackQueryDTO;
import com.csust.eyediagnosis.model.dto.userfeedback.UserFeedbackSubmitDTO;
import com.csust.eyediagnosis.model.vo.userfeedback.UserFeedbackVO;
import com.csust.eyediagnosis.service.UserFeedbackAttachmentService;
import com.csust.eyediagnosis.service.UserFeedbackLifecycleService;
import com.csust.eyediagnosis.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户侧反馈接口：提交、我的反馈、详情、截图上传与读取。
 *
 * <p>与「Critic 反馈」（{@code agent.feedback.requested}）无关，见 {@code GLOSSARY.md}。
 * 所有方法都要求登录（{@code @AuthCheck} 不带 mustRole 只校验会话），
 * 具体「谁能看哪一条」由 {@link UserFeedbackLifecycleService} 在服务层裁决。
 */
@RestController
@RequestMapping("/feedback")
public class UserFeedbackController {

    private final UserFeedbackLifecycleService feedbacks;
    private final UserFeedbackAttachmentService attachments;
    private final UserService users;

    public UserFeedbackController(UserFeedbackLifecycleService feedbacks,
                                  UserFeedbackAttachmentService attachments,
                                  UserService users) {
        this.feedbacks = feedbacks;
        this.attachments = attachments;
        this.users = users;
    }

    /** 反馈里有用户自己写的内容，任何中间层都不该缓存。 */
    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/attachment")
    @Operation(summary = "上传反馈截图")
    @AuthCheck
    public BaseResponse<String> uploadAttachment(@RequestPart("file") MultipartFile file,
                                                 HttpServletRequest request) {
        return ResultUtils.success(attachments.upload(file, users.getLoginUser(request).getId()));
    }

    @PostMapping
    @Operation(summary = "提交用户反馈")
    @AuthCheck
    public BaseResponse<UserFeedbackVO> submit(@RequestBody UserFeedbackSubmitDTO submit,
                                               HttpServletRequest request) {
        return ResultUtils.success(feedbacks.submit(submit, users.getLoginUser(request)));
    }

    @PostMapping("/mine")
    @Operation(summary = "我的反馈分页")
    @AuthCheck
    public BaseResponse<Page<UserFeedbackVO>> mine(@RequestBody UserFeedbackQueryDTO query,
                                                   HttpServletRequest request) {
        return ResultUtils.success(feedbacks.pageMine(query, users.getLoginUser(request)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "反馈详情（本人或管理员）")
    @AuthCheck
    public BaseResponse<UserFeedbackVO> detail(@PathVariable Long id, HttpServletRequest request) {
        return ResultUtils.success(feedbacks.detail(id, users.getLoginUser(request)));
    }

    @GetMapping("/{id}/attachment")
    @Operation(summary = "反馈截图的限时链接（本人或管理员）")
    @AuthCheck
    public BaseResponse<String> attachment(@PathVariable Long id, HttpServletRequest request) {
        return ResultUtils.success(feedbacks.attachmentUrl(id, users.getLoginUser(request)));
    }
}
