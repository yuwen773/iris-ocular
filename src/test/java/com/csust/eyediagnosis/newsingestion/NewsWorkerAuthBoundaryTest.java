package com.csust.eyediagnosis.newsingestion;

import com.csust.eyediagnosis.config.ControlCenterProperties;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.config.SameOriginFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L1 authentication and channel-boundary tests covering {@link NewsWorkerAuthFilter},
 * {@link SameOriginFilter}, {@link NewsWorkerLeaseController}, and {@link NewsWorkerExceptionHandler}.
 */
class NewsWorkerAuthBoundaryTest {

    private static final String VALID_WORKER_KEY = "prod-news-worker-key-a1b2c3d4e5f6";

    private NewsIngestionProperties properties;
    private NewsIngestionService ingestionService;
    private NewsWireCodec codec;
    private MockMvc mockMvc;

    @RestController
    static class DummyPublicController {
        @PostMapping("/news/add")
        public String addNews() {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        properties = new NewsIngestionProperties();
        properties.setEnabled(true);
        NewsIngestionProperties.WorkerConfig worker = new NewsIngestionProperties.WorkerConfig();
        worker.setKey(VALID_WORKER_KEY);
        worker.setAllowedSources(List.of("pubmed", "clinicaltrials-gov"));
        properties.setWorkers(Map.of("worker-1", worker));

        ingestionService = mock(NewsIngestionService.class);
        when(ingestionService.claim(any())).thenReturn(Optional.empty());
        codec = new NewsWireCodec(JsonConfig.createNewsProtocolObjectMapper());

        NewsWorkerAuthFilter authFilter = new NewsWorkerAuthFilter(properties);
        SameOriginFilter sameOriginFilter = new SameOriginFilter(new ControlCenterProperties(), (request, action, reason) -> { });
        ReflectionTestUtils.setField(sameOriginFilter, "additionalHosts", List.of());

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new NewsWorkerLeaseController(ingestionService, codec),
                        new DummyPublicController())
                .setControllerAdvice(new NewsWorkerExceptionHandler(codec))
                .addFilters(authFilter, sameOriginFilter)
                .build();
    }

    @Test
    void missingOrWrongWorkerKeyReturns401ControlError() throws Exception {
        mockMvc.perform(post("/api/internal/news/v1/leases/claim").contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"UNAUTHENTICATED\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, "wrong-worker-key-000000000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHENTICATED\"}", true));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void cookieOnlyAndBearerOnlyCannotAccessInternalWorkerChannel() throws Exception {
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header("Cookie", "JSESSIONID=user-session-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHENTICATED\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header("Authorization", "Bearer miniapp-jwt-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHENTICATED\"}", true));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void workerKeyMixedWithCookieOrBearerIsRejectedWith403() throws Exception {
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY)
                        .header("Cookie", "JSESSIONID=user-session-1"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY)
                        .header("Authorization", "Bearer miniapp-jwt-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void workerKeyDoesNotBypassSameOriginOnPublicOrTraversalPaths() throws Exception {
        mockMvc.perform(post("/api/news/add")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("CROSS_ORIGIN_BLOCKED")));

        mockMvc.perform(post("/api/internal/news/v1/leases/../news/add")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("CROSS_ORIGIN_BLOCKED")));

        mockMvc.perform(post("/api/internal/news/v1/leases;bypass=1/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("CROSS_ORIGIN_BLOCKED")));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void validWorkerKeyPassesWithoutOriginAndRespectsHttpsIpAndEnabledGuards() throws Exception {
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isNoContent());

        // requireHttps enforcement
        properties.setRequireHttps(true);
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY)
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .secure(true)
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isNoContent());

        // Remote IP allowlist enforcement
        properties.setRequireHttps(false);
        properties.setAllowedRemoteAddresses(List.of("10.0.0.5"));
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY)
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY)
                        .with(req -> {
                            req.setRemoteAddr("10.0.0.5");
                            return req;
                        }))
                .andExpect(status().isNoContent());

        // Fail-closed enabled=false: valid key gets 503 UNAVAILABLE, invalid key still gets 401
        properties.setAllowedRemoteAddresses(List.of());
        properties.setEnabled(false);
        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"code\":\"UNAVAILABLE\"}", true));

        mockMvc.perform(post("/api/internal/news/v1/leases/claim")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, "wrong-worker-key-000000000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHENTICATED\"}", true));
    }

    @Test
    void oversizedBodyReturnsRaw400ControlErrorWithoutBaseResponseWrapper() throws Exception {
        byte[] oversized = new byte[NewsWireModels.MAX_REQUEST_BYTES + 1];
        mockMvc.perform(post("/api/internal/news/v1/leases/task-001/batches")
                        .contextPath("/api")
                        .header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, VALID_WORKER_KEY)
                        .header("Idempotency-Key", "1:1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.msg").doesNotExist());
    }
}
