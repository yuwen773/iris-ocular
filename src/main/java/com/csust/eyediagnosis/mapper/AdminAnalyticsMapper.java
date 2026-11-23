package com.csust.eyediagnosis.mapper;

import com.csust.eyediagnosis.model.vo.stats.ConfidenceSummaryVO;
import com.csust.eyediagnosis.model.vo.stats.HistogramBucketVO;
import com.csust.eyediagnosis.model.vo.stats.LatencyStatVO;
import com.csust.eyediagnosis.model.vo.stats.ModelVersionStatVO;
import com.csust.eyediagnosis.model.vo.stats.QualityScoreStatVO;
import com.csust.eyediagnosis.model.vo.stats.ReasonStatVO;
import com.csust.eyediagnosis.model.vo.stats.ReviewerStatVO;
import com.csust.eyediagnosis.model.vo.stats.StatusStatVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 数据分析的聚合查询。
 *
 * <p>口径以 {@code docs/design/iris22-admin-metric-registry.md} 为准。实现约束：
 * <ul>
 *   <li>所有查询显式带 {@code isDelete = 0}；</li>
 *   <li><b>判读</b>按 {@code createTime} 筛窗，<b>复核</b>按 {@code humanReviewedAt} 筛窗，
 *       两者不得混用；</li>
 *   <li>{@code confidence} 只有 {@code 1 < value <= 100} 的样本参与聚合，
 *       量纲未知的样本单独计数（不自动乘 100）；</li>
 *   <li>契约外的枚举值不由 SQL 丢弃，交给 Service 归入「未归类 / 未记录」。</li>
 * </ul>
 *
 * <p>列别名与 VO 字段同名（项目配置 {@code map-underscore-to-camel-case: false}）。
 * {@code Long} 在全局 JsonConfig 里被序列化成 String，前端沿用既有归一逻辑。
 */
public interface AdminAnalyticsMapper {

    /* ── 复核（按 humanReviewedAt 筛窗） ── */

    /** 待复核积压：全站快照，不限时间窗。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND humanReviewStatus = 'AWAITING_REVIEW'")
    long countAwaitingReviewTotal();

    /** 窗口内创建、当前处于复核队列（含已完成）的记录数。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND humanReviewStatus IN ('AWAITING_REVIEW','REVIEWED','OVERRIDDEN') "
            + "AND createTime >= #{from} AND createTime < #{to}")
    long countQueuedCreatedBetween(@Param("from") Date from, @Param("to") Date to);

    /** 窗口内创建、当前已完成复核的记录数。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND humanReviewStatus IN ('REVIEWED','OVERRIDDEN') "
            + "AND createTime >= #{from} AND createTime < #{to}")
    long countCompletedCreatedBetween(@Param("from") Date from, @Param("to") Date to);

    /** 窗口内完成的复核数（按复核时间）。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND humanReviewStatus IN ('REVIEWED','OVERRIDDEN') "
            + "AND humanReviewedAt >= #{from} AND humanReviewedAt < #{to}")
    long countReviewedByReviewTime(@Param("from") Date from, @Param("to") Date to);

    /** 窗口内完成的、结论被推翻的复核数。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND humanReviewStatus = 'OVERRIDDEN' "
            + "AND humanReviewedAt >= #{from} AND humanReviewedAt < #{to}")
    long countOverriddenByReviewTime(@Param("from") Date from, @Param("to") Date to);

    /**
     * 复核人工作量。
     *
     * <p>REVIEWED 与 OVERRIDDEN 是互斥状态：{@code reviewed} 已含两者，
     * {@code overridden} 是其中的子集，调用方不得把两列相加当总数。
     * 名称缺失（用户已删或为空）时返回 null，由前端兜底显示「复核人 #id」。
     */
    @Select("SELECT d.humanReviewerId AS reviewerId, u.userName AS reviewerName, "
            + "COUNT(*) AS reviewed, "
            + "SUM(CASE WHEN d.humanReviewStatus = 'OVERRIDDEN' THEN 1 ELSE 0 END) AS overridden, "
            + "AVG(TIMESTAMPDIFF(MINUTE, d.createTime, d.humanReviewedAt)) AS avgMinutes "
            + "FROM t_diagnosis d "
            + "LEFT JOIN t_user u ON u.id = d.humanReviewerId AND u.isDelete = 0 "
            + "WHERE d.isDelete = 0 "
            + "AND d.humanReviewStatus IN ('REVIEWED','OVERRIDDEN') "
            + "AND d.humanReviewedAt >= #{from} AND d.humanReviewedAt < #{to} "
            + "GROUP BY d.humanReviewerId, u.userName "
            + "ORDER BY reviewed DESC "
            + "LIMIT 50")
    List<ReviewerStatVO> reviewerStats(@Param("from") Date from, @Param("to") Date to);

