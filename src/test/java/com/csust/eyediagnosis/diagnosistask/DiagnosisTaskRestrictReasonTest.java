package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.grpc.DiagnosisProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 患者任务准入：只有「四条都满足」才进待复核，其余每一种都留下可读的原因码。
 *
 * <p>OOD 未评估不是「在分布内」。这条门禁是产品规则，不因为 Python 侧还没产出
 * 版本绑定的 OOD 证据就放宽；这里钉住的是「不许放宽」以及「拒绝时说清楚是哪一条」。
 */
class DiagnosisTaskRestrictReasonTest {

    private static DiagnosisProto.DiagnosisResponse.Builder ready() {
        return DiagnosisProto.DiagnosisResponse.newBuilder()
                .setPredictedLabel("normal")
                .setOodStatus(DiagnosisProto.OodStatus.IN_DISTRIBUTION);
    }

    @Test
    void fullySatisfiedResponseIsAdmitted() {
        assertNull(DiagnosisTaskRunner.restrictReason(ready().build()));
    }

    @Test
    void oodNotEvaluatedIsRestrictedNotAdmitted() {
        DiagnosisProto.DiagnosisResponse response = ready()
                .setOodStatus(DiagnosisProto.OodStatus.NOT_EVALUATED)
                .build();
        assertEquals("OOD_NOT_EVALUATED", DiagnosisTaskRunner.restrictReason(response));
    }

    @Test
    void oodEvaluationFailureAndOutOfDistributionAreDistinguished() {
        assertEquals("OOD_EVALUATION_FAILED", DiagnosisTaskRunner.restrictReason(
                ready().setOodStatus(DiagnosisProto.OodStatus.EVALUATION_FAILED).build()));
        assertEquals("OOD_OUT_OF_DISTRIBUTION", DiagnosisTaskRunner.restrictReason(
                ready().setOodStatus(DiagnosisProto.OodStatus.OUT_OF_DISTRIBUTION).build()));
        assertEquals("OOD_OUT_OF_DISTRIBUTION", DiagnosisTaskRunner.restrictReason(
                ready().setOodStatus(DiagnosisProto.OodStatus.NOT_EVALUATED).setIsOod(true).build()));
    }

    @Test
    void abstainCarriesItsOwnReason() {
        assertEquals("ABSTAIN_QUALITY_FAIL", DiagnosisTaskRunner.restrictReason(
                ready().setRejected(true).setAbstainReason("QUALITY_FAIL").build()));
        assertEquals("ABSTAIN_UNSPECIFIED", DiagnosisTaskRunner.restrictReason(
                ready().setRejected(true).build()));
    }

    @Test
    void missingPredictionIsRestricted() {
        assertEquals("PREDICTION_MISSING", DiagnosisTaskRunner.restrictReason(
                DiagnosisProto.DiagnosisResponse.newBuilder()
                        .setOodStatus(DiagnosisProto.OodStatus.IN_DISTRIBUTION)
                        .build()));
    }
}
