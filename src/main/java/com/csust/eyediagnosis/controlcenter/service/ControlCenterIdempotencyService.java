package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.common.InsertRaceGuard;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigRequest;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.ConfigRequestMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 配置中心请求级幂等（ADR D12；WP-01C 重写插入语义）。
 *
 * <p><b>占位/回填与业务写同事务</b>，唯一键 {@code (actorId, requestId)} 是判重点：
 * <ol>
 *   <li>{@link #reserve} 是事务里的第一条写：插入一行「处理中」的记录。撞唯一键说明
 *       另一个<b>已提交</b>的事务用过同一个号——读回它的结果，同摘要就重放，
 *       不同摘要就 40908；</li>
 *   <li>{@link #complete} 在业务写之后、提交之前回填首次结果；</li>
 *   <li>业务失败则整个事务（含占位与回填）一起回滚，{@code requestId} 不被消耗，
 *       客户端可以原样重试。</li>
 * </ol>
 *
 * <p>为什么不能像 WP-01B 那样「业务提交后再补写」：那一版在「业务已提交、记录未写入」
 * 之间崩溃时，同一 {@code requestId} 会再执行一次——对发布就是重复发布。
 * 反过来担心「撞唯一键会连带回滚业务写」是多余的：能撞到的一定是别人已经提交的
 * 同一个请求，回滚本事务正是正确结果。
 *
 * <p>「处理中」状态只存在于未提交事务里；已提交行必定带结果（占位与回填同事务）。
 * 读到 {@code resultCode < 0} 时按防御分支返回 40900，不当成正常路径。
 */
@Slf4j
@Service
public class ControlCenterIdempotencyService {

    /** 占位行的结果码：负数表示「尚未回填」。 */
    static final int PENDING_RESULT_CODE = -1;

    /**
     * 与 {@code t_config_request.requestId VARCHAR(64)} 对齐的字符上限（按 Unicode 码点计）。
     *
     * <p>不拦的话，超长 requestId 会让 MySQL 在严格模式下抛 1406，
     * 最终变成 {@code 50000 系统错误}——客户端以为服务坏了，其实只是输入超长。
     */
    static final int REQUEST_ID_MAX_LENGTH = 64;

    private final ConfigRequestMapper requestMapper;
    private final InsertRaceGuard insertRaceGuard;

    private final ObjectMapper mapper = new ObjectMapper();

    public ControlCenterIdempotencyService(ConfigRequestMapper requestMapper,
                                           InsertRaceGuard insertRaceGuard) {
        this.requestMapper = requestMapper;
        this.insertRaceGuard = insertRaceGuard;
    }

    /**
     * 在业务事务内占位。
     *
     * @return 命中已提交的同号请求时返回其首次结果（调用方直接返回，不再执行业务）
     * @throws BusinessException 摘要不一致（40908）或防御性的「仍在处理」（40900）
     */
    public <T> Optional<T> reserve(long actorId, String requestId, String digest,
                                   String action, Class<T> type) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        validateRequestId(requestId);

        ConfigRequest reservation = new ConfigRequest();
        reservation.setActorId(actorId);
        reservation.setRequestId(requestId);
        reservation.setRequestDigest(digest);
        reservation.setAction(action);
        reservation.setResultCode(PENDING_RESULT_CODE);
        // savepoint：撞键后本事务在 PostgreSQL 上仍然可用，才能继续回读首次结果。
        if (insertRaceGuard.tryInsert(() -> requestMapper.insert(reservation))) {
            return Optional.empty();
        }
        return replayExisting(actorId, requestId, digest, type);
    }

    /** 回填首次结果；与业务写同事务，业务回滚时一并消失。 */
    public void complete(long actorId, String requestId, Object result) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        validateRequestId(requestId);
        String payload = null;
        if (result != null) {
            try {
                payload = mapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                // 序列化失败不影响业务：这一次拿不到重放载荷，但状态本身已提交且不可变。
                log.warn("配置中心幂等结果序列化失败，按无载荷记录, requestId={}", requestId, e);
            }
        }
        int rows = requestMapper.complete(actorId, requestId, 0, payload);
        if (rows == 0) {
            // 占位行不见了：说明调用方把 reserve 放在了别的事务里——这是编码错误，必须显式报错。
            throw new IllegalStateException("幂等占位记录缺失，reserve 与 complete 必须在同一事务内");
        }
    }

    /** 发布/回滚这类不可重试动作必须带 requestId。 */
    public static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该操作必须携带 requestId");
        }
        validateRequestId(requestId);
    }

    /** 长度上限按 Unicode 码点算，与 MySQL 的 VARCHAR 计数口径一致。 */
    static void validateRequestId(String requestId) {
        if (requestId != null && requestId.codePointCount(0, requestId.length()) > REQUEST_ID_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "requestId 长度不能超过 " + REQUEST_ID_MAX_LENGTH);
        }
    }

    private <T> Optional<T> replayExisting(long actorId, String requestId, String digest, Class<T> type) {
        ConfigRequest existing = requestMapper.selectByActorAndRequestForUpdate(actorId, requestId);
        if (existing == null) {
            // 对方在「插入 → 查询」之间回滚了：本事务的插入已经失败，只能让客户端重试。
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT, "并发请求冲突，请重试");
        }
        if (!existing.getRequestDigest().equals(digest)) {
            throw new BusinessException(ErrorCode.CONFIG_IDEMPOTENCY_MISMATCH);
        }
        if (existing.getResultCode() != null && existing.getResultCode() < 0) {
            // 构造上不可达（占位与回填同事务）；保留分支是为了让异常可见，而不是静默回放空结果。
            log.warn("配置中心幂等记录处于未完成状态, actorId={}, requestId={}", actorId, requestId);
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT, "同一请求仍在处理中，请稍后重试");
        }
        if (existing.getResultPayload() == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(existing.getResultPayload(), type));
        } catch (JsonProcessingException e) {
            log.warn("配置中心幂等结果无法反序列化，按未命中处理, requestId={}", requestId, e);
            return Optional.empty();
        }
    }
}
