package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.controlcenter.model.entity.ConfigRequest;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.ConfigRequestMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 幂等键的输入边界（WP-01D）。
 *
 * <p>超长 {@code requestId} 以前会一路写进 {@code VARCHAR(64)}，由 MySQL 抛 1406，
 * 最终变成 50000「系统错误」。现在在写库之前就按 40000 拒绝——这两条用例锁住
 * 「先校验、后落库」这个顺序，避免以后有人把校验挪到 insert 之后。
 */
class ControlCenterIdempotencyServiceTest {

    private static final int LIMIT = ControlCenterIdempotencyService.REQUEST_ID_MAX_LENGTH;

    private final ConfigRequestMapper mapper = mock(ConfigRequestMapper.class);

    private final ControlCenterIdempotencyService service = new ControlCenterIdempotencyService(mapper);

    @Test
    void blankRequestIdTurnsIdempotencyOffWithoutTouchingTheDatabase() {
        assertTrue(service.reserve(1L, "  ", "digest", "DRAFT_UPDATE", Object.class).isEmpty());
        service.complete(1L, "  ", "payload");
        verifyNoInteractions(mapper);
    }

    @Test
    void overlongRequestIdIsRejectedBeforeTheInsert() {
        String tooLong = "r".repeat(LIMIT + 1);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.reserve(1L, tooLong, "digest", "DRAFT_UPDATE", Object.class));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void requestIdAtTheLimitIsAccepted() {
        when(mapper.insert(any(ConfigRequest.class))).thenReturn(1);

        assertTrue(service.reserve(1L, "r".repeat(LIMIT), "digest", "DRAFT_UPDATE", Object.class).isEmpty());
    }

    @Test
    void requireRequestIdAppliesTheSameLimit() {
        String tooLong = "r".repeat(LIMIT + 1);

        BusinessException blank = assertThrows(BusinessException.class,
                () -> ControlCenterIdempotencyService.requireRequestId(" "));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), blank.getCode());

        BusinessException overlong = assertThrows(BusinessException.class,
                () -> ControlCenterIdempotencyService.requireRequestId(tooLong));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), overlong.getCode());
    }

    @Test
    void lengthIsMeasuredInCodePointsNotUtf16Units() {
        // 64 个 emoji 在 UTF-16 里是 128 个 char，但 MySQL VARCHAR(64) 按字符计——不能误拒。
        String emoji = "😀".repeat(LIMIT);
        when(mapper.insert(any(ConfigRequest.class))).thenReturn(1);

        assertTrue(service.reserve(1L, emoji, "digest", "DRAFT_UPDATE", Object.class).isEmpty());
    }
}
