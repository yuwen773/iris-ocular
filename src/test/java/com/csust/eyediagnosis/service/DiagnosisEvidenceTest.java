package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.grpc.DiagnosisProto;
import com.csust.eyediagnosis.model.entity.Diagnosis;
import com.csust.eyediagnosis.model.entity.InferenceCache;
import com.csust.eyediagnosis.model.vo.diagnosis.DiagnosisResultVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判读证据的语义拆分：分类概率 / 校准状态 / 图像质量 / 能否面向患者呈现，
 * 各读各的字段，不从别的字段推，也不互相冒充。
 */
class DiagnosisEvidenceTest {

    private static final String MODEL = "efficientnet-b4@1.0.0";
    private static final String WEIGHT = "A".repeat(64);
    private static final String PREPROCESS = "v1";
    private static final String POLICY = "medical-safety-v3";

    private static DiagnosisProto.DiagnosisResponse response() {
        return DiagnosisProto.DiagnosisResponse.newBuilder()
                .setPredictedLabel("normal")
                .setProbability(82.0)
                .setClassificationProbability(0.82)
                .setCalibrationStatus("NOT_CALIBRATED")
                .setCalibrationVersion("calibration-v0")
                .setImageQualityState(DiagnosisProto.ImageQualityState.QUALITY_PASSED)
                .setImageQualityVersion("quality-blur-v0-uncalibrated")
                .setImageQualityCalibrationState("UNCALIBRATED")
                .setPatientConclusionAllowed(true)
                .setOodStatus(DiagnosisProto.OodStatus.NOT_EVALUATED)
                .setQualityScore(25)
                .setRequiresReview(true)
                .setModelVersion(MODEL)
                .setWeightSha256(WEIGHT)
                .setPreprocessVersion(PREPROCESS)
                .setInferencePolicyVersion(POLICY)
                .build();
    }

    /**
     * 一份快照写三个目标，取值口径必须一致。
     *
     * <p>这是 {@code DiagnosisEvidence} 存在的理由：三个目标各写一遍，
     * 列宽与归一化口径就会互相漂移（权重哈希大小写、复核状态、质量分）。
     */
    @Test
    void oneSnapshotAppliesTheSameEvidenceToEveryTarget() {
        DiagnosisEvidence evidence = DiagnosisEvidence.from(response(), MODEL, WEIGHT, PREPROCESS, POLICY);

        Diagnosis diagnosis = new Diagnosis();
        DiagnosisResultVO vo = new DiagnosisResultVO();
        InferenceCache cache = evidence.newCache("hash");
        evidence.applyTo(diagnosis);
        evidence.applyTo(vo);

        // 权重哈希在三个目标都归一为小写 64 字符（V16 归一后的口径）
        assertEquals(WEIGHT.toLowerCase(), diagnosis.getWeightSha256());
        assertEquals(WEIGHT.toLowerCase(), vo.getWeightSha256());
        assertEquals(WEIGHT.toLowerCase(), cache.getWeightSha256());
        assertEquals(MODEL, diagnosis.getModelVersion());
        assertEquals(MODEL, vo.getModelVersion());
        assertEquals(MODEL, cache.getModelVersion());
        assertEquals("AWAITING_REVIEW", diagnosis.getHumanReviewStatus());
        assertEquals("AWAITING_REVIEW", vo.getHumanReviewStatus());
        assertTrue(vo.getRequiresReview());
        assertTrue(cache.getRequiresReview());
        assertEquals(25, diagnosis.getQualityScore());
        assertEquals(25, vo.getQualityScore());
        assertEquals(25, cache.getQualityScore());
    }

    @Test
    void independentEvidenceFieldsReachTheResultView() {
        DiagnosisResultVO vo = new DiagnosisResultVO();
        DiagnosisEvidence.fromResponse(response()).applyTo(vo);

        assertEquals(0.82, vo.getClassificationProbability());
        assertEquals("calibration-v0", vo.getCalibrationVersion());
        // 对外只说产品语言，不透出 proto 的 QUALITY_ 前缀
        assertEquals("PASSED", vo.getImageQualityState());
        assertEquals("quality-blur-v0-uncalibrated", vo.getImageQualityVersion());
        assertEquals("UNCALIBRATED", vo.getImageQualityCalibrationState());
        assertTrue(vo.getPatientConclusionAllowed());
    }

    @Test
    void independentEvidenceFieldsReachTheStoredRecord() {
        Diagnosis diagnosis = new Diagnosis();
        DiagnosisEvidence.fromResponse(response()).applyTo(diagnosis);

        assertEquals(0.82, diagnosis.getClassificationProbability());
        assertEquals("calibration-v0", diagnosis.getCalibrationVersion());
        assertEquals("PASSED", diagnosis.getImageQualityState());
        assertEquals("quality-blur-v0-uncalibrated", diagnosis.getImageQualityVersion());
        assertEquals("UNCALIBRATED", diagnosis.getImageQualityCalibrationState());
        assertTrue(diagnosis.getPatientConclusionAllowed());
    }

