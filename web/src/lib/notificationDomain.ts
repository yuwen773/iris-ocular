/**
 * 站内通知的前端领域层：类型镜像、筛选文案、深链映射。
 *
 * <p>这里**不重复维护通知正文**：`title` / `body` 由服务端在发送时定稿存库，
 * 页面直接渲染。前端只负责「按哪个类型筛」与「点进去去哪」。
 */

export type NotificationTypeOption = {
  value: string
  label: string
  /** 筛选项的一行说明：这一类型在讲什么。 */
  hint: string
}

/** 与服务端 `NotificationType` 一一对应；新增类型时两边一起改（服务端是文案真源）。 */
export const NOTIFICATION_TYPES: readonly NotificationTypeOption[] = [
  { value: "DIAGNOSIS_READY", label: "判读结果已出", hint: "影像分析完成，等待医生复核" },
  { value: "DIAGNOSIS_RESTRICTED", label: "判读未能完成", hint: "质量或分布校验未通过" },
  { value: "REVIEW_DONE", label: "复核完成", hint: "医生已给出复核结论" },
  { value: "FEEDBACK_REPLIED", label: "反馈回复", hint: "你提交的意见反馈收到回复" },
]

const TYPE_LABELS = new Map(NOTIFICATION_TYPES.map((option) => [option.value, option.label]))

/** 类型的中文短名；认不出的取值说「其他通知」，不冒充某个已知类型。 */
export function notificationTypeLabel(type: string | null | undefined): string {
  return (type && TYPE_LABELS.get(type)) || "其他通知"
}

/**
 * 点进通知后落在哪个页面。
 *
 * <p>只跳列表页、不带记录 id：关联对象可能已被删除，而通知是历史消息，
 * 不应该因为点进去是 404 就变成一条坏消息。认不出的关联类型返回 null，
 * 页面只标已读、不跳转。
 */
export function notificationTargetPath(refType: string | null | undefined): string | null {
  switch (refType) {
    case "DIAGNOSIS_TASK":
      return "/records"
    case "USER_FEEDBACK":
      return "/feedback"
    default:
      return null
  }
}

/** 未读判定：服务端以 `readAt` 是否为空表示已读状态。 */
export function notificationIsUnread(notification: { readAt?: string | null }): boolean {
  return !notification.readAt
}
