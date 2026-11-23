// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest"

import { AUTH_EXPIRED_EVENT } from "./http"
import { fetchKnowledgeText, getAdminAnalytics, getAdminStats } from "./adminApi"

function stubJson(body: unknown, status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn<typeof fetch>(async () =>
      new Response(typeof body === "string" ? body : JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    ),
  )
}

function stubRaw(body: string, status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn<typeof fetch>(async () =>
      new Response(body, { status, headers: { "Content-Type": "text/plain" } }),
    ),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("fetchKnowledgeText", () => {
  it("错误信封（数值 code 且非 0）按错误处理，并广播未登录事件", async () => {
    stubJson({ code: 40100, msg: "请先登录" })
    const codes: number[] = []
    const onExpired = (event: Event) => {
      codes.push((event as CustomEvent<{ code: number }>).detail.code)
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired)

    await expect(fetchKnowledgeText("42")).rejects.toMatchObject({ code: 40100 })
    expect(codes).toEqual([40100])

    window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired)
  })

  it("JSON 正文不算错误：code=0 时照常返回内容", async () => {
    // 「响应是 JSON」不等于「出错了」——端点将来可以合法返回 JSON 正文，
    // 那时阅读器应该拿到正文，而不是一律显示「服务返回…」。
    stubJson({ code: 0, data: { text: "正文" } })
    await expect(fetchKnowledgeText("42")).resolves.toContain("正文")
  })

  it("非 JSON 的错误状态按状态码报", async () => {
    stubRaw("boom", 502)
    await expect(fetchKnowledgeText("42")).rejects.toMatchObject({ code: 502 })
  })

  it("非 JSON 的正常响应按原文返回", async () => {
    stubRaw("## 标题")
    await expect(fetchKnowledgeText("42")).resolves.toBe("## 标题")
  })
})

describe("getAdminAnalytics", () => {
  // 与真实序列化一致：Long 字段是字符串，int/double 是数字，可空数值是 null。
  const payload = {
    code: 0,
    msg: "ok",
    data: {
      days: 30,
      rangeStart: "2026-11-01",
      rangeEnd: "2026-11-30",
      review: {
        dataStatus: "HISTORICAL_ONLY",
        awaitingTotal: "120",
        queuedInWindow: "7",
        completedInWindow: "4",
        completionRate: null,
        reviewedInWindow: "4",
        overriddenInWindow: "1",
        overrideRate: 0.25,
        latencySamples: "4",
        avgMinutes: null,
        p50Minutes: 60,
        p90Minutes: 180,
        maxMinutes: 240,
        reviewers: [
          { reviewerId: "42", reviewerName: null, reviewed: "3", overridden: "1", avgMinutes: null },
        ],
        queueReasons: [{ reason: "LOW_CONFIDENCE", count: "6", share: null }],
        unclassifiedReasonCount: "0",
        unknownReasonCount: "1",
      },
      rejection: {
        total: "12",
        rejected: "3",
        rate: 0.25,
        reasons: [{ reason: "QUALITY_FAIL", count: "2", share: 0.6667 }],
        unclassifiedReasonCount: "0",
        unknownReasonCount: "0",
      },
      model: {
        versions: [
          {
            modelVersion: null,
            count: "10",
            rejected: "2",
            rejectionRate: 0.2,
            avgConfidence: 81.2,
            confidenceSamples: "10",
            ambiguousConfidence: "0",
          },
        ],
        confidence: { samples: "10", avg: 81.2, ambiguous: "0", outOfRange: "0", missing: "2" },
        confidenceHistogram: [{ bucket: "80–90", count: "10" }],
        qualityScores: [
          { score: 100, count: "10" },
          { score: null, count: "2" },
        ],
        qualityOutOfContract: "0",
        qualityUnknown: "2",
        qualityReasons: [{ reason: "blur", count: "2", share: null }],
        unclassifiedQualityReasonCount: "0",
        oodStatuses: [{ status: "NOT_EVALUATED", count: "12" }],
        calibrationStatuses: [{ status: "NOT_CALIBRATED", count: "12" }],
      },
      dataQuality: {
        unknownReviewStatuses: [],
        unknownOodStatuses: [],
        unknownCalibrationStatuses: [],
        qualityOutOfContract: "0",
        ambiguousConfidence: "0",
        confidenceOutOfRange: "0",
        confidenceMissing: "2",
        reviewedWithoutTime: "0",
      },
    },
  }

  it("Long 字符串计数转成数字，比率/时延的 null 保持 null", async () => {
    stubJson(payload)

    const data = await getAdminAnalytics(30)

    expect(data.days).toBe(30)
    expect(data.review.awaitingTotal).toBe(120)
    expect(data.review.completionRate).toBeNull()
    expect(data.review.avgMinutes).toBeNull()
    expect(data.review.p50Minutes).toBe(60)
    expect(data.review.reviewers[0].reviewerId).toBe("42")
    expect(data.rejection.rate).toBe(0.25)
    expect(data.model.qualityScores[1].score).toBeNull()
    expect(data.model.qualityUnknown).toBe(2)
    // 版本为空归入 UNRECORDED，不显示空白行。
    expect(data.model.versions[0].modelVersion).toBe("UNRECORDED")
  })

  it("响应缺少领域时报本地错误，不冒充网络失败", async () => {
    const withoutModel: Record<string, unknown> = { ...payload.data }
    delete withoutModel.model
    stubJson({ ...payload, data: withoutModel })

    await expect(getAdminAnalytics(30)).rejects.toMatchObject({
      code: -2,
      message: "统计响应缺少 model 领域",
    })
  })

  it("data 为 null 时给本地载荷错误，不让原生 TypeError 漏出去", async () => {
    stubJson({ ...payload, data: null })

    await expect(getAdminAnalytics(30)).rejects.toMatchObject({
      code: -2,
      message: "统计响应为空",
    })
  })
})

describe("getAdminStats 计数口径", () => {
  it("真实 0 保留为 0，缺失字段保持 undefined，字符串计数转数字", async () => {
    stubJson({
      code: 0,
      data: {
        days: 30,
        rangeStart: "2026-11-01",
        rangeEnd: "2026-11-30",
        avgConfidence: null,
        diagnosisInRange: "0",
        diagnosisAiRejected: "3",
        knowledgeFailed: null,
        newsByStatus: [{ name: "3", value: "2" }],
      },
    })

    const stats = await getAdminStats(30)

    expect(stats.diagnosisInRange).toBe(0)
    expect(stats.diagnosisAiRejected).toBe(3)
    expect(stats.knowledgeFailed).toBeUndefined()
    expect(stats.newsByStatus).toEqual([{ name: "3", value: 2 }])
    expect(stats.diagnosisDistribution).toBeUndefined()
  })
})
