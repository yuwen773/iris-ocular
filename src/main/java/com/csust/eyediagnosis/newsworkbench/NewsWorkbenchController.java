package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newscuration.NewsCurationModels;
import com.csust.eyediagnosis.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin-only workbench endpoints exposing News Engine operational visibility, source/task scheduling,
 * evidence candidate review, reserve-before-IO receipt reconciliation, and NewsBench history (WP-05).
 */
@RestController
@RequestMapping("/admin/news-workbench")
@Tag(name = "NewsWorkbenchController", description = "管理员资讯引擎工作台")
@RequiredArgsConstructor
public class NewsWorkbenchController {

    private final NewsWorkbenchService workbenchService;
    private final UserService userService;
    private final NewsIssueService issueService;

    @GetMapping("/issue-preview")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "按发布日期预览资讯刊物")
    public ResponseEntity<BaseResponse<NewsIssueService.Preview>> previewIssue(
            @RequestParam("startOn") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startOn,
            @RequestParam("endOn") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endOn) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResultUtils.success(issueService.preview(startOn, endOn)));
    }

    @GetMapping("/overview")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "获取资讯引擎工作台总览统计")
    public BaseResponse<NewsWorkbenchModels.OverviewVO> getOverview() {
        return ResultUtils.success(workbenchService.getOverview());
    }

    @GetMapping("/sources")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "列出各信源启停状态、游标配置及任务统计")
    public BaseResponse<List<NewsWorkbenchModels.SourceDetailVO>> listSources() {
        return ResultUtils.success(workbenchService.listSources());
    }

    @PostMapping("/sources/{source}/toggle")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "启停指定资讯信源")
    public BaseResponse<NewsWorkbenchModels.SourceDetailVO> toggleSource(
            @PathVariable("source") String source,
            @RequestBody(required = false) NewsWorkbenchModels.SourceToggleRequest body) {
        Boolean enabled = body == null ? null : body.enabled();
        return ResultUtils.success(workbenchService.toggleSource(source, enabled));
    }

    @PostMapping("/sources/{source}/configs")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "登记或更新信源配置版本与初始游标")
    public BaseResponse<NewsWorkbenchModels.SourceDetailVO> registerSourceConfig(
            @PathVariable("source") String source,
            @RequestBody(required = false) NewsWorkbenchModels.RegisterSourceConfigRequest body) {
        return ResultUtils.success(workbenchService.registerSourceConfig(source, body));
    }

    @GetMapping("/tasks")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "分页查询资讯采集窗口任务")
    public BaseResponse<NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CollectionTaskVO>> listTasks(
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(workbenchService.listTasks(source, status, current, pageSize));
    }

    @PostMapping("/tasks/enqueue")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "手动入队资讯采集时间窗任务")
    public BaseResponse<NewsWorkbenchModels.CollectionTaskVO> enqueueTask(
            @RequestBody(required = false) NewsWorkbenchModels.EnqueueTaskRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.enqueueTask(body, operator));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "重新打开失败的采集时间窗任务")
    public BaseResponse<NewsWorkbenchModels.CollectionTaskVO> retryFailedTask(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) NewsWorkbenchModels.RetryTaskRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.retryFailedTask(taskId, body, operator));
    }

    @GetMapping("/tasks/{taskId}/events")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查看采集任务的操作审计记录")
    public BaseResponse<List<NewsWorkbenchModels.TaskEventVO>> listTaskEvents(
            @PathVariable("taskId") String taskId) {
        return ResultUtils.success(workbenchService.listTaskEvents(taskId));
    }

    @GetMapping("/tasks/{taskId}/leases")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查看采集任务历史租约世代记录")
    public BaseResponse<List<NewsWorkbenchModels.LeaseRecordVO>> listTaskLeases(
            @PathVariable("taskId") String taskId) {
        return ResultUtils.success(workbenchService.listTaskLeases(taskId));
    }

    @GetMapping("/candidates")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "分页查询精选候选队列")
    public BaseResponse<NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CandidateSummaryVO>> listCandidates(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "evidenceTier", required = false) String evidenceTier,
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(workbenchService.listCandidates(status, source, evidenceTier, current, pageSize));
    }

    @GetMapping("/candidates/{candidateId}")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查看精选候选冻结证据快照与关联回执详情")
    public BaseResponse<NewsWorkbenchModels.CandidateDetailVO> getCandidateDetail(
            @PathVariable("candidateId") long candidateId) {
        return ResultUtils.success(workbenchService.getCandidateDetail(candidateId));
    }

    @GetMapping("/budgets")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查询日精选预算与熔断状态")
    public BaseResponse<List<NewsWorkbenchModels.BudgetSummaryVO>> listBudgets(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "limit", defaultValue = "14") int limit) {
        return ResultUtils.success(workbenchService.listBudgets(scope, limit));
    }

    @PostMapping("/budgets")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "配置或更新日精选额度")
    public BaseResponse<NewsWorkbenchModels.BudgetSummaryVO> configureBudget(
            @RequestBody(required = false) NewsWorkbenchModels.ConfigureBudgetRequest body) {
        return ResultUtils.success(workbenchService.configureBudget(body));
    }

    @PostMapping("/budgets/reset-circuit")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "人工解除鉴权熔断")
    public BaseResponse<NewsWorkbenchModels.BudgetSummaryVO> resetCircuitBreaker(
            @RequestBody(required = false) NewsWorkbenchModels.ResetCircuitRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.resetCircuitBreaker(body, operator));
    }

    @GetMapping("/receipts")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "分页查询付费模型调用回执台账")
    public BaseResponse<NewsWorkbenchModels.PageVO<NewsWorkbenchModels.CallReceiptVO>> listReceipts(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "budgetScope", required = false) String budgetScope,
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(workbenchService.listReceipts(state, budgetScope, current, pageSize));
    }

    @PostMapping("/receipts/reclaim-stale")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "回收超时悬空模型调用回执")
    public BaseResponse<NewsCurationModels.StaleReclaimResult> reclaimStaleCalls() {
        return ResultUtils.success(workbenchService.reclaimStaleCalls());
    }

    @PostMapping("/receipts/{callId}/reconcile")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "对账确认 OUTCOME_UNKNOWN 模型调用回执")
    public BaseResponse<NewsWorkbenchModels.CallReceiptVO> reconcileReceipt(
            @PathVariable("callId") String callId,
            @RequestBody(required = false) NewsWorkbenchModels.ReconcileReceiptRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.reconcileReceipt(callId, body, operator));
    }

    @GetMapping("/bench-runs")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查询已落库的 NewsBench 评测历史记录")
    public BaseResponse<List<NewsWorkbenchModels.BenchRunSummaryVO>> listBenchRuns(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResultUtils.success(workbenchService.listBenchRuns(limit));
    }

    // ---- WP-08B.2：证据核验账本与候选转换 ----

    @GetMapping("/materials/{materialRevisionId}/evidence-verifications")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查看资料修订的人工证据核验历史")
    public ResponseEntity<BaseResponse<List<NewsWorkbenchModels.EvidenceVerificationVO>>> listEvidenceVerifications(
            @PathVariable("materialRevisionId") long materialRevisionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResultUtils.success(workbenchService.listEvidenceVerifications(materialRevisionId)));
    }

    @PostMapping("/materials/{materialRevisionId}/evidence-verifications")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "登记人工证据核验（同行评审 / 完整性 / 研究设计 / 授权全文范围）")
    public BaseResponse<NewsWorkbenchModels.EvidenceVerificationVO> verifyEvidence(
            @PathVariable("materialRevisionId") long materialRevisionId,
            @RequestBody(required = false) NewsWorkbenchModels.SubmitEvidenceVerificationRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.verifyEvidence(materialRevisionId, body, operator));
    }

    @PostMapping("/evidence-verifications/{verificationId}/revoke")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "撤回一条证据核验（保留历史，依赖它的候选需重新核验）")
    public BaseResponse<NewsWorkbenchModels.EvidenceVerificationVO> revokeEvidence(
            @PathVariable("verificationId") long verificationId,
            @RequestBody(required = false) NewsWorkbenchModels.RevokeEvidenceVerificationRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.revokeEvidence(verificationId, body, operator));
    }

    @PostMapping("/receipts/{callId}/materialize")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "把冻结的生产联合精选回执转成待人工审核候选（幂等；可显式替代一条失效旧候选）")
    public BaseResponse<NewsWorkbenchModels.MaterializedCandidateVO> materializeCandidate(
            @PathVariable("callId") String callId,
            @RequestBody(required = false) NewsWorkbenchModels.MaterializeCandidateRequest body,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.materializeCandidate(callId, operator,
                body == null ? null : body.supersedesCandidateId(),
                body == null ? null : body.reason()));
    }

    // ---- N1：管理员可实际调用的生产精选入口 ----

    @GetMapping("/materials")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "分页查询已准入资料修订的精选就绪状态（证据路由、活跃候选、最近回执）")
    public ResponseEntity<BaseResponse<NewsWorkbenchModels.PageVO<NewsWorkbenchModels.MaterialCurationVO>>> listCurationMaterials(
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResultUtils.success(workbenchService.listCurationMaterials(source, current, pageSize)));
    }

    @GetMapping("/materials/{materialRevisionId}/receipts")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查看某资料修订在生产策略下的模型调用回执历史")
    public ResponseEntity<BaseResponse<List<NewsWorkbenchModels.CallReceiptVO>>> listMaterialReceipts(
            @PathVariable("materialRevisionId") long materialRevisionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResultUtils.success(workbenchService.listMaterialReceipts(materialRevisionId)));
    }

    @PostMapping("/materials/{materialRevisionId}/curate")
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "触发一次管理员生产联合精选（P05）；结果只进回执，需显式转候选")
    public BaseResponse<NewsWorkbenchModels.CurationRunVO> runCuration(
            @PathVariable("materialRevisionId") long materialRevisionId,
            HttpServletRequest request) {
        User operator = userService.getLoginUser(request);
        return ResultUtils.success(workbenchService.runCuration(materialRevisionId, operator));
    }
}
