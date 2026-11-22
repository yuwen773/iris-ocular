// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"

import { AdminDashboardPage } from "./AdminDashboardPage"
import { getAdminStats, listDiagnoses, type AdminStats } from "@/lib/adminApi"

vi.mock("@/lib/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/adminApi")>()
  return {
    ...actual,
    getAdminStats: vi.fn(),
    listDiagnoses: vi.fn(() => Promise.resolve({ records: [], total: 0, current: 1, pageSize: 5 })),
  }
})

afterEach(() => {
  cleanup()
  vi.mocked(getAdminStats).mockReset()
  vi.mocked(listDiagnoses).mockClear()
})

function statsOf(days: number, inRange: number, overrides: Partial<AdminStats> = {}): AdminStats {
  return {
    days,
    rangeStart: `2026-11-${String(days).padStart(2, "0")}`,
    rangeEnd: "2026-11-11",
    userTotal: 10,
    userNew: 1,
    previousUserNew: 1,
    userAdmin: 1,
    diagnosisTotal: inRange,
    diagnosisInRange: inRange,
    previousDiagnosisInRange: inRange,
    diagnosisToday: 1,
    diagnosisAiRejected: 0,
    diagnosisAwaitingReview: 0,
    diagnosisReviewed: 0,
    diagnosisAwaitingReviewTotal: 0,
    avgConfidence: 0.9,
    knowledgeTotal: 1,
    knowledgeChunks: 1,
    knowledgeFailed: 0,
    knowledgeByStatus: [],
    newsTotal: 0,
    newsByStatus: [],
    trendDays: [],
    diagnosisTrend: [],
    previousDiagnosisTrend: [],
    previousUserTrend: [],
    userTrend: [],
    diagnosisDistribution: [],
    reviewDistribution: [],
    diagnosisTrendByResult: [],
    ...overrides,
  }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminDashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe("AdminDashboardPage 判读结论口径", () => {
  it("空结果按 aiRejected 分开说：拒识=未出结论，其它=无判读结果", async () => {
    vi.mocked(getAdminStats).mockResolvedValue(statsOf(30, 3))
    vi.mocked(listDiagnoses).mockResolvedValue({
      records: [
        {
          id: "1",
          result: null,
          aiRejected: true,
          abstainReason: "图像质量不足",
          username: "a",
        },
        { id: "2", result: null, aiRejected: false, username: "b" },
      ],
      total: 2,
      current: 1,
    })

    renderPage()

    // 结论格：拒识行说「未出结论」，非拒识的空行说「无判读结果」。
    const rejected = await screen.findByText("未出结论")
    expect(rejected.className).toContain("admin-chip")
    expect(screen.getByText("无判读结果").className).toContain("admin-chip")

    // 结论格一律不写「AI 拒识」——空结果不是拒识，那个标签留给复核状态列。
    const chips = Array.from(document.querySelectorAll(".admin-chip")).map((el) => el.textContent)
    expect(chips).toContain("未出结论")
    expect(chips).toContain("无判读结果")
    expect(chips).not.toContain("AI 拒识")
  })
})

describe("AdminDashboardPage B3 运营语义", () => {
  it("复核改为历史只读入口，不再出现积压或「去复核」", async () => {
    vi.mocked(getAdminStats).mockResolvedValue(statsOf(30, 3))

    renderPage()

    await screen.findByText("复核历史记录")
    expect(screen.getByText("历史数据")).toBeTruthy()
    expect(screen.getByText(/正式临床复核写入已安全关闭/)).toBeTruthy()
    expect(screen.queryByText(/积压/)).toBeNull()
    expect(screen.queryByText("去复核")).toBeNull()
    expect(screen.queryByText("复核状态分布")).toBeNull()

    const link = screen.getByRole("link", {
      name: /查看历史复核统计、状态分布与复核时延/,
    })
    expect(link.getAttribute("href")).toBe("/admin/data-analysis?days=30&section=review")
  })

  it("AI 拒识只能查看记录，不写「去处理」", async () => {
    vi.mocked(getAdminStats).mockResolvedValue(statsOf(30, 3, { diagnosisAiRejected: 2 }))

    renderPage()

    const link = await screen.findByRole("link", { name: "查看记录" })
    expect(link.getAttribute("href")).toBe("/admin/diagnosis?review=aiRejected")
    expect(screen.queryByRole("link", { name: "去处理" })).toBeTruthy()
    expect(screen.queryByText("立即处理")).toBeNull()
  })

  it("字段缺失显示「—」，不把未知补成 0", async () => {
    vi.mocked(getAdminStats).mockResolvedValue(
      statsOf(30, 3, { knowledgeFailed: undefined, newsByStatus: undefined }),
    )

    renderPage()

    await screen.findByLabelText("核心运营概况")
    expect(screen.getByText("待办口径未接入")).toBeTruthy()
    expect(screen.queryByText("当前没有待处理事项")).toBeNull()
  })

  it("不再展示混合量纲的平均置信度，只给数据分析入口", async () => {
    vi.mocked(getAdminStats).mockResolvedValue(statsOf(30, 3))

    renderPage()

    await screen.findByText("复核历史记录")
    expect(screen.queryByText(/平均置信度/)).toBeNull()
    expect(screen.getByRole("link", { name: "数据分析 · 模型与图像质量" })).toBeTruthy()
  })
})

describe("AdminDashboardPage 切窗", () => {
  it("切到新窗口时旧读数立刻消失，等新窗口数据回来才显示", async () => {
    let resolve90: (value: AdminStats) => void = () => {}
    vi.mocked(getAdminStats).mockImplementation((days) =>
      days === 30
        ? Promise.resolve(statsOf(30, 111))
        : new Promise<AdminStats>((resolve) => {
            resolve90 = resolve
          }),
    )

    renderPage()
    await screen.findByText("111")
    expect(screen.getAllByText(/近 30 天/).length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole("button", { name: "90 天" }))

    // 新窗口在途：旧的 30 天读数与采样行必须立即不可见，不能挂在新标签下。
    await waitFor(() => expect(screen.queryByText("111")).toBeNull())
    expect(screen.queryByText(/近 30 天/)).toBeNull()

    resolve90(statsOf(90, 222))

    await screen.findByText("222")
    expect(screen.getAllByText(/近 90 天/).length).toBeGreaterThan(0)
  })

  it("服务端回显窗口与选中值不一致时，同样当作没读到", async () => {
    // 第二次请求回的是 30 天数据（模拟后端回显错窗），不能挂到 90 天标签下。
    vi.mocked(getAdminStats)
      .mockResolvedValueOnce(statsOf(30, 111))
      .mockResolvedValueOnce(statsOf(30, 999))

    renderPage()
    await screen.findByText("111")

    fireEvent.click(screen.getByRole("button", { name: "90 天" }))

    await waitFor(() => expect(screen.getByRole("button", { name: "90 天" }).getAttribute("aria-pressed")).toBe("true"))
    expect(screen.queryByText("999")).toBeNull()
  })
})
