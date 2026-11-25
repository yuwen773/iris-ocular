import "@/styles/admin-news-workbench.css"

import { useState, type FormEvent, type ReactElement } from "react"
import { Link, useSearchParams } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Layers, Plus, RefreshCw, ShieldCheck } from "lucide-react"

import { AdminEmpty } from "@/components/admin/AdminEmpty"
import { AdminPageHead } from "@/components/admin/AdminPageHead"
import { DataTable, type Column } from "@/components/admin/DataTable"
import { Drawer } from "@/components/admin/Drawer"
import { Modal } from "@/components/admin/Modal"
import { StatTile } from "@/components/admin/StatTile"
import { RiskTag } from "@/components/RiskTag"
import { FaultNote } from "@/components/fault"
import { createNewsFromCandidate } from "@/lib/adminApi"
import { newsStatusView } from "@/lib/adminDomain"
import { describeError, type ErrorDetail } from "@/lib/api"
import { formatTimestamp } from "@/lib/domain"
import { fault } from "@/lib/faults"
import {
  candidateStatusView,
  configureWorkbenchBudget,
  enqueueWorkbenchTask,
  evidenceTierLabel,
  formatBpsPercent,
  formatMicrosUsd,
  getWorkbenchCandidateDetail,
  getWorkbenchOverview,
  listTaskLeases,
  listWorkbenchBenchRuns,
  listWorkbenchBudgets,
  listWorkbenchCandidates,
  listWorkbenchReceipts,
  listWorkbenchSources,
  listWorkbenchTasks,
  receiptStateView,
  reclaimStaleWorkbenchCalls,
  reconcileWorkbenchReceipt,
  registerWorkbenchSourceConfig,
  resetWorkbenchCircuitBreaker,
  taskStatusView,
  toggleWorkbenchSource,
  type BenchRunSummary,
  type BudgetSummary,
  type CallReceipt,
  type CandidateSummary,
  type CollectionTask,
  type SourceDetail,
} from "@/lib/newsWorkbenchApi"
import { queryKeys } from "@/lib/queryKeys"
import { AdminNewsIssuePreview } from "./AdminNewsIssuePreview"

type WorkbenchTab = "candidates" | "tasks" | "sources" | "budgets" | "bench" | "issue"

const TABS: ReadonlyArray<{ key: WorkbenchTab; label: string }> = [
  { key: "candidates", label: "精选候选" },
  { key: "tasks", label: "采集任务" },
  { key: "sources", label: "信源与游标" },
  { key: "budgets", label: "预算与调用回执" },
  { key: "bench", label: "NewsBench 评测" },
  { key: "issue", label: "刊物预览" },
]

const SOURCES = ["pubmed", "europe-pmc", "clinicaltrials-gov"] as const

function readTab(value: string | null): WorkbenchTab {
  return TABS.some((tab) => tab.key === value) ? (value as WorkbenchTab) : "candidates"
}

