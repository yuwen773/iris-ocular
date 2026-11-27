package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.common.InsertRaceGuard;
import com.csust.eyediagnosis.controlcenter.model.dto.DraftSaveRequest;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigAuditEvent;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigDraft;
import com.csust.eyediagnosis.controlcenter.model.enums.ConfigErrorReason;
import com.csust.eyediagnosis.controlcenter.model.vo.DraftVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ValidationVO;
import com.csust.eyediagnosis.controlcenter.registry.ConfigKey;
import com.csust.eyediagnosis.controlcenter.registry.ConfigRegistry;
import com.csust.eyediagnosis.controlcenter.registry.ConfigValueValidator;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.ConfigDraftMapper;
import com.csust.eyediagnosis.model.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 草稿生命周期：创建、读取、修改、校验。
 *
 * <p>并发模型（ADR D3）：所有修改都是「带 revision 条件的 UPDATE」，受影响行数
 * 为 0 即 40900。这里刻意不做 read-modify-write——两次并发编辑都会成功写回，
 * 后写的静默覆盖先写的，而这正是配置中心最不能出现的失败。
 *
 * <p>事务边界：草稿写 + 审计同事务提交；失败审计走独立事务（见
 * {@link ControlCenterAuditService#recordFailure}）。
 */
@Service
public class ControlCenterDraftService {

    private final ConfigDraftMapper draftMapper;
    private final ConfigRegistry registry;
    private final ConfigValueValidator validator;
    private final ConfigPayloadCodec codec;
    private final ControlCenterAuditService audit;
    private final ControlCenterIdempotencyService idempotency;
    private final InsertRaceGuard insertRaceGuard;

    public ControlCenterDraftService(ConfigDraftMapper draftMapper,
                                     ConfigRegistry registry,
                                     ConfigValueValidator validator,
                                     ConfigPayloadCodec codec,
                                     ControlCenterAuditService audit,
                                     ControlCenterIdempotencyService idempotency,
                                     InsertRaceGuard insertRaceGuard) {
        this.draftMapper = draftMapper;
        this.registry = registry;
        this.validator = validator;
        this.codec = codec;
        this.audit = audit;
        this.idempotency = idempotency;
        this.insertRaceGuard = insertRaceGuard;
    }

    /** 读取当前草稿；不存在时返回 revision=0 的空草稿（不是 404：前端首屏需要它）。 */
    public DraftVO get() {
        ConfigDraft row = draftMapper.selectById(ConfigDraft.SINGLETON_ID);
        return row == null ? emptyDraft() : toVO(row);
    }

    /** 创建草稿。已存在则 40900——不静默覆盖别人的草稿。 */
    @Transactional
    public DraftVO create(DraftSaveRequest request, User actor, String requestDigest) {
        java.util.Optional<DraftVO> replay = idempotency.reserve(actor.getId(), request.getRequestId(),
                requestDigest, "DRAFT_CREATE", DraftVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, Object> values = acceptedValues(request);

        ConfigDraft draft = new ConfigDraft();
        draft.setId(ConfigDraft.SINGLETON_ID);
        draft.setRevision(1L);
        draft.setStatus(ConfigDraft.STATUS_DRAFT);
        draft.setPayload(codec.canonical(values));
        draft.setUpdatedBy(actor.getId());
        // 撞键后还要写失败审计并抛 40900；PostgreSQL 上事务已被中止，必须先回滚到
        // savepoint，失败审计才能落库、40900 才不会被 25P02 顶掉。
        if (!insertRaceGuard.tryInsert(() -> draftMapper.insert(draft))) {
            audit.recordFailure("DRAFT_CREATE", actor.getId(), "1", request.getRequestId(),
                    null, ConfigErrorReason.VERSION_CONFLICT.name(), null);
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT, "草稿已存在，请改为更新");
        }

        audit.record("DRAFT_CREATE", actor.getId(), "1", request.getRequestId(),
                null, "1", ConfigAuditEvent.RESULT_SUCCESS, null, changedKeys(Map.of(), values));
        DraftVO created = get();
        idempotency.complete(actor.getId(), request.getRequestId(), created);
        return created;
    }

    /** 更新草稿：必须带 expectedRevision，条件更新失败即 40900。 */
    @Transactional
    public DraftVO update(long id, DraftSaveRequest request, User actor, String requestDigest) {
        java.util.Optional<DraftVO> replay = idempotency.reserve(actor.getId(), request.getRequestId(),
                requestDigest, "DRAFT_UPDATE", DraftVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireSingleton(id);
        if (request.getExpectedRevision() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "更新草稿必须携带 expectedRevision");
        }
        Map<String, Object> values = acceptedValues(request);

        ConfigDraft before = draftMapper.selectById(ConfigDraft.SINGLETON_ID);
        if (before == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "草稿不存在，请先创建");
        }

        int rows = draftMapper.updateWithRevision(ConfigDraft.SINGLETON_ID,
                request.getExpectedRevision(), codec.canonical(values), actor.getId());
        if (rows == 0) {
            audit.recordFailure("DRAFT_UPDATE", actor.getId(), "1", request.getRequestId(),
                    String.valueOf(request.getExpectedRevision()),
                    ConfigErrorReason.VERSION_CONFLICT.name(), null);
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT);
        }

        long after = request.getExpectedRevision() + 1;
        audit.record("DRAFT_UPDATE", actor.getId(), "1", request.getRequestId(),
                String.valueOf(before.getRevision()), String.valueOf(after),
                ConfigAuditEvent.RESULT_SUCCESS, null,
                changedKeys(codec.parse(before.getPayload()), values));
        DraftVO updated = get();
        idempotency.complete(actor.getId(), request.getRequestId(), updated);
        return updated;
    }

    /**
     * 校验草稿。
     *
     * <p>校验失败是<b>正常结果</b>：返回 valid=false 与字段级原因，并发一条
     * FAILED 审计。只有 revision 不符才是异常（40900）——那说明校验的不是
     * 用户看到的这份内容。
     */
    @Transactional
    public ValidationVO validate(long id, long expectedRevision, String requestId, User actor,
                                 String requestDigest) {
        java.util.Optional<ValidationVO> replay = idempotency.reserve(actor.getId(), requestId,
                requestDigest, "DRAFT_VALIDATE", ValidationVO.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireSingleton(id);
        ConfigDraft before = draftMapper.selectById(ConfigDraft.SINGLETON_ID);
        if (before == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "草稿不存在，请先创建");
        }
        if (!Objects.equals(before.getRevision(), expectedRevision)) {
            audit.recordFailure("DRAFT_VALIDATE", actor.getId(), "1", requestId,
                    String.valueOf(expectedRevision),
                    ConfigErrorReason.VERSION_CONFLICT.name(), null);
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT);
        }

        ConfigValueValidator.Result result = validator.validate(codec.parse(before.getPayload()));
        if (!result.valid()) {
            audit.record("DRAFT_VALIDATE", actor.getId(), "1", requestId,
                    String.valueOf(before.getRevision()), String.valueOf(before.getRevision()),
                    ConfigAuditEvent.RESULT_FAILED, result.primaryReason(),
                    Map.of("fieldErrors", result.fieldErrors()));
            ValidationVO invalid = new ValidationVO(before.getId(), before.getRevision(), false, result.fieldErrors());
            idempotency.complete(actor.getId(), requestId, invalid);
            return invalid;
        }

        String resultJson;
        try {
            resultJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("valid", true, "fieldErrors", Map.of()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("校验结果无法序列化", e);
        }
        int rows = draftMapper.markValidated(ConfigDraft.SINGLETON_ID, expectedRevision, resultJson);
        if (rows == 0) {
            audit.recordFailure("DRAFT_VALIDATE", actor.getId(), "1", requestId,
                    String.valueOf(expectedRevision),
                    ConfigErrorReason.STALE_VALIDATION.name(), null);
            throw new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT);
        }

        audit.record("DRAFT_VALIDATE", actor.getId(), "1", requestId,
                String.valueOf(before.getRevision()), String.valueOf(before.getRevision()),
                ConfigAuditEvent.RESULT_SUCCESS, null, null);
        ValidationVO valid = new ValidationVO(before.getId(), before.getRevision(), true, Map.of());
        idempotency.complete(actor.getId(), requestId, valid);
        return valid;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> acceptedValues(DraftSaveRequest request) {
        if (request.getSchemaVersion() == null
                || request.getSchemaVersion() != ConfigRegistry.SCHEMA_VERSION) {
            throw new BusinessException(ErrorCode.CONFIG_SCHEMA_UNSUPPORTED,
                    "载荷版本必须是 " + ConfigRegistry.SCHEMA_VERSION);
        }
        Map<String, Object> values = request.getValues();
        if (values == null) {
            return Map.of();
        }
        ConfigValueValidator.Result result = validator.validate(values);
        if (result.valid()) {
            return values;
        }
        throw rejectionFor(result);
    }

    /** 把字段级校验错误映射成错误码：未知键与部署保护键单独成码，其余是参数错误。 */
    private BusinessException rejectionFor(ConfigValueValidator.Result result) {
        ConfigErrorReason reason = ConfigErrorReason.fromName(result.primaryReason());
        ErrorCode code = reason == null ? ErrorCode.PARAMS_ERROR : reason.errorCode();
        List<String> keys = new ArrayList<>(new TreeMap<>(result.fieldErrors()).keySet());
        String message = switch (code) {
            case CONFIG_UNKNOWN_KEY -> "存在未注册的配置键：" + String.join("、", keys);
            case CONFIG_DEPLOY_PROTECTED -> "以下配置属于部署事实，不允许在线修改：" + String.join("、", keys);
            default -> "配置值不合法：" + String.join("、", keys);
        };
        return new BusinessException(code, message);
    }

    private void requireSingleton(long id) {
        if (id != ConfigDraft.SINGLETON_ID) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "草稿 id 必须为 1");
        }
    }

    private DraftVO emptyDraft() {
        return new DraftVO(ConfigDraft.SINGLETON_ID, 0L, "NONE", Map.of(), null, null, null, null);
    }

    private DraftVO toVO(ConfigDraft row) {
        Map<String, Object> values = codec.parse(row.getPayload());
        Object validationResult = row.getValidationResult() == null
                ? null
                : codec.parse(row.getValidationResult());
        return new DraftVO(row.getId(), row.getRevision(), row.getStatus(), values,
                row.getValidationRevision(), validationResult, row.getUpdatedBy(), row.getUpdateTime());
    }

    /** 字段级 diff：只记键名，不记值——审计不需要复制配置内容。 */
    private Map<String, Object> changedKeys(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (ConfigKey key : registry.definitions()) {
            Object oldValue = before == null ? null : before.get(key.key());
            Object newValue = after == null ? null : after.get(key.key());
            if (!Objects.equals(oldValue, newValue)) {
                keys.add(key.key());
            }
        }
        if (!keys.isEmpty()) {
            changed.put("changedKeys", List.copyOf(keys));
        }
        return changed;
    }
}
