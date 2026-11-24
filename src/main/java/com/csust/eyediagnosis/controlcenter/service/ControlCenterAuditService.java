package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.controlcenter.model.entity.ConfigAuditEvent;
import com.csust.eyediagnosis.mapper.ConfigAuditEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 配置中心审计。
 *
 * <p>两种写入姿势，用途不同：
 * <ul>
 *   <li>{@link #record} —— 与调用方同一事务。配置操作成功/失败的审计必须
 *       和业务写一起提交，否则会出现「发布成功但没有审计」（ADR D4）。</li>
 *   <li>{@link #recordDenied} —— 独立事务（REQUIRES_NEW），用于权限拒绝。
 *       拒绝路径本身没有业务事务，审计也不能因为主流程后续失败而消失；
 *       写失败只记日志，绝不把 403 变成 500。</li>
 * </ul>
 *
 * <p>落库内容约定：只记动作、主体、结果、原因码与字段级摘要。任何名字里带
 * secret/token/password/key 的字段一律脱敏——即便当前 6 个品牌键都不是敏感值，
 * 这条防御也不能省，否则未来加一个敏感键就会静默泄漏。
 */
@Slf4j
@Service
public class ControlCenterAuditService {

    private static final String REDACTED = "***";

    private final ConfigAuditEventMapper auditEventMapper;

    public ControlCenterAuditService(ConfigAuditEventMapper auditEventMapper) {
        this.auditEventMapper = auditEventMapper;
    }

    /** 与调用方同事务写入。 */
    public void record(String action, Long actorId, String targetId, String requestId,
                       String beforeVersion, String afterVersion, String result,
                       String reasonCode, Map<String, Object> payload) {
        ConfigAuditEvent event = build(action, actorId, targetId, requestId,
                beforeVersion, afterVersion, result, reasonCode, payload);
        auditEventMapper.insert(event);
    }

    /**
     * 权限拒绝的独立事务审计。
     *
     * <p>刻意吞掉异常：审计写不进去是运维问题，不能因此把一次「本来就该拒绝」
     * 的请求变成 500——那会让攻击者用写审计来制造可用性故障。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(String action, Long actorId, String targetId, String requestId,
                             String reasonCode) {
        recordIsolated(action, actorId, targetId, requestId, null, null,
                ConfigAuditEvent.RESULT_DENIED, reasonCode, null);
    }

    /**
     * 失败路径的独立事务审计。
     *
     * <p>业务方法在 @Transactional 里抛异常时，同事务写入的审计会跟着回滚；
     * 「操作失败」恰恰是最需要留痕的一类。因此失败审计走独立事务，
     * 同样吞掉写入异常，绝不改变调用方的错误码。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String action, Long actorId, String targetId, String requestId,
                              String beforeVersion, String reasonCode, Map<String, Object> payload) {
        recordIsolated(action, actorId, targetId, requestId, beforeVersion, null,
                ConfigAuditEvent.RESULT_FAILED, reasonCode, payload);
    }

    private void recordIsolated(String action, Long actorId, String targetId, String requestId,
                                String beforeVersion, String afterVersion,
                                String result, String reasonCode, Map<String, Object> payload) {
        try {
            record(action, actorId, targetId, requestId, beforeVersion, afterVersion,
                    result, reasonCode, payload);
        } catch (RuntimeException e) {
            log.warn("配置中心审计写入失败（不影响主流程）, action={}, reason={}", action, reasonCode, e);
        }
    }

    private ConfigAuditEvent build(String action, Long actorId, String targetId, String requestId,
                                   String beforeVersion, String afterVersion, String result,
                                   String reasonCode, Map<String, Object> payload) {
        ConfigAuditEvent event = new ConfigAuditEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setActorId(actorId);
        event.setAction(action);
        event.setTargetId(targetId);
        event.setRequestId(requestId);
        event.setBeforeVersion(beforeVersion);
        event.setAfterVersion(afterVersion);
        event.setResult(result);
        event.setReasonCode(reasonCode);
        event.setPayload(serialize(redact(payload)));
        event.setOccurredAt(new Date());
        return event;
    }

    private Map<String, Object> redact(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            String lower = key.toLowerCase();
            boolean sensitive = lower.contains("secret") || lower.contains("password")
                    || lower.contains("token") || lower.contains("credential")
                    || lower.endsWith("key") || lower.contains("apikey");
            safe.put(key, sensitive ? REDACTED : value);
        });
        return safe;
    }

    private String serialize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("配置中心审计载荷序列化失败，按无载荷记录", e);
            return null;
        }
    }
}