export function AdminNewsWorkbenchPage(): ReactElement {
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = readTab(searchParams.get("tab"))
  const queryClient = useQueryClient()

  const [actionError, setActionError] = useState<ErrorDetail | null>(null)
  const [actionNote, setActionNote] = useState<string | null>(null)

  const [candStatus, setCandStatus] = useState("")
  const [candSource, setCandSource] = useState("")
  const [candPage, setCandPage] = useState({ current: 1, pageSize: 10 })
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | null>(null)
  const [draftingBusy, setDraftingBusy] = useState(false)
  const [createdNewsId, setCreatedNewsId] = useState<string | null>(null)

  const [taskStatus, setTaskStatus] = useState("")
  const [taskSource, setTaskSource] = useState("")
  const [taskPage, setTaskPage] = useState({ current: 1, pageSize: 10 })
  const [selectedLeaseTaskId, setSelectedLeaseTaskId] = useState<string | null>(null)
  const [enqueueOpen, setEnqueueOpen] = useState(false)
  const [enqueueBusy, setEnqueueBusy] = useState(false)
  const [enqueueForm, setEnqueueForm] = useState({
    taskId: "",
    source: "pubmed",
    sourceConfigVersion: "1",
    query: "ophthalmology OR myopia",
    windowStartOn: "2026-10-01",
    windowEndBefore: "2026-10-02",
    maxAttempts: "3",
  })

  const [toggleTarget, setToggleTarget] = useState<SourceDetail | null>(null)
  const [toggleBusy, setToggleBusy] = useState(false)
  const [configTarget, setConfigTarget] = useState<string | null>(null)
  const [configBusy, setConfigBusy] = useState(false)
  const [configForm, setConfigForm] = useState({
    sourceConfigVersion: "1",
    query: "ophthalmology",
    initialCursor: "",
  })

  const [receiptState, setReceiptState] = useState("")
  const [receiptPage, setReceiptPage] = useState({ current: 1, pageSize: 10 })
  const [budgetOpen, setBudgetOpen] = useState(false)
  const [budgetBusy, setBudgetBusy] = useState(false)
  const [budgetForm, setBudgetForm] = useState({
    budgetDate: new Date().toISOString().slice(0, 10),
    scope: "GLOBAL",
    maxCalls: "50",
    maxTokens: "200000",
    maxCostMicros: "2000000",
  })
  const [resetCircuitTarget, setResetCircuitTarget] = useState<BudgetSummary | null>(null)
  const [resetCircuitBusy, setResetCircuitBusy] = useState(false)
  const [reclaimBusy, setReclaimBusy] = useState(false)

  const [reconcileTarget, setReconcileTarget] = useState<CallReceipt | null>(null)
  const [reconcileBusy, setReconcileBusy] = useState(false)
  const [reconcileForm, setReconcileForm] = useState<{
    resolution: "CONFIRMED_NOT_CHARGED" | "CONFIRMED_CHARGED_NO_OUTPUT" | "CONFIRMED_CHARGED_WITH_OUTPUT"
    note: string
    recoveredRawJson: string
    promptTokens: string
    completionTokens: string
    actualCostMicros: string
    providerRequestId: string
  }>({
    resolution: "CONFIRMED_NOT_CHARGED",
    note: "",
    recoveredRawJson: "",
    promptTokens: "",
    completionTokens: "",
    actualCostMicros: "",
    providerRequestId: "",
  })

  const overviewQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.overview,
    queryFn: getWorkbenchOverview,
  })
  const overview = overviewQuery.data
  const overviewFault = overviewQuery.error ? describeError(overviewQuery.error) : null

  const candidateParams = {
    current: candPage.current,
    pageSize: candPage.pageSize,
    ...(candStatus ? { status: candStatus } : {}),
    ...(candSource ? { source: candSource } : {}),
  }
  const candidatesQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.candidates(candidateParams),
    queryFn: () => listWorkbenchCandidates(candidateParams),
    enabled: activeTab === "candidates",
  })

  const candidateDetailQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.candidateDetail(selectedCandidateId ?? ""),
    queryFn: () => getWorkbenchCandidateDetail(selectedCandidateId!),
    enabled: Boolean(selectedCandidateId),
  })

  const taskParams = {
    current: taskPage.current,
    pageSize: taskPage.pageSize,
    ...(taskStatus ? { status: taskStatus } : {}),
    ...(taskSource ? { source: taskSource } : {}),
  }
  const tasksQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.tasks(taskParams),
    queryFn: () => listWorkbenchTasks(taskParams),
    enabled: activeTab === "tasks",
  })

  const leaseQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.taskLeases(selectedLeaseTaskId ?? ""),
    queryFn: () => listTaskLeases(selectedLeaseTaskId!),
    enabled: Boolean(selectedLeaseTaskId),
  })

  const sourcesQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.sources,
    queryFn: listWorkbenchSources,
    enabled: activeTab === "sources",
  })

  const budgetsQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.budgets(),
    queryFn: () => listWorkbenchBudgets(),
    enabled: activeTab === "budgets",
  })

  const receiptParams = {
    current: receiptPage.current,
    pageSize: receiptPage.pageSize,
    ...(receiptState ? { state: receiptState } : {}),
  }
  const receiptsQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.receipts(receiptParams),
    queryFn: () => listWorkbenchReceipts(receiptParams),
    enabled: activeTab === "budgets",
  })

  const benchQuery = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.benchRuns,
    queryFn: () => listWorkbenchBenchRuns(20),
    enabled: activeTab === "bench",
  })

  function switchTab(next: WorkbenchTab) {
    setActionError(null)
    setActionNote(null)
    setSearchParams({ tab: next }, { replace: true })
  }

  async function refreshAll() {
    await queryClient.invalidateQueries({ queryKey: queryKeys.admin.newsWorkbench.all })
  }

  async function handleCreateDraft(candidateId: string, submitForReview: boolean) {
    if (draftingBusy) return
    setDraftingBusy(true)
    setActionError(null)
    setActionNote(null)
    setCreatedNewsId(null)
    try {
      const newsId = await createNewsFromCandidate(candidateId, submitForReview)
      setCreatedNewsId(newsId)
      setActionNote(
        submitForReview
          ? `已从候选 #${candidateId} 建稿并提交审核，资讯 ID #${newsId}。`
          : `已从候选 #${candidateId} 保存草稿，资讯 ID #${newsId}。`,
      )
      await Promise.all([
        refreshAll(),
        queryClient.invalidateQueries({ queryKey: queryKeys.admin.newsAll }),
      ])
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setDraftingBusy(false)
    }
  }

  async function handleEnqueueTask(event: FormEvent) {
    event.preventDefault()
    if (enqueueBusy) return
    setEnqueueBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      const created = await enqueueWorkbenchTask({
        ...(enqueueForm.taskId.trim() ? { taskId: enqueueForm.taskId.trim() } : {}),
        source: enqueueForm.source,
        sourceConfigVersion: Number(enqueueForm.sourceConfigVersion),
        query: enqueueForm.query.trim(),
        windowStartOn: enqueueForm.windowStartOn.trim(),
        windowEndBefore: enqueueForm.windowEndBefore.trim(),
        maxAttempts: Number(enqueueForm.maxAttempts) || 3,
      })
      setEnqueueOpen(false)
      setActionNote(`已入队采集任务 ${created.taskId}（${created.source}）。`)
      await refreshAll()
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setEnqueueBusy(false)
    }
  }

  async function handleToggleSource() {
    if (!toggleTarget || toggleBusy) return
    setToggleBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      const updated = await toggleWorkbenchSource(toggleTarget.source, !toggleTarget.enabled)
      setToggleTarget(null)
      setActionNote(`信源 ${updated.source} 已${updated.enabled ? "启用" : "停用"}。`)
      await refreshAll()
    } catch (cause) {
      setToggleTarget(null)
      setActionError(describeError(cause))
    } finally {
      setToggleBusy(false)
    }
  }

  async function handleRegisterConfig(event: FormEvent) {
    event.preventDefault()
    if (!configTarget || configBusy) return
    setConfigBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      await registerWorkbenchSourceConfig(configTarget, {
        sourceConfigVersion: Number(configForm.sourceConfigVersion),
        query: configForm.query.trim(),
        ...(configForm.initialCursor.trim()
          ? { initialCursor: configForm.initialCursor.trim() }
          : {}),
      })
      const targetName = configTarget
      setConfigTarget(null)
      setActionNote(`已登记信源 ${targetName} 配置版本 v${configForm.sourceConfigVersion}。`)
      await refreshAll()
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setConfigBusy(false)
    }
  }

  async function handleConfigureBudget(event: FormEvent) {
    event.preventDefault()
    if (budgetBusy) return
    setBudgetBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      const saved = await configureWorkbenchBudget({
        budgetDate: budgetForm.budgetDate.trim(),
        scope: budgetForm.scope.trim() || "GLOBAL",
        maxCalls: Number(budgetForm.maxCalls),
        maxTokens: Number(budgetForm.maxTokens),
        maxCostMicros: Number(budgetForm.maxCostMicros),
      })
      setBudgetOpen(false)
      setActionNote(`已更新 ${saved.budgetDate}（${saved.scope}）日额度上限。`)
      await refreshAll()
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setBudgetBusy(false)
    }
  }

  async function handleResetCircuit() {
    if (!resetCircuitTarget || resetCircuitBusy) return
    setResetCircuitBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      const saved = await resetWorkbenchCircuitBreaker({
        budgetDate: resetCircuitTarget.budgetDate,
        scope: resetCircuitTarget.scope,
      })
      setResetCircuitTarget(null)
      setActionNote(`已解除 ${saved.budgetDate}（${saved.scope}）鉴权熔断。`)
      await refreshAll()
    } catch (cause) {
      setResetCircuitTarget(null)
      setActionError(describeError(cause))
    } finally {
      setResetCircuitBusy(false)
    }
  }

  async function handleReclaimStale() {
    if (reclaimBusy) return
    setReclaimBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      const result = await reclaimStaleWorkbenchCalls()
      setActionNote(
        `超时回执回收完成：未出站释放 ${result.abortedPreIoCount} 条，转入 OUTCOME_UNKNOWN ${result.markedUnknownCount} 条。`,
      )
      await refreshAll()
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setReclaimBusy(false)
    }
  }

  async function handleReconcile(event: FormEvent) {
    event.preventDefault()
    if (!reconcileTarget || reconcileBusy) return
    setReconcileBusy(true)
    setActionError(null)
    setActionNote(null)
    try {
      const needsUsage = reconcileForm.resolution !== "CONFIRMED_NOT_CHARGED"
      const updated = await reconcileWorkbenchReceipt(reconcileTarget.callId, {
        resolution: reconcileForm.resolution,
        ...(reconcileForm.note.trim() ? { note: reconcileForm.note.trim() } : {}),
        ...(reconcileForm.resolution === "CONFIRMED_CHARGED_WITH_OUTPUT"
          ? { recoveredRawJson: reconcileForm.recoveredRawJson }
          : {}),
        ...(needsUsage
          ? {
              promptTokens: Number(reconcileForm.promptTokens),
              completionTokens: Number(reconcileForm.completionTokens),
              actualCostMicros: Number(reconcileForm.actualCostMicros),
            }
          : {}),
        ...(reconcileForm.providerRequestId.trim()
          ? { providerRequestId: reconcileForm.providerRequestId.trim() }
          : {}),
      })
      setReconcileTarget(null)
      setActionNote(`回执 ${updated.callId} 已对账为 ${updated.state}。`)
      await refreshAll()
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setReconcileBusy(false)
    }
  }

  const candidateColumns: Column<CandidateSummary>[] = [
    {
      key: "suggestedTitle",
      title: "候选标题 / 资料标识",
      width: "34%",
      render: (row) => (
        <div>
          <strong>{row.suggestedTitle || "（未生成建议标题）"}</strong>
          <div className="admin-workbench__mono">
            {row.source} · {row.sourceRecordId}
          </div>
        </div>
      ),
    },
    {
      key: "status",
      title: "精选状态 / 证据等级",
      width: "20%",
      render: (row) => {
        const view = candidateStatusView(row.status)
        return (
          <div>
            <RiskTag risk={view.risk}>{view.label}</RiskTag>
            <div className="admin-workbench__mono">{evidenceTierLabel(row.evidenceTier)}</div>
          </div>
        )
      },
    },
    {
      key: "claimsCount",
      title: "声明 / 违规",
      width: "14%",
      mono: true,
      render: (row) => `${row.claimsCount} / ${row.violationsCount}`,
    },
    {
      key: "linkedNewsId",
      title: "关联新闻",
      width: "18%",
      render: (row) =>
        row.linkedNewsId ? (
          <div>
            <Link to={`/admin/news/${row.linkedNewsId}/edit`} className="admin-workbench__mono">
              #{row.linkedNewsId}
            </Link>{" "}
            <RiskTag risk={newsStatusView(row.linkedNewsStatus).risk}>
              {newsStatusView(row.linkedNewsStatus).label}
            </RiskTag>
          </div>
        ) : (
          "未建稿"
        ),
    },
    {
      key: "action",
      title: "操作",
      width: "14%",
      render: (row) => (
        <button
          type="button"
          className="admin-btn admin-btn--ghost admin-btn--sm"
          onClick={() => {
            setCreatedNewsId(null)
            setActionError(null)
            setSelectedCandidateId(row.id)
          }}
        >
          查看证据
        </button>
      ),
    },
  ]

  const taskColumns: Column<CollectionTask>[] = [
    {
      key: "taskId",
      title: "任务 ID / 信源",
      width: "28%",
      render: (row) => (
        <div>
          <span className="admin-workbench__mono">{row.taskId}</span>
          <div className="admin-workbench__mono">
            {row.source} · cfg v{row.sourceConfigVersion}
          </div>
        </div>
      ),
    },
    {
      key: "windowStartOn",
      title: "采集窗口",
      width: "18%",
      mono: true,
      render: (row) => `${row.windowStartOn} → ${row.windowEndBefore}`,
    },
    {
      key: "status",
      title: "状态 / 租约",
      width: "20%",
      render: (row) => {
        const view = taskStatusView(row.status)
        return (
          <div>
            <RiskTag risk={view.risk}>{view.label}</RiskTag>{" "}
            {row.leaseExpired ? <RiskTag risk="critical">租约已过期</RiskTag> : null}
            {row.lastFailureCode ? (
              <div className="admin-workbench__mono">{row.lastFailureCode}</div>
            ) : null}
          </div>
        )
      },
    },
    {
      key: "generation",
      title: "世代 / 尝试",
      width: "18%",
      mono: true,
      render: (row) =>
        `gen ${row.generation} · ${row.attemptCount}/${row.maxAttempts}` +
        (row.leaseWorkerId ? ` (${row.leaseWorkerId})` : ""),
    },
    {
      key: "action",
      title: "操作",
      width: "16%",
      render: (row) => (
        <button
          type="button"
          className="admin-btn admin-btn--ghost admin-btn--sm"
          onClick={() => setSelectedLeaseTaskId(row.taskId)}
        >
          租约历史
        </button>
      ),
    },
  ]

  const budgetColumns: Column<BudgetSummary>[] = [
    { key: "budgetDate", title: "UTC 日期", width: "14%", mono: true },
    { key: "scope", title: "作用域", width: "12%", mono: true },
    {
      key: "maxCalls",
      title: "调用 (用/预/限)",
      width: "18%",
      mono: true,
      render: (b) => `${b.usedCalls} / ${b.reservedCalls} / ${b.maxCalls}`,
    },
    {
      key: "maxCostMicros",
      title: "费用 (用/预/限)",
      width: "26%",
      mono: true,
      render: (b) =>
        `${formatMicrosUsd(b.usedCostMicros)} / ${formatMicrosUsd(b.reservedCostMicros)} / ${formatMicrosUsd(b.maxCostMicros)}`,
    },
    {
      key: "circuitBroken",
      title: "熔断态",
      width: "16%",
      render: (b) => (
        <div>
          <RiskTag risk={b.circuitBroken ? "critical" : "normal"}>
            {b.circuitBroken ? "已熔断" : "正常"}
          </RiskTag>
          {b.circuitReason ? <div className="admin-workbench__mono">{b.circuitReason}</div> : null}
        </div>
      ),
    },
    {
      key: "action",
      title: "操作",
      width: "14%",
      render: (b) =>
        b.circuitBroken ? (
          <button
            type="button"
            className="admin-btn admin-btn--danger admin-btn--sm"
            onClick={() => setResetCircuitTarget(b)}
          >
            解除熔断
          </button>
        ) : (
          "—"
        ),
    },
  ]

  const receiptColumns: Column<CallReceipt>[] = [
    {
      key: "callId",
      title: "回执 ID / 幂等键",
      width: "26%",
      render: (r) => (
        <div>
          <div className="admin-workbench__mono">{r.callId}</div>
          <div className="admin-workbench__mono">{r.idempotencyKey}</div>
        </div>
      ),
    },
    {
      key: "state",
      title: "状态",
      width: "16%",
      render: (r) => {
        const view = receiptStateView(r.state)
        return (
          <div>
            <RiskTag risk={view.risk}>{view.label}</RiskTag>
            {r.errorCode ? <div className="admin-workbench__mono">{r.errorCode}</div> : null}
          </div>
        )
      },
    },
    {
      key: "actualCostMicros",
      title: "实付 / 预扣",
      width: "20%",
      mono: true,
      render: (r) =>
        `${formatMicrosUsd(r.actualCostMicros)} / ${formatMicrosUsd(r.reservedCostMicros)}`,
    },
    {
      key: "reconciledBy",
      title: "对账人 / 时间",
      width: "22%",
      render: (r) =>
        r.reconciledBy ? (
          <div className="admin-workbench__mono">
            {r.reconciledBy} · {formatTimestamp(r.reconciledAt ?? undefined)}
          </div>
        ) : (
          "—"
        ),
    },
    {
      key: "action",
      title: "操作",
      width: "16%",
      render: (r) =>
        r.state === "OUTCOME_UNKNOWN" ? (
          <button
            type="button"
            className="admin-btn admin-btn--primary admin-btn--sm"
            onClick={() => {
              setReconcileForm({
                resolution: "CONFIRMED_NOT_CHARGED",
                note: "",
                recoveredRawJson: "",
                promptTokens: "",
                completionTokens: "",
                actualCostMicros: "",
                providerRequestId: r.providerRequestId ?? "",
              })
              setReconcileTarget(r)
            }}
          >
            人工对账
          </button>
        ) : (
          "—"
        ),
    },
  ]

  const benchColumns: Column<BenchRunSummary>[] = [
    {
      key: "runId",
      title: "Run ID / 模式",
      width: "24%",
      render: (r) => (
        <div>
          <div className="admin-workbench__mono">{r.runId}</div>
          <div className="admin-workbench__mono">{r.mode}</div>
        </div>
      ),
    },
    {
      key: "datasetVersion",
      title: "数据集 / 策略",
      width: "20%",
      mono: true,
      render: (r) => `${r.datasetVersion} · ${r.policyVersion}`,
    },
    {
      key: "passedCases",
      title: "通过 / 总数",
      width: "14%",
      mono: true,
      render: (r) => `${r.passedCases} / ${r.totalCases}`,
    },
    {
      key: "precisionBps",
      title: "精准 / 召回 / 证据拦截",
      width: "24%",
      mono: true,
      render: (r) =>
        `${formatBpsPercent(r.precisionBps)} / ${formatBpsPercent(r.recallBps)} / ${formatBpsPercent(r.evidenceCatchBps)}`,
    },
    {
      key: "gatePassed",
      title: "门禁结论",
      width: "18%",
      render: (r) => (
        <RiskTag risk={r.gatePassed ? "normal" : "critical"}>
          {r.gatePassed ? "通过" : "未通过"}
        </RiskTag>
      ),
    },
  ]

  const selectedDetail = candidateDetailQuery.data
  const detailFault = candidateDetailQuery.error ? describeError(candidateDetailQuery.error) : null

  return (
    <div className="admin-workbench">
      <AdminPageHead
        code="08"
        title="资讯引擎工作台"
        eyebrow="ADMIN · NEWS ENGINE"
        description="基于 WP-02 至 WP-04 已持久化数据进行任务追踪、信源/预算治理、候选证据审查、未知回执对账与 NewsBench 只读审阅。"
        meta={
          <span className="admin-workbench__statusbadge">
            {overview ? (
              <RiskTag risk={overview.curationEnabled ? "normal" : "attention"}>
                {overview.curationEnabled ? "在线精选已启用" : "在线精选已关闭（仅离线/对账）"}
              </RiskTag>
            ) : (
              "引擎状态读取中…"
            )}
          </span>
        }
        actions={
          <button type="button" className="admin-btn admin-btn--ghost" onClick={() => void refreshAll()}>
            <RefreshCw size={14} strokeWidth={2} aria-hidden="true" />
            刷新数据
          </button>
        }
      />

      {overviewFault ? (
        <FaultNote
          fault={fault({
            severity: "attention",
            kind: "client",
            title: overviewFault.text,
            code: overviewFault.code,
          })}
        />
      ) : null}

      <section className="admin-workbench__tiles" aria-label="引擎总览读数">
        <StatTile
          label="采集任务完成数"
          value={overview ? String(overview.tasks.completedCount) : "—"}
          delta={
            overview
              ? `待领 ${overview.tasks.pendingCount} · 租约中 ${overview.tasks.leasedCount} · 过期 ${overview.tasks.expiredLeaseCount}`
              : undefined
          }
          tone={overview && overview.tasks.expiredLeaseCount > 0 ? "attention" : undefined}
          toneLabel={overview && overview.tasks.expiredLeaseCount > 0 ? "含过期租约" : undefined}
        />
        <StatTile
          label="有效精选候选"
          value={
            overview
              ? String(overview.candidates.acceptedCount + overview.candidates.needsReviewCount)
              : "—"
          }
          delta={
            overview
              ? `通过 ${overview.candidates.acceptedCount} · 待复核 ${overview.candidates.needsReviewCount} · 已建稿 ${overview.candidates.draftedNewsCount}`
              : undefined
          }
        />
        <StatTile
          label="今日全局预算（已用+预扣）"
          value={
            overview?.todayBudget
              ? formatMicrosUsd(
                  overview.todayBudget.usedCostMicros + overview.todayBudget.reservedCostMicros,
                )
              : "—"
          }
          delta={
            overview
              ? overview.todayBudget
                ? `未知回执 ${overview.receipts.unknownOutcomeCount} · 调用 ${overview.todayBudget.usedCalls}/${overview.todayBudget.maxCalls}`
                : `未知回执 ${overview.receipts.unknownOutcomeCount} · 今日尚未配置额度`
              : undefined
          }
          tone={
            overview?.todayBudget?.circuitBroken
              ? "critical"
              : overview && overview.receipts.unknownOutcomeCount > 0
                ? "attention"
                : undefined
          }
          toneLabel={
            overview?.todayBudget?.circuitBroken
              ? "鉴权熔断"
              : overview && overview.receipts.unknownOutcomeCount > 0
                ? "待人工对账"
                : undefined
          }
        />
        <StatTile
          label="最近 NewsBench"
          value={
            overview?.latestBenchRun
              ? `${overview.latestBenchRun.passedCases}/${overview.latestBenchRun.totalCases}`
              : "—"
          }
          delta={
            overview?.latestBenchRun
              ? `精准 ${formatBpsPercent(overview.latestBenchRun.precisionBps)} · 证据拦截 ${formatBpsPercent(overview.latestBenchRun.evidenceCatchBps)}`
              : "暂无落库评测运行"
          }
          tone={
            overview?.latestBenchRun
              ? overview.latestBenchRun.gatePassed
                ? "normal"
                : "critical"
              : undefined
          }
          toneLabel={
            overview?.latestBenchRun
              ? overview.latestBenchRun.gatePassed
                ? "门禁通过"
                : "门禁未通过"
              : undefined
          }
        />
      </section>

      {actionError ? (
        <FaultNote
          fault={fault({
            severity: "attention",
            kind: "client",
            title: actionError.text,
            code: actionError.code,
          })}
        />
      ) : null}

      {actionNote ? <p className="admin-workbench__note num">{actionNote}</p> : null}

      <div className="admin-workbench__tabsrow">
        <div className="admin-workbench__tabs" role="tablist" aria-label="资讯引擎工作台视图">
          {TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.key}
              className="admin-workbench__tab"
              onClick={() => switchTab(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {activeTab === "candidates" ? (
        <>
          <div className="admin-workbench__toolbar">
            <div className="admin-workbench__filters">
              <select
                className="admin-workbench__select"
                aria-label="候选状态筛选"
                value={candStatus}
                onChange={(e) => {
                  setCandStatus(e.target.value)
                  setCandPage((p) => ({ ...p, current: 1 }))
                }}
              >
                <option value="">全部候选状态</option>
                <option value="CURATED_ACCEPTED">已通过 (CURATED_ACCEPTED)</option>
                <option value="CURATED_NEEDS_REVIEW">需复核 (CURATED_NEEDS_REVIEW)</option>
                <option value="REJECTED_EVIDENCE_VIOLATION">证据违规</option>
                <option value="CURATED_REJECTED">已淘汰</option>
                <option value="NEEDS_RECONCILIATION">待对账</option>
              </select>
              <select
                className="admin-workbench__select"
                aria-label="候选信源筛选"
                value={candSource}
                onChange={(e) => {
                  setCandSource(e.target.value)
                  setCandPage((p) => ({ ...p, current: 1 }))
                }}
              >
                <option value="">全部信源</option>
                {SOURCES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <DataTable<CandidateSummary>
            columns={candidateColumns}
            rows={candidatesQuery.data?.records}
            rowKey={(row) => row.id}
            loading={candidatesQuery.isPending}
            error={candidatesQuery.error ? describeError(candidatesQuery.error).text : null}
            emptyContent={
              <AdminEmpty
                icon={Layers}
                title="暂无精选候选记录"
                description="当完成原始资料精选或离线对账核销后，候选证据条目会显示在这里。"
              />
            }
            page={{
              current: candPage.current,
              pageSize: candPage.pageSize,
              total: candidatesQuery.data?.total ?? 0,
            }}
            onPageChange={setCandPage}
          />
        </>
      ) : null}

      {activeTab === "tasks" ? (
        <>
          <div className="admin-workbench__toolbar">
            <div className="admin-workbench__filters">
              <select
                className="admin-workbench__select"
                aria-label="任务状态筛选"
                value={taskStatus}
                onChange={(e) => {
                  setTaskStatus(e.target.value)
                  setTaskPage((p) => ({ ...p, current: 1 }))
                }}
              >
                <option value="">全部任务状态</option>
                <option value="PENDING">待领取 (PENDING)</option>
                <option value="LEASED">租约中 (LEASED)</option>
                <option value="RETRY_SCHEDULED">待重试</option>
                <option value="COMPLETED">已完成</option>
                <option value="FAILED">已失败</option>
              </select>
              <select
                className="admin-workbench__select"
                aria-label="任务信源筛选"
                value={taskSource}
                onChange={(e) => {
                  setTaskSource(e.target.value)
                  setTaskPage((p) => ({ ...p, current: 1 }))
                }}
              >
                <option value="">全部信源</option>
                {SOURCES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="button"
              className="admin-btn admin-btn--primary"
              onClick={() => setEnqueueOpen(true)}
            >
              <Plus size={14} strokeWidth={2} aria-hidden="true" />
              手动入队窗口
            </button>
          </div>

          <DataTable<CollectionTask>
            columns={taskColumns}
            rows={tasksQuery.data?.records}
            rowKey={(row) => row.taskId}
            loading={tasksQuery.isPending}
            error={tasksQuery.error ? describeError(tasksQuery.error).text : null}
            emptyContent={
              <AdminEmpty
                icon={Layers}
                title="暂无采集窗口任务"
                description="可通过「手动入队窗口」添加待调度采集时间窗。"
              />
            }
            page={{
              current: taskPage.current,
              pageSize: taskPage.pageSize,
              total: tasksQuery.data?.total ?? 0,
            }}
            onPageChange={setTaskPage}
          />
        </>
      ) : null}

      {activeTab === "sources" ? (
        <section className="admin-workbench__sources" aria-label="信源开关与游标配置">
          {sourcesQuery.isPending ? (
            <p className="admin-workbench__note">正在加载信源配置…</p>
          ) : sourcesQuery.error ? (
            <FaultNote
              fault={fault({
                severity: "attention",
                kind: "client",
                title: describeError(sourcesQuery.error).text,
                code: describeError(sourcesQuery.error).code,
              })}
            />
          ) : (
            (sourcesQuery.data ?? []).map((src) => (
              <article key={src.source} className="admin-workbench__sourcecard">
                <div className="admin-workbench__sourcehead">
                  <h2 className="admin-workbench__sourcename">{src.source}</h2>
                  <RiskTag risk={src.enabled ? "normal" : "attention"}>
                    {src.enabled ? "已启用" : "已停用"}
                  </RiskTag>
                </div>

                <dl className="admin-workbench__facts">
                  <div className="admin-workbench__factrow">
                    <dt className="admin-workbench__factkey">可见原始资料</dt>
                    <dd className="admin-workbench__factval num">{src.visibleMaterialCount}</dd>
                  </div>
                  <div className="admin-workbench__factrow">
                    <dt className="admin-workbench__factkey">任务（待领 / 租约 / 失败 / 完成）</dt>
                    <dd className="admin-workbench__factval num">
                      {src.pendingTaskCount} / {src.leasedTaskCount} / {src.failedTaskCount} /{" "}
                      {src.completedTaskCount}
                    </dd>
                  </div>
                </dl>

                <div>
                  <p className="admin-workbench__sectiontitle">配置与提交游标 · CURSORS</p>
                  {src.cursors.length === 0 ? (
                    <p className="admin-workbench__note">尚未登记查询配置</p>
                  ) : (
                    <ul className="admin-workbench__cursorlist">
                      {src.cursors.map((c) => (
                        <li key={c.sourceConfigVersion} className="admin-workbench__cursoritem">
                          <span className="admin-workbench__mono">
                            v{c.sourceConfigVersion} · 游标：{c.committedCursor || "未推进"}
                          </span>
                          <span>{c.queryText}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                <div className="admin-workbench__sourceactions">
                  <button
                    type="button"
                    className={
                      src.enabled ? "admin-btn admin-btn--ghost admin-btn--sm" : "admin-btn admin-btn--primary admin-btn--sm"
                    }
                    onClick={() => setToggleTarget(src)}
                  >
                    {src.enabled ? "停用信源" : "启用信源"}
                  </button>
                  <button
                    type="button"
                    className="admin-btn admin-btn--ghost admin-btn--sm"
                    onClick={() => {
                      setConfigForm({
                        sourceConfigVersion: String((Number(src.cursors[0]?.sourceConfigVersion ?? 0) || 0) + 1),
                        query: src.cursors[0]?.queryText ?? "ophthalmology",
                        initialCursor: "",
                      })
                      setConfigTarget(src.source)
                    }}
                  >
                    登记配置版本
                  </button>
                </div>
              </article>
            ))
          )}
        </section>
      ) : null}

      {activeTab === "budgets" ? (
        <>
          <div className="admin-workbench__toolbar">
            <div className="admin-workbench__filters">
              <button
                type="button"
                className="admin-btn admin-btn--primary admin-btn--sm"
                onClick={() => setBudgetOpen(true)}
              >
                配置额度
              </button>
              <button
                type="button"
                className="admin-btn admin-btn--ghost admin-btn--sm"
                disabled={reclaimBusy}
                onClick={() => void handleReclaimStale()}
              >
                {reclaimBusy ? "回收中…" : "回收超时调用"}
              </button>
            </div>
            <select
              className="admin-workbench__select"
              aria-label="回执状态筛选"
              value={receiptState}
              onChange={(e) => {
                setReceiptState(e.target.value)
                setReceiptPage((p) => ({ ...p, current: 1 }))
              }}
            >
              <option value="">全部回执状态</option>
              <option value="OUTCOME_UNKNOWN">结果未知 (OUTCOME_UNKNOWN)</option>
              <option value="SUCCEEDED">已成功 (SUCCEEDED)</option>
              <option value="RESERVED">已预扣 (RESERVED)</option>
              <option value="DISPATCHED">在途 (DISPATCHED)</option>
              <option value="ABORTED_PRE_IO">未出站终止</option>
              <option value="FAILED_DEFINITIVE">调用失败</option>
              <option value="FAILED_CHARGED">已扣费失败</option>
              <option value="FAILED_UNCHARGED">未扣费失败</option>
            </select>
          </div>

          <section aria-label="日预算与熔断表">
            <h2 className="admin-workbench__paneltitle">日精选预算与鉴权熔断</h2>
            <DataTable<BudgetSummary>
              columns={budgetColumns}
              rows={budgetsQuery.data}
              rowKey={(b) => `${b.budgetDate}:${b.scope}`}
              loading={budgetsQuery.isPending}
              error={budgetsQuery.error ? describeError(budgetsQuery.error).text : null}
              hidePager
              emptyText="暂无预算配置记录"
              page={{ current: 1, pageSize: 14, total: budgetsQuery.data?.length ?? 0 }}
              onPageChange={() => {}}
            />
          </section>

          <section aria-label="模型调用回执台账">
            <h2 className="admin-workbench__paneltitle">付费模型调用回执与人工对账</h2>
            <DataTable<CallReceipt>
              columns={receiptColumns}
              rows={receiptsQuery.data?.records}
              rowKey={(r) => r.callId}
              loading={receiptsQuery.isPending}
              error={receiptsQuery.error ? describeError(receiptsQuery.error).text : null}
              emptyText="暂无模型调用回执记录"
              page={{
                current: receiptPage.current,
                pageSize: receiptPage.pageSize,
                total: receiptsQuery.data?.total ?? 0,
              }}
              onPageChange={setReceiptPage}
            />
          </section>
        </>
      ) : null}

      {activeTab === "bench" ? (
        <DataTable<BenchRunSummary>
          columns={benchColumns}
          rows={benchQuery.data}
          rowKey={(r) => r.runId}
          loading={benchQuery.isPending}
          error={benchQuery.error ? describeError(benchQuery.error).text : null}
          hidePager
          emptyContent={
            <AdminEmpty
              icon={ShieldCheck}
              title="暂无 NewsBench 评测运行记录"
              description="通过离线测试或受权评测命令运行 NewsBench 后，门禁结果将展示在此处。"
            />
          }
          page={{ current: 1, pageSize: 20, total: benchQuery.data?.length ?? 0 }}
          onPageChange={() => {}}
        />
      ) : null}

      {activeTab === "issue" ? <AdminNewsIssuePreview /> : null}

      <p className="admin-workbench__footer">
        边界声明：工作台不开放实时付费 LLM 触发或外部网络抓取入口；所有发布门禁仍由 WP-04 服务层强制执行。
      </p>

      {/* 候选证据审查 Drawer */}
      <Drawer
        open={Boolean(selectedCandidateId)}
        title="精选候选证据审查"
        size="workspace"
        onClose={() => {
          setSelectedCandidateId(null)
          setCreatedNewsId(null)
        }}
      >
        {candidateDetailQuery.isPending ? (
          <p className="admin-workbench__note">正在读取冻结证据快照…</p>
        ) : detailFault ? (
          <FaultNote
            fault={fault({
              severity: "attention",
              kind: "client",
              title: detailFault.text,
              code: detailFault.code,
            })}
          />
        ) : selectedDetail ? (
          <div className="admin-workbench__drawerstack">
            <dl className="admin-workbench__facts">
              <div className="admin-workbench__factrow">
                <dt className="admin-workbench__factkey">候选 ID / 状态</dt>
                <dd className="admin-workbench__factval">
                  <span className="admin-workbench__mono">#{selectedDetail.id}</span>{" "}
                  <RiskTag risk={candidateStatusView(selectedDetail.status).risk}>
                    {candidateStatusView(selectedDetail.status).label}
                  </RiskTag>
                </dd>
              </div>
              <div className="admin-workbench__factrow">
                <dt className="admin-workbench__factkey">资料来源 / 修订 ID</dt>
                <dd className="admin-workbench__factval admin-workbench__mono">
                  {selectedDetail.source} · {selectedDetail.sourceRecordId} (rev {selectedDetail.materialRevisionId})
                </dd>
              </div>
              <div className="admin-workbench__factrow">
                <dt className="admin-workbench__factkey">证据快照 SHA-256</dt>
                <dd className="admin-workbench__factval admin-workbench__mono">
                  {selectedDetail.evidenceSnapshotSha256}
                </dd>
              </div>
            </dl>

            <div className="admin-workbench__draweractions">
              {selectedDetail.linkedNewsId ? (
                <Link
                  to={`/admin/news/${selectedDetail.linkedNewsId}/edit`}
                  className="admin-btn admin-btn--primary"
                >
                  打开已关联新闻 (#{selectedDetail.linkedNewsId})
                </Link>
              ) : (
                <>
                  <button
                    type="button"
                    className="admin-btn admin-btn--ghost"
                    disabled={draftingBusy}
                    onClick={() => void handleCreateDraft(selectedDetail.id, false)}
                  >
                    保存为草稿
                  </button>
                  <button
                    type="button"
                    className="admin-btn admin-btn--primary"
                    disabled={draftingBusy}
                    onClick={() => void handleCreateDraft(selectedDetail.id, true)}
                  >
                    建稿并提交审核
                  </button>
                </>
              )}
              {createdNewsId ? (
                <Link to={`/admin/news/${createdNewsId}/edit`} className="admin-btn admin-btn--ghost">
                  去编辑草稿 #{createdNewsId}
                </Link>
              ) : null}
            </div>

            <div className="admin-workbench__section">
              <h3 className="admin-workbench__sectiontitle">结构化声明与逐字引文 · CLAIMS</h3>
              {selectedDetail.claims.length === 0 ? (
                <p className="admin-workbench__note">无绑定声明</p>
              ) : (
                <ul className="admin-workbench__claimlist">
                  {selectedDetail.claims.map((claim) => (
                    <li key={claim.id} className="admin-workbench__claimcard">
                      <span className="admin-workbench__mono">
                        {claim.id} · 字段：{claim.field}
                      </span>
                      <strong>{claim.statement}</strong>
                      <blockquote className="admin-workbench__quote">{claim.quote}</blockquote>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {selectedDetail.limitations.length > 0 ? (
              <div className="admin-workbench__section">
                <h3 className="admin-workbench__sectiontitle">临床局限性 · LIMITATIONS</h3>
                <ul className="admin-workbench__claimlist">
                  {selectedDetail.limitations.map((item, idx) => (
                    <li key={idx} className="admin-workbench__note">
                      • {item}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {selectedDetail.violations.length > 0 ? (
              <div className="admin-workbench__section">
                <h3 className="admin-workbench__sectiontitle">门禁违规记录 · VIOLATIONS</h3>
                <ul className="admin-workbench__claimlist">
                  {selectedDetail.violations.map((v, idx) => (
                    <li key={idx} className="admin-workbench__claimcard">
                      <RiskTag risk="critical">{v.code}</RiskTag>
                      <span className="admin-workbench__mono">{v.target}</span>
                      <span>{v.message}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            <div className="admin-workbench__section">
              <h3 className="admin-workbench__sectiontitle">冻结原始证据快照 · EVIDENCE JSON</h3>
              <pre className="admin-workbench__pre">{selectedDetail.evidenceSnapshotJson}</pre>
            </div>
          </div>
        ) : null}
      </Drawer>

      {/* 采集任务租约历史 Drawer */}
      <Drawer
        open={Boolean(selectedLeaseTaskId)}
        title={`租约历史 · ${selectedLeaseTaskId ?? ""}`}
        onClose={() => setSelectedLeaseTaskId(null)}
      >
        {leaseQuery.isPending ? (
          <p className="admin-workbench__note">正在读取租约记录…</p>
        ) : leaseQuery.error ? (
          <FaultNote
            fault={fault({
              severity: "attention",
              kind: "client",
              title: describeError(leaseQuery.error).text,
              code: describeError(leaseQuery.error).code,
            })}
          />
        ) : (leaseQuery.data ?? []).length === 0 ? (
          <p className="admin-workbench__note">该任务尚未产生领取租约记录。</p>
        ) : (
          <ul className="admin-workbench__revlist">
            {(leaseQuery.data ?? []).map((lease) => (
              <li key={`${lease.taskId}-${lease.generation}`} className="admin-workbench__revcard">
                <span className="admin-workbench__mono">
                  世代 {lease.generation} · Worker：{lease.workerId}
                </span>
                <span className="admin-workbench__mono">
                  领取时间：{formatTimestamp(lease.claimedAt ?? undefined)}
                </span>
                <span className="admin-workbench__mono">
                  过期时间：{formatTimestamp(lease.leaseExpiresAt ?? undefined)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Drawer>

      {/* 手动入队窗口 Modal */}
      <Modal
        open={enqueueOpen}
        title="手动入队采集窗口"
        onClose={() => !enqueueBusy && setEnqueueOpen(false)}
        footer={
          <>
            <button
              type="button"
              className="admin-btn admin-btn--ghost"
              onClick={() => setEnqueueOpen(false)}
              disabled={enqueueBusy}
            >
              取消
            </button>
            <button
              type="submit"
              form="enqueue-task-form"
              className="admin-btn admin-btn--primary"
              disabled={enqueueBusy}
            >
              {enqueueBusy ? "提交中…" : "确认入队"}
            </button>
          </>
        }
      >
        <form id="enqueue-task-form" className="admin-workbench__formgrid" onSubmit={handleEnqueueTask}>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">信源 · SOURCE</span>
            <select
              className="admin-workbench__select"
              value={enqueueForm.source}
              onChange={(e) => setEnqueueForm((f) => ({ ...f, source: e.target.value }))}
            >
              {SOURCES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">配置版本 · CONFIG VERSION</span>
            <input
              className="admin-workbench__input"
              type="number"
              min={1}
              required
              value={enqueueForm.sourceConfigVersion}
              onChange={(e) =>
                setEnqueueForm((f) => ({ ...f, sourceConfigVersion: e.target.value }))
              }
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">检索表达式 · QUERY</span>
            <input
              className="admin-workbench__input"
              type="text"
              required
              value={enqueueForm.query}
              onChange={(e) => setEnqueueForm((f) => ({ ...f, query: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">窗口起 · WINDOW START (YYYY-MM-DD)</span>
            <input
              className="admin-workbench__input"
              type="date"
              required
              value={enqueueForm.windowStartOn}
              onChange={(e) => setEnqueueForm((f) => ({ ...f, windowStartOn: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">窗口止 · WINDOW END BEFORE (YYYY-MM-DD)</span>
            <input
              className="admin-workbench__input"
              type="date"
              required
              value={enqueueForm.windowEndBefore}
              onChange={(e) => setEnqueueForm((f) => ({ ...f, windowEndBefore: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">任务 ID（留空自动生成）</span>
            <input
              className="admin-workbench__input"
              type="text"
              value={enqueueForm.taskId}
              onChange={(e) => setEnqueueForm((f) => ({ ...f, taskId: e.target.value }))}
              placeholder="可选，如 manual-pubmed-01"
            />
          </label>
        </form>
      </Modal>

      {/* 启停信源确认 Modal */}
      <Modal
        open={Boolean(toggleTarget)}
        title={toggleTarget?.enabled ? "确认停用信源" : "确认启用信源"}
        onClose={() => !toggleBusy && setToggleTarget(null)}
        footer={
          <>
            <button
              type="button"
              className="admin-btn admin-btn--ghost"
              onClick={() => setToggleTarget(null)}
              disabled={toggleBusy}
            >
              取消
            </button>
            <button
              type="button"
              className="admin-btn admin-btn--primary"
              disabled={toggleBusy}
              onClick={() => void handleToggleSource()}
            >
              {toggleBusy ? "提交中…" : "确认变更"}
            </button>
          </>
        }
      >
        {toggleTarget ? (
          <p className="admin-workbench__note">
            确认{toggleTarget.enabled ? "停用" : "启用"}信源{" "}
            <strong className="admin-workbench__mono">{toggleTarget.source}</strong> 吗？停用后采集
            Worker 将不再领取该信源的待办时间窗。
          </p>
        ) : null}
      </Modal>

      {/* 登记信源配置版本 Modal */}
      <Modal
        open={Boolean(configTarget)}
        title={`登记信源配置 · ${configTarget ?? ""}`}
        onClose={() => !configBusy && setConfigTarget(null)}
        footer={
          <>
            <button
              type="button"
              className="admin-btn admin-btn--ghost"
              onClick={() => setConfigTarget(null)}
              disabled={configBusy}
            >
              取消
            </button>
            <button
              type="submit"
              form="register-source-config-form"
              className="admin-btn admin-btn--primary"
              disabled={configBusy}
            >
              {configBusy ? "保存中…" : "确认登记"}
            </button>
          </>
        }
      >
        <form
          id="register-source-config-form"
          className="admin-workbench__formgrid"
          onSubmit={handleRegisterConfig}
        >
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">配置版本 · SOURCE CONFIG VERSION</span>
            <input
              className="admin-workbench__input"
              type="number"
              min={1}
              required
              value={configForm.sourceConfigVersion}
              onChange={(e) =>
                setConfigForm((f) => ({ ...f, sourceConfigVersion: e.target.value }))
              }
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">查询语句 · QUERY</span>
            <input
              className="admin-workbench__input"
              type="text"
              required
              value={configForm.query}
              onChange={(e) => setConfigForm((f) => ({ ...f, query: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">初始游标（可选 YYYY-MM-DD）</span>
            <input
              className="admin-workbench__input"
              type="date"
              value={configForm.initialCursor}
              onChange={(e) => setConfigForm((f) => ({ ...f, initialCursor: e.target.value }))}
            />
          </label>
        </form>
      </Modal>

      {/* 配置日预算 Modal */}
      <Modal
        open={budgetOpen}
        title="配置日精选预算"
        onClose={() => !budgetBusy && setBudgetOpen(false)}
        footer={
          <>
            <button
              type="button"
              className="admin-btn admin-btn--ghost"
              onClick={() => setBudgetOpen(false)}
              disabled={budgetBusy}
            >
              取消
            </button>
            <button
              type="submit"
              form="configure-budget-form"
              className="admin-btn admin-btn--primary"
              disabled={budgetBusy}
            >
              {budgetBusy ? "保存中…" : "确认保存"}
            </button>
          </>
        }
      >
        <form
          id="configure-budget-form"
          className="admin-workbench__formgrid"
          onSubmit={handleConfigureBudget}
        >
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">UTC 日期 · BUDGET DATE</span>
            <input
              className="admin-workbench__input"
              type="date"
              required
              value={budgetForm.budgetDate}
              onChange={(e) => setBudgetForm((f) => ({ ...f, budgetDate: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">作用域 · SCOPE</span>
            <input
              className="admin-workbench__input"
              type="text"
              required
              value={budgetForm.scope}
              onChange={(e) => setBudgetForm((f) => ({ ...f, scope: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">最大调用数 · MAX CALLS</span>
            <input
              className="admin-workbench__input"
              type="number"
              min={0}
              required
              value={budgetForm.maxCalls}
              onChange={(e) => setBudgetForm((f) => ({ ...f, maxCalls: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">最大 Tokens · MAX TOKENS</span>
            <input
              className="admin-workbench__input"
              type="number"
              min={0}
              required
              value={budgetForm.maxTokens}
              onChange={(e) => setBudgetForm((f) => ({ ...f, maxTokens: e.target.value }))}
            />
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">最大费用微美元 · MAX COST MICROS</span>
            <input
              className="admin-workbench__input"
              type="number"
              min={0}
              required
              value={budgetForm.maxCostMicros}
              onChange={(e) => setBudgetForm((f) => ({ ...f, maxCostMicros: e.target.value }))}
            />
          </label>
        </form>
      </Modal>

      {/* 解除熔断确认 Modal */}
      <Modal
        open={Boolean(resetCircuitTarget)}
        title="解除鉴权熔断"
        onClose={() => !resetCircuitBusy && setResetCircuitTarget(null)}
        footer={
          <>
            <button
              type="button"
              className="admin-btn admin-btn--ghost"
              onClick={() => setResetCircuitTarget(null)}
              disabled={resetCircuitBusy}
            >
              取消
            </button>
            <button
              type="button"
              className="admin-btn admin-btn--danger"
              disabled={resetCircuitBusy}
              onClick={() => void handleResetCircuit()}
            >
              {resetCircuitBusy ? "处理中…" : "确认解除"}
            </button>
          </>
        }
      >
        {resetCircuitTarget ? (
          <p className="admin-workbench__note">
            确认解除{" "}
            <strong className="admin-workbench__mono">
              {resetCircuitTarget.budgetDate} ({resetCircuitTarget.scope})
            </strong>{" "}
            的鉴权熔断状态吗？请确保已核查上游密钥与配额后再放行。
          </p>
        ) : null}
      </Modal>

      {/* 人工对账 Modal */}
      <Modal
        open={Boolean(reconcileTarget)}
        title="人工对账 OUTCOME_UNKNOWN 回执"
        onClose={() => !reconcileBusy && setReconcileTarget(null)}
        footer={
          <>
            <button
              type="button"
              className="admin-btn admin-btn--ghost"
              onClick={() => setReconcileTarget(null)}
              disabled={reconcileBusy}
            >
              取消
            </button>
            <button
              type="submit"
              form="reconcile-receipt-form"
              className="admin-btn admin-btn--primary"
              disabled={reconcileBusy}
            >
              {reconcileBusy ? "提交中…" : "提交对账"}
            </button>
          </>
        }
      >
        <form
          id="reconcile-receipt-form"
          className="admin-workbench__formgrid"
          onSubmit={handleReconcile}
        >
          <p className="admin-workbench__note num">回执 ID：{reconcileTarget?.callId}</p>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">对账结论 · RESOLUTION</span>
            <select
              className="admin-workbench__select"
              value={reconcileForm.resolution}
              onChange={(e) =>
                setReconcileForm((f) => ({
                  ...f,
                  resolution: e.target.value as typeof reconcileForm.resolution,
                }))
              }
            >
              <option value="CONFIRMED_NOT_CHARGED">确认未扣费 (CONFIRMED_NOT_CHARGED)</option>
              <option value="CONFIRMED_CHARGED_NO_OUTPUT">
                确认已扣费无输出 (CONFIRMED_CHARGED_NO_OUTPUT)
              </option>
              <option value="CONFIRMED_CHARGED_WITH_OUTPUT">
                确认已扣费并恢复输出 (CONFIRMED_CHARGED_WITH_OUTPUT)
              </option>
            </select>
          </label>
          <label className="admin-workbench__field">
            <span className="admin-workbench__label">对账备注 · NOTE</span>
            <input
              className="admin-workbench__input"
              type="text"
              value={reconcileForm.note}
              onChange={(e) => setReconcileForm((f) => ({ ...f, note: e.target.value }))}
              placeholder="账单核对说明"
            />
          </label>
          {reconcileForm.resolution !== "CONFIRMED_NOT_CHARGED" ? (
            <>
              <label className="admin-workbench__field">
                <span className="admin-workbench__label">Prompt Tokens</span>
                <input
                  className="admin-workbench__input"
                  type="number"
                  min={0}
                  required
                  value={reconcileForm.promptTokens}
                  onChange={(e) =>
                    setReconcileForm((f) => ({ ...f, promptTokens: e.target.value }))
                  }
                />
              </label>
              <label className="admin-workbench__field">
                <span className="admin-workbench__label">Completion Tokens</span>
                <input
                  className="admin-workbench__input"
                  type="number"
                  min={0}
                  required
                  value={reconcileForm.completionTokens}
                  onChange={(e) =>
                    setReconcileForm((f) => ({ ...f, completionTokens: e.target.value }))
                  }
                />
              </label>
              <label className="admin-workbench__field">
                <span className="admin-workbench__label">实际费用微美元 · ACTUAL COST MICROS</span>
                <input
                  className="admin-workbench__input"
                  type="number"
                  min={0}
                  required
                  value={reconcileForm.actualCostMicros}
                  onChange={(e) =>
                    setReconcileForm((f) => ({ ...f, actualCostMicros: e.target.value }))
                  }
                />
              </label>
            </>
          ) : null}
          {reconcileForm.resolution === "CONFIRMED_CHARGED_WITH_OUTPUT" ? (
            <label className="admin-workbench__field">
              <span className="admin-workbench__label">恢复的模型原始 JSON · RECOVERED JSON</span>
              <textarea
                className="admin-workbench__textarea"
                rows={4}
                required
                value={reconcileForm.recoveredRawJson}
                onChange={(e) =>
                  setReconcileForm((f) => ({ ...f, recoveredRawJson: e.target.value }))
                }
              />
            </label>
          ) : null}
        </form>
      </Modal>
    </div>
  )
}

export default AdminNewsWorkbenchPage
