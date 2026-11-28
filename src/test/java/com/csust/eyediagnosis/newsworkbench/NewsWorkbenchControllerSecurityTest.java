package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.aop.AuthInterceptor;
import com.csust.eyediagnosis.config.ControlCenterProperties;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.config.SameOriginFilter;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.GlobalExceptionHandler;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.newsingestion.NewsCollectionScheduleStatusService;
import com.csust.eyediagnosis.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L1 security, auth-boundary, SameOrigin CSRF, and error-envelope tests for {@link NewsWorkbenchController}.
 */
class NewsWorkbenchControllerSecurityTest {

    private NewsWorkbenchService workbenchService;
    private UserService userService;
    private NewsIssueService issueService;
    private NewsCollectionScheduleStatusService scheduleStatus;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workbenchService = mock(NewsWorkbenchService.class);
        userService = mock(UserService.class);
        issueService = mock(NewsIssueService.class);
        scheduleStatus = mock(NewsCollectionScheduleStatusService.class);

        User admin = new User();
        admin.setId(1001L);
        admin.setUserRole(AuthConstant.ADMIN_ROLE);
        when(userService.getLoginUser(any())).thenReturn(admin);

        SameOriginFilter sameOriginFilter = new SameOriginFilter(new ControlCenterProperties(), (request, action, reason) -> { });
        ReflectionTestUtils.setField(sameOriginFilter, "additionalHosts", List.of());

        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter(
                new JsonConfig().jacksonObjectMapper(Jackson2ObjectMapperBuilder.json()));

