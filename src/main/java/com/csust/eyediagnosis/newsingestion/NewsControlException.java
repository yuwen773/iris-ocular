package com.csust.eyediagnosis.newsingestion;

import org.springframework.http.HttpStatus;

/**
 * Protocol error carrying the exact HTTP status and {@code ControlError.code} defined by OpenAPI.
 */
public class NewsControlException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public NewsControlException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static NewsControlException invalidRequest() {
        return new NewsControlException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid worker request");
    }

    public static NewsControlException unauthenticated() {
        return new NewsControlException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Worker authentication required");
    }

    public static NewsControlException forbidden() {
        return new NewsControlException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Worker forbidden");
    }

    public static NewsControlException unknownTask() {
        return new NewsControlException(HttpStatus.NOT_FOUND, "UNKNOWN_TASK", "Unknown collection task");
    }

    public static NewsControlException staleLease() {
        return new NewsControlException(HttpStatus.CONFLICT, "STALE_LEASE", "Stale or expired lease");
    }

    public static NewsControlException idempotencyConflict() {
        return new NewsControlException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency conflict");
    }

    public static NewsControlException incompleteBatches() {
        return new NewsControlException(HttpStatus.CONFLICT, "INCOMPLETE_BATCHES", "Incomplete window batches");
    }

    public static NewsControlException unavailable() {
        return new NewsControlException(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "Service unavailable");
    }
}