    /**
     * 落库的 confidence 与分析工作台的口径一致：写的是「模型自评概率百分数」。
     *
     * <p>分析页 B-03/B-04 只读这一列；不写就是面板永远空着。同时它**不是**校准置信度：
     * 未校准时 VO 的 confidence 仍为 null，界面据此标注「模型输出概率（未标定）」。
     * 两个口径分开，不能为了填满面板把 VO 也填上。
     */
    @Test
    void storedRecordKeepsTheModelSelfAssessmentWhileTheViewStaysHonest() {
        Diagnosis diagnosis = new Diagnosis();
        DiagnosisResultVO vo = new DiagnosisResultVO();
        DiagnosisEvidence evidence = DiagnosisEvidence.fromResponse(response());
        evidence.applyTo(vo);
        evidence.applyTo(diagnosis);

        assertEquals(82.0, diagnosis.getConfidence(), "落库口径：模型自评概率百分数（top1 × 100）");
        assertNull(vo.getConfidence(), "未标定时不向界面透出置信度");
    }

    /**
     * 读回路径与写入路径同一口径：库里存自评概率供分析，接口未标定就不叫置信度。
     *
     * <p>界面（RecordsPage / ProfilePage / 管理端）读的就是 {@code objToVo} 的结果；
     * 这里不挡，同一张图在记录页被写「置信度」、在 RecordSheet 被写「模型输出概率」。
     */
    @Test
    void readBackHidesSelfAssessmentUnlessCalibrated() {
        Diagnosis uncalibrated = new Diagnosis();
        uncalibrated.setCalibrationStatus("NOT_CALIBRATED");
        uncalibrated.setConfidence(82.0);
        uncalibrated.setCalibratedProbability(null);
        assertNull(DiagnosisResultVO.objToVo(uncalibrated).getConfidence());

        Diagnosis calibrated = new Diagnosis();
        calibrated.setCalibrationStatus("CALIBRATED");
        calibrated.setConfidence(82.0);
        calibrated.setCalibratedProbability(91.0);
        assertEquals(91.0, DiagnosisResultVO.objToVo(calibrated).getConfidence(),
                "已校准：读回的是校准后概率，不是原始 softmax");
    }

    @Test
    void uncalibratedProbabilityIsNotExposedAsConfidence() {
        // 未标定时不给出「置信度」：那等于让界面把原始 softmax 读成临床可靠性。
        DiagnosisResultVO vo = new DiagnosisResultVO();
        DiagnosisEvidence.fromResponse(response()).applyTo(vo);
        assertNull(vo.getCalibratedProbability(), "未标定时不得透出校准概率");
    }

    @Test
    void cacheCarriesTheSameEvidenceSoCacheHitsDoNotLoseIt() {
        InferenceCache cache = new InferenceCache();
        DiagnosisEvidence.fromResponse(response()).applyTo(cache);

        assertEquals(0.82, cache.getClassificationProbability());
        assertEquals("calibration-v0", cache.getCalibrationVersion());
        assertEquals("PASSED", cache.getImageQualityState());
        assertEquals("quality-blur-v0-uncalibrated", cache.getImageQualityVersion());
        assertEquals("UNCALIBRATED", cache.getImageQualityCalibrationState());
        assertTrue(cache.getPatientConclusionAllowed());
    }

    /**
     * 算法没给的证据必须留空：空串会变成「版本号是空字符串」这种半真半假的值，
     * 0.0 会被读成「模型输出 0%」，false 会被读成「明确不允许面向患者」。
     */
    @Test
    void missingEvidenceFieldsStayEmptyInsteadOfBeingGuessed() {
        DiagnosisProto.DiagnosisResponse bare = DiagnosisProto.DiagnosisResponse.newBuilder()
                .setPredictedLabel("normal")
                .build();

        DiagnosisResultVO vo = new DiagnosisResultVO();
        InferenceCache cache = new InferenceCache();
        DiagnosisEvidence evidence = DiagnosisEvidence.fromResponse(bare);
        evidence.applyTo(vo);
        evidence.applyTo(cache);

        assertNull(vo.getClassificationProbability());
        assertNull(vo.getCalibrationVersion());
        assertNull(vo.getImageQualityState());
        assertNull(vo.getImageQualityVersion());
        assertNull(vo.getImageQualityCalibrationState());
        assertNull(vo.getPatientConclusionAllowed());
        assertNull(cache.getClassificationProbability());
        assertNull(cache.getPatientConclusionAllowed());
    }

    @Test
    void qualityStateUsesTheProductVocabularyNotTheProtoEnumName() {
        assertEquals("PASSED", DiagnosisEvidence.imageQualityStateOf(
                DiagnosisProto.ImageQualityState.QUALITY_PASSED));
        assertEquals("FAILED", DiagnosisEvidence.imageQualityStateOf(
                DiagnosisProto.ImageQualityState.QUALITY_FAILED));
        assertEquals("NOT_EVALUATED", DiagnosisEvidence.imageQualityStateOf(
                DiagnosisProto.ImageQualityState.QUALITY_NOT_EVALUATED));
        assertNull(DiagnosisEvidence.imageQualityStateOf(
                DiagnosisProto.ImageQualityState.QUALITY_UNSPECIFIED));
    }
}
