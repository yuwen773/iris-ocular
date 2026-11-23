package com.csust.eyediagnosis.agent.service;

import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.dto.agent.MessageDTO;
import com.csust.eyediagnosis.service.UserService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式期间的账号闸门：封禁发生在「开写之后」时，这条连接也必须停。
 */
class AccountStreamGuardTest {

    private static MessageDTO message(String id) {
        MessageDTO dto = new MessageDTO();
        dto.setContent(id);
        return dto;
    }

    @Test
    void anonymousStreamIsNotGated() {
        UserService userService = mock(UserService.class);
        Flux<MessageDTO> source = Flux.just(message("m1"));
        Flux<MessageDTO> guarded = new AccountStreamGuard(userService, Duration.ZERO).guard(null, source);
        // 没有账号可封：连装配都不该动，流本身原样返回
        assertSame(source, guarded);
    }

    @Test
    void activeAccountLetsEveryEventThrough() {
        UserService userService = mock(UserService.class);
        when(userService.isAccountActive(7L)).thenReturn(true);
        List<MessageDTO> emitted = new AccountStreamGuard(userService, Duration.ZERO)
                .guard(7L, Flux.just(message("m1"), message("m2")))
                .collectList().block();
        assertEquals(2, emitted.size());
    }

    @Test
    void banMidStreamCutsTheRemainingEvents() {
        UserService userService = mock(UserService.class);
        // 第一个事件时还能用，之后已经被封禁
        when(userService.isAccountActive(7L)).thenReturn(true, false, false);
        Flux<MessageDTO> guarded = new AccountStreamGuard(userService, Duration.ZERO)
                .guard(7L, Flux.just(message("m1"), message("m2"), message("m3")));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> guarded.collectList().block());
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), failure.getCode());
    }

    @Test
    void checkRunsOncePerIntervalNotPerEvent() {
        UserService userService = mock(UserService.class);
        AtomicInteger checks = new AtomicInteger();
        when(userService.isAccountActive(7L)).thenAnswer(invocation -> {
            checks.incrementAndGet();
            return true;
        });
        // 30 秒的回查间隔：一次短回答只应该查一次
        new AccountStreamGuard(userService, Duration.ofSeconds(30))
                .guard(7L, Flux.just(message("m1"), message("m2"), message("m3")))
                .collectList().block();
        assertEquals(1, checks.get());
    }

    @Test
    void zeroIntervalChecksEveryEvent() {
        UserService userService = mock(UserService.class);
        AtomicInteger checks = new AtomicInteger();
        when(userService.isAccountActive(7L)).thenAnswer(invocation -> {
            checks.incrementAndGet();
            return true;
        });
        new AccountStreamGuard(userService, Duration.ZERO)
                .guard(7L, Flux.just(message("m1"), message("m2"), message("m3")))
                .collectList().block();
        assertEquals(3, checks.get());
    }

    @Test
    void eachSubscriptionStartsWithAFreshClock() {
        UserService userService = mock(UserService.class);
        AtomicInteger checks = new AtomicInteger();
        when(userService.isAccountActive(7L)).thenAnswer(invocation -> {
            checks.incrementAndGet();
            return true;
        });
        Flux<MessageDTO> guarded = new AccountStreamGuard(userService, Duration.ofSeconds(30))
                .guard(7L, Flux.fromArray(new MessageDTO[]{message("m1")}));
        guarded.collectList().block();
        guarded.collectList().block();
        // 同一个 guard 被订阅两次，各有一份时间片：第二次也要检查，不能沿用上一轮的截止时间
        assertEquals(2, checks.get());
    }
}
