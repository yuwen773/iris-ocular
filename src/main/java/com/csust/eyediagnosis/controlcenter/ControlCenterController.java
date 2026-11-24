package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.controlcenter.model.dto.ApprovalDecisionRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.DraftSaveRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.DraftValidateRequest;
import com.csust.eyediagnosis.controlcenter.model.dto.PublishRequestBody;
import com.csust.eyediagnosis.controlcenter.model.dto.RollbackRequestBody;
import com.csust.eyediagnosis.controlcenter.model.vo.ActiveConfigVO;
import com.csust.eyediagnosis.controlcenter.model.vo.DraftVO;
import com.csust.eyediagnosis.controlcenter.model.vo.PageVO;
import com.csust.eyediagnosis.controlcenter.model.vo.PublishRequestVO;
import com.csust.eyediagnosis.controlcenter.model.vo.RegistryVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ValidationVO;
import com.csust.eyediagnosis.controlcenter.security.ConfigAction;
import com.csust.eyediagnosis.controlcenter.security.ConfigPermission;
import com.csust.eyediagnosis.controlcenter.security.ConfigPermissionAspect;
import com.csust.eyediagnosis.controlcenter.service.ConfigPayloadCodec;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterDraftService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterPublishService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterReadService;
import com.csust.eyediagnosis.controlcenter.service.ControlCenterReleaseTransactions;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

/**
 * 配置中心 API（WP-01B 基础读写 + WP-01C 发布审批/发布/回滚）。
 *
 * <p>控制器只做三件事：判空、取操作人、算请求摘要。幂等占位与业务写都在
 * service 的<b>同一个事务</b>里（见 {@link ControlCenterDraftService} 与
 * {@link ControlCenterReleaseTransactions}），不再有「业务提交后补写幂等记录」
 * 的窗口（WP-01B R-1）。
 *
 * <p>权限：所有方法由 {@link ConfigPermission} 校验动作；发布/批准/拒绝/回滚
 * 要求请求者同时位于发布人白名单（默认空 ⇒ 默认无人可发布）。写方法走 CSRF 过滤器。
 */
@RestController
@RequestMapping("/admin/control-center")
@ConditionalOnProperty(prefix = "eye-diagnosis.control-center", name = "enabled", havingValue = "true")
public class ControlCenterController {

    private final ControlCenterReadService readService;
    private final ControlCenterDraftService draftService;
    private final ControlCenterPublishService publishService;
    private final ConfigPayloadCodec codec;
    private final UserService userService;

    public ControlCenterController(ControlCenterReadService readService,
                                   ControlCenterDraftService draftService,
                                   ControlCenterPublishService publishService,
                                   ConfigPayloadCodec codec,
                                   UserService userService) {
        this.readService = readService;
        this.draftService = draftService;
        this.publishService = publishService;
        this.codec = codec;
        this.userService = userService;
    }

    // ------------------------------------------------------------------ 读取

    @GetMapping("/registry")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<RegistryVO> registry() {
        return ResultUtils.success(readService.registry());
    }

    @GetMapping("/active")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<ActiveConfigVO> active() {
        return ResultUtils.success(readService.active());
    }

    @GetMapping("/draft")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<DraftVO> draft() {
        return ResultUtils.success(draftService.get());
    }

