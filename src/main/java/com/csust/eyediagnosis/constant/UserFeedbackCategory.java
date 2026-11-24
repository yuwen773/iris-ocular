package com.csust.eyediagnosis.constant;

/**
 * 用户反馈的分类，对应 {@code t_user_feedback.category}。
 *
 * <p>值域由本类守卫（DB 不设 CHECK：本机 3306 是 5.7，CHECK 会被解析后忽略）。
 * 前端展示文案在 {@code web/src/lib/userFeedbackDomain.ts}，两侧共用同一组枚举值。
 */
public final class UserFeedbackCategory {

    /** 功能建议 */
    public static final String SUGGESTION = "SUGGESTION";
    /** 使用问题 */
    public static final String ISSUE = "ISSUE";
    /** 对判读 / Agent 结论的质疑 */
    public static final String RESULT_DOUBT = "RESULT_DOUBT";
    /** 其他 */
    public static final String OTHER = "OTHER";

    private UserFeedbackCategory() {
    }

    public static boolean isValid(String category) {
        return SUGGESTION.equals(category) || ISSUE.equals(category)
                || RESULT_DOUBT.equals(category) || OTHER.equals(category);
    }
}
