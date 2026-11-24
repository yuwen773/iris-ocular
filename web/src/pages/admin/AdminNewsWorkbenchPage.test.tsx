// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"

import { AdminNewsWorkbenchPage } from "./AdminNewsWorkbenchPage"
import { createNewsFromCandidate } from "@/lib/adminApi"
import {
  enqueueWorkbenchTask,
  getWorkbenchCandidateDetail,
  getWorkbenchOverview,
  listTaskLeases,
  listWorkbenchBenchRuns,
  listWorkbenchBudgets,
  listWorkbenchCandidates,
  listWorkbenchReceipts,
  listWorkbenchSources,
  listWorkbenchTasks,
  reconcileWorkbenchReceipt,
  toggleWorkbenchSource,
} from "@/lib/newsWorkbenchApi"

vi.mock("@/lib/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/adminApi")>()
  return {
    ...actual,
    createNewsFromCandidate: vi.fn(),
  }
})

vi.mock("@/lib/newsWorkbenchApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/newsWorkbenchApi")>()
  return {
    ...actual,
    getWorkbenchOverview: vi.fn(),
    listWorkbenchCandidates: vi.fn(),
    getWorkbenchCandidateDetail: vi.fn(),
    listWorkbenchTasks: vi.fn(),
    enqueueWorkbenchTask: vi.fn(),
    listTaskLeases: vi.fn(),
    listWorkbenchSources: vi.fn(),
    toggleWorkbenchSource: vi.fn(),
    listWorkbenchBudgets: vi.fn(),
    listWorkbenchReceipts: vi.fn(),
    reconcileWorkbenchReceipt: vi.fn(),
    listWorkbenchBenchRuns: vi.fn(),
  }
})

