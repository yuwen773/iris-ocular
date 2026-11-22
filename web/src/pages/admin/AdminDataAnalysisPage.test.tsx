// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"

import { AdminDataAnalysisPage } from "./AdminDataAnalysisPage"
import { getAdminAnalytics, type AdminAnalytics } from "@/lib/adminApi"

vi.mock("@/lib/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/adminApi")>()
  return {
    ...actual,
    getAdminAnalytics: vi.fn(),
  }
})

afterEach(() => {
  cleanup()
  vi.mocked(getAdminAnalytics).mockReset()
})

const RANGES: Record<number, string> = {
  7: "2026-11-24",
  30: "2026-11-01",
  90: "2026-09-01",
  365: "2025-12-01",
}

function analyticsOf(days: number, patch: Partial<AdminAnalytics> = {}): AdminAnalytics {
  const base: AdminAnalytics = {
    days,
    rangeStart: RANGES[days] ?? "2026-11-01",
    rangeEnd: "2026-11-30",
    review: {
      dataStatus: "HISTORICAL_ONLY",
      awaitingTotal: 5,
      queuedInWindow: 7,
      completedInWindow: 4,
      completionRate: 4 / 7,
      reviewedInWindow: 4,
      overriddenInWindow: 1,
      overrideRate: 0.25,
      latencySamples: 4,
      avgMinutes: 90.2,
      p50Minutes: 60,
      p90Minutes: 180,
      maxMinutes: 240,
      reviewers: [
        { reviewerId: "1", reviewerName: "王医生", reviewed: 4, overridden: 1, avgMinutes: 90 },
      ],
      queueReasons: [
        { reason: "LOW_CONFIDENCE", count: 6, share: 6 / 7 },
        { reason: "UNRECORDED", count: 1, share: 1 / 7 },
      ],
      unclassifiedReasonCount: 0,
      unknownReasonCount: 1,
    },
    rejection: {
      total: 12,
      rejected: 3,
      rate: 0.25,
      reasons: [
        { reason: "QUALITY_FAIL", count: 2, share: 2 / 3 },
        { reason: "UNCLASSIFIED", count: 1, share: 1 / 3 },
      ],
      unclassifiedReasonCount: 1,
      unknownReasonCount: 0,
    },
    model: {
      versions: [
        {
          modelVersion: "efficientnet-b4-v1",
          count: 10,
          rejected: 2,
          rejectionRate: 0.2,
          avgConfidence: 81.2,
          confidenceSamples: 9,
          ambiguousConfidence: 1,
        },
      ],
      confidence: { samples: 9, avg: 81.2, ambiguous: 1, outOfRange: 1, missing: 2 },
      confidenceHistogram: [{ bucket: "80–90", count: 9 }],
      qualityScores: [
        { score: 100, count: 2 },
        { score: 25, count: 1 },
        { score: null, count: 2 },
      ],
      qualityOutOfContract: 1,
      qualityUnknown: 2,
      qualityReasons: [
        { reason: "blur", count: 2, share: 2 / 3 },
        { reason: "weird", count: 1, share: 1 / 3 },
      ],
      unclassifiedQualityReasonCount: 1,
      oodStatuses: [{ status: "NOT_EVALUATED", count: 12 }],
      calibrationStatuses: [{ status: "NOT_CALIBRATED", count: 12 }],
    },
    dataQuality: {
      unknownReviewStatuses: [],
      unknownOodStatuses: [],
      unknownCalibrationStatuses: [],
      qualityOutOfContract: 1,
      ambiguousConfidence: 1,
      confidenceOutOfRange: 1,
      confidenceMissing: 2,
      reviewedWithoutTime: 1,
    },
  }
  return { ...base, ...patch }
}

