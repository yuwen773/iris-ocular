package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 模型版本一行的运行读数。
 *
 * <p>{@code avgConfidence} 只由量纲可靠（1 < confidence ≤ 100）的样本计算；
 * 量纲未知的记录计入 {@code ambiguousConfidence}，不参与平均。
 * 置信度是模型自评，<b>不是准确率</b>。
 */
@Data
public class ModelVersionStatVO implements Serializable {

    /** 模型版本；空值在 Service 里归为 UNRECORDED */
    private String modelVersion;

    /** 窗口内判读量 */
    private long count;

    /** 其中 AI 拒识数 */
    private long rejected;

    /** 拒识率 = rejected / count；count 为 0 时为 null */
    private Double rejectionRate;

    /** 置信度均值（仅 1 < confidence ≤ 100 的样本）；无有效样本为 null */
    private Double avgConfidence;

    /** 参与置信度均值的有效样本数 */
    private long confidenceSamples;

    /** 量纲无法判定而排除的样本数 */
    private long ambiguousConfidence;

    private static final long serialVersionUID = 1L;
}
