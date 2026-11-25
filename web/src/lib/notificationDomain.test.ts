import { describe, expect, it } from "vitest"

import {
  NOTIFICATION_TYPES,
  notificationIsUnread,
  notificationTargetPath,
  notificationTypeLabel,
} from "./notificationDomain"

describe("通知领域层", () => {
  it("四个类型与服务端 NotificationType 一一对应", () => {
    expect(NOTIFICATION_TYPES.map((option) => option.value)).toEqual([
      "DIAGNOSIS_READY",
      "DIAGNOSIS_RESTRICTED",
      "REVIEW_DONE",
      "FEEDBACK_REPLIED",
    ])
  })

  it("类型标签：认不出的取值说「其他通知」，不冒充已知类型", () => {
    expect(notificationTypeLabel("REVIEW_DONE")).toBe("复核完成")
    expect(notificationTypeLabel("SOMETHING_NEW")).toBe("其他通知")
    expect(notificationTypeLabel(null)).toBe("其他通知")
  })

  it("深链只到列表页；认不出的关联类型不跳转", () => {
    expect(notificationTargetPath("DIAGNOSIS_TASK")).toBe("/records")
    expect(notificationTargetPath("USER_FEEDBACK")).toBe("/feedback")
    expect(notificationTargetPath("SOMETHING_ELSE")).toBeNull()
    expect(notificationTargetPath(undefined)).toBeNull()
  })

  it("未读判定只看 readAt 是否存在", () => {
    expect(notificationIsUnread({ readAt: null })).toBe(true)
    expect(notificationIsUnread({})).toBe(true)
    expect(notificationIsUnread({ readAt: "2026-11-24T10:00:00+08:00" })).toBe(false)
  })
})
