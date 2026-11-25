// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { cleanup, render, screen } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"

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
    getUnreadNotificationCount: vi.fn(),
  }
})

import { getUnreadNotificationCount } from "@/lib/api"
import { NotificationBell } from "./NotificationBell"

function renderBell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <MemoryRouter>
          <NotificationBell />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  sessionState.status = "authed"
  vi.mocked(getUnreadNotificationCount).mockResolvedValue(0)
})

afterEach(() => {
  cleanup()
  vi.mocked(getUnreadNotificationCount).mockReset()
})

describe("NotificationBell", () => {
  it("未读为 0 时只显示铃铛本体，不显示「0」", async () => {
    renderBell()

    const link = await screen.findByRole("link", { name: "通知" })
    expect(link.getAttribute("href")).toBe("/notifications")
    expect(screen.queryByText("0")).toBeNull()
  })

  it("未读数出现在角标与可读标签里", async () => {
    vi.mocked(getUnreadNotificationCount).mockResolvedValue(3)
    renderBell()

    expect(await screen.findByRole("link", { name: "通知，3 条未读" })).toBeTruthy()
    expect(screen.getByText("3")).toBeTruthy()
  })

  it("超过 99 显示 99+，避免角标撑破顶栏", async () => {
    vi.mocked(getUnreadNotificationCount).mockResolvedValue(150)
    renderBell()

    expect(await screen.findByText("99+")).toBeTruthy()
  })

  it("未登录不渲染，也不发个人请求", () => {
    sessionState.status = "anon"
    renderBell()

    expect(screen.queryByRole("link", { name: /通知/ })).toBeNull()
    expect(getUnreadNotificationCount).not.toHaveBeenCalled()
  })
})
