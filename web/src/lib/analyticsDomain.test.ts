import { describe, expect, it } from "vitest"

import type { AdminAnalytics } from "@/lib/adminApi"
import {
  buildAvailability,
  buildQualityIssues,
  calibrationStatusLabel,
  confidenceHistogramRows,
  formatConfidence,
  formatCount,
  formatMinutes,
  formatRate,
  metricLiveRows,
  oodCapability,
  oodStatusLabel,
  parseAnalyticsDays,
  parseAnalyticsSection,
  qualityReasonLabel,
  qualityScoreLabel,
  qualityScoreRows,
  reasonRows,
  reviewDataStatus,
  reviewerLabel,
} from "@/lib/analyticsDomain"

function analytics(): AdminAnalytics {
  return {
    days: 30,
    rangeStart: "2026-11-01",
    rangeEnd: "2026-11-30",
    review: {
      dataStatus: "HISTORICAL_ONLY",
      awaitingTotal: 5,
      queuedInWindow: 7,
      completedInWindow: 4,
      completionRate: null,
      reviewedInWindow: 4,
      overriddenInWindow: 1,
      overrideRate: 0.25,
      latencySamples: 0,
      avgMinutes: null,
      p50Minutes: null,
      p90Minutes: null,
      maxMinutes: null,
      reviewers: [
        { reviewerId: "42", reviewerName: null, reviewed: 3, overridden: 1, avgMinutes: null },
      ],
      queueReasons: [{ reason: "UNRECORDED", count: 2, share: null }],
      unclassifiedReasonCount: 1,
      unknownReasonCount: 0,
    },
    rejection: {
      total: 10,
      rejected: 3,
      rate: null,
      reasons: [
        { reason: "QUALITY_FAIL", count: 1, share: 1 / 3 },
        { reason: "UNCLASSIFIED", count: 2, share: 2 / 3 },
      ],
      unclassifiedReasonCount: 2,
      unknownReasonCount: 0,
    },
    model: {
      versions: [],
      confidence: { samples: 0, avg: null, ambiguous: 2, outOfRange: 1, missing: 3 },
      confidenceHistogram: [],
      qualityScores: [
        { score: 100, count: 2 },
        { score: 25, count: 1 },
        { score: null, count: 4 },
        { score: 60, count: 1 },
      ],
      qualityOutOfContract: 1,
      qualityUnknown: 4,
      qualityReasons: [],
      unclassifiedQualityReasonCount: 0,
      oodStatuses: [{ status: "NOT_EVALUATED", count: 10 }],
      calibrationStatuses: [{ status: "NOT_CALIBRATED", count: 10 }],
    },
    dataQuality: {
      unknownReviewStatuses: [{ status: "APPROVED", count: 2 }],
      unknownOodStatuses: [],
      unknownCalibrationStatuses: [],
      qualityOutOfContract: 1,
      ambiguousConfidence: 2,
      confidenceOutOfRange: 1,
      confidenceMissing: 3,
      reviewedWithoutTime: 1,
    },
  }
}

describe("窗口与分区解析", () => {
  it("只认四个窗口，其它回落到 30 天", () => {
    expect(parseAnalyticsDays("7")).toBe(7)
    expect(parseAnalyticsDays("365")).toBe(365)
    expect(parseAnalyticsDays("999")).toBe(30)
    expect(parseAnalyticsDays(null)).toBe(30)
    expect(parseAnalyticsDays("abc")).toBe(30)
  })

  it("未知分区回落到分析概览", () => {
    expect(parseAnalyticsSection("model")).toBe("model")
    expect(parseAnalyticsSection("nope")).toBe("overview")
    expect(parseAnalyticsSection(null)).toBe("overview")
  })
})

describe("读数格式化", () => {
  it("null 一律破折号，不补 0", () => {
    expect(formatRate(null)).toBe("—")
    expect(formatMinutes(null)).toBe("—")
    expect(formatConfidence(null)).toBe("—")
    expect(formatCount(Number.NaN)).toBe("—")
  })

  it("置信度按 0–100 直出，不再乘 100", () => {
    expect(formatConfidence(81.24)).toBe("81.2%")
    expect(formatConfidence(0.74)).toBe("0.7%")
  })

  it("比率是 0–1，显示成百分比", () => {
    expect(formatRate(0.257)).toBe("25.7%")
    expect(formatRate(0)).toBe("0.0%")
  })
})

