package com.csust.eyediagnosis.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.model.dto.notification.NotificationQueryDTO;
import com.csust.eyediagnosis.model.dto.notification.NotificationReadAllDTO;
import com.csust.eyediagnosis.model.vo.notification.NotificationVO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.NotificationService;
import com.csust.eyediagnosis.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

/**
 * 站内通知接口：我的通知、未读数、标记已读、全部已读。
 *
 * <p>接收人恒取会话用户，所有查询与写入都带 {@code userId = 当前用户} 条件；
 * 请求体里没有、也不接受 {@code userId}。
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final UserService users;

    public NotificationController(NotificationService notifications, UserService users) {
        this.notifications = notifications;
        this.users = users;
    }

    /** 通知是个人消息，任何中间层都不该缓存。 */
    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    @Operation(summary = "我的通知分页")
    @AuthCheck
    public BaseResponse<Page<NotificationVO>> list(NotificationQueryDTO query,
                                                   HttpServletRequest request) {
        User actor = users.getLoginUser(request);
        return ResultUtils.success(notifications.page(actor.getId(), query));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "未读通知数")
    @AuthCheck
    public BaseResponse<Long> unreadCount(HttpServletRequest request) {
        User actor = users.getLoginUser(request);
        return ResultUtils.success(notifications.unreadCount(actor.getId()));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "标记一条已读（幂等）")
    @AuthCheck
    public BaseResponse<NotificationVO> markRead(@PathVariable Long id, HttpServletRequest request) {
        User actor = users.getLoginUser(request);
        return ResultUtils.success(notifications.markRead(actor.getId(), id));
    }

    @PostMapping("/read-all")
    @Operation(summary = "全部已读（只清当前筛选）")
    @AuthCheck
    public BaseResponse<Integer> markAllRead(@RequestBody(required = false) NotificationReadAllDTO dto,
                                             HttpServletRequest request) {
        User actor = users.getLoginUser(request);
        return ResultUtils.success(notifications.markAllRead(actor.getId(), dto));
    }
}