    /**
     * 端到端复核时延（判读创建 → 复核完成）。
     *
     * <p>MySQL 8 没有 {@code PERCENTILE_CONT()}，用最近秩：
     * {@code rn = CEIL(total × p)}。没有「进入队列时间」字段，所以这是端到端时延，
     * 不是复核处理时长。
     */
    @Select("WITH latency AS ("
            + "  SELECT TIMESTAMPDIFF(MINUTE, createTime, humanReviewedAt) AS m, "
            + "         ROW_NUMBER() OVER (ORDER BY TIMESTAMPDIFF(MINUTE, createTime, humanReviewedAt)) AS rn, "
            + "         COUNT(*) OVER () AS total "
            + "  FROM t_diagnosis "
            + "  WHERE isDelete = 0 AND humanReviewStatus IN ('REVIEWED','OVERRIDDEN') "
            + "    AND humanReviewedAt IS NOT NULL "
            + "    AND humanReviewedAt >= #{from} AND humanReviewedAt < #{to}"
            + ") "
            + "SELECT COUNT(*) AS total, AVG(m) AS avgMinutes, MAX(m) AS maxMinutes, "
            + "       MAX(CASE WHEN rn = CEIL(total * 0.5) THEN m END) AS p50Minutes, "
            + "       MAX(CASE WHEN rn = CEIL(total * 0.9) THEN m END) AS p90Minutes "
            + "FROM latency")
    LatencyStatVO reviewLatency(@Param("from") Date from, @Param("to") Date to);

    /**
     * 触发复核原因（A-12，含 LOW_CONFIDENCE）。
     *
     * <p>分母是「进入过复核队列」的记录，用当前状态兜底（没有状态历史表）：
     * {@code AWAITING_REVIEW / REVIEWED / OVERRIDDEN}。这里<b>不再加
     * {@code abstainReason IS NOT NULL}</b>：原因为空的入队记录本身就是数据质量信号，
     * 必须作为 {@code UNRECORDED} 出现，不能静默丢弃。
     *
     * <p>与 A-05（未出结论原因）是两个问题：A-05 只看 {@code aiRejected = 1}，
     * 本查询看「谁进了复核队列」，两者不得复用同一个过滤条件。
     */
    @Select("SELECT abstainReason AS reason, COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 "
            + "AND humanReviewStatus IN ('AWAITING_REVIEW','REVIEWED','OVERRIDDEN') "
            + "AND createTime >= #{from} AND createTime < #{to} "
            + "GROUP BY abstainReason ORDER BY count DESC")
    List<ReasonStatVO> queueReasons(@Param("from") Date from, @Param("to") Date to);

    /* ── 拒识（按 createTime 筛窗） ── */

    /** 窗口内判读总量。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND createTime >= #{from} AND createTime < #{to}")
    long countDiagnosesBetween(@Param("from") Date from, @Param("to") Date to);

    /** 窗口内 AI 拒识总数（只认 aiRejected）。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 AND aiRejected = 1 "
            + "AND createTime >= #{from} AND createTime < #{to}")
    long countRejectedBetween(@Param("from") Date from, @Param("to") Date to);

    /** 未出结论原因（只统计 aiRejected = 1）。 */
    @Select("SELECT abstainReason AS reason, COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 AND aiRejected = 1 "
            + "AND createTime >= #{from} AND createTime < #{to} "
            + "GROUP BY abstainReason ORDER BY count DESC")
    List<ReasonStatVO> rejectionReasons(@Param("from") Date from, @Param("to") Date to);

    /* ── 模型与图像质量 ── */

    /**
     * 各模型版本的运行量与置信度。
     *
     * <p>置信度均值只取 {@code 1 < confidence <= 100} 的样本；其余按量纲未知计数，
     * 不猜测、不自动乘 100。
     *
     * <p>这一列的写入方只有一个：{@code DiagnosisEvidence.applyTo(Diagnosis)} 把
     * {@code top1 × 100} 落库（口径见 {@code docs/design/iris22-admin-metric-registry.md}
     * B-03/B-04）。它是「模型自评概率百分数」，不是校准置信度——界面上只在
     * {@code calibrationStatus = CALIBRATED} 时才称其为置信度。
     */
    @Select("SELECT modelVersion, COUNT(*) AS count, "
            + "SUM(CASE WHEN aiRejected = 1 THEN 1 ELSE 0 END) AS rejected, "
            + "AVG(CASE WHEN confidence > 1 AND confidence <= 100 THEN confidence END) AS avgConfidence, "
            + "SUM(CASE WHEN confidence > 1 AND confidence <= 100 THEN 1 ELSE 0 END) AS confidenceSamples, "
            + "SUM(CASE WHEN confidence > 0 AND confidence <= 1 THEN 1 ELSE 0 END) AS ambiguousConfidence "
            + "FROM t_diagnosis WHERE isDelete = 0 "
            + "AND createTime >= #{from} AND createTime < #{to} "
            + "GROUP BY modelVersion ORDER BY count DESC, modelVersion")
    List<ModelVersionStatVO> modelVersionStats(@Param("from") Date from, @Param("to") Date to);

