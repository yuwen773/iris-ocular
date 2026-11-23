package com.csust.eyediagnosis.service.impl;

import com.csust.eyediagnosis.mapper.AdminAnalyticsMapper;
import com.csust.eyediagnosis.model.vo.stats.AdminAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.ConfidenceSummaryVO;
import com.csust.eyediagnosis.model.vo.stats.DataQualityVO;
import com.csust.eyediagnosis.model.vo.stats.HistogramBucketVO;
import com.csust.eyediagnosis.model.vo.stats.LatencyStatVO;
import com.csust.eyediagnosis.model.vo.stats.ModelAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.ModelVersionStatVO;
import com.csust.eyediagnosis.model.vo.stats.QualityScoreStatVO;
import com.csust.eyediagnosis.model.vo.stats.ReasonStatVO;
import com.csust.eyediagnosis.model.vo.stats.RejectionAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.ReviewAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.ReviewerStatVO;
import com.csust.eyediagnosis.model.vo.stats.StatusStatVO;
import com.csust.eyediagnosis.service.AdminAnalyticsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据分析读数。
 *
 * <p>实现纪律（与 {@code docs/design/iris22-admin-metric-registry.md} 对应）：
 * <ol>
 *   <li><b>比率为 null 表示不适用</b>：分母为 0 时绝不返回 0%，避免「没有数据」被读成「占比为零」；</li>
 *   <li><b>未知值不丢弃</b>：契约外枚举归入 UNCLASSIFIED，空值归入 UNRECORDED，
 *       并单独计数进入数据质量；</li>
 *   <li><b>置信度量纲不猜</b>：只有 1 &lt; confidence ≤ 100 的样本参与聚合，
 *       (0,1] 的样本计入量纲未知，不自动乘 100；</li>
 *   <li><b>复核维度是历史数据</b>：正式复核写入已安全关闭，dataStatus 恒为 HISTORICAL_ONLY。</li>
 * </ol>
 */
