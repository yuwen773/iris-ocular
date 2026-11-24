package com.csust.eyediagnosis.constant;

import java.util.Map;
import java.util.Set;

/**
 * 用户反馈的处理状态，对应 {@code t_user_feedback.status}。
 *
 * <p>与「Critic 反馈」（{@code FourRoleGraph.feedback}）、「复核意见」、
 * 「审核意见」都不是同一个概念，见 {@code GLOSSARY.md}。
 *
 * <p>状态共用一个列，不另设 reviewStatus —— 它们同属一条处理生命周期，
 * 并列两列会造成状态双写漂移（与 {@link NewsStatus} 的取舍一致）。
 *
 * <p>迁移表是本类的唯一真源：写侧只做 {@link #canMove} 校验，
 * 迁移用条件更新（{@code WHERE status = 旧值}），不做读-改-写。
 */
public final class UserFeedbackStatus {

    /** 待处理：用户刚提交，管理员尚未受理 */
    public static final String PENDING = "PENDING";
    /** 处理中：管理员已受理（含已回复但未结案） */
    public static final String PROCESSING = "PROCESSING";
    /** 已解决：给出结论并结案；之前必须已有回复正文 */
    public static final String RESOLVED = "RESOLVED";
    /** 已忽略：不采纳（重复提交、与产品无关等） */
    public static final String IGNORED = "IGNORED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            PENDING, Set.of(PROCESSING, RESOLVED, IGNORED),
            PROCESSING, Set.of(PENDING, RESOLVED, IGNORED),
            RESOLVED, Set.of(PROCESSING),
            IGNORED, Set.of(PENDING));

    private UserFeedbackStatus() {
    }

    public static boolean isValid(String status) {
        return status != null && ALLOWED.containsKey(status);
    }

    /** 合法迁移：{@code from} 必须存在，{@code to} 必须在它的后继集合里。 */
    public static boolean canMove(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 错误信息里用的显示值：null 换成 {@code UNKNOWN}，其余原样返回。
     *
     * <p>名字刻意不叫 {@code label}：它不产出人类标签（那是前端的
     * {@code userFeedbackDomain.ts} 的事），只做「别把 null 拼进字符串」这一件事。
     */
    public static String orUnknown(String status) {
        return status == null ? "UNKNOWN" : status;
    }
}
