package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.exception.GlobalExceptionHandler;
import com.csust.eyediagnosis.controller.DiagnosisTaskController;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class DiagnosisTaskApiTest {
    @org.junit.jupiter.api.BeforeEach
    void prepareDatabaseWhenConfigured() {
        String url = System.getenv("ISSUE17_TEST_URL");
        if (url == null || url.isBlank()) return;
        if (!LegacyGateSupport.isIsolated(url, "issue17_test")) throw new IllegalArgumentException("Expected isolated test database");
        var dataSource = LegacyGateSupport.dataSource(url);
        var jdbc = new JdbcTemplate(dataSource);
        LegacyGateSupport.ensureSchema(url, dataSource, java.util.List.of(), () -> com.csust.eyediagnosis.integration.DiagnosisTaskSchemaFixture.apply(dataSource, jdbc));
    }
    @Test
    void simultaneousIdenticalRequestsReturnOneTask() throws Exception {
        String url = System.getenv("ISSUE17_TEST_URL");
        Assumptions.assumeTrue(url != null && LegacyGateSupport.isIsolated(url, "issue17_test"));
        var jdbc = new JdbcTemplate(LegacyGateSupport.dataSource(url));
        var storage = mock(DiagnosisTaskStorage.class);
        var bothUploaded = new java.util.concurrent.CountDownLatch(2);
        when(storage.upload(any(), any(), any())).thenAnswer(invocation -> {
            bothUploaded.countDown();
            if (!bothUploaded.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("upload barrier timeout");
            return "v1";
        });
        var service = new DiagnosisTaskService(jdbc, storage);
        var user = new User(); user.setId(97L);
        var users = mock(UserService.class);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenReturn(user);
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosisTaskController(service, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String key = java.util.UUID.randomUUID().toString();
        byte[] bytes = png(0);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> create = () -> {
                var response = mvc.perform(multipart("/diagnosis-tasks").file("file", bytes).param("idempotencyKey", key))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("taskId").asText();
            };
            var one = pool.submit(create); var two = pool.submit(create);
            org.junit.jupiter.api.Assertions.assertEquals(one.get(30, java.util.concurrent.TimeUnit.SECONDS), two.get(30, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_diagnosis_task WHERE userId=97 AND idempotencyKey=?", Integer.class, key));
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM t_diagnosis_task WHERE userId=97 AND idempotencyKey=?", key);
        }
    }
    @Test
    void unauthenticatedRequestCannotReachTaskService() throws Exception {
        var users = mock(UserService.class);
        var tasks = mock(DiagnosisTaskService.class);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenThrow(
                new com.csust.eyediagnosis.exception.BusinessException(com.csust.eyediagnosis.exception.ErrorCode.NOT_LOGIN_ERROR));
        var aspect = new com.csust.eyediagnosis.aop.AuthInterceptor();
        org.springframework.test.util.ReflectionTestUtils.setField(aspect, "userService", users);
        var proxyFactory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(new DiagnosisTaskController(tasks, users));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(aspect);
        Object controller = proxyFactory.getProxy();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/diagnosis-tasks/anything")).andExpect(jsonPath("$.code").value(40100));
        org.mockito.Mockito.verifyNoInteractions(tasks);
    }
    @Test
    void truncatedJpegIsRejectedBeforeStorage() throws Exception {
        var image = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", output);
        byte[] complete = output.toByteArray();
        byte[] truncated = java.util.Arrays.copyOf(complete, complete.length - 2);
        var storage = mock(DiagnosisTaskStorage.class);
        var users = mock(UserService.class);
        var user = new User(); user.setId(95L);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenReturn(user);
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosisTaskController(
                        new DiagnosisTaskService(mock(JdbcTemplate.class), storage), users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(multipart("/diagnosis-tasks").file("file", truncated).param("idempotencyKey", "truncated-jpeg"))
                .andExpect(jsonPath("$.code").value(40000));
        org.mockito.Mockito.verifyNoInteractions(storage);
        mvc.perform(multipart("/diagnosis-tasks").file("file", complete).param("idempotencyKey", "valid-jpeg"))
                .andExpect(jsonPath("$.code").value(0));
        org.mockito.Mockito.verify(storage).upload(any(), any(), any());
    }
    @Test
    void invalidImageIsRejectedAndMissingVersionCreatesRestrictedTask() throws Exception {
        String url = System.getenv("ISSUE17_TEST_URL");
        Assumptions.assumeTrue(url != null && LegacyGateSupport.isIsolated(url, "issue17_test"));
        var dataSource = LegacyGateSupport.dataSource(url);
        var jdbc = new JdbcTemplate(dataSource);
        LegacyGateSupport.ensureSchema(url, dataSource, java.util.List.of(), () -> com.csust.eyediagnosis.integration.DiagnosisTaskSchemaFixture.apply(dataSource, jdbc));
        var storage = mock(DiagnosisTaskStorage.class);
        when(storage.upload(any(), any(), any())).thenReturn(null);
        var user = new User(); user.setId(95L);
        var users = mock(UserService.class);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenReturn(user);
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosisTaskController(new DiagnosisTaskService(jdbc, storage), users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String invalidKey = java.util.UUID.randomUUID().toString();
        String versionKey = java.util.UUID.randomUUID().toString();
        mvc.perform(multipart("/diagnosis-tasks").file("file", new byte[]{1, 2}).param("idempotencyKey", invalidKey))
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(multipart("/diagnosis-tasks").file("file", png(0)).param("idempotencyKey", versionKey))
                .andExpect(jsonPath("$.data.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.restrictReason").value("STORAGE_VERSION_MISSING"));
        // 请求重放走的是「从库里读回」这条路：原因只在内存里带着的话，用户刷新状态就只剩「受限」。
        mvc.perform(multipart("/diagnosis-tasks").file("file", png(0)).param("idempotencyKey", versionKey))
                .andExpect(jsonPath("$.data.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.restrictReason").value("STORAGE_VERSION_MISSING"));
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(1)).upload(any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals("STORAGE_VERSION_MISSING", jdbc.queryForObject(
                "SELECT restrictReason FROM t_diagnosis_task WHERE userId=95 AND idempotencyKey=?", String.class, versionKey));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_diagnosis_task WHERE userId=95 AND idempotencyKey=?", Integer.class, invalidKey));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_diagnosis_task WHERE userId=95 AND idempotencyKey=? AND objectVersion IS NULL AND status='RESTRICTED'", Integer.class, versionKey));
        jdbc.update("DELETE FROM t_diagnosis_task WHERE userId=95 AND idempotencyKey=?", versionKey);
    }
    @Test
    void creationReplaysIdenticalRequestAndRejectsChangedImage() throws Exception {
        String url = System.getenv("ISSUE17_TEST_URL");
        Assumptions.assumeTrue(url != null && LegacyGateSupport.isIsolated(url, "issue17_test"));
        var dataSource = LegacyGateSupport.dataSource(url);
        var jdbc = new JdbcTemplate(dataSource);
        LegacyGateSupport.ensureSchema(url, dataSource, java.util.List.of(), () -> com.csust.eyediagnosis.integration.DiagnosisTaskSchemaFixture.apply(dataSource, jdbc));
        var storage = mock(DiagnosisTaskStorage.class);
        when(storage.upload(any(), any(), any())).thenReturn("v1");
        var service = new DiagnosisTaskService(jdbc, storage);
        var users = mock(UserService.class);
        var patient = new User(); patient.setId(41L);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenReturn(patient);
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosisTaskController(service, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String key = java.util.UUID.randomUUID().toString();
        byte[] first = png(0xff000000), second = png(0xffffffff);
        var firstResponse = mvc.perform(multipart("/diagnosis-tasks").file("file", first)
                        .param("idempotencyKey", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING")).andReturn();
        String id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(firstResponse.getResponse().getContentAsString()).path("data").path("taskId").asText();
        mvc.perform(multipart("/diagnosis-tasks").file("file", first).param("idempotencyKey", key))
                .andExpect(jsonPath("$.data.taskId").value(id));
        mvc.perform(multipart("/diagnosis-tasks").file("file", second).param("idempotencyKey", key))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_diagnosis_task WHERE userId=41 AND idempotencyKey=?", Integer.class, key));
        jdbc.update("DELETE FROM t_diagnosis_task WHERE userId=41 AND idempotencyKey=?", key);
    }

    private static byte[] png(int color) throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
    @Test
    void actualDatabaseQueryRestrictsOwnerAndUnknownState() throws Exception {
        String url = System.getenv("ISSUE17_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Requires isolated issue17 database");
        if (!LegacyGateSupport.isIsolated(url, "issue17_test")) {
            throw new IllegalArgumentException("Expected the isolated local issue17_test database");
        }
        var dataSource = LegacyGateSupport.dataSource(url);
        var jdbc = new JdbcTemplate(dataSource);
        LegacyGateSupport.ensureSchema(url, dataSource, java.util.List.of(), () -> com.csust.eyediagnosis.integration.DiagnosisTaskSchemaFixture.apply(dataSource, jdbc));
        String taskId = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO t_diagnosis_task (taskId,userId,idempotencyKey,requestDigest,imageDigest,objectKey,objectVersion,imageFormat,status,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                taskId, 2, taskId, "a".repeat(64), "b".repeat(64), "key", "version", "png", "FUTURE_STATE");
        var users = mock(UserService.class);
        var patient = new User();
        patient.setId(1L);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenReturn(patient);
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosisTaskController(new DiagnosisTaskService(jdbc, mock(DiagnosisTaskStorage.class)), users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/diagnosis-tasks/" + taskId)).andExpect(jsonPath("$.code").value(40400));
        mvc.perform(get("/diagnosis-tasks"))
                .andExpect(jsonPath("$.data[?(@.taskId == '" + taskId + "')]").isEmpty());
        patient.setId(2L);
        mvc.perform(get("/diagnosis-tasks/" + taskId))
                .andExpect(jsonPath("$.data.status").value("RESTRICTED"));
        mvc.perform(get("/diagnosis-tasks"))
                .andExpect(jsonPath("$.data[?(@.taskId == '" + taskId + "')].status").value(org.hamcrest.Matchers.hasItem("RESTRICTED")));
        jdbc.update("DELETE FROM t_diagnosis_task WHERE taskId = ?", taskId);
    }

    @Test
    void patientCannotReadAnotherPatientsTask() throws Exception {
        var users = mock(UserService.class);
        var tasks = mock(DiagnosisTaskService.class);
        var patient = new User();
        patient.setId(1L);
        when(users.getLoginUser(any(HttpServletRequest.class))).thenReturn(patient);
        when(tasks.getOwned("task-1", 1L)).thenThrow(new com.csust.eyediagnosis.exception.BusinessException(
                com.csust.eyediagnosis.exception.ErrorCode.NOT_FOUND_ERROR));
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosisTaskController(tasks, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/diagnosis-tasks/task-1"))
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