describe("分桶", () => {
  it("置信度固定十桶，后端缺的桶补 0", () => {
    const rows = confidenceHistogramRows([{ bucket: "80–90", count: 9 }])
    expect(rows).toHaveLength(10)
    expect(rows[0]).toEqual({ bucket: "0–10", count: 0 })
    expect(rows[8]).toEqual({ bucket: "80–90", count: 9 })
  })

  it("质量分只保留五个契约值，空值与契约外值都不进桶", () => {
    const rows = qualityScoreRows(analytics().model.qualityScores)
    expect(rows).toEqual([
      { score: 0, count: 0 },
      { score: 25, count: 1 },
      { score: 50, count: 0 },
      { score: 75, count: 0 },
      { score: 100, count: 2 },
    ])
    expect(qualityScoreLabel(100)).toBe("100 · 无质量原因")
    expect(qualityScoreLabel(25)).toBe("25 · 3 项原因")
  })
})

describe("原因与状态", () => {
  it("原因按计数降序，契约外/空值文案落在页面上", () => {
    const rows = reasonRows(analytics().rejection.reasons)
    expect(rows.map((row) => row.label)).toEqual(["未归类原因", "图像质量不合格"])
    expect(rows.map((row) => row.share)).toEqual([2 / 3, 1 / 3])
  })

  it("契约外状态保留原值，不硬翻成已知项", () => {
    expect(oodStatusLabel("NOT_EVALUATED")).toBe("尚未评估")
    expect(oodStatusLabel("WEIRD")).toBe("WEIRD（契约外）")
    expect(calibrationStatusLabel("CALIBRATED")).toBe("已校准")
    expect(qualityReasonLabel("blur")).toBe("模糊")
    expect(qualityReasonLabel("glare")).toBe("glare")
  })

  it("复核人缺名字时用 id 兜底，不编名字", () => {
    expect(reviewerLabel(analytics().review.reviewers[0])).toBe("复核人 #42（未记录名称）")
    expect(
      reviewerLabel({ reviewerId: null, reviewerName: null, reviewed: 0, overridden: 0, avgMinutes: null }),
    ).toBe("未记录复核人")
  })

  it("OOD 只有一个未评估值时识别为「检测未启用」", () => {
    expect(oodCapability([{ status: "NOT_EVALUATED", count: 10 }]).disabled).toBe(true)
    expect(
      oodCapability([
        { status: "NOT_EVALUATED", count: 10 },
        { status: "IN_DISTRIBUTION", count: 1 },
      ]).disabled,
    ).toBe(false)
    expect(oodCapability([]).disabled).toBe(false)
  })

  it("复核数据状态只信后端，缺失时明确说无法确认", () => {
    expect(reviewDataStatus("HISTORICAL_ONLY")).toEqual({ label: "历史数据", tone: "historical" })
    expect(reviewDataStatus("").label).toBe("无法确认统计状态")
    expect(reviewDataStatus("").tone).toBe("unknown")
  })
})

describe("数据质量清单", () => {
  it("只收大于 0 的异常，并把契约外状态逐条列出", () => {
    const issues = buildQualityIssues(analytics())
    const keys = issues.map((issue) => issue.key)
    expect(keys).toContain("ambiguous-confidence")
    expect(keys).toContain("quality-out-of-contract")
    expect(keys).toContain("review-status-APPROVED")
    expect(keys).not.toContain("review-unknown")
    expect(issues.find((issue) => issue.key === "confidence-missing")?.count).toBe(3)
    expect(issues.every((issue) => issue.count > 0)).toBe(true)
  })
})

describe("口径抽屉的运行时计数", () => {
  it("只从响应取值，null 保持破折号", () => {
    const rows = metricLiveRows("B-03", analytics())
    expect(rows).toEqual([
      { label: "有效置信度样本", value: "0" },
      { label: "平均置信度（0–100）", value: "—" },
      { label: "量纲歧义 (0,1]", value: "2" },
      { label: "越界（≤0 或 >100）", value: "1" },
      { label: "缺失", value: "3" },
    ])
  })

  it("没有对应字段的指标返回空列表", () => {
    expect(metricLiveRows("NOT-A-METRIC", analytics())).toEqual([])
  })
})

describe("可用性摘要", () => {
  it("复核状态无法确认时摘要也不能写成当前或历史", () => {
    const data = analytics()
    data.review.dataStatus = ""
    const items = buildAvailability(data)
    const review = items.find((item) => item.title === "复核效率")
    expect(review?.statusLabel).toBe("无法确认统计状态")
    expect(review?.statusTone).toBe("unknown")
  })
})