function renderPage(entry = "/admin/data-analysis") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <AdminDataAnalysisPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe("AdminDataAnalysisPage 窗口与缓存", () => {
  it("默认按 30 天请求，页头写出后端回显的窗口", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage()

    expect(await screen.findByText("2026-11-01 ~ 2026-11-30 · 近 30 天")).toBeTruthy()
    expect(getAdminAnalytics).toHaveBeenCalledWith(30)
    expect(screen.getByRole("button", { name: "近 30 天" }).getAttribute("aria-pressed")).toBe(
      "true",
    )
  })

  it("切窗时旧窗口读数立刻消失，新读数回来前不挂在新标签下", async () => {
    let resolve90: (value: AdminAnalytics) => void = () => {}
    vi.mocked(getAdminAnalytics).mockImplementation((days) =>
      days === 30
        ? Promise.resolve(analyticsOf(30, { rejection: { ...analyticsOf(30).rejection, total: 111 } }))
        : new Promise<AdminAnalytics>((resolve) => {
            resolve90 = resolve
          }),
    )

    renderPage()
    await screen.findByText("111 条判读")

    fireEvent.click(screen.getByRole("button", { name: "近 90 天" }))

    await waitFor(() => expect(screen.queryByText("111 条判读")).toBeNull())
    expect(screen.queryByText("2026-11-01 ~ 2026-11-30 · 近 30 天")).toBeNull()

    resolve90(
      analyticsOf(90, { rejection: { ...analyticsOf(90).rejection, total: 222 } }),
    )

    expect(await screen.findByText("222 条判读")).toBeTruthy()
    expect(screen.getAllByText(/近 90 天/).length).toBeGreaterThan(0)
  })

  it("空窗口给专属空态，不摆一排 0", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(
      analyticsOf(30, {
        rejection: {
          total: 0,
          rejected: 0,
          rate: null,
          reasons: [],
          unclassifiedReasonCount: 0,
          unknownReasonCount: 0,
        },
      }),
    )

    renderPage()

    expect(await screen.findByText("该时间范围内暂无可分析记录")).toBeTruthy()
    expect(screen.queryByLabelText("数据可用性")).toBeNull()
  })

  it("接口失败时给故障提示与重试，不显示 0", async () => {
    vi.mocked(getAdminAnalytics).mockRejectedValue(new Error("boom"))

    renderPage()

    expect(await screen.findByText(/出了点意料之外的情况/)).toBeTruthy()
    expect(screen.getByRole("button", { name: "重试" })).toBeTruthy()
    expect(screen.queryByLabelText("数据可用性")).toBeNull()
  })
})

