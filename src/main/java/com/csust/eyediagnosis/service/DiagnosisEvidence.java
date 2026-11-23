package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.constant.HumanReviewStatus;
import com.csust.eyediagnosis.grpc.DiagnosisProto;
import com.csust.eyediagnosis.model.entity.Diagnosis;
import com.csust.eyediagnosis.model.entity.InferenceCache;
import com.csust.eyediagnosis.model.vo.diagnosis.DiagnosisResultVO;
import com.csust.eyediagnosis.utils.JsonUtils;
import cn.hutool.core.util.StrUtil;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 诊断证据快照：一次归一化，写入多个目标。
 *
 * <p>版本化的推理安全字段原本在三处各写一遍（{@code doDiagnosis} 的内联
 * {@code diagnosis.set*}、{@code buildDiagnosisResult} 的 VO 映射、
 * 缓存映射），列宽与取值口径会互相漂移。这里把「从 gRPC 响应读出证据」
 * 收成一处，对外只有三个 {@code applyTo} 与一个 {@code newCache}。
 * {@code topPredictions} 的序列化只在 {@code newCache} 时需要，故留到
 * 那一刻才算，VO / 记录路径不做这份无用功。
 *
 * <p>历史记录必须自带证据，不依赖可能过期或被覆盖的共享缓存（V9 约定），
 * 因此 {@link Diagnosis} 与 {@link InferenceCache} 都从同一个快照取值。
 *
 * <p>一处刻意的分歧放在这里而不是散在各调用方：缓存要能与 proto 精确往返
 * （{@code toProto} 需要原始 {@code calibratedProbability}），而面向用户的
 * VO / 诊断记录在未校准时不得透出该概率，故置 null。
 */
public final class DiagnosisEvidence {

    private static final String CALIBRATED = "CALIBRATED";

    private final DiagnosisProto.DiagnosisResponse resp;
    private final String modelVersion;
    private final String weightSha256;
    private final String preprocessVersion;
    private final String inferencePolicyVersion;
    private final String calibrationStatus;
    private final Double exposedCalibratedProbability;
    private final Integer qualityScore;
    private final String qualityReasons;
    private final String oodStatus;
    private final Double oodScore;
    private final boolean rejected;
    private final String abstainReason;
    private final boolean requiresReview;

    // ── 相互独立的证据字段（2026-11-23）─────────────────────────────────
    // 分类概率、校准状态与版本、图像质量状态与版本、能否面向患者呈现：
    // 各读各的字段，不从别的字段推。缺字段时留空，不猜。
    private final Double classificationProbability;
    private final String calibrationVersion;
    private final String imageQualityState;
    private final String imageQualityVersion;
    private final String imageQualityCalibrationState;
    private final Boolean patientConclusionAllowed;

    private DiagnosisEvidence(DiagnosisProto.DiagnosisResponse resp,
                              String configuredModelVersion,
                              String configuredWeightSha256,
                              String configuredPreprocessVersion,
                              String configuredPolicyVersion) {
        this.resp = resp;
        this.modelVersion = StrUtil.blankToDefault(resp.getModelVersion(), configuredModelVersion);
        this.weightSha256 = normalizeWeightSha256(
                StrUtil.blankToDefault(resp.getWeightSha256(), configuredWeightSha256));
        this.preprocessVersion = StrUtil.blankToDefault(resp.getPreprocessVersion(), configuredPreprocessVersion);
        this.inferencePolicyVersion = StrUtil.blankToDefault(
                resp.getInferencePolicyVersion(), configuredPolicyVersion);
        this.calibrationStatus = resp.getCalibrationStatus();
        this.exposedCalibratedProbability = CALIBRATED.equals(resp.getCalibrationStatus())
                ? resp.getCalibratedProbability() : null;
        this.qualityScore = resp.getQualityScore();
        this.qualityReasons = resp.getQualityReasons();
        this.oodStatus = resp.getOodStatus().name();
        this.oodScore = resp.getOodScore();
        this.rejected = resp.getRejected();
        this.abstainReason = resp.getAbstainReason();
        this.requiresReview = resp.getRequiresReview();
        // 缺字段时留空，不猜：optional 字段用 has* 判断 presence，
        // 否则 proto3 的默认值 0 / 0.0 / false 会被当成「算法真的这么说了」。
        this.classificationProbability = resp.hasClassificationProbability()
                ? resp.getClassificationProbability() : null;
        this.calibrationVersion = blankToNull(resp.getCalibrationVersion());
        this.imageQualityState = imageQualityStateOf(resp.getImageQualityState());
        this.imageQualityVersion = blankToNull(resp.getImageQualityVersion());
        this.imageQualityCalibrationState = blankToNull(resp.getImageQualityCalibrationState());
        this.patientConclusionAllowed = resp.hasPatientConclusionAllowed()
                ? resp.getPatientConclusionAllowed() : null;
    }

