package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 未出结论（AI 拒识）读数。
 *
 * <p>「拒识」只认 {@code aiRejected = 1}：QUALITY_FAIL / OOD 这类真正没有给出
 * 结论的记录。低置信度（LOW_CONFIDENCE）会进入复核队列，但 {@code aiRejected}
 * 为 false，<b>不在本 VO 里</b>——它属于 {@link ReviewAnalyticsVO#getQueueReasons()}。
 * 两个问题不能复用一个过滤条件。
 */
@Data
public class RejectionAnalyticsVO implements Serializable {

    /** 窗口内判读总量（A-04 分母） */
    private long total;

    /** 窗口内 AI 拒识数（A-04 分子） */
    private long rejected;

    /** 拒识率 = rejected / total；分母为 0 时为 null */
    private Double rate;

    /** 未出结论原因构成（A-05） */
    private List<ReasonStatVO> reasons = new ArrayList<>();

    /** 契约外原因取值的记录数 */
    private long unclassifiedReasonCount;

    /** 原因为空的记录数 */
    private long unknownReasonCount;

    private static final long serialVersionUID = 1L;
}