    @GetMapping("/releases")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<PageVO<ReleaseVO>> releases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResultUtils.success(readService.releases(page, pageSize));
    }

    @GetMapping("/releases/{id}")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<ReleaseVO> release(@PathVariable long id) {
        return ResultUtils.success(readService.release(id));
    }

    @GetMapping("/publish-requests")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<PageVO<PublishRequestVO>> publishRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResultUtils.success(publishService.listPublishRequests(status, page, pageSize));
    }

    @GetMapping("/publish-requests/{id}")
    @ConfigPermission(ConfigAction.READ)
    public BaseResponse<PublishRequestVO> publishRequest(@PathVariable long id) {
        return ResultUtils.success(publishService.getPublishRequest(id));
    }

    // ------------------------------------------------------------------ 草稿

    @PostMapping("/drafts")
    @ConfigPermission(ConfigAction.EDIT)
    public BaseResponse<DraftVO> createDraft(@RequestBody DraftSaveRequest body, HttpServletRequest request) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空");
        }
        return ResultUtils.success(draftService.create(body, actor(request),
                digestDraftSave(body, "POST", "/admin/control-center/drafts")));
    }

    @PutMapping("/drafts/{id}")
    @ConfigPermission(ConfigAction.EDIT)
    public BaseResponse<DraftVO> updateDraft(@PathVariable long id,
                                             @RequestBody DraftSaveRequest body,
                                             HttpServletRequest request) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空");
        }
        return ResultUtils.success(draftService.update(id, body, actor(request),
                digestDraftSave(body, "PUT", "/admin/control-center/drafts/" + id)));
    }

    @PostMapping("/drafts/{id}/validate")
    @ConfigPermission(ConfigAction.VALIDATE)
    public BaseResponse<ValidationVO> validateDraft(@PathVariable long id,
                                                    @RequestBody DraftValidateRequest body,
                                                    HttpServletRequest request) {
        if (body == null || body.getExpectedRevision() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "校验必须携带 expectedRevision");
        }
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("expectedRevision", body.getExpectedRevision());
        String digest = codec.requestDigest("POST",
                "/admin/control-center/drafts/" + id + "/validate", canonical);
        return ResultUtils.success(draftService.validate(id, body.getExpectedRevision(),
                body.getRequestId(), actor(request), digest));
    }

    // ------------------------------------------------------------------ 发布

    @PostMapping("/drafts/{id}/publish-requests")
    @ConfigPermission(ConfigAction.EDIT)
    public BaseResponse<PublishRequestVO> requestPublish(@PathVariable long id,
                                                         @RequestBody PublishRequestBody body,
                                                         HttpServletRequest request) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空");
        }
        return ResultUtils.success(publishService.requestPublish(id, body, actor(request)));
    }

    @PostMapping("/publish-requests/{id}/approve")
    @ConfigPermission(ConfigAction.PUBLISH)
    public BaseResponse<ReleaseVO> approve(@PathVariable long id,
                                           @RequestBody ApprovalDecisionRequest body,
                                           HttpServletRequest request) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空");
        }
        return ResultUtils.success(publishService.approve(id, body, actor(request)));
    }

    @PostMapping("/publish-requests/{id}/reject")
    @ConfigPermission(ConfigAction.PUBLISH)
    public BaseResponse<PublishRequestVO> reject(@PathVariable long id,
                                                 @RequestBody ApprovalDecisionRequest body,
                                                 HttpServletRequest request) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空");
        }
        return ResultUtils.success(publishService.reject(id, body, actor(request)));
    }

    @PostMapping("/releases/{id}/rollback")
    @ConfigPermission(ConfigAction.ROLLBACK)
    public BaseResponse<ReleaseVO> rollback(@PathVariable long id,
                                            @RequestBody RollbackRequestBody body,
                                            HttpServletRequest request) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空");
        }
        return ResultUtils.success(publishService.rollback(id, body, actor(request)));
    }

    // ------------------------------------------------------------------

    /** 草稿写入的请求摘要：只覆盖业务字段，不含 requestId 自身。 */
    private String digestDraftSave(DraftSaveRequest body, String method, String path) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("schemaVersion", body.getSchemaVersion());
        canonical.put("values", body.getValues());
        canonical.put("expectedRevision", body.getExpectedRevision());
        return codec.requestDigest(method, path, canonical);
    }

    /**
     * 取本次操作人。
     *
     * <p>正常情况下 {@link ConfigPermissionAspect} 已经解析过并挂在请求上；
     * 回落到 userService 只是防御——如果哪天注解被误删，这里仍能给出正确主体
     * （代价是一次多余查询，而不是一个空 actorId 的审计）。
     */
    private User actor(HttpServletRequest request) {
        User actor = ConfigPermissionAspect.actorOf(request);
        if (actor != null) {
            return actor;
        }
        return userService.getLoginUser(request);
    }
}
