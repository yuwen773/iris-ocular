package com.csust.eyediagnosis.controlcenter;

/**
 * 控制中心 CSRF token 的取用响应。
 *
 * @param token      会话绑定的 token 值
 * @param headerName 提交时必须使用的请求头名（固定 {@code X-CSRF-Token}）
 */
public record CsrfTokenVO(String token, String headerName) {
}
