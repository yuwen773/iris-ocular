package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.grpc.DiagnosisProto;
import com.csust.eyediagnosis.model.entity.Diagnosis;
import com.csust.eyediagnosis.model.entity.InferenceCache;
import com.csust.eyediagnosis.model.vo.diagnosis.DiagnosisResultVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判读证据的语义拆分：分类概率 / 校准状态 / 图像质量 / 能否面向患者呈现，
 * 各读各的字段，不从别的字段推，也不互相冒充。
 */
class DiagnosisEvidenceTest {

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
                .build();
    }

    @Test
    void independentEvidenceFieldsReachTheResultView() {
        DiagnosisResultVO vo = new DiagnosisResultVO();
        DiagnosisEvidence.fromResponse(response()).applyTo(vo);

        assertEquals(0.82, vo.getClassificationProbability());
        assertEquals("calibration-v0", vo.getCalibrationVersion());
        assertEquals("QUALITY_PASSED", vo.getImageQualityState());
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
        assertEquals("QUALITY_PASSED", diagnosis.getImageQualityState());
        assertEquals("quality-blur-v0-uncalibrated", diagnosis.getImageQualityVersion());
        assertEquals("UNCALIBRATED", diagnosis.getImageQualityCalibrationState());
        assertTrue(diagnosis.getPatientConclusionAllowed());
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
        assertEquals("QUALITY_PASSED", cache.getImageQualityState());
        assertEquals("quality-blur-v0-uncalibrated", cache.getImageQualityVersion());
        assertEquals("UNCALIBRATED", cache.getImageQualityCalibrationState());
        assertTrue(cache.getPatientConclusionAllowed());
    }

    @Test
    void missingEvidenceFieldsStayEmptyInsteadOfBeingGuessed() {
        DiagnosisProto.DiagnosisResponse bare = DiagnosisProto.DiagnosisResponse.newBuilder()
                .setPredictedLabel("normal")
                .build();

        DiagnosisResultVO vo = new DiagnosisResultVO();
        DiagnosisEvidence.fromResponse(bare).applyTo(vo);

        assertEquals("QUALITY_UNSPECIFIED", vo.getImageQualityState());
        assertEquals("", vo.getCalibrationVersion());
        assertFalse(Boolean.TRUE.equals(vo.getPatientConclusionAllowed()));
    }
}
