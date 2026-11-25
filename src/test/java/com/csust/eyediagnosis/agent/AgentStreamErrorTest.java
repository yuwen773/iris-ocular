package com.csust.eyediagnosis.agent;

import com.csust.eyediagnosis.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 流式失败的分类。
 *
 * <p>最容易搞错的两点：
 * <ol>
 *   <li>模型服务拒绝调用时，客户端必须知道是「配置/额度」而不是「网络断了」——
 *       否则运维只会看到一句「连接中断」，查不到 API Key 头上；</li>
 *   <li>第三方（MCP、知识库）返回的 401 不是模型服务故障，不能混为一谈。</li>
 * </ol>
 */
class AgentStreamErrorTest {

    @Test
    void 模型服务返回401归为鉴权失败() {
        assertEquals(ErrorCode.MODEL_SERVICE_UNAUTHORIZED,
                AgentStreamError.classify(modelError(HttpStatus.UNAUTHORIZED, "")));
    }

    @Test
    void 模型服务返回403归为额度不足() {
        assertEquals(ErrorCode.MODEL_SERVICE_QUOTA_EXCEEDED,
                AgentStreamError.classify(modelError(HttpStatus.FORBIDDEN, "")));
    }

    @Test
    void 额度错误看响应体里的错误码更准() {
        String body = "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\"}}";
        assertEquals(ErrorCode.MODEL_SERVICE_QUOTA_EXCEEDED,
                AgentStreamError.classify(modelError(HttpStatus.BAD_REQUEST, body)));
    }

    @Test
    void 模型服务其它状态码归为不可用() {
        assertEquals(ErrorCode.MODEL_SERVICE_UNAVAILABLE,
                AgentStreamError.classify(modelError(HttpStatus.NOT_FOUND, "")));
        assertEquals(ErrorCode.MODEL_SERVICE_UNAVAILABLE,
                AgentStreamError.classify(modelError(HttpStatus.INTERNAL_SERVER_ERROR, "")));
    }

    @Test
    void 第三方服务的401不算模型服务故障() {
        WebClientResponseException error = new WebClientResponseException(
                HttpStatus.UNAUTHORIZED.value(), "Unauthorized", HttpHeaders.EMPTY, new byte[0],
                StandardCharsets.UTF_8, request("https://mcp.example.com/sse"));
        assertEquals(ErrorCode.SYSTEM_ERROR, AgentStreamError.classify(error));
    }

    @Test
    void 包装过的异常也能认出来() {
        RuntimeException wrapped = new RuntimeException("流中断",
                modelError(HttpStatus.UNAUTHORIZED, ""));
        assertEquals(ErrorCode.MODEL_SERVICE_UNAUTHORIZED, AgentStreamError.classify(wrapped));
    }

    @Test
    void 兜底是服务端异常而不是模型服务故障() {
        assertEquals(ErrorCode.SYSTEM_ERROR, AgentStreamError.classify(new RuntimeException("boom")));
    }

    @Test
    void 每种故障都有一句能给人看的话() {
        List<ErrorCode> codes = List.of(
                ErrorCode.MODEL_SERVICE_UNAUTHORIZED,
                ErrorCode.MODEL_SERVICE_QUOTA_EXCEEDED,
                ErrorCode.MODEL_SERVICE_UNAVAILABLE,
                ErrorCode.SYSTEM_ERROR);
        for (ErrorCode code : codes) {
            assertFalse(AgentStreamError.message(code).isBlank(), code + " 缺少说明");
        }
    }

    private static WebClientResponseException modelError(HttpStatus status, String body) {
        return new WebClientResponseException(
                status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
                request("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
    }

    private static MockClientHttpRequest request(String url) {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
    }
}
