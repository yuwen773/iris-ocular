package com.csust.eyediagnosis.model.dto.userfeedback;

import com.csust.eyediagnosis.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 用户反馈的分页查询条件。用户侧与管理侧共用，差异由服务层强制：
 * 用户侧恒加 {@code userId = 当前用户}，管理侧才看得到全部。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UserFeedbackQueryDTO extends PageRequest implements Serializable {

    /** 分类精确匹配；空表示不限 */
    private String category;

    /** 状态精确匹配；空表示不限 */
    private String status;

    /** 关键词：正文模糊匹配（不搜回复正文，回复只对本人可见的那一条有意义） */
    private String keyword;

    /** 提交时间下界（含当天），格式 yyyy-MM-dd */
    private String startDate;

    /** 提交时间上界（含当天），格式 yyyy-MM-dd */
    private String endDate;

    private static final long serialVersionUID = 1L;
}
