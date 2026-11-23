package com.csust.eyediagnosis.agent.service;

import com.csust.eyediagnosis.common.LogAnonymizer;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.model.dto.agent.MessageDTO;
import com.csust.eyediagnosis.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式响应期间的账号状态闸门。
 *
 * <p>入口处的 {@code getLoginUser} 只回答「开始这一轮时账号还能不能用」。流式回答可能
 * 持续数分钟，而封禁是**这之后**才发生的事：管理员点了封禁，这条连接却还在一个事件一个
 * 事件地往下吐会话内容。封禁的意义不是「下次登录被拒」，而是「现在就读不到」。
 *
 * <p>做法是按时间片回查（默认 {@value #DEFAULT_RECHECK_SECONDS} 秒一次，不是每个事件一次）：
 * 一次主键查询的代价换「最迟这么久之后停」，既不把每个 token 都变成一次数据库往返，
 * 也不给长连接留永久豁免。
 */
@Slf4j
@Component
public class AccountStreamGuard {

    /** 默认回查间隔（秒）。 */
    static final long DEFAULT_RECHECK_SECONDS = 5;

    private final UserService userService;
    private final Duration recheckInterval;

    @Autowired
    public AccountStreamGuard(UserService userService) {
        this(userService, Duration.ofSeconds(DEFAULT_RECHECK_SECONDS));
    }

    AccountStreamGuard(UserService userService, Duration recheckInterval) {
        this.userService = userService;
        this.recheckInterval = recheckInterval;
    }

    /**
     * 给事件流套上账号状态闸门。
     *
     * <p>{@code userId} 为空（匿名会话）时不加闸门：没有账号可封。
     * 一旦账号在该轮进行中变为不可用，流以 {@link ErrorCode#NOT_LOGIN_ERROR} 终止，
     * 后续事件不再投递。
     */
    public Flux<MessageDTO> guard(Long userId, Flux<MessageDTO> stream) {
        if (userId == null) {
            return stream;
        }
        return Flux.defer(() -> {
            // 每个订阅一份时间片：同一 guard 被订阅两次（重试、重新生成）时各查各的。
            // concatMap 保证同一订阅内事件串行处理，所以这里只要 get/set。
            AtomicLong nextCheckNanos = new AtomicLong(System.nanoTime());
            return stream.concatMap(message -> {
                long now = System.nanoTime();
                long due = nextCheckNanos.get();
                if (now < due) {
                    return Mono.just(message);
                }
                nextCheckNanos.set(now + recheckInterval.toNanos());
                if (userService.isAccountActive(userId)) {
                    return Mono.just(message);
                }
                log.warn("Agent 流式对话期间账号已不可用，终止本轮, userIdHash={}",
                        LogAnonymizer.userId(userId));
                return Mono.error(new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "账号已不可用"));
            });
        });
    }
}