@Service
public class AdminAnalyticsServiceImpl implements AdminAnalyticsService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 365;

    /** 拒识原因契约。 */
    private static final Set<String> KNOWN_ABSTAIN_REASONS =
            Set.of("QUALITY_FAIL", "OOD", "LOW_CONFIDENCE", "NOT_ASSESSED", "MODEL_NOT_LOADED");

    /** OOD 状态契约。 */
    private static final Set<String> KNOWN_OOD_STATUSES =
            Set.of("NOT_EVALUATED", "IN_DISTRIBUTION", "OUT_OF_DISTRIBUTION", "EVALUATION_FAILED");

    /** 校准状态契约。 */
    private static final Set<String> KNOWN_CALIBRATION_STATUSES =
            Set.of("CALIBRATED", "NOT_CALIBRATED");

    /** 质量原因标签契约（Python 侧逗号分隔输出）。 */
    private static final Set<String> KNOWN_QUALITY_REASONS =
            Set.of("blur", "brightness", "contrast", "fundus_coverage");

    /** 质量分契约值（Python 侧 100 - 25 × 原因数），按从好到差展示。 */
    private static final int[] QUALITY_SCORES = {100, 75, 50, 25, 0};

    private static final Set<Integer> KNOWN_QUALITY_SCORES = Set.of(100, 75, 50, 25, 0);

    private static final String UNCLASSIFIED = "UNCLASSIFIED";
    private static final String UNRECORDED = "UNRECORDED";

    @Resource
    private AdminAnalyticsMapper adminAnalyticsMapper;

    @Override
    public AdminAnalyticsVO analytics(int days) {
        int window = Math.min(Math.max(days <= 0 ? DEFAULT_DAYS : days, MIN_DAYS), MAX_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate startDay = today.minusDays(window - 1L);
        // 右开区间：包含今天的全天。
        Date from = Date.from(startDay.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date to = Date.from(today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());

        AdminAnalyticsVO vo = new AdminAnalyticsVO();
        vo.setDays(window);
        vo.setRangeStart(startDay.toString());
        vo.setRangeEnd(today.toString());

        ModelAnalyticsVO model = buildModel(from, to);
        vo.setReview(buildReview(from, to));
        vo.setRejection(buildRejection(from, to));
        vo.setModel(model);
        vo.setDataQuality(buildDataQuality(from, to, model));
        return vo;
    }

    /* ── 复核 ── */

    private ReviewAnalyticsVO buildReview(Date from, Date to) {
        ReviewAnalyticsVO vo = new ReviewAnalyticsVO();
        vo.setAwaitingTotal(adminAnalyticsMapper.countAwaitingReviewTotal());

        long queued = adminAnalyticsMapper.countQueuedCreatedBetween(from, to);
        long completed = adminAnalyticsMapper.countCompletedCreatedBetween(from, to);
        vo.setQueuedInWindow(queued);
        vo.setCompletedInWindow(completed);
        vo.setCompletionRate(rate(completed, queued));

        long reviewed = adminAnalyticsMapper.countReviewedByReviewTime(from, to);
        long overridden = adminAnalyticsMapper.countOverriddenByReviewTime(from, to);
        vo.setReviewedInWindow(reviewed);
        vo.setOverriddenInWindow(overridden);
        vo.setOverrideRate(rate(overridden, reviewed));

        LatencyStatVO latency = adminAnalyticsMapper.reviewLatency(from, to);
        if (latency == null) {
            latency = new LatencyStatVO();
        }
        vo.setLatencySamples(latency.getTotal());
        vo.setAvgMinutes(minutes(latency.getAvgMinutes()));
        vo.setP50Minutes(minutes(latency.getP50Minutes()));
        vo.setP90Minutes(minutes(latency.getP90Minutes()));
        vo.setMaxMinutes(minutes(latency.getMaxMinutes()));

        List<ReviewerStatVO> reviewers = adminAnalyticsMapper.reviewerStats(from, to);
        for (ReviewerStatVO reviewer : reviewers) {
            // 名称为空时保持 null：前端显示「复核人 #id（未记录名称）」，
            // 不编一个名字，也不把 id 当姓名。
            if (reviewer.getReviewerName() != null && reviewer.getReviewerName().isBlank()) {
                reviewer.setReviewerName(null);
            }
            reviewer.setAvgMinutes(minutes(reviewer.getAvgMinutes()));
        }
        vo.setReviewers(new ArrayList<>(reviewers));

        // 分母用队列总数（与 A-07 同一个过滤条件），不是「有原因的那几条」
        ReasonClassification queue = classifyReasons(
                adminAnalyticsMapper.queueReasons(from, to), queued);
        vo.setQueueReasons(queue.rows());
        vo.setUnclassifiedReasonCount(queue.unclassified());
        vo.setUnknownReasonCount(queue.unknown());
        return vo;
    }

    /* ── 拒识 ── */

    private RejectionAnalyticsVO buildRejection(Date from, Date to) {
        RejectionAnalyticsVO vo = new RejectionAnalyticsVO();
        long total = adminAnalyticsMapper.countDiagnosesBetween(from, to);
        long rejected = adminAnalyticsMapper.countRejectedBetween(from, to);
        vo.setTotal(total);
        vo.setRejected(rejected);
        vo.setRate(rate(rejected, total));

        ReasonClassification reasons = classifyReasons(
                adminAnalyticsMapper.rejectionReasons(from, to), rejected);
        vo.setReasons(reasons.rows());
        vo.setUnclassifiedReasonCount(reasons.unclassified());
        vo.setUnknownReasonCount(reasons.unknown());
        return vo;
    }

    /* ── 模型与质量 ── */

    private ModelAnalyticsVO buildModel(Date from, Date to) {
        ModelAnalyticsVO vo = new ModelAnalyticsVO();

        List<ModelVersionStatVO> versions = adminAnalyticsMapper.modelVersionStats(from, to);
        for (ModelVersionStatVO version : versions) {
            if (version.getModelVersion() == null || version.getModelVersion().isBlank()) {
                version.setModelVersion(UNRECORDED);
            }
            version.setAvgConfidence(round(version.getAvgConfidence(), 4));
            version.setRejectionRate(rate(version.getRejected(), version.getCount()));
        }
        vo.setVersions(new ArrayList<>(versions));

        ConfidenceSummaryVO confidence = adminAnalyticsMapper.confidenceSummary(from, to);
        if (confidence == null) {
            confidence = new ConfidenceSummaryVO();
        }
        confidence.setAvg(round(confidence.getAvg(), 4));
        vo.setConfidence(confidence);

        vo.setConfidenceHistogram(buildHistogram(
                adminAnalyticsMapper.confidenceHistogram(from, to)));

        fillQuality(vo, adminAnalyticsMapper.qualityScoreCounts(from, to));
        fillQualityReasons(vo, adminAnalyticsMapper.qualityReasonRows(from, to));

        vo.setOodStatuses(normalizeStatuses(adminAnalyticsMapper.oodStatusCounts(from, to)));
        vo.setCalibrationStatuses(
                normalizeStatuses(adminAnalyticsMapper.calibrationStatusCounts(from, to)));
        return vo;
    }

    /** 置信度直方图固定 10 桶：空桶补 0，柱图横轴才连续。 */
    private List<HistogramBucketVO> buildHistogram(List<HistogramBucketVO> rows) {
        Map<String, Long> byBucket = new HashMap<>();
        for (HistogramBucketVO row : rows) {
            byBucket.merge(row.getBucket(), row.getCount(), Long::sum);
        }
        List<HistogramBucketVO> buckets = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            String label = i == 9 ? "90–100" : (i * 10) + "–" + (i * 10 + 10);
            buckets.add(new HistogramBucketVO(label, byBucket.getOrDefault(label, 0L)));
        }
        return buckets;
    }

    /** 质量分：固定 5 个契约桶（缺的补 0），越界与空值单独计数。 */
    private void fillQuality(ModelAnalyticsVO vo, List<QualityScoreStatVO> rows) {
        Map<Integer, Long> byScore = new HashMap<>();
        long unknown = 0;
        long outOfContract = 0;
        for (QualityScoreStatVO row : rows) {
            Integer score = row.getScore();
            if (score == null) {
                unknown += row.getCount();
            } else if (KNOWN_QUALITY_SCORES.contains(score)) {
                byScore.merge(score, row.getCount(), Long::sum);
            } else {
                outOfContract += row.getCount();
            }
        }
        List<QualityScoreStatVO> buckets = new ArrayList<>(QUALITY_SCORES.length);
        for (int score : QUALITY_SCORES) {
            buckets.add(new QualityScoreStatVO(score, byScore.getOrDefault(score, 0L)));
        }
        vo.setQualityScores(buckets);
        vo.setQualityUnknown(unknown);
        vo.setQualityOutOfContract(outOfContract);
    }

    /** 质量原因是多标签：按逗号拆分，一行可计多个标签。 */
    private void fillQualityReasons(ModelAnalyticsVO vo, List<String> rawRows) {
        Map<String, Long> counts = new HashMap<>();
        long unclassified = 0;
        for (String raw : rawRows) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String tag = part.trim();
                if (tag.isEmpty()) {
                    continue;
                }
                if (KNOWN_QUALITY_REASONS.contains(tag)) {
                    counts.merge(tag, 1L, Long::sum);
                } else {
                    unclassified++;
                }
            }
        }
        List<ReasonStatVO> rows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            rows.add(new ReasonStatVO(entry.getKey(), entry.getValue()));
        }
        rows.sort(Comparator.comparingLong(ReasonStatVO::getCount).reversed()
                .thenComparing(ReasonStatVO::getReason));
        vo.setQualityReasons(rows);
        vo.setUnclassifiedQualityReasonCount(unclassified);
    }

    /* ── 数据质量 ── */

    private DataQualityVO buildDataQuality(Date from, Date to, ModelAnalyticsVO model) {
        DataQualityVO vo = new DataQualityVO();
        vo.setUnknownReviewStatuses(new ArrayList<>(
                adminAnalyticsMapper.unknownReviewStatusCounts(from, to)));

        List<StatusStatVO> unknownOod = new ArrayList<>();
        for (StatusStatVO row : model.getOodStatuses()) {
            if (!UNRECORDED.equals(row.getStatus()) && !KNOWN_OOD_STATUSES.contains(row.getStatus())) {
                unknownOod.add(row);
            }
        }
        vo.setUnknownOodStatuses(unknownOod);

        List<StatusStatVO> unknownCalibration = new ArrayList<>();
        for (StatusStatVO row : model.getCalibrationStatuses()) {
            if (!UNRECORDED.equals(row.getStatus())
                    && !KNOWN_CALIBRATION_STATUSES.contains(row.getStatus())) {
                unknownCalibration.add(row);
            }
        }
        vo.setUnknownCalibrationStatuses(unknownCalibration);

        vo.setQualityOutOfContract(model.getQualityOutOfContract());
        vo.setAmbiguousConfidence(model.getConfidence().getAmbiguous());
        vo.setConfidenceOutOfRange(model.getConfidence().getOutOfRange());
        vo.setConfidenceMissing(model.getConfidence().getMissing());
        vo.setReviewedWithoutTime(adminAnalyticsMapper.countReviewedWithoutTime(from, to));
        return vo;
    }

    /* ── 归一工具 ── */

    /**
     * 原因归组：契约内的原因原样保留，契约外合并成 UNCLASSIFIED，空值合并成
     * UNRECORDED；三者都出现在返回列表里，并各自计数。
     *
     * @param denominator 权威分母（拒识数 / 入队数）；传 0 时退化为返回行合计
     */
    private ReasonClassification classifyReasons(List<ReasonStatVO> rows, long denominator) {
        long unclassified = 0;
        long unknown = 0;
        List<ReasonStatVO> knownRows = new ArrayList<>();
        for (ReasonStatVO row : rows) {
            String raw = row.getReason();
            if (raw == null || raw.isBlank()) {
                unknown += row.getCount();
            } else if (KNOWN_ABSTAIN_REASONS.contains(raw.trim())) {
                knownRows.add(new ReasonStatVO(raw.trim(), row.getCount()));
            } else {
                unclassified += row.getCount();
            }
        }
        knownRows.sort(Comparator.comparingLong(ReasonStatVO::getCount).reversed()
                .thenComparing(ReasonStatVO::getReason));
        if (unclassified > 0) {
            knownRows.add(new ReasonStatVO(UNCLASSIFIED, unclassified));
        }
        if (unknown > 0) {
            knownRows.add(new ReasonStatVO(UNRECORDED, unknown));
        }

        long knownTotal = knownRows.stream().mapToLong(ReasonStatVO::getCount).sum();
        long effectiveDenominator = denominator > 0 ? denominator : knownTotal;
        for (ReasonStatVO row : knownRows) {
            row.setShare(rate(row.getCount(), effectiveDenominator));
        }
        return new ReasonClassification(knownRows, unclassified, unknown);
    }

    /** 状态分布：空值单独归 UNRECORDED；契约外原值保留，由数据质量区再计数。 */
    private List<StatusStatVO> normalizeStatuses(List<StatusStatVO> rows) {
        List<StatusStatVO> out = new ArrayList<>();
        long unrecorded = 0;
        for (StatusStatVO row : rows) {
            if (row.getStatus() == null || row.getStatus().isBlank()) {
                unrecorded += row.getCount();
            } else {
                out.add(row);
            }
        }
        if (unrecorded > 0) {
            out.add(new StatusStatVO(UNRECORDED, unrecorded));
        }
        return out;
    }

    /** 分母为 0 时返回 null（「不适用」），绝不返回 0。 */
    private Double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return round((double) numerator / denominator, 4);
    }

    private Double minutes(Double value) {
        return round(value, 1);
    }

    private Double round(Double value, int scale) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private record ReasonClassification(List<ReasonStatVO> rows, long unclassified, long unknown) {
    }
}
