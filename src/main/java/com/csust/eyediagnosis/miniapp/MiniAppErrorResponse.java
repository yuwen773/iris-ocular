package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 过滤器层的错误信封：HTTP 200 + 业务码，与 {@code GlobalExceptionHandler} 同形。
 *
 * <p>过滤器跑在 MVC 之前，走不到全局异常处理，只能自己写响应；手拼 JSON 会把
 * 变长文案与转义交给运气，统一用 Jackson 序列化 {@link BaseResponse}。
 */
final class MiniAppErrorResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MiniAppErrorResponse() {
    }

    static void write(HttpServletResponse response, ErrorCode code, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        MAPPER.writeValue(response.getWriter(), new BaseResponse<>(code.getCode(), null, msg));
    }
}
