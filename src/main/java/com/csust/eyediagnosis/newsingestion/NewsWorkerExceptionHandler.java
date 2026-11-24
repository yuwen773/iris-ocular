package com.csust.eyediagnosis.newsingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Isolated exception mapper for {@link NewsWorkerLeaseController} returning {@code ControlError}
 * JSON instead of the global {@code BaseResponse} wrapper.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NewsWorkerLeaseController.class)
@RequiredArgsConstructor
public class NewsWorkerExceptionHandler {

    private final NewsWireCodec codec;

    @ExceptionHandler(NewsControlException.class)
    public ResponseEntity<String> handleControlException(NewsControlException ex) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(codec.writeControlError(ex.getCode()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<String> handleBindingException(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(codec.writeControlError("INVALID_REQUEST"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> handleDatabaseUnavailable(DataAccessException ex) {
        log.error("Database failure on internal news worker control plane", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(codec.writeControlError("UNAVAILABLE"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception ex) {
        log.error("Unexpected failure on internal news worker control plane", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(codec.writeControlError("UNAVAILABLE"));
    }
}
