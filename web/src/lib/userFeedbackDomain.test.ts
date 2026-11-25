import { describe, expect, it } from "vitest"

import {
  FEEDBACK_CATEGORIES,
  FEEDBACK_TEXT_LIMIT,
  feedbackCategoryLabel,
  feedbackIsClosed,
  feedbackNextStatuses,
  feedbackStatusHint,
  feedbackStatusLabel,
  feedbackStatusRisk,
  feedbackTargetLabel,
  imageTaskStatusLabel,
  validateFeedbackContent,
} from "./userFeedbackDomain"

describe("用户反馈领域层", () => {
  it("分类是闭集：未知值显示「未分类」，不冒充「其他」", () => {
    expect(FEEDBACK_CATEGORIES).toHaveLength(4)
    expect(feedbackCategoryLabel("RESULT_DOUBT")).toBe("结果质疑")
    expect(feedbackCategoryLabel("COMPLAINT")).toBe("未分类")
    expect(feedbackCategoryLabel(null)).toBe("未分类")
  })

  it("状态标签永远带文字，未知状态落到中性档", () => {
    expect(feedbackStatusLabel("PENDING")).toBe("待处理")
    expect(feedbackStatusLabel("PROCESSING")).toBe("处理中")
    expect(feedbackStatusLabel("RESOLVED")).toBe("已解决")
    expect(feedbackStatusLabel("IGNORED")).toBe("已忽略")
    expect(feedbackStatusRisk("RESOLVED")).toBe("normal")
    expect(feedbackStatusRisk("IGNORED")).toBe("unknown")
    expect(feedbackStatusLabel("BOGUS")).toBe("未记录")
    expect(feedbackStatusRisk("BOGUS")).toBe("unknown")
    expect(feedbackStatusHint("PENDING")).toContain("还没有人处理")
  })

  it("迁移矩阵与后端一致：结案之间不能互跳", () => {
    expect(feedbackNextStatuses("PENDING")).toEqual(["PROCESSING", "RESOLVED", "IGNORED"])
    expect(feedbackNextStatuses("PROCESSING")).toEqual(["PENDING", "RESOLVED", "IGNORED"])
    expect(feedbackNextStatuses("RESOLVED")).toEqual(["PROCESSING"])
    expect(feedbackNextStatuses("IGNORED")).toEqual(["PENDING"])
    // 未知状态不给任何目标：先刷新，而不是猜一个能点的动作。
    expect(feedbackNextStatuses("BOGUS")).toEqual([])
    expect(feedbackNextStatuses(null)).toEqual([])
  })

  it("已结案不允许直接追加回复", () => {
    expect(feedbackIsClosed("RESOLVED")).toBe(true)
    expect(feedbackIsClosed("IGNORED")).toBe(true)
    expect(feedbackIsClosed("PROCESSING")).toBe(false)
    expect(feedbackIsClosed(null)).toBe(false)
  })

  it("关联对象文案：没有关联时明说，不冒充某个类型", () => {
    expect(feedbackTargetLabel("DIAGNOSIS_TASK", "t-1")).toBe("判读任务 · t-1")
    expect(feedbackTargetLabel("AGENT_SESSION", "s-1")).toBe("Agent 会话 · s-1")
    expect(feedbackTargetLabel(null, null)).toBe("未关联具体对象")
  })

  it("判读任务状态翻成中文；认不出的取值说「状态未知」", () => {
    expect(imageTaskStatusLabel("PROCESSING")).toBe("判读中")
    expect(imageTaskStatusLabel("WAITING_REVIEW")).toBe("等待复核")
    expect(imageTaskStatusLabel("RESTRICTED")).toBe("受限")
    expect(imageTaskStatusLabel("UNKNOWN")).toBe("状态未知")
    expect(imageTaskStatusLabel(null)).toBe("状态未知")
  })

  it("正文校验：空 / 超长 / 正常三态", () => {
    expect(validateFeedbackContent("   ").ok).toBe(false)

    const tooLong = validateFeedbackContent("字".repeat(FEEDBACK_TEXT_LIMIT + 1))
    expect(tooLong.ok).toBe(false)
    if (!tooLong.ok) expect(tooLong.message).toContain(String(FEEDBACK_TEXT_LIMIT + 1))

    // 通过时返回去掉首尾空白的正文：服务端也会 trim，两边口径一致。
    expect(validateFeedbackContent("  希望支持批量导出  ")).toEqual({
      ok: true,
      content: "希望支持批量导出",
    })
  })
})
