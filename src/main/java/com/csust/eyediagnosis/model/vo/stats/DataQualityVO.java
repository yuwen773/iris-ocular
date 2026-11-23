package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据质量自检：把「契约外 / 无法判定 / 时间缺失」的记录数暴露给前端，
 * 让管理员能解释「为什么图表加总与原始记录数不一致」。
 *
 * <p>这些计数不参与任何比率的分母，只用于展示与排障。
 */
@Data
public class DataQualityVO implements Serializable {

    /** 契约外的复核状态（如历史库里的 PENDING_AI / APPROVED）及数量 */
    private List<StatusStatVO> unknownReviewStatuses = new ArrayList<>();

    /** 契约外的 OOD 状态（空值不算，空值在 model 里归 UNRECORDED） */
    private List<StatusStatVO> unknownOodStatuses = new ArrayList<>();

    /** 契约外的校准状态 */
    private List<StatusStatVO> unknownCalibrationStatuses = new ArrayList<>();

    /** 质量分非空但不在 {100,75,50,25,0} 的记录数 */
    private long qualityOutOfContract;

    /** 置信度在 (0,1] 之间、无法可靠判定量纲的记录数（不参与聚合） */
    private long ambiguousConfidence;

    /** 置信度 ≤0 或 >100 的记录数（不参与聚合） */
    private long confidenceOutOfRange;

    /** 置信度为空的记录数 */
    private long confidenceMissing;

    /** 状态为 REVIEWED/OVERRIDDEN 但 humanReviewedAt 为空的记录数（不进入时延统计） */
    private long reviewedWithoutTime;

    private static final long serialVersionUID = 1L;
}
