// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"

import { AdminNewsWorkbenchPage } from "./AdminNewsWorkbenchPage"
import { createNewsFromCandidate } from "@/lib/adminApi"
import {
  enqueueWorkbenchTask,
  getCollectionSchedule,
  getWorkbenchCandidateDetail,
  getWorkbenchOverview,
  getNewsIssuePreview,
  listCurationMaterials,
  listEvidenceVerifications,
  listMaterialReceipts,
  listTaskEvents,
  listTaskLeases,
  listWorkbenchBenchRuns,
  listWorkbenchBudgets,
  listWorkbenchCandidates,
  listWorkbenchReceipts,
  listWorkbenchSources,
  listWorkbenchTasks,
  materializeCurationCandidate,
  reconcileWorkbenchReceipt,
  retryWorkbenchTask,
  revokeEvidenceVerification,
  runCuration,
  submitEvidenceVerification,
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
    getCollectionSchedule: vi.fn(),
    getNewsIssuePreview: vi.fn(),
    listWorkbenchCandidates: vi.fn(),
    getWorkbenchCandidateDetail: vi.fn(),
    listWorkbenchTasks: vi.fn(),
    enqueueWorkbenchTask: vi.fn(),
    retryWorkbenchTask: vi.fn(),
    listTaskLeases: vi.fn(),
    listTaskEvents: vi.fn(),
    listWorkbenchSources: vi.fn(),
    toggleWorkbenchSource: vi.fn(),
    listWorkbenchBudgets: vi.fn(),
    listWorkbenchReceipts: vi.fn(),
    reconcileWorkbenchReceipt: vi.fn(),
    listWorkbenchBenchRuns: vi.fn(),
    listCurationMaterials: vi.fn(),
    runCuration: vi.fn(),
    listMaterialReceipts: vi.fn(),
    listEvidenceVerifications: vi.fn(),
    submitEvidenceVerification: vi.fn(),
    revokeEvidenceVerification: vi.fn(),
    materializeCurationCandidate: vi.fn(),
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
  vi.mocked(getCollectionSchedule).mockResolvedValue({
    ingestionEnabled: false,
    scheduleEnabled: false,
    sources: [],
    workers: [],
  })
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
        blockingReason: null,
        blockedByTaskId: null,
        retryable: false,
      },
    ],
    total: 1,
    current: 1,
    size: 10,
    pages: 1,
  })
  vi.mocked(listTaskLeases).mockResolvedValue([])
  vi.mocked(listTaskEvents).mockResolvedValue([])
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
    blockingReason: null,
    blockedByTaskId: null,
    retryable: false,
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
  vi.mocked(listCurationMaterials).mockResolvedValue({
    records: [
      {
        materialRevisionId: "201",
        materialId: "101",
        source: "pubmed",
        sourceRecordId: "PMID-1",
        title: "角膜塑形镜近视控制多中心随访",
        publishedOn: "2026-10-01",
        pmid: "12345678",
        doi: "10.1000/xyz",
        license: "CC_BY",
        abstractPresent: true,
        evidenceTier: "PEER_REVIEWED_ABSTRACT",
        eligibleType: "RESEARCH_INSIGHT",
        gateReason: null,
        evidenceGateOpen: true,
        activeEvidenceOverlaySha256: "e".repeat(64),
        candidateId: null,
        candidateStatus: null,
        latestCallId: null,
        latestReceiptState: null,
        latestErrorCode: null,
        curatable: true,
        profileKey: "prod",
      },
    ],
    total: 1,
    current: 1,
    size: 10,
    pages: 1,
  })
  vi.mocked(runCuration).mockResolvedValue({
    callId: "call-prod-1",
    eligibleType: "RESEARCH_INSIGHT",
    state: "SUCCEEDED",
    replayed: false,
    provider: "dashscope",
    modelName: "qwen3.8-max-0902",
    promptTokens: 3200,
    completionTokens: 900,
    actualCostMicros: 71000,
    httpStatus: 200,
    errorCode: null,
    frozenOutputAvailable: true,
    materializable: true,
  })
  vi.mocked(listMaterialReceipts).mockResolvedValue([])
  vi.mocked(listEvidenceVerifications).mockResolvedValue([])
  vi.mocked(submitEvidenceVerification).mockResolvedValue({
    id: "9001",
    materialRevisionId: "201",
    attribute: "PEER_REVIEW",
    value: "VERIFIED",
    method: "PUBMED_RECORD",
    evidenceUrl: "https://pubmed.ncbi.nlm.nih.gov/12345678/",
    sourceVersion: "pubmed-2026",
    evidenceNote: null,
    verifiedBy: "admin:1001",
    verifiedAt: "2026-10-22T12:00:00Z",
    active: true,
    revokedBy: null,
    revokedAt: null,
    revokeNote: null,
  })
  vi.mocked(revokeEvidenceVerification).mockResolvedValue({
    id: "9001",
    materialRevisionId: "201",
    attribute: "PEER_REVIEW",
    value: "VERIFIED",
    method: "PUBMED_RECORD",
    evidenceUrl: "https://pubmed.ncbi.nlm.nih.gov/12345678/",
    sourceVersion: "pubmed-2026",
    evidenceNote: null,
    verifiedBy: "admin:1001",
    verifiedAt: "2026-10-22T12:00:00Z",
    active: false,
    revokedBy: "admin:1001",
    revokedAt: "2026-10-22T12:05:00Z",
    revokeNote: null,
  })
  vi.mocked(materializeCurationCandidate).mockResolvedValue({
    candidateId: "7002",
    status: "CURATED_NEEDS_REVIEW",
    callId: "call-prod-1",
    created: true,
    evidenceVerificationSha256: "f".repeat(64),
    supersededCandidateId: null,
  })
})