function renderWorkbench(initialEntry = "/admin/news-workbench") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AdminNewsWorkbenchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.mocked(getWorkbenchOverview).mockResolvedValue({
    sources: { totalSources: 3, enabledSources: 3 },
    tasks: {
      pendingCount: 1,
      leasedCount: 0,
      retryScheduledCount: 0,
      completedCount: 5,
      failedCount: 0,
      expiredLeaseCount: 0,
    },
    materials: { visibleMaterialCount: 12, visibleRevisionCount: 14 },
    candidates: {
      acceptedCount: 2,
      needsReviewCount: 1,
      rejectedCount: 0,
      evidenceViolationCount: 1,
      needsReconciliationCount: 1,
      draftedNewsCount: 1,
    },
    receipts: {
      unknownOutcomeCount: 1,
      inFlightCount: 0,
      staleInFlightCount: 0,
      succeededCount: 3,
    },
    todayBudget: null,
    curationEnabled: false,
    latestBenchRun: null,
  })
  vi.mocked(listWorkbenchCandidates).mockResolvedValue({
    records: [
      {
        id: "7001",
        materialId: "101",
        materialRevisionId: "201",
        source: "pubmed",
        sourceRecordId: "PMID-1",
        contentDigest: "a".repeat(64),
        policyVersion: "news-curation-v1",
        callId: "call-7001",
        status: "CURATED_ACCEPTED",
        decision: "ACCEPT",
        evidenceTier: "PEER_REVIEWED_ABSTRACT",
        relevanceScore: 91,
        suggestedTitle: "角膜塑形镜近视控制多中心随访",
        suggestedSummary: "摘要总结内容。",
        category: "临床进展",
        claimsCount: 1,
        violationsCount: 0,
        evidenceSnapshotSha256: "b".repeat(64),
        linkedNewsId: null,
        linkedNewsStatus: null,
        createdAt: "2026-10-22T10:00:00Z",
        updatedAt: "2026-10-22T10:00:00Z",
      },
    ],
    total: 1,
    current: 1,
    size: 10,
    pages: 1,
  })
  vi.mocked(getWorkbenchCandidateDetail).mockResolvedValue({
    id: "7001",
    materialId: "101",
    materialRevisionId: "201",
    source: "pubmed",
    sourceRecordId: "PMID-1",
    contentDigest: "a".repeat(64),
    policyVersion: "news-curation-v1",
    callId: "call-7001",
    status: "CURATED_ACCEPTED",
    decision: "ACCEPT",
    evidenceTier: "PEER_REVIEWED_ABSTRACT",
    relevanceScore: 91,
    suggestedTitle: "角膜塑形镜近视控制多中心随访",
    suggestedSummary: "摘要总结内容。",
    category: "临床进展",
    limitations: ["需延长随访"],
    claims: [
      {
        id: "claim-1",
        field: "abstract",
        statement: "Orthokeratology reduced axial elongation",
        quote: "reduced axial elongation over 12 months",
      },
    ],
    violations: [],
    evidenceSnapshotSha256: "b".repeat(64),
    evidenceSnapshotJson: '{"title":"Myopia Trial"}',
    callReceipt: null,
    linkedNewsId: null,
    linkedNewsStatus: null,
    createdAt: "2026-10-22T10:00:00Z",
    updatedAt: "2026-10-22T10:00:00Z",
  })
  vi.mocked(createNewsFromCandidate).mockResolvedValue("9501")
  vi.mocked(listWorkbenchTasks).mockResolvedValue({
    records: [
      {
        taskId: "task-pub-1",
        source: "pubmed",
        sourceConfigVersion: "1",
        queryText: "ophthalmology",
        windowStartOn: "2026-10-01",
        windowEndBefore: "2026-10-02",
        status: "LEASED",
        generation: 2,
        attemptCount: 1,
        maxAttempts: 3,
        leaseWorkerId: "worker-1",
        leaseExpiresAt: "2026-10-01T10:00:00Z",
        leaseExpired: true,
        nextAttemptAt: null,
        lastFailureCode: null,
        committedBatchCount: null,
        committedRecordCount: null,
        committedCursor: null,
        completedAt: null,
        createdAt: "2026-10-01T09:50:00Z",
        updatedAt: "2026-10-01T09:50:00Z",
      },
    ],
    total: 1,
    current: 1,
    size: 10,
    pages: 1,
  })
  vi.mocked(listTaskLeases).mockResolvedValue([])
  vi.mocked(enqueueWorkbenchTask).mockResolvedValue({
    taskId: "manual-task-2",
    source: "pubmed",
    sourceConfigVersion: "1",
    queryText: "myopia",
    windowStartOn: "2026-10-02",
    windowEndBefore: "2026-10-03",
    status: "PENDING",
    generation: 0,
    attemptCount: 0,
    maxAttempts: 3,
    leaseWorkerId: null,
    leaseExpiresAt: null,
    leaseExpired: false,
    nextAttemptAt: null,
    lastFailureCode: null,
    committedBatchCount: null,
    committedRecordCount: null,
    committedCursor: null,
    completedAt: null,
    createdAt: "2026-10-22T12:00:00Z",
    updatedAt: "2026-10-22T12:00:00Z",
  })
  vi.mocked(listWorkbenchSources).mockResolvedValue([
    {
      source: "pubmed",
      enabled: true,
      updatedAt: "2026-10-22T10:00:00Z",
      visibleMaterialCount: 12,
      pendingTaskCount: 0,
      leasedTaskCount: 1,
      failedTaskCount: 0,
      completedTaskCount: 5,
      cursors: [
        {
          sourceConfigVersion: "1",
          queryText: "ophthalmology",
          committedCursor: "2026-10-01",
          updatedAt: "2026-10-22T10:00:00Z",
        },
      ],
    },
  ])
  vi.mocked(toggleWorkbenchSource).mockResolvedValue({
    source: "pubmed",
    enabled: false,
    updatedAt: "2026-10-22T10:05:00Z",
    visibleMaterialCount: 12,
    pendingTaskCount: 0,
    leasedTaskCount: 1,
    failedTaskCount: 0,
    completedTaskCount: 5,
    cursors: [],
  })
  vi.mocked(listWorkbenchBudgets).mockResolvedValue([])
  vi.mocked(listWorkbenchReceipts).mockResolvedValue({
    records: [
      {
        callId: "unk-call-1",
        idempotencyKey: "201:v1:1",
        materialRevisionId: "201",
        policyVersion: "v1",
        attemptNo: 1,
        budgetDate: "2026-10-22",
        budgetScope: "GLOBAL",
        provider: "dashscope",
        modelName: "qwen3.5-plus",
        promptSha256: "c".repeat(64),
        state: "OUTCOME_UNKNOWN",
        reservedTokens: 4000,
        reservedCostMicros: 50000,
        promptTokens: null,
        completionTokens: null,
        actualCostMicros: null,
        httpStatus: 504,
        providerRequestId: null,
        responseSha256: null,
        errorCode: "READ_TIMEOUT",
        latencyMs: 20000,
        reconciledBy: null,
        reconcileNote: null,
        reconciledAt: null,
        reservedAt: "2026-10-22T09:00:00Z",
        dispatchedAt: "2026-10-22T09:00:01Z",
        deadlineAt: "2026-10-22T09:00:21Z",
        completedAt: null,
      },
    ],
    total: 1,
    current: 1,
    size: 10,
    pages: 1,
  })
  vi.mocked(reconcileWorkbenchReceipt).mockResolvedValue({
    callId: "unk-call-1",
    idempotencyKey: "201:v1:1",
    materialRevisionId: "201",
    policyVersion: "v1",
    attemptNo: 1,
    budgetDate: "2026-10-22",
    budgetScope: "GLOBAL",
    provider: "dashscope",
    modelName: "qwen3.5-plus",
    promptSha256: "c".repeat(64),
    state: "FAILED_UNCHARGED",
    reservedTokens: 4000,
    reservedCostMicros: 50000,
    promptTokens: null,
    completionTokens: null,
    actualCostMicros: null,
    httpStatus: 504,
    providerRequestId: null,
    responseSha256: null,
    errorCode: "READ_TIMEOUT",
    latencyMs: 20000,
    reconciledBy: "admin:1001",
    reconcileNote: "No charge",
    reconciledAt: "2026-10-22T12:00:00Z",
    reservedAt: "2026-10-22T09:00:00Z",
    dispatchedAt: "2026-10-22T09:00:01Z",
    deadlineAt: "2026-10-22T09:00:21Z",
    completedAt: "2026-10-22T12:00:00Z",
  })
  vi.mocked(listWorkbenchBenchRuns).mockResolvedValue([
    {
      runId: "nb-1",
      datasetVersion: "v1",
      datasetSha256: "d".repeat(64),
      policyVersion: "news-curation-v1",
      mode: "OFFLINE_FIXTURE",
      totalCases: 18,
      passedCases: 18,
      precisionBps: 10000,
      recallBps: 10000,
      evidenceCatchBps: 10000,
      licenseComplianceBps: 10000,
      injectionCatchBps: 10000,
      totalCalls: 18,
      totalTokens: 14760,
      totalCostMicros: 72000,
      gatePassed: true,
      createdAt: "2026-10-22T11:00:00Z",
    },
  ])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AdminNewsWorkbenchPage", () => {
  it("renders honest curation disabled status, dash for unset budget, and candidate draft flow", async () => {
    renderWorkbench()

    await screen.findByText("在线精选已关闭（仅离线/对账）")
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(2)
    await screen.findByText("角膜塑形镜近视控制多中心随访")

    fireEvent.click(screen.getByRole("button", { name: "查看证据" }))
    await screen.findByText("reduced axial elongation over 12 months")

    fireEvent.click(screen.getByRole("button", { name: "建稿并提交审核" }))
    await waitFor(() => expect(vi.mocked(createNewsFromCandidate)).toHaveBeenCalledWith("7001", true))
    await screen.findByText(/去编辑草稿 #9501/)
  })

  it("supports task enqueue modal and source toggle confirmation", async () => {
    renderWorkbench()

    fireEvent.click(screen.getByRole("tab", { name: "采集任务" }))
    await screen.findByText("task-pub-1")
    expect(screen.getByText("租约已过期")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "手动入队窗口" }))
    fireEvent.click(await screen.findByRole("button", { name: "确认入队" }))
    await waitFor(() => expect(vi.mocked(enqueueWorkbenchTask)).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole("tab", { name: "信源与游标" }))
    await screen.findByRole("button", { name: "停用信源" })
    fireEvent.click(screen.getByRole("button", { name: "停用信源" }))
    fireEvent.click(await screen.findByRole("button", { name: "确认变更" }))
    await waitFor(() => expect(vi.mocked(toggleWorkbenchSource)).toHaveBeenCalledWith("pubmed", false))
  })

  it("reconciles OUTCOME_UNKNOWN receipt and displays NewsBench runs", async () => {
    renderWorkbench()

    fireEvent.click(screen.getByRole("tab", { name: "预算与调用回执" }))
    await screen.findByText("unk-call-1")
    fireEvent.click(screen.getByRole("button", { name: "人工对账" }))
    fireEvent.click(await screen.findByRole("button", { name: "提交对账" }))
    await waitFor(() =>
      expect(vi.mocked(reconcileWorkbenchReceipt)).toHaveBeenCalledWith(
        "unk-call-1",
        expect.objectContaining({ resolution: "CONFIRMED_NOT_CHARGED" }),
      ),
    )

    fireEvent.click(screen.getByRole("tab", { name: "NewsBench 评测" }))
    await screen.findByText("nb-1")
    expect(screen.getByText("18 / 18")).toBeTruthy()
    expect(screen.getByText("100.0% / 100.0% / 100.0%")).toBeTruthy()
  })
})