describe("AdminDataAnalysisPage 判读与复核", () => {
  it("历史复核数据必须标历史并写清写入已关闭", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage("/admin/data-analysis?section=review")

    expect(await screen.findByText("历史数据")).toBeTruthy()
    expect(
      screen.getByText(/历史复核数据 · 当前复核写入已安全关闭。/),
    ).toBeTruthy()
    expect(screen.getByText(/不代表当前运营复核能力/)).toBeTruthy()
  })

  it("复核状态无法确认时不冒充历史，也不冒充当前", async () => {
    const base = analyticsOf(30)
    vi.mocked(getAdminAnalytics).mockResolvedValue(
      analyticsOf(30, { review: { ...base.review, dataStatus: "" } }),
    )

    renderPage("/admin/data-analysis?section=review")

    expect(await screen.findByText("无法确认统计状态")).toBeTruthy()
    expect(screen.getByText(/当前复核写入已安全关闭/)).toBeTruthy()
    expect(screen.queryByText(/历史复核数据 ·/)).toBeNull()
  })

  it("拒识原因与触发复核原因是两套分母，各自写清", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage("/admin/data-analysis?section=review")

    expect(await screen.findByText("AI 未出结论原因")).toBeTruthy()
    expect(screen.getByText("触发复核原因")).toBeTruthy()
    expect(screen.getByText(/分母：窗口内 AI 拒识 3 条（判读总量 12 条）/)).toBeTruthy()
    expect(screen.getByText(/分母：窗口内进入复核队列 7 条/)).toBeTruthy()
    // 空原因作为独立桶可见，不被静默丢弃。切到读数表读取完整文案。
    const queueView = screen.getByRole("group", { name: "触发复核原因构成 视图切换" })
    fireEvent.click(within(queueView).getByRole("button", { name: "表" }))
    expect(screen.getByText("未记录原因")).toBeTruthy()
  })

  it("分母不可用时比率显示破折号，不补 0%", async () => {
    const base = analyticsOf(30)
    vi.mocked(getAdminAnalytics).mockResolvedValue(
      analyticsOf(30, {
        review: {
          ...base.review,
          completionRate: null,
          overrideRate: null,
          latencySamples: 0,
          avgMinutes: null,
          p50Minutes: null,
          p90Minutes: null,
          maxMinutes: null,
        },
      }),
    )

    renderPage("/admin/data-analysis?section=review")

    expect(await screen.findByText("复核完成率")).toBeTruthy()
    expect(screen.queryByText("0.0%")).toBeNull()
    expect(screen.getAllByText("—").length).toBeGreaterThan(0)
    expect(screen.getByText(/暂无有效复核时间样本/)).toBeTruthy()
  })

  it("指标口径抽屉同时给出定义与本窗口计数", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage("/admin/data-analysis?section=review")
    await screen.findByText("复核完成率")

    fireEvent.click(screen.getByRole("button", { name: "查看「复核完成率」的口径" }))

    expect(await screen.findByText("B0 · A-08")).toBeTruthy()
    expect(screen.getByText("(REVIEWED + OVERRIDDEN) ÷ 进入队列集合")).toBeTruthy()
    expect(screen.getByText("本次窗口计数")).toBeTruthy()
    expect(screen.getByText("窗口内入队")).toBeTruthy()
  })
})

describe("AdminDataAnalysisPage 模型与数据质量", () => {
  it("OOD 未启用时只陈述状态，不画 100% 分布图", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage("/admin/data-analysis?section=model")

    expect(await screen.findByText("尚未评估")).toBeTruthy()
    expect(screen.getByText(/OOD 检测当前未启用/)).toBeTruthy()
    expect(screen.getByText(/不展示 100% 分布图/)).toBeTruthy()
    expect(screen.queryByText("100.0%")).toBeNull()
  })

  it("质量分只按五个精确档位分桶，越界与缺失分开计数", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage("/admin/data-analysis?section=model")

    expect(await screen.findByText("图像质量分档")).toBeTruthy()
    expect(screen.getByText(/契约外质量分 1 条、质量分缺失 2 条/)).toBeTruthy()
    expect(screen.getByText(/契约值 \{100,75,50,25,0\} · 不就近归类/)).toBeTruthy()
  })

  it("数据质量页逐项计数，并可回到对应口径", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage("/admin/data-analysis?section=quality")

    expect(await screen.findByText("置信度量纲歧义")).toBeTruthy()
    expect(screen.getByText("质量分越界")).toBeTruthy()
    expect(screen.getByText("已复核但缺复核时间")).toBeTruthy()
    expect(screen.getByText("质量原因未归类")).toBeTruthy()

    const inspectButtons = screen.getAllByRole("button", { name: "查看口径" })
    fireEvent.click(inspectButtons[0])

    expect(await screen.findByText("B0 · B-03")).toBeTruthy()
  })

  it("分区标签与可用性卡片都能切换分区", async () => {
    vi.mocked(getAdminAnalytics).mockResolvedValue(analyticsOf(30))

    renderPage()
    await screen.findByText("数据可用性")

    fireEvent.click(screen.getByRole("button", { name: /判读与复核/ }))
    expect(await screen.findByText("历史数据")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "模型与图像质量" }))
    expect(await screen.findByText("置信度可用性")).toBeTruthy()
  })
})