it("shows disabled automatic collection and empty worker telemetry in the sources tab", async () => {
  renderWorkbench("/admin/news-workbench?tab=sources")
  expect(await screen.findByText("调度未运行")).toBeTruthy()
  expect(screen.getByText("尚无已认证的领取记录")).toBeTruthy()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AdminNewsWorkbenchPage", () => {
  it("previews a date window with all published source revisions and refreshes it on demand", async () => {
    const article = {
      newsId: "9501",
      revisionNo: 2,
      title: "已发布资讯",
      summary: "已审核的摘要",
      publishTime: "2026-10-22 09:00:00",
      materialRevisionId: "201",
      source: "pubmed",
      sourceRecordId: "PMID-1",
      pmid: "123",
      doi: null,
    }
    vi.mocked(getNewsIssuePreview).mockResolvedValueOnce({
      startOn: "2026-10-22",
      endOn: "2026-10-22",
      articleCount: 2,
      entries: [{
        identifierConflict: false,
        articles: [article, { ...article, newsId: "9502", title: "第二条来源", source: "europe-pmc", materialRevisionId: "202" }],
      }],
    }).mockResolvedValueOnce({
      startOn: "2026-10-22", endOn: "2026-10-22", articleCount: 0, entries: [],
    })

    renderWorkbench("/admin/news-workbench?tab=issue")
    expect(getNewsIssuePreview).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText("开始发布日期"), { target: { value: "2026-10-22" } })
    fireEvent.change(screen.getByLabelText("结束发布日期"), { target: { value: "2026-10-22" } })
    fireEvent.click(screen.getByRole("button", { name: "生成预览" }))

    await screen.findByText(/2 篇已发布资讯 · 1 个条目/)
    expect(getNewsIssuePreview).toHaveBeenCalledWith("2026-10-22", "2026-10-22")
    expect(screen.getByRole("link", { name: "第二条来源" }).getAttribute("href")).toBe("/admin/news/9502/edit")
    expect(screen.getByText(/europe-pmc · PMID-1 · 发布修订 2 · 资料修订 202/)).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "生成预览" }))
    await screen.findByText("该日期范围内暂无可追溯的已发布资讯。")
    expect(screen.queryByRole("link", { name: "第二条来源" })).toBeNull()
  })

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

  it("shows blocking reason and retries a failed collection window", async () => {
    vi.mocked(listWorkbenchTasks).mockResolvedValue({
      records: [
        {
          taskId: "task-failed-1",
          source: "pubmed",
          sourceConfigVersion: "1",
          queryText: "ophthalmology",
          windowStartOn: "2026-10-01",
          windowEndBefore: "2026-10-02",
          status: "FAILED",
          generation: 2,
          attemptCount: 3,
          maxAttempts: 3,
          leaseWorkerId: null,
          leaseExpiresAt: null,
          leaseExpired: false,
          nextAttemptAt: null,
          lastFailureCode: "SOURCE_TIMEOUT",
          committedBatchCount: null,
          committedRecordCount: null,
          committedCursor: null,
          completedAt: null,
          createdAt: "2026-10-01T09:50:00Z",
          updatedAt: "2026-10-01T09:50:00Z",
          blockingReason: "本窗口已失败（SOURCE_TIMEOUT），需从失败窗口重试入口重新打开；系统不会自动跳过",
          blockedByTaskId: null,
          retryable: true,
        },
      ],
      total: 1,
      current: 1,
      size: 10,
      pages: 1,
    })
    vi.mocked(retryWorkbenchTask).mockResolvedValue({
      taskId: "task-failed-1",
      source: "pubmed",
      sourceConfigVersion: "1",
      queryText: "ophthalmology",
      windowStartOn: "2026-10-01",
      windowEndBefore: "2026-10-02",
      status: "PENDING",
      generation: 2,
      attemptCount: 3,
      maxAttempts: 6,
      leaseWorkerId: null,
      leaseExpiresAt: null,
      leaseExpired: false,
      nextAttemptAt: null,
      lastFailureCode: null,
      committedBatchCount: null,
      committedRecordCount: null,
      committedCursor: null,
      completedAt: null,
      createdAt: "2026-10-01T09:50:00Z",
      updatedAt: "2026-10-01T09:50:00Z",
      blockingReason: null,
      blockedByTaskId: null,
      retryable: false,
    })

    renderWorkbench()

    fireEvent.click(screen.getByRole("tab", { name: "采集任务" }))
    await screen.findByText("task-failed-1")
    expect(screen.getByText(/本窗口已失败（SOURCE_TIMEOUT）/)).toBeTruthy()
    expect(screen.getByText(/系统不会自动跳过/)).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "重试" }))
    fireEvent.click(await screen.findByRole("button", { name: "确认重试" }))
    await waitFor(() =>
      expect(vi.mocked(retryWorkbenchTask)).toHaveBeenCalledWith("task-failed-1", {
        maxAttempts: 3,
      }),
    )
    await screen.findByText(/已重新打开失败窗口 task-failed-1/)
  })

  it("runs admin curation from the AI tab, converts the receipt, and records evidence", async () => {
    vi.mocked(getWorkbenchOverview).mockResolvedValue({
      ...(await vi.mocked(getWorkbenchOverview)()),
      curationEnabled: true,
    })

    renderWorkbench("/admin/news-workbench?tab=curation")

    await screen.findByText("角膜塑形镜近视控制多中心随访")
    const runButton = screen.getByRole("button", { name: "运行 AI 精选" }) as HTMLButtonElement
    expect(runButton.disabled).toBe(false)

    fireEvent.click(runButton)
    await screen.findByText("本次精选运行结果")
    expect(vi.mocked(runCuration)).toHaveBeenCalledWith("201")
    expect(screen.getByText(/call-prod-1/)).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "转为待审候选" }))
    await waitFor(() =>
      expect(vi.mocked(materializeCurationCandidate)).toHaveBeenCalledWith("call-prod-1"),
    )
    await screen.findByText(/已生成待审候选 #7002/)

    fireEvent.click(screen.getByRole("button", { name: "证据核验" }))
    await screen.findByText("登记核验 · VERIFY")
    fireEvent.change(screen.getByLabelText("证据链接 · EVIDENCE URL"), {
      target: { value: "https://pubmed.ncbi.nlm.nih.gov/12345678/" },
    })
    fireEvent.click(screen.getByRole("button", { name: "登记证据核验" }))
    await waitFor(() =>
      expect(vi.mocked(submitEvidenceVerification)).toHaveBeenCalledWith(
        "201",
        expect.objectContaining({ attribute: "PEER_REVIEW", method: "PUBMED_RECORD" }),
      ),
    )
  })

  it("keeps the curation run button disabled while curation is off", async () => {
    renderWorkbench("/admin/news-workbench?tab=curation")

    await screen.findByText("角膜塑形镜近视控制多中心随访")
    const button = screen.getByRole("button", { name: "运行 AI 精选" }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(
      screen.getByText(/在线精选当前关闭（eye-diagnosis.news-curation.enabled=false）/),
    ).toBeTruthy()
  })
})
