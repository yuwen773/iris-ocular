// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"

vi.mock("@/lib/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/adminApi")>()
  return {
    ...actual,
    listAdminUserFeedback: vi.fn(),
    userFeedbackPendingCount: vi.fn(),
    changeUserFeedbackStatus: vi.fn(),
    replyUserFeedback: vi.fn(),
    adminUserFeedbackAttachmentUrl: vi.fn(),
  }
})

import {
  adminUserFeedbackAttachmentUrl,
  changeUserFeedbackStatus,
  listAdminUserFeedback,
  replyUserFeedback,
  userFeedbackPendingCount,
  type AdminFeedback,
} from "@/lib/adminApi"
import { AdminFeedbackPage } from "./AdminFeedbackPage"

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminFeedbackPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function row(overrides: Partial<AdminFeedback> = {}): AdminFeedback {
  return {
    id: "101",
    category: "ISSUE",
    content: "上传后一直转圈",
    userName: "张三",
    status: "PENDING",
    createTime: "2026-11-23T10:00:00+08:00",
    hasAttachment: false,
    ...overrides,
  }
}

function pageOf(records: AdminFeedback[]) {
  return { records, total: records.length, current: 1, pages: 1 }
}

beforeEach(() => {
  vi.mocked(listAdminUserFeedback).mockResolvedValue(pageOf([row()]))
  vi.mocked(userFeedbackPendingCount).mockResolvedValue(1)
})

afterEach(() => {
  cleanup()
  vi.mocked(listAdminUserFeedback).mockReset()
  vi.mocked(userFeedbackPendingCount).mockReset()
  vi.mocked(changeUserFeedbackStatus).mockReset()
  vi.mocked(replyUserFeedback).mockReset()
  vi.mocked(adminUserFeedbackAttachmentUrl).mockReset()
})

describe("AdminFeedbackPage 台账", () => {
  it("显示正文、状态与待处理读数", async () => {
    vi.mocked(userFeedbackPendingCount).mockResolvedValue(3)

    renderPage()

    expect(await screen.findByText("上传后一直转圈")).toBeTruthy()
    // 状态标签与筛选项都写「待处理」：标签是 span（.risk），筛选是 button。
    expect(document.querySelector(".risk")?.textContent).toBe("待处理")
    expect(await screen.findByText("待处理 3 条")).toBeTruthy()
  })

  it("状态段控件点一下即按新条件重查，并回到第一页", async () => {
    renderPage()

    await screen.findByText("上传后一直转圈")
    fireEvent.click(screen.getByRole("button", { name: "已解决" }))

    await waitFor(() =>
      expect(listAdminUserFeedback).toHaveBeenCalledWith(
        expect.objectContaining({ status: "RESOLVED", current: 1 }),
      ),
    )
  })

  it("空台账说清楚为什么空，并给清筛选的出口", async () => {
    vi.mocked(listAdminUserFeedback).mockResolvedValue(pageOf([]))

    renderPage()
    await screen.findByText("还没有反馈")

    fireEvent.click(screen.getByRole("button", { name: "已忽略" }))
    await waitFor(() =>
      expect(listAdminUserFeedback).toHaveBeenCalledWith(
        expect.objectContaining({ status: "IGNORED" }),
      ),
    )
    expect(await screen.findByText(/当前筛选条件下没有反馈/)).toBeTruthy()
    expect(screen.getByRole("button", { name: "清筛选" })).toBeTruthy()
  })
})

describe("AdminFeedbackPage 回复与状态流转", () => {
  it("空回复不发请求，给出可读的校验提示", async () => {
    renderPage()

    fireEvent.click(await screen.findByRole("button", { name: /详情/ }))
    await screen.findByText("反馈详情")
    fireEvent.click(screen.getByRole("button", { name: "回复用户" }))

    expect(await screen.findByText("回复不能是空的——用户要读到一句人话。")).toBeTruthy()
    expect(replyUserFeedback).not.toHaveBeenCalled()
  })

  it("回复成功后刷新台账并给出处理回执", async () => {
    vi.mocked(replyUserFeedback).mockResolvedValue(
      row({ status: "PROCESSING", reply: "已修复，感谢反馈" }),
    )

    renderPage()

    fireEvent.click(await screen.findByRole("button", { name: /详情/ }))
    await screen.findByText("反馈详情")
    fireEvent.change(screen.getByPlaceholderText(/说清处理结果/), {
      target: { value: "已修复，感谢反馈" },
    })
    fireEvent.click(screen.getByRole("button", { name: "回复用户" }))

    await waitFor(() =>
      expect(replyUserFeedback).toHaveBeenCalledWith("101", "已修复，感谢反馈"),
    )
    expect(await screen.findByText("回复已发送")).toBeTruthy()
  })

  it("标记已解决前必须有回复；有回复时把回复一起写入", async () => {
    vi.mocked(changeUserFeedbackStatus).mockResolvedValue(
      row({ status: "RESOLVED", reply: "已修复" }),
    )

    renderPage()

    fireEvent.click(await screen.findByRole("button", { name: /详情/ }))
    await screen.findByText("反馈详情")

    // 没有回复：按钮点了也不发请求。
    fireEvent.click(screen.getByRole("button", { name: "标记为已解决" }))
    expect(await screen.findByText("标记已解决前，先写一句回复给用户。")).toBeTruthy()
    expect(changeUserFeedbackStatus).not.toHaveBeenCalled()

    fireEvent.change(screen.getByPlaceholderText(/说清处理结果/), {
      target: { value: "已修复" },
    })
    fireEvent.click(screen.getByRole("button", { name: "标记为已解决" }))

    await waitFor(() =>
      expect(changeUserFeedbackStatus).toHaveBeenCalledWith("101", "RESOLVED", "已修复"),
    )
  })
})
