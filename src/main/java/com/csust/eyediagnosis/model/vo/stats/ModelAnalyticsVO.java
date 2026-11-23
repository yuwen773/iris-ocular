package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型与图像质量读数。
 *
 * <p>这里的每一个数都来自推理链路真实写入的证据字段（V9 起）：
 * {@code modelVersion} / {@code confidence} / {@code qualityScore} /
 * {@code qualityReasons} / {@code oodStatus} / {@code calibrationStatus}。
 *
 * <p><b>置信度不是准确率。</b>系统没有真实标签数据源，任何页面都不得把
 * {@code avgConfidence} 写成「准确率」。
 */
@Data
public class ModelAnalyticsVO implements Serializable {

    /** 各模型版本的运行量、拒识率与置信度（A-01/B-01/B-02） */
    private List<ModelVersionStatVO> versions = new ArrayList<>();

    /** 置信度汇总（B-03/B-04） */
    private ConfidenceSummaryVO confidence = new ConfidenceSummaryVO();

    /** 置信度十档直方图（B-04），固定 10 桶 */
    private List<HistogramBucketVO> confidenceHistogram = new ArrayList<>();

    /** 图像质量分档（B-05），固定 5 档 + 越界/未记录计数 */
    private List<QualityScoreStatVO> qualityScores = new ArrayList<>();

    /** 契约外质量分记录数（非空且不在 {100,75,50,25,0}） */
    private long qualityOutOfContract;

    /** 质量分为空的记录数 */
    private long qualityUnknown;

    /** 质量原因构成（B-06，多标签，合计可超过记录数） */
    private List<ReasonStatVO> qualityReasons = new ArrayList<>();

    /** 契约外质量原因标签的出现次数 */
    private long unclassifiedQualityReasonCount;

    /** OOD 状态构成（B-07）；空值归入 UNRECORDED */
    private List<StatusStatVO> oodStatuses = new ArrayList<>();

    /** 校准状态构成（B-08）；空值归入 UNRECORDED */
    private List<StatusStatVO> calibrationStatuses = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