    /** 空串与「没给」在库里都该是 NULL，免得出现「版本号是空字符串」这种半真半假的值。 */
    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    /**
     * proto 的质量门枚举 → 对外契约值。
     *
     * <p>对外只说 {@code PASSED / FAILED / NOT_EVALUATED} 三个词，不透出 proto 的
     * {@code QUALITY_} 前缀：这一列要进数据库、进 API、进前端，名字得是产品语言，
     * 而不是某版 IDL 的实现细节。UNSPECIFIED 表示「算法没给」，落 null。
     */
    static String imageQualityStateOf(DiagnosisProto.ImageQualityState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case QUALITY_PASSED -> "PASSED";
            case QUALITY_FAILED -> "FAILED";
            case QUALITY_NOT_EVALUATED -> "NOT_EVALUATED";
            default -> null;
        };
    }

    /**
     * 仅从响应构造快照（无部署配置可用时，例如纯函数式的结果 VO 映射）。
     */
    public static DiagnosisEvidence fromResponse(DiagnosisProto.DiagnosisResponse resp) {
        return from(resp, "", "", "", "");
    }

    /**
     * 从推理响应构造证据快照。配置值只在响应缺字段时兜底，
     * 保证缓存键与落库证据使用同一套版本指纹。
     */
    public static DiagnosisEvidence from(DiagnosisProto.DiagnosisResponse resp,
                                         String configuredModelVersion,
                                         String configuredWeightSha256,
                                         String configuredPreprocessVersion,
                                         String configuredPolicyVersion) {
        return new DiagnosisEvidence(resp, configuredModelVersion, configuredWeightSha256,
                configuredPreprocessVersion, configuredPolicyVersion);
    }

    public static String normalizeWeightSha256(String weightSha256) {
        return StrUtil.isBlank(weightSha256) ? "" : weightSha256.trim().toLowerCase(Locale.ROOT);
    }

    /** 写入诊断记录：历史证据自带，不引用共享缓存。 */
    public void applyTo(Diagnosis diagnosis) {
        diagnosis.setAiRejected(rejected);
        diagnosis.setAbstainReason(abstainReason);
        diagnosis.setModelVersion(modelVersion);
        diagnosis.setWeightSha256(weightSha256);
        diagnosis.setPreprocessVersion(preprocessVersion);
        diagnosis.setInferencePolicyVersion(inferencePolicyVersion);
        diagnosis.setCalibrationStatus(calibrationStatus);
        diagnosis.setCalibratedProbability(exposedCalibratedProbability);
        diagnosis.setQualityScore(qualityScore);
        diagnosis.setQualityReasons(qualityReasons);
        diagnosis.setOodStatus(oodStatus);
        diagnosis.setOodScore(oodScore);
        diagnosis.setClassificationProbability(classificationProbability);
        diagnosis.setCalibrationVersion(calibrationVersion);
        diagnosis.setImageQualityState(imageQualityState);
        diagnosis.setImageQualityVersion(imageQualityVersion);
        diagnosis.setImageQualityCalibrationState(imageQualityCalibrationState);
        diagnosis.setPatientConclusionAllowed(patientConclusionAllowed);
        diagnosis.setHumanReviewStatus(requiresReview
                ? HumanReviewStatus.AWAITING_REVIEW
                : HumanReviewStatus.AI_ONLY);
    }

    /** 写入前端结果 VO（证据字段部分；疾病结论由调用方补齐）。 */
    public void applyTo(DiagnosisResultVO vo) {
        vo.setAiRejected(rejected);
        vo.setAbstainReason(abstainReason);
        vo.setQualityScore(qualityScore);
        vo.setQualityReasons(qualityReasons);
        vo.setModelVersion(modelVersion);
        vo.setWeightSha256(weightSha256);
        vo.setPreprocessVersion(preprocessVersion);
        vo.setInferencePolicyVersion(inferencePolicyVersion);
        vo.setCalibrationStatus(calibrationStatus);
        vo.setCalibratedProbability(exposedCalibratedProbability);
        vo.setOodStatus(oodStatus);
        vo.setOodScore(oodScore);
        vo.setClassificationProbability(classificationProbability);
        vo.setCalibrationVersion(calibrationVersion);
        vo.setImageQualityState(imageQualityState);
        vo.setImageQualityVersion(imageQualityVersion);
        vo.setImageQualityCalibrationState(imageQualityCalibrationState);
        vo.setPatientConclusionAllowed(patientConclusionAllowed);
        vo.setRequiresReview(requiresReview);
        vo.setHumanReviewStatus(requiresReview
                ? HumanReviewStatus.AWAITING_REVIEW : HumanReviewStatus.AI_ONLY);
    }

    /** 写入推理缓存行（证据字段部分；分类与序列化字段由调用方补齐）。 */
    public void applyTo(InferenceCache cache) {
        cache.setModelVersion(modelVersion);
        cache.setWeightSha256(weightSha256);
        cache.setPreprocessVersion(preprocessVersion);
        cache.setInferencePolicyVersion(inferencePolicyVersion);
        // 缓存保留原始概率，保证 toProto 精确往返；用户侧口径见类注释。
        cache.setCalibratedProbability(resp.getCalibratedProbability());
        cache.setCalibrationStatus(calibrationStatus);
        cache.setQualityScore(qualityScore);
        cache.setQualityReasons(qualityReasons);
        cache.setOodScore(oodScore);
        cache.setOodStatus(oodStatus);
        cache.setClassificationProbability(classificationProbability);
        cache.setCalibrationVersion(calibrationVersion);
        cache.setImageQualityState(imageQualityState);
        cache.setImageQualityVersion(imageQualityVersion);
        cache.setImageQualityCalibrationState(imageQualityCalibrationState);
        cache.setPatientConclusionAllowed(patientConclusionAllowed);
        cache.setRejected(rejected);
        cache.setAbstainReason(abstainReason);
        cache.setRequiresReview(requiresReview);
    }

    /** 与 V7/V9 的缓存唯一键保持一致：同一份证据只能产出一行缓存。 */
    public InferenceCache newCache(String imageHash) {
        InferenceCache cache = new InferenceCache();
        cache.setImageHash(imageHash);
        applyTo(cache);
        cache.setPredictedLabel(resp.getPredictedLabel());
        cache.setProbability(resp.getProbability());
        cache.setDescription(resp.getDescription());
        cache.setReliabilityScore(resp.getReliabilityScore());
        cache.setTopPredictions(serializeTopPredictions(resp));
        cache.setIsOod(resp.getIsOod());
        cache.setCreateTime(new Date());
        return cache;
    }

    private static String serializeTopPredictions(DiagnosisProto.DiagnosisResponse response) {
        List<Map<String, Object>> topPredictions = response.getTopPredictionsList().stream()
                .map(prediction -> Map.<String, Object>of(
                        "label", prediction.getLabel(),
                        "probability", prediction.getProbability()))
                .toList();
        return JsonUtils.toJson(topPredictions);
    }
}
