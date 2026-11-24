// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter, Route, Routes } from "react-router-dom"

import { ThemeProvider } from "@/components/theme-provider"

const sessionState = vi.hoisted(() => ({
  status: "authed" as "authed" | "anon" | "loading",
}))

vi.mock("@/lib/session", () => ({
  useSession: () => ({
    status: sessionState.status,
    user: null,
    scope: "u1",
    signIn: vi.fn(),
    signOut: vi.fn(),
    refresh: vi.fn(),
  }),
}))

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>()
  return {
    ...actual,
    listMyNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn(),
    markNotificationRead: vi.fn(),
    markAllNotificationsRead: vi.fn(),
  }
})

const pushMock = vi.hoisted(() => vi.fn())
vi.mock("@/components/fault", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/fault")>()
  return {
    ...actual,
    useFault: () => ({ push: pushMock, dismiss: vi.fn() }),
  }
})

import {
  getUnreadNotificationCount,
  listMyNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type UserNotification,
} from "@/lib/api"
import { NotificationsPage } from "./NotificationsPage"

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <MemoryRouter initialEntries={["/notifications"]}>
          <Routes>
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/records" element={<p>判读记录页</p>} />
            <Route path="/feedback" element={<p>我的反馈页</p>} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

function notification(overrides: Partial<UserNotification> = {}): UserNotification {
  return {
    id: "1",
    type: "DIAGNOSIS_READY",
    title: "判读结果已出",
    body: "任务 abc123 的影像分析已完成，等待医生复核。",
    refType: "DIAGNOSIS_TASK",
    refId: "t-1",
    readAt: null,
    createTime: "2026-11-24T10:00:00+08:00",
    ...overrides,
  }
}

beforeEach(() => {
  sessionState.status = "authed"
  vi.mocked(listMyNotifications).mockResolvedValue({
    records: [],
    total: 0,
    current: 1,
    pages: 0,
  })
  vi.mocked(getUnreadNotificationCount).mockResolvedValue(1)
  vi.mocked(markNotificationRead).mockResolvedValue(notification({ readAt: "2026-11-24T11:00:00+08:00" }))
  vi.mocked(markAllNotificationsRead).mockResolvedValue(1)
})

afterEach(() => {
  cleanup()
  vi.mocked(listMyNotifications).mockReset()
  vi.mocked(getUnreadNotificationCount).mockReset()
  vi.mocked(markNotificationRead).mockReset()
  vi.mocked(markAllNotificationsRead).mockReset()
  pushMock.mockReset()
})

describe("NotificationsPage 列表", () => {
  it("默认在「未读」页签，请求带 unreadOnly", async () => {
    renderPage()

    await waitFor(() =>
      expect(listMyNotifications).toHaveBeenCalledWith(
        expect.objectContaining({ unreadOnly: true, current: 1, pageSize: 20 }),
        expect.anything(),
      ),
    )
  })

  it("切到「全部」后不再限制未读", async () => {
    renderPage()
    await waitFor(() => expect(listMyNotifications).toHaveBeenCalled())

    fireEvent.click(screen.getByRole("tab", { name: "全部" }))

    await waitFor(() => {
      const lastCall = vi.mocked(listMyNotifications).mock.calls.at(-1)?.[0]
      expect(lastCall?.unreadOnly).toBeUndefined()
    })
  })

  it("点未读通知先标已读，再跳到通知指向的列表页", async () => {
    vi.mocked(listMyNotifications).mockResolvedValue({
      records: [notification()],
      total: 1,
      current: 1,
      pages: 1,
    })
    renderPage()

    fireEvent.click(await screen.findByText(/任务 abc123 的影像分析已完成/))

    await waitFor(() => expect(markNotificationRead).toHaveBeenCalledWith("1"))
    expect(await screen.findByText("判读记录页")).toBeTruthy()
  })

  it("已读通知再点不重复写库", async () => {
    vi.mocked(listMyNotifications).mockResolvedValue({
      records: [notification({ readAt: "2026-11-24T11:00:00+08:00" })],
      total: 1,
      current: 1,
      pages: 1,
    })
    renderPage()

    fireEvent.click(await screen.findByText(/任务 abc123 的影像分析已完成/))

    expect(await screen.findByText("判读记录页")).toBeTruthy()
    expect(markNotificationRead).not.toHaveBeenCalled()
  })

  it("空列表把「没有未读」与「还没有通知」分开说", async () => {
    renderPage()
    expect(await screen.findByText("没有未读通知")).toBeTruthy()

    fireEvent.click(screen.getByRole("tab", { name: "全部" }))
    expect(await screen.findByText("还没有通知")).toBeTruthy()
  })

  it("未登录只给登录入口，不发个人请求", () => {
    sessionState.status = "anon"
    renderPage()

    expect(screen.getByText("登录后才能查看通知")).toBeTruthy()
    expect(listMyNotifications).not.toHaveBeenCalled()
    expect(getUnreadNotificationCount).not.toHaveBeenCalled()
  })
})

describe("NotificationsPage 全部已读", () => {
  it("只清当前类型筛选下的未读", async () => {
    renderPage()
    await waitFor(() => expect(listMyNotifications).toHaveBeenCalled())

    fireEvent.click(screen.getByRole("button", { name: "复核完成" }))
    fireEvent.click(screen.getByRole("button", { name: /全部已读/ }))

    await waitFor(() => expect(markAllNotificationsRead).toHaveBeenCalledWith("REVIEW_DONE"))
  })

  it("没有类型筛选时清全部未读", async () => {
    renderPage()
    await waitFor(() => expect(listMyNotifications).toHaveBeenCalled())

    fireEvent.click(screen.getByRole("button", { name: /全部已读/ }))

    await waitFor(() => expect(markAllNotificationsRead).toHaveBeenCalledWith(undefined))
  })
})