    /** 置信度汇总与量纲自检（一次查询返回全部计数）。 */
    @Select("SELECT "
            + "COALESCE(SUM(CASE WHEN confidence > 1 AND confidence <= 100 THEN 1 ELSE 0 END), 0) AS samples, "
            + "AVG(CASE WHEN confidence > 1 AND confidence <= 100 THEN confidence END) AS `avg`, "
            + "COALESCE(SUM(CASE WHEN confidence > 0 AND confidence <= 1 THEN 1 ELSE 0 END), 0) AS ambiguous, "
            + "COALESCE(SUM(CASE WHEN confidence <= 0 OR confidence > 100 THEN 1 ELSE 0 END), 0) AS outOfRange, "
            + "COALESCE(SUM(CASE WHEN confidence IS NULL THEN 1 ELSE 0 END), 0) AS missing "
            + "FROM t_diagnosis WHERE isDelete = 0 "
            + "AND createTime >= #{from} AND createTime < #{to}")
    ConfidenceSummaryVO confidenceSummary(@Param("from") Date from, @Param("to") Date to);

    /** 置信度十档直方图（只含量纲可靠的样本）。 */
    @Select("SELECT CASE WHEN confidence >= 100 THEN '90–100' "
            + "ELSE CONCAT(FLOOR(confidence / 10) * 10, '–', FLOOR(confidence / 10) * 10 + 10) END AS bucket, "
            + "COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to} "
            + "AND confidence > 1 AND confidence <= 100 "
            + "GROUP BY bucket ORDER BY bucket")
    List<HistogramBucketVO> confidenceHistogram(@Param("from") Date from, @Param("to") Date to);

    /** 质量分分布（含空值行，由 Service 区分越界与未记录）。 */
    @Select("SELECT qualityScore AS score, COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to} "
            + "GROUP BY qualityScore ORDER BY qualityScore")
    List<QualityScoreStatVO> qualityScoreCounts(@Param("from") Date from, @Param("to") Date to);

    /** 质量原因原始串（逗号分隔，多标签由 Service 拆分计数）。 */
    @Select("SELECT qualityReasons FROM t_diagnosis "
            + "WHERE isDelete = 0 AND qualityReasons IS NOT NULL AND qualityReasons <> '' "
            + "AND createTime >= #{from} AND createTime < #{to}")
    List<String> qualityReasonRows(@Param("from") Date from, @Param("to") Date to);

    /** OOD 状态分布（空值由 Service 归入 UNRECORDED）。 */
    @Select("SELECT oodStatus AS status, COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to} "
            + "GROUP BY oodStatus ORDER BY count DESC")
    List<StatusStatVO> oodStatusCounts(@Param("from") Date from, @Param("to") Date to);

    /** 校准状态分布（空值由 Service 归入 UNRECORDED）。 */
    @Select("SELECT calibrationStatus AS status, COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to} "
            + "GROUP BY calibrationStatus ORDER BY count DESC")
    List<StatusStatVO> calibrationStatusCounts(@Param("from") Date from, @Param("to") Date to);

    /* ── 数据质量自检 ── */

    /** 契约外的复核状态（如历史库里的 PENDING_AI / APPROVED）。 */
    @Select("SELECT humanReviewStatus AS status, COUNT(*) AS count FROM t_diagnosis "
            + "WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to} "
            + "AND humanReviewStatus NOT IN ('AI_ONLY','AWAITING_REVIEW','REVIEWED','OVERRIDDEN') "
            + "GROUP BY humanReviewStatus ORDER BY count DESC")
    List<StatusStatVO> unknownReviewStatusCounts(@Param("from") Date from, @Param("to") Date to);

    /** 已完成复核但缺少复核时间的记录数（不进入时延统计）。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis WHERE isDelete = 0 "
            + "AND humanReviewStatus IN ('REVIEWED','OVERRIDDEN') AND humanReviewedAt IS NULL "
            + "AND createTime >= #{from} AND createTime < #{to}")
    long countReviewedWithoutTime(@Param("from") Date from, @Param("to") Date to);
}
