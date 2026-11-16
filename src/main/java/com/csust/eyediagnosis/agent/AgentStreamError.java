package com.csust.eyediagnosis.agent;

import com.csust.eyediagnosis.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * 流式对话失败的翻译层。
 *
 * <p>模型服务（DashScope）失败时，流里必须能区分「服务端自己坏了」和
 * 「服务端配置错了」：前者重试可能有用，后者重试多少次都一样，用户看到的
 * 也不该是同一句话。分类结果同时给出机器可读的码和给人看的说明，
 * 前端按码决定措辞与处置。
 */
public final class AgentStreamError {

    /** 只有指向模型服务的失败才归到「模型服务」这一类——MCP 等第三方返回的 401 不是同一件事。 */
    private static final String MODEL_SERVICE_HOST = "dashscope.aliyuncs.com";

    private static final Map<ErrorCode, String> MESSAGES = Map.of(
            ErrorCode.MODEL_SERVICE_UNAUTHORIZED,
            "模型服务鉴权失败：服务端调用大模型时被拒绝（API Key 无效、失效或无权限），请联系管理员检查服务端配置。",
            ErrorCode.MODEL_SERVICE_QUOTA_EXCEEDED,
            "模型服务额度不足：大模型调用被额度策略拒绝，请联系管理员处理。",
            ErrorCode.MODEL_SERVICE_UNAVAILABLE,
            "模型服务暂时不可用：调用大模型时出错，请稍后重试。",
            ErrorCode.SYSTEM_ERROR,
            "系统繁忙，请稍后重试。"
    );

    private AgentStreamError() {
    }

    /**
     * 把流里抛出的异常归类成对外的错误码。
     *
     * <p>只有模型服务自身的拒绝才细分鉴权 / 额度；其余异常一律按服务端异常处理，
     * 避免把第三方（MCP、知识库）的失败说成模型服务的问题。
     */
    public static ErrorCode classify(Throwable error) {
        WebClientResponseException response = findModelServiceResponse(error);
        if (response == null) {
            return ErrorCode.SYSTEM_ERROR;
        }
        int status = response.getStatusCode().value();
        // 响应体里的错误码比状态码更具体：额度不足与鉴权失败都可能是 4xx。
        String body = response.getResponseBodyAsString();
        if (status == HttpStatus.UNAUTHORIZED.value() || body.contains("invalid_api_key")) {
            return ErrorCode.MODEL_SERVICE_UNAUTHORIZED;
        }
        if (status == HttpStatus.FORBIDDEN.value() || body.contains("AllocationQuota")) {
            return ErrorCode.MODEL_SERVICE_QUOTA_EXCEEDED;
        }
        return ErrorCode.MODEL_SERVICE_UNAVAILABLE;
    }

    /** 面向用户的说明：说清发生了什么、接下来找谁。 */
    public static String message(ErrorCode code) {
        return MESSAGES.getOrDefault(code, MESSAGES.get(ErrorCode.SYSTEM_ERROR));
    }

    /**
     * 在异常链里找模型服务返回的响应异常。
     *
     * <p>Reactor 包装过的异常不会直接暴露原始类型，只能沿 cause 链找；
     * 请求地址不是模型服务的（第三方 HTTP 调用）一律不认作模型服务故障。
     */
    private static WebClientResponseException findModelServiceResponse(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientResponseException response && isModelService(response)) {
                return response;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isModelService(WebClientResponseException response) {
        if (response.getRequest() == null || response.getRequest().getURI() == null) {
            return false;
        }
        String host = response.getRequest().getURI().getHost();
        return host != null && host.endsWith(MODEL_SERVICE_HOST);
    }
}
