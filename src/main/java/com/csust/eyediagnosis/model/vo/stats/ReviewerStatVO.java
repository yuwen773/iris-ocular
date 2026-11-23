package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 复核人工作量一行。
 *
 * <p>复核人一律称「复核人」，不得写成「医生」：账号角色不构成临床资质证明。
 * 名称为空时前端显示「复核人 #id（未记录名称）」。
 */
@Data
public class ReviewerStatVO implements Serializable {

    /** 复核人用户 id */
    private Long reviewerId;

    /** 复核人显示名（t_user.userName，可为空） */
    private String reviewerName;

    /** 复核量（REVIEWED + OVERRIDDEN，两者互斥，不相加两次） */
    private long reviewed;

    /** 其中推翻 AI 结论的数量 */
    private long overridden;

    /** 端到端复核时延均值（分钟）；无有效时间样本时为 null */
    private Double avgMinutes;

    private static final long serialVersionUID = 1L;
}
