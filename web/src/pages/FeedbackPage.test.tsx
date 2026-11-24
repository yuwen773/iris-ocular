// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
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
    listMyUserFeedback: vi.fn(),
    submitUserFeedback: vi.fn(),
    uploadUserFeedbackAttachment: vi.fn(),
    getMyUserFeedbackAttachmentUrl: vi.fn(),
    getSessions: vi.fn(),
  }
})

vi.mock("@/lib/diagnosisTaskApi", () => ({
  diagnosisTaskApi: { list: vi.fn() },
}))

const pushMock = vi.hoisted(() => vi.fn())
vi.mock("@/components/fault", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/fault")>()
  return {
    ...actual,
    useFault: () => ({ push: pushMock, dismiss: vi.fn() }),
  }
})

import {
  getMyUserFeedbackAttachmentUrl,
  getSessions,
  listMyUserFeedback,
  submitUserFeedback,
  uploadUserFeedbackAttachment,
  type UserFeedback,
} from "@/lib/api"
import { diagnosisTaskApi } from "@/lib/diagnosisTaskApi"
import { FeedbackPage } from "./FeedbackPage"

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <MemoryRouter>
          <FeedbackPage />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

function feedback(overrides: Partial<UserFeedback> = {}): UserFeedback {
  return {
    id: "1",
    category: "ISSUE",
    content: "上传后一直转圈",
    status: "PENDING",
    createTime: "2026-11-23T10:00:00+08:00",
    ...overrides,
  }
}

beforeEach(() => {
  sessionState.status = "authed"
  vi.mocked(listMyUserFeedback).mockResolvedValue({ records: [], total: 0, current: 1, pages: 0 })
  vi.mocked(diagnosisTaskApi.list).mockResolvedValue([])
  vi.mocked(getSessions).mockResolvedValue([])
  vi.mocked(submitUserFeedback).mockResolvedValue(feedback())
})

afterEach(() => {
  cleanup()
  vi.mocked(listMyUserFeedback).mockReset()
  vi.mocked(submitUserFeedback).mockReset()
  vi.mocked(uploadUserFeedbackAttachment).mockReset()
  vi.mocked(getMyUserFeedbackAttachmentUrl).mockReset()
  vi.mocked(diagnosisTaskApi.list).mockReset()
  vi.mocked(getSessions).mockReset()
  pushMock.mockReset()
})

describe("FeedbackPage 提交", () => {
  it("没有选分类时不发请求，给出可读的校验提示", async () => {
    renderPage()

    fireEvent.change(screen.getByPlaceholderText(/说清楚发生了什么/), {
      target: { value: "希望支持批量导出" },
    })
    fireEvent.click(screen.getByRole("button", { name: /提交反馈/ }))

    expect(await screen.findByText("先选一个分类——管理员据此分派处理。")).toBeTruthy()
    expect(submitUserFeedback).not.toHaveBeenCalled()
  })

  it("选好分类、填好正文后提交，成功后清空表单并提示", async () => {
    renderPage()

    fireEvent.click(screen.getByRole("radio", { name: /功能建议/ }))
    fireEvent.change(screen.getByPlaceholderText(/说清楚发生了什么/), {
      target: { value: "  希望支持批量导出  " },
    })
    fireEvent.click(screen.getByRole("button", { name: /提交反馈/ }))

    await waitFor(() =>
      expect(submitUserFeedback).toHaveBeenCalledWith({
        category: "SUGGESTION",
        content: "希望支持批量导出",
      }),
    )
    // 提交成功走全站浮条，不在表单里留一块「成功」告示。
    expect(pushMock).toHaveBeenCalled()
    await waitFor(() =>
      expect((screen.getByPlaceholderText(/说清楚发生了什么/) as HTMLTextAreaElement).value).toBe(""),
    )
  })

  it("关联判读任务时送的是 taskId（/diagnosis-tasks），不是判读记录的 id", async () => {
    const taskId = "2f6c1b7a-9d3e-4c55-8e21-0a1b2c3d4e5f"
    vi.mocked(diagnosisTaskApi.list).mockResolvedValue([{ taskId, status: "PROCESSING" }])
    renderPage()

    fireEvent.click(screen.getByRole("radio", { name: /结果质疑/ }))
    fireEvent.change(screen.getByPlaceholderText(/说清楚发生了什么/), {
      target: { value: "结论和我的预期不一致" },
    })
    fireEvent.change(screen.getByLabelText("类型"), { target: { value: "DIAGNOSIS_TASK" } })

    // 选项出现后再挑一条：下拉里的 value 就是提交时服务端要比对的 taskId
    const taskSelect = (await screen.findByLabelText("判读任务")) as HTMLSelectElement
    await waitFor(() => expect(taskSelect.querySelectorAll("option")).toHaveLength(2))
    fireEvent.change(taskSelect, { target: { value: taskId } })

    fireEvent.click(screen.getByRole("button", { name: /提交反馈/ }))

    await waitFor(() =>
      expect(submitUserFeedback).toHaveBeenCalledWith({
        category: "RESULT_DOUBT",
        content: "结论和我的预期不一致",
        targetType: "DIAGNOSIS_TASK",
        targetId: taskId,
      }),
    )
  })

  it("空列表说清楚「还没有提交过」，不把读取中伪装成空", async () => {
    renderPage()
    expect(await screen.findByText("还没有提交过反馈")).toBeTruthy()
  })
})

describe("FeedbackPage 我的反馈", () => {
  it("显示状态标签、正文与管理员回复", async () => {
    vi.mocked(listMyUserFeedback).mockResolvedValue({
      records: [
        feedback({
          status: "RESOLVED",
          reply: "已修复，感谢反馈",
          repliedAt: "2026-11-23T12:00:00+08:00",
        }),
      ],
      total: 1,
      current: 1,
      pages: 1,
    })

    renderPage()

    expect(await screen.findByText("已解决")).toBeTruthy()
    expect(screen.getByText("上传后一直转圈")).toBeTruthy()
    expect(screen.getByText("已修复，感谢反馈")).toBeTruthy()
  })

  it("未登录时只给登录入口，不发个人请求", () => {
    sessionState.status = "anon"
    renderPage()

    expect(screen.getByText("登录后才能提交反馈")).toBeTruthy()
    expect(listMyUserFeedback).not.toHaveBeenCalled()
  })
})
