package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.mapper.AdminAnalyticsMapper;
import com.csust.eyediagnosis.model.vo.stats.AdminAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.ConfidenceSummaryVO;
import com.csust.eyediagnosis.model.vo.stats.HistogramBucketVO;
import com.csust.eyediagnosis.model.vo.stats.LatencyStatVO;
import com.csust.eyediagnosis.model.vo.stats.ModelVersionStatVO;
import com.csust.eyediagnosis.model.vo.stats.QualityScoreStatVO;
import com.csust.eyediagnosis.model.vo.stats.ReasonStatVO;
import com.csust.eyediagnosis.model.vo.stats.ReviewerStatVO;
import com.csust.eyediagnosis.model.vo.stats.StatusStatVO;
import com.csust.eyediagnosis.service.impl.AdminAnalyticsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据分析读数的口径。
 *
 * <p>这里守的是三条硬规矩（对应 B1 的四条边界）：
 * <ol>
 *   <li>分母为 0 → 比率为 null，不是 0；</li>
 *   <li>契约外与空值都要计数，不能静默丢弃；</li>
 *   <li>置信度只有 {@code 1 < c <= 100} 参与聚合，(0,1] 不自动乘 100。</li>
 * </ol>
 */
class AdminAnalyticsServiceTest {

    private AdminAnalyticsMapper mapper;
    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminAnalyticsMapper.class);
        // MyBatis 实际返回空列表而不是 null，测试也按空列表打底，
        // 免得失败原因是 NPE 而不是断言。
        when(mapper.reviewerStats(any(), any())).thenReturn(List.of());
        when(mapper.queueReasons(any(), any())).thenReturn(List.of());
        when(mapper.rejectionReasons(any(), any())).thenReturn(List.of());
        when(mapper.modelVersionStats(any(), any())).thenReturn(List.of());
        when(mapper.confidenceHistogram(any(), any())).thenReturn(List.of());
        when(mapper.qualityScoreCounts(any(), any())).thenReturn(List.of());
        when(mapper.qualityReasonRows(any(), any())).thenReturn(List.of());
        when(mapper.oodStatusCounts(any(), any())).thenReturn(List.of());
        when(mapper.calibrationStatusCounts(any(), any())).thenReturn(List.of());
        when(mapper.unknownReviewStatusCounts(any(), any())).thenReturn(List.of());

        service = new AdminAnalyticsServiceImpl();
        ReflectionTestUtils.setField(service, "adminAnalyticsMapper", mapper);
    }

    @Test
    void 时间窗收敛到七到三百六十五天并带出日期范围() {
        AdminAnalyticsVO one = service.analytics(1);
        assertEquals(7, one.getDays());
        assertEquals(LocalDate.now().minusDays(6).toString(), one.getRangeStart());
        assertEquals(LocalDate.now().toString(), one.getRangeEnd());

        // 0 / 负数不是「零天窗口」，落到默认 30 天
        assertEquals(30, service.analytics(0).getDays());
        assertEquals(365, service.analytics(9999).getDays());
    }

    @Test
    void 复核维度固定标记为历史数据() {
        assertEquals("HISTORICAL_ONLY", service.analytics(30).getReview().getDataStatus());
    }

    @Test
    void 分母为零时比率为空而不是零() {
        AdminAnalyticsVO vo = service.analytics(30);

        assertNull(vo.getReview().getCompletionRate());
        assertNull(vo.getReview().getOverrideRate());
        assertNull(vo.getRejection().getRate());
        // 空窗口下分布也是空，不应报错
        assertEquals(10, vo.getModel().getConfidenceHistogram().size());
        assertEquals(5, vo.getModel().getQualityScores().size());
    }

    @Test
    void 拒识率与完成率按分母计算() {
        when(mapper.countDiagnosesBetween(any(), any())).thenReturn(200L);
        when(mapper.countRejectedBetween(any(), any())).thenReturn(50L);
        when(mapper.countQueuedCreatedBetween(any(), any())).thenReturn(40L);
        when(mapper.countCompletedCreatedBetween(any(), any())).thenReturn(30L);
        when(mapper.countReviewedByReviewTime(any(), any())).thenReturn(10L);
        when(mapper.countOverriddenByReviewTime(any(), any())).thenReturn(3L);

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(0.25, vo.getRejection().getRate());
        assertEquals(0.75, vo.getReview().getCompletionRate());
        assertEquals(0.3, vo.getReview().getOverrideRate());
    }

    @Test
    void 契约外原因与空原因分别计数且不丢弃() {
        when(mapper.countRejectedBetween(any(), any())).thenReturn(10L);
        when(mapper.rejectionReasons(any(), any())).thenReturn(List.of(
                new ReasonStatVO("QUALITY_FAIL", 5),
                new ReasonStatVO("ok", 2),
                new ReasonStatVO(null, 1),
                new ReasonStatVO("中文原因", 2)));

        AdminAnalyticsVO vo = service.analytics(30);

        // "ok" 与中文自由文本都不在契约内，合并为 UNCLASSIFIED（2 + 2）
        assertEquals(4, vo.getRejection().getUnclassifiedReasonCount());
        assertEquals(1, vo.getRejection().getUnknownReasonCount());
        // 已知 5 + 未归类 4 + 未记录 1 = 拒识总数 10，一条都不能丢
        assertEquals(10, sumCounts(vo.getRejection().getReasons()));

        ReasonStatVO known = find(vo.getRejection().getReasons(), "QUALITY_FAIL");
        assertEquals(5, known.getCount());
        assertEquals(0.5, known.getShare());
        assertEquals(4, find(vo.getRejection().getReasons(), "UNCLASSIFIED").getCount());
        assertEquals(1, find(vo.getRejection().getReasons(), "UNRECORDED").getCount());
    }

    @Test
    void 触发复核原因与拒识原因使用不同过滤集合() {
        // A-12 的集合是「进了复核队列」；LOW_CONFIDENCE 会出现在这里，
        // 但因为 aiRejected=false 不会出现在拒识统计里。
        when(mapper.countQueuedCreatedBetween(any(), any())).thenReturn(10L);
        when(mapper.queueReasons(any(), any())).thenReturn(List.of(
                new ReasonStatVO("LOW_CONFIDENCE", 6),
                new ReasonStatVO("QUALITY_FAIL", 3),
                new ReasonStatVO(null, 1)));

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(1, vo.getReview().getUnknownReasonCount());
        assertEquals(0, vo.getReview().getUnclassifiedReasonCount());
        assertEquals(6, find(vo.getReview().getQueueReasons(), "LOW_CONFIDENCE").getCount());
        // 队列原因的分母是队列总数（10），不是已知原因的合计
        assertEquals(0.6, find(vo.getReview().getQueueReasons(), "LOW_CONFIDENCE").getShare());
    }

    @Test
    void 置信度量纲未知不参与聚合也不乘一百() {
        ConfidenceSummaryVO summary = new ConfidenceSummaryVO();
        summary.setSamples(8);
        summary.setAvg(83.45678);
        summary.setAmbiguous(3);
        summary.setOutOfRange(1);
        summary.setMissing(2);
        when(mapper.confidenceSummary(any(), any())).thenReturn(summary);

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(8, vo.getModel().getConfidence().getSamples());
        assertEquals(83.4568, vo.getModel().getConfidence().getAvg());
        assertEquals(3, vo.getDataQuality().getAmbiguousConfidence());
        assertEquals(1, vo.getDataQuality().getConfidenceOutOfRange());
        assertEquals(2, vo.getDataQuality().getConfidenceMissing());
    }

    @Test
    void 置信度直方图固定十桶并补齐空桶() {
        when(mapper.confidenceHistogram(any(), any())).thenReturn(List.of(
                new HistogramBucketVO("0–10", 1),
                new HistogramBucketVO("90–100", 4)));

        List<HistogramBucketVO> buckets = service.analytics(30).getModel().getConfidenceHistogram();

        assertEquals(10, buckets.size());
        assertEquals("0–10", buckets.get(0).getBucket());
        assertEquals(1, buckets.get(0).getCount());
        assertEquals("50–60", buckets.get(5).getBucket());
        assertEquals(0, buckets.get(5).getCount());
        assertEquals("90–100", buckets.get(9).getBucket());
        assertEquals(4, buckets.get(9).getCount());
    }

    @Test
    void 质量分按契约值分档越界与空值单独计数() {
        when(mapper.qualityScoreCounts(any(), any())).thenReturn(List.of(
                new QualityScoreStatVO(100, 5),
                new QualityScoreStatVO(80, 2),
                new QualityScoreStatVO(null, 3)));

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(5, vo.getModel().getQualityScores().size());
        assertEquals(100, vo.getModel().getQualityScores().get(0).getScore());
        assertEquals(5, vo.getModel().getQualityScores().get(0).getCount());
        assertEquals(0, vo.getModel().getQualityScores().get(1).getCount());
        assertEquals(2, vo.getModel().getQualityOutOfContract());
        assertEquals(3, vo.getModel().getQualityUnknown());
        assertEquals(2, vo.getDataQuality().getQualityOutOfContract());
    }

    @Test
    void 质量原因是多标签按逗号拆分计数() {
        when(mapper.qualityReasonRows(any(), any())).thenReturn(List.of(
                "blur,contrast",
                "blur",
                "weird,blur"));

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(3, find(vo.getModel().getQualityReasons(), "blur").getCount());
        assertEquals(1, find(vo.getModel().getQualityReasons(), "contrast").getCount());
        assertEquals(1, vo.getModel().getUnclassifiedQualityReasonCount());
    }

    @Test
    void 空状态归入未记录契约外状态原样保留并进入数据质量() {
        when(mapper.oodStatusCounts(any(), any())).thenReturn(List.of(
                new StatusStatVO("NOT_EVALUATED", 7),
                new StatusStatVO("WHATEVER", 2),
                new StatusStatVO(null, 1)));
        when(mapper.calibrationStatusCounts(any(), any())).thenReturn(List.of(
                new StatusStatVO("CALIBRATED", 6),
                new StatusStatVO(null, 4)));
        when(mapper.unknownReviewStatusCounts(any(), any())).thenReturn(List.of(
                new StatusStatVO("APPROVED", 3)));

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(1, findStatus(vo.getModel().getOodStatuses(), "UNRECORDED").getCount());
        assertEquals(2, findStatus(vo.getModel().getOodStatuses(), "WHATEVER").getCount());
        assertEquals(4, findStatus(vo.getModel().getCalibrationStatuses(), "UNRECORDED").getCount());
        assertEquals(1, vo.getDataQuality().getUnknownOodStatuses().size());
        assertEquals("WHATEVER", vo.getDataQuality().getUnknownOodStatuses().get(0).getStatus());
        assertEquals(0, vo.getDataQuality().getUnknownCalibrationStatuses().size());
        assertEquals(3, vo.getDataQuality().getUnknownReviewStatuses().get(0).getCount());
    }

    @Test
    void 复核人名称缺失保持空值且时延四舍五入() {
        ReviewerStatVO reviewer = new ReviewerStatVO();
        reviewer.setReviewerId(12L);
        reviewer.setReviewerName("  ");
        reviewer.setReviewed(40);
        reviewer.setOverridden(3);
        reviewer.setAvgMinutes(620.456);
        when(mapper.reviewerStats(any(), any())).thenReturn(List.of(reviewer));

        AdminAnalyticsVO vo = service.analytics(30);

        assertNull(vo.getReview().getReviewers().get(0).getReviewerName());
        assertEquals(620.5, vo.getReview().getReviewers().get(0).getAvgMinutes());
    }

    @Test
    void 时延为空时所有分位保持空值() {
        when(mapper.reviewLatency(any(), any())).thenReturn(null);

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(0, vo.getReview().getLatencySamples());
        assertNull(vo.getReview().getAvgMinutes());
        assertNull(vo.getReview().getP50Minutes());
        assertNull(vo.getReview().getP90Minutes());
        assertNull(vo.getReview().getMaxMinutes());
    }

    @Test
    void 时延有样本时带出分位与样本数() {
        LatencyStatVO latency = new LatencyStatVO();
        latency.setTotal(25);
        latency.setAvgMinutes(100.04);
        latency.setP50Minutes(80.06);
        latency.setP90Minutes(240.0);
        latency.setMaxMinutes(600.0);
        when(mapper.reviewLatency(any(), any())).thenReturn(latency);

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals(25, vo.getReview().getLatencySamples());
        assertEquals(100.0, vo.getReview().getAvgMinutes());
        assertEquals(80.1, vo.getReview().getP50Minutes());
        assertEquals(240.0, vo.getReview().getP90Minutes());
        assertEquals(600.0, vo.getReview().getMaxMinutes());
    }

    @Test
    void 模型版本缺失归入未记录() {
        ModelVersionStatVO version = new ModelVersionStatVO();
        version.setModelVersion(null);
        version.setCount(4);
        version.setRejected(1);
        when(mapper.modelVersionStats(any(), any())).thenReturn(List.of(version));

        AdminAnalyticsVO vo = service.analytics(30);

        assertEquals("UNRECORDED", vo.getModel().getVersions().get(0).getModelVersion());
        assertEquals(0.25, vo.getModel().getVersions().get(0).getRejectionRate());
    }

    @Test
    void 已复核但缺复核时间的记录单独计数() {
        when(mapper.countReviewedWithoutTime(any(), any())).thenReturn(3L);

        assertEquals(3, service.analytics(30).getDataQuality().getReviewedWithoutTime());
    }

    private static ReasonStatVO find(List<ReasonStatVO> rows, String reason) {
        return rows.stream()
                .filter(row -> reason.equals(row.getReason()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少原因行：" + reason));
    }

    private static StatusStatVO findStatus(List<StatusStatVO> rows, String status) {
        return rows.stream()
                .filter(row -> status.equals(row.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少状态行：" + status));
    }

    private static long sumCounts(List<ReasonStatVO> rows) {
        return rows.stream().mapToLong(ReasonStatVO::getCount).sum();
    }
}