        AuthInterceptor auth = new AuthInterceptor();
        ReflectionTestUtils.setField(auth, "userService", userService);
        AspectJProxyFactory proxy = new AspectJProxyFactory(
                new NewsWorkbenchController(workbenchService, userService, issueService));
        proxy.addAspect(auth);
        AspectJProxyFactory scheduleProxy = new AspectJProxyFactory(new NewsCollectionScheduleController(scheduleStatus));
        scheduleProxy.addAspect(auth);
        mockMvc = MockMvcBuilders.standaloneSetup(proxy.getProxy(), scheduleProxy.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jsonConverter)
                .addFilters(sameOriginFilter)
                .build();
    }

    @Test
    void onlyAdministratorsReadScheduleTelemetryAndTheResponseIsNotCached() throws Exception {
        User viewer = new User();
        viewer.setUserRole(AuthConstant.DEFAULT_ROLE);
        when(userService.getLoginUser(any())).thenReturn(viewer);
        mockMvc.perform(get("/api/admin/news-workbench/scheduling").contextPath("/api"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        verifyNoInteractions(scheduleStatus);

        viewer.setUserRole(AuthConstant.ADMIN_ROLE);
        when(scheduleStatus.status()).thenReturn(new NewsCollectionScheduleStatusService.ScheduleStatus(
                false, false, List.of(), List.of()));
        mockMvc.perform(get("/api/admin/news-workbench/scheduling").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.scheduleEnabled").value(false));
    }

    @Test
    void everyControllerEndpointRequiresAdminAuthCheck() {
        List<Method> mappedMethods = new ArrayList<>();
        for (Method method : NewsWorkbenchController.class.getDeclaredMethods()) {
            boolean mapped = method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(RequestMapping.class);
            if (mapped) {
                mappedMethods.add(method);
            }
        }
        assertEquals(26, mappedMethods.size());
        for (Method method : mappedMethods) {
            AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
            assertNotNull(authCheck, () -> "Missing @AuthCheck on " + method.getName());
            assertEquals(AuthConstant.ADMIN_ROLE, authCheck.mustRole(),
                    () -> "Expected ADMIN_ROLE @AuthCheck on " + method.getName());
        }
    }

    @Test
    void evidenceLedgerAndMaterializeEndpointsStayAdminOnlyAndNeverCacheReads() throws Exception {
        User viewer = new User();
        viewer.setId(1002L);
        viewer.setUserRole(AuthConstant.DEFAULT_ROLE);
        when(userService.getLoginUser(any())).thenReturn(viewer);

        mockMvc.perform(get("/api/admin/news-workbench/materials/7/evidence-verifications").contextPath("/api"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        verifyNoInteractions(workbenchService);

        viewer.setUserRole(AuthConstant.ADMIN_ROLE);
        when(workbenchService.listEvidenceVerifications(7L)).thenReturn(List.of());
        mockMvc.perform(get("/api/admin/news-workbench/materials/7/evidence-verifications").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data").isEmpty());

        when(workbenchService.verifyEvidence(eq(7L), any(), any())).thenReturn(
                new NewsWorkbenchModels.EvidenceVerificationVO(11L, 7L, "PEER_REVIEW", "VERIFIED", "PUBMED_RECORD",
                        "https://pubmed.ncbi.nlm.nih.gov/42000001/", "pubmed-2026", null, "admin:1001",
                        "2026-11-26T08:00:00Z", true, null, null, null));
        mockMvc.perform(post("/api/admin/news-workbench/materials/7/evidence-verifications")
                        .contextPath("/api")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"attribute":"PEER_REVIEW","value":"VERIFIED","method":"PUBMED_RECORD",
                                 "evidenceUrl":"https://pubmed.ncbi.nlm.nih.gov/42000001/","sourceVersion":"pubmed-2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attribute").value("PEER_REVIEW"))
                .andExpect(jsonPath("$.data.active").value(true));

        when(workbenchService.revokeEvidence(eq(11L), any(), any())).thenReturn(
                new NewsWorkbenchModels.EvidenceVerificationVO(11L, 7L, "PEER_REVIEW", "VERIFIED", "PUBMED_RECORD",
                        "https://pubmed.ncbi.nlm.nih.gov/42000001/", "pubmed-2026", null, "admin:1001",
                        "2026-11-26T08:00:00Z", false, "admin:1001", "2026-11-26T09:00:00Z", "记录失效"));
        mockMvc.perform(post("/api/admin/news-workbench/evidence-verifications/11/revoke")
                        .contextPath("/api")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"记录失效\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.revokeNote").value("记录失效"));

        when(workbenchService.materializeCandidate(eq("call-1"), any(), isNull(), isNull())).thenReturn(
                new NewsWorkbenchModels.MaterializedCandidateVO(21L, "CURATED_NEEDS_REVIEW", "call-1", true,
                        "d".repeat(64), null));
        mockMvc.perform(post("/api/admin/news-workbench/receipts/call-1/materialize")
                        .contextPath("/api")
                        .header("Origin", "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CURATED_NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.created").value(true));

        when(workbenchService.materializeCandidate(eq("call-2"), any(), isNull(), isNull())).thenThrow(
                new BusinessException(ErrorCode.PARAMS_ERROR, "证据核验已撤回或变更，请重新核验后再转换"));
        mockMvc.perform(post("/api/admin/news-workbench/receipts/call-2/materialize")
                        .contextPath("/api")
                        .header("Origin", "http://localhost"))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("证据核验已撤回或变更")));
    }

    @Test
    void curationRunEntryIsAdminOnlyAndReadsNeverCache() throws Exception {
        User viewer = new User();
        viewer.setId(1003L);
        viewer.setUserRole(AuthConstant.DEFAULT_ROLE);
        when(userService.getLoginUser(any())).thenReturn(viewer);

        mockMvc.perform(get("/api/admin/news-workbench/materials").contextPath("/api"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        mockMvc.perform(get("/api/admin/news-workbench/materials/9/receipts").contextPath("/api"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        mockMvc.perform(post("/api/admin/news-workbench/materials/9/curate")
                        .contextPath("/api")
                        .header("Origin", "http://localhost"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        verifyNoInteractions(workbenchService);

        viewer.setUserRole(AuthConstant.ADMIN_ROLE);
        when(workbenchService.listCurationMaterials(eq("pubmed"), eq(1L), eq(10L)))
                .thenReturn(new NewsWorkbenchModels.PageVO<>(List.of(), 0, 1, 10, 0));
        mockMvc.perform(get("/api/admin/news-workbench/materials?source=pubmed").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.records").isEmpty());

        when(workbenchService.listMaterialReceipts(9L)).thenReturn(List.of());
        mockMvc.perform(get("/api/admin/news-workbench/materials/9/receipts").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data").isEmpty());

        when(workbenchService.runCuration(eq(9L), any())).thenReturn(
                new NewsWorkbenchModels.CurationRunVO("call-9", "RESEARCH_RADAR", "SUCCEEDED", false,
                        "QWEN", "fixture-model", 400, 160, 900L, 200, null, true, true));
        mockMvc.perform(post("/api/admin/news-workbench/materials/9/curate")
                        .contextPath("/api")
                        .header("Origin", "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.materializable").value(true));

        when(workbenchService.runCuration(eq(10L), any())).thenThrow(
                new BusinessException(ErrorCode.OPERATION_ERROR, "今日精选预算已耗尽，未发出请求"));
        mockMvc.perform(post("/api/admin/news-workbench/materials/10/curate")
                        .contextPath("/api")
                        .header("Origin", "http://localhost"))
                .andExpect(jsonPath("$.code").value(ErrorCode.OPERATION_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("预算已耗尽")));
    }

    @Test
    void issuePreviewRequiresAdminAndDisablesHttpCaching() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 22);
        var previewRequest = get("/api/admin/news-workbench/issue-preview").contextPath("/api")
                .param("startOn", day.toString()).param("endOn", day.toString());
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mockMvc.perform(previewRequest)
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        User user = new User();
        user.setId(1001L);
        user.setUserRole("user");
        org.mockito.Mockito.doReturn(user).when(userService).getLoginUser(any());
        mockMvc.perform(previewRequest)
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        verifyNoInteractions(issueService);

        user.setUserRole(AuthConstant.ADMIN_ROLE);
        when(issueService.preview(day, day))
                .thenReturn(new NewsIssueService.Preview(day, day, 0, List.of()));
        mockMvc.perform(previewRequest)
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.articleCount").value(0))
                .andExpect(jsonPath("$.data.entries").isEmpty());
    }

    @Test
    void crossOriginAndMissingOriginPostsAreBlockedBySameOriginFilter() throws Exception {
        mockMvc.perform(post("/api/admin/news-workbench/sources/pubmed/toggle")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("CROSS_ORIGIN_BLOCKED")));

        mockMvc.perform(post("/api/admin/news-workbench/receipts/call-1/reconcile")
                        .contextPath("/api")
                        .header("Origin", "https://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"CONFIRMED_NOT_CHARGED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("CROSS_ORIGIN_BLOCKED")));

        verifyNoInteractions(workbenchService);
    }

    @Test
    void businessExceptionMapsToEnvelopeAndLeaseViewNeverExposesTokenHash() throws Exception {
        when(workbenchService.toggleSource(eq("unknown"), eq(false)))
                .thenThrow(new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的信源: unknown"));

        mockMvc.perform(post("/api/admin/news-workbench/sources/unknown/toggle")
                        .contextPath("/api")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value("不支持的信源: unknown"));

        when(workbenchService.listTaskLeases("task-1")).thenReturn(List.of(
                new NewsWorkbenchModels.LeaseRecordVO(
                        "task-1",
                        2L,
                        "worker-1",
                        Instant.parse("2026-10-15T12:00:00Z"),
                        Instant.parse("2026-10-15T11:55:00Z")
                )
        ));

        String json = mockMvc.perform(get("/api/admin/news-workbench/tasks/task-1/leases")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.data[0].generation").value("2"))
                .andExpect(jsonPath("$.data[0].workerId").value("worker-1"))
                .andExpect(content().string(not(containsString("leaseTokenHash"))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(json.contains("\"workerId\":\"worker-1\""));
    }

    @Test
    void failedWindowRetryAndAuditEndpointsAreAdminGated() throws Exception {
        User admin = new User();
        admin.setId(1001L);
        admin.setUserRole(AuthConstant.ADMIN_ROLE);
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/api/admin/news-workbench/tasks/task-1/retry")
                        .contextPath("/api")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAttempts\":2}"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        mockMvc.perform(get("/api/admin/news-workbench/tasks/task-1/events").contextPath("/api"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        // 之前的桩是「抛异常」，重新打桩要用 doReturn：when(...) 会先真的调用一次 mock。
        org.mockito.Mockito.doReturn(admin).when(userService).getLoginUser(any());
        when(workbenchService.retryFailedTask(eq("task-1"), any(), any())).thenReturn(new NewsWorkbenchModels.CollectionTaskVO(
                "task-1", "MANUAL", "pubmed", 1L, "ophthalmology", "2026-10-01", "2026-10-02",
                "PENDING", 1L, 2, 5, null, null, false, null, null, null, null, null,
                null, null, null, null, null, false));

        mockMvc.perform(post("/api/admin/news-workbench/tasks/task-1/retry")
                        .contextPath("/api")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAttempts\":2,\"note\":\"重跑失败窗口\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        when(workbenchService.listTaskEvents("task-1")).thenReturn(List.of(
                new NewsWorkbenchModels.TaskEventVO(
                        1L, "task-1", "RETRY", "FAILED", "PENDING", 1001L, "admin", "重跑失败窗口",
                        Instant.parse("2026-11-25T10:00:00Z"))
        ));
        mockMvc.perform(get("/api/admin/news-workbench/tasks/task-1/events").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("RETRY"))
                .andExpect(jsonPath("$.data[0].fromStatus").value("FAILED"))
                .andExpect(jsonPath("$.data[0].toStatus").value("PENDING"));
    }
}
