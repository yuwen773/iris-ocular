package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.config.SameOriginFilter;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.GlobalExceptionHandler;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L1 security, auth-boundary, SameOrigin CSRF, and error-envelope tests for {@link NewsWorkbenchController}.
 */
class NewsWorkbenchControllerSecurityTest {

    private NewsWorkbenchService workbenchService;
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workbenchService = mock(NewsWorkbenchService.class);
        userService = mock(UserService.class);

        User admin = new User();
        admin.setId(1001L);
        admin.setUserRole(AuthConstant.ADMIN_ROLE);
        when(userService.getLoginUser(any())).thenReturn(admin);

        SameOriginFilter sameOriginFilter = new SameOriginFilter();
        ReflectionTestUtils.setField(sameOriginFilter, "additionalHosts", List.of());

        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter(
                new JsonConfig().jacksonObjectMapper(Jackson2ObjectMapperBuilder.json()));

        mockMvc = MockMvcBuilders.standaloneSetup(new NewsWorkbenchController(workbenchService, userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jsonConverter)
                .addFilters(sameOriginFilter)
                .build();
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
        assertEquals(16, mappedMethods.size());
        for (Method method : mappedMethods) {
            AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
            assertNotNull(authCheck, () -> "Missing @AuthCheck on " + method.getName());
            assertEquals(AuthConstant.ADMIN_ROLE, authCheck.mustRole(),
                    () -> "Expected ADMIN_ROLE @AuthCheck on " + method.getName());
        }
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
}
