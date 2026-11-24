package com.csust.eyediagnosis.constant;

import cn.hutool.core.util.StrUtil;

import java.util.Set;

/**
 * 通知类型与**发送时定稿的文案**。
 *
 * <p>文案真源放在服务端：通知在写入那一刻把 title / body 存进库里，之后前端
 * 改名、枚举调整都不会改写历史消息。前端只镜像「类型 → 中文分组 / 深链」，
 * 不重复维护一份文案。
 *
 * <p>正文只给指针（哪个任务、去哪个列表看），**不放结论、病名、严重度**：
 * 站内通知可能被截屏、被预览，真正的判读内容在登录后的记录页，那里有免责声明。
 */
public final class NotificationType {

    /** 影像分析完成，等待医生复核（任务状态 WAITING_REVIEW）。 */
    public static final String DIAGNOSIS_READY = "DIAGNOSIS_READY";
    /** 判读未能完成（任务状态 RESTRICTED：质量、分布或算法侧拒绝）。 */
    public static final String DIAGNOSIS_RESTRICTED = "DIAGNOSIS_RESTRICTED";
    /** 医生复核有了结论（批准或驳回都算「复核完成」）。 */
    public static final String REVIEW_DONE = "REVIEW_DONE";
    /** 管理员回复了用户反馈（回复或带回复的状态流转）。 */
    public static final String FEEDBACK_REPLIED = "FEEDBACK_REPLIED";

    /** 全部合法类型：查询过滤与写入共用这一份，不靠「能生成文案」间接判断。 */
    private static final Set<String> VALID = Set.of(
            DIAGNOSIS_READY, DIAGNOSIS_RESTRICTED, REVIEW_DONE, FEEDBACK_REPLIED);

    /** 通知的定稿文案（含它指向哪类关联对象）。 */
    public record Copy(String title, String body, String refType) {
    }

    private NotificationType() {
    }

    public static boolean isValid(String type) {
        return type != null && VALID.contains(type);
    }

    /**
     * 生成一条通知的文案；未知类型返回 {@code null}（调用方据此不写库）。
     *
     * @param refId 关联对象 id；任务 id 取后 6 位做短引用，避免 36 位 UUID 撑满一行
     */
    public static Copy copyOf(String type, String refId) {
        if (StrUtil.isBlank(type) || StrUtil.isBlank(refId)) {
            return null;
        }
        String shortRef = shortRef(refId);
        return switch (type) {
            case DIAGNOSIS_READY -> new Copy("判读结果已出",
                    "任务 " + shortRef + " 的影像分析已完成，等待医生复核。", "DIAGNOSIS_TASK");
            case DIAGNOSIS_RESTRICTED -> new Copy("判读未能完成",
                    "任务 " + shortRef + " 未能完成判读，请查看记录或重新上传。", "DIAGNOSIS_TASK");
            case REVIEW_DONE -> new Copy("医生复核完成",
                    "任务 " + shortRef + " 已完成复核，可以查看结果。", "DIAGNOSIS_TASK");
            case FEEDBACK_REPLIED -> new Copy("反馈有了回复",
                    "你提交的意见反馈收到了管理员回复。", "USER_FEEDBACK");
            default -> null;
        };
    }

    private static String shortRef(String refId) {
        return refId.length() <= 6 ? refId : refId.substring(refId.length() - 6);
    }
}
