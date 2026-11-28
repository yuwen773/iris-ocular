/**
 * Iris News Engine 管理员工作台 API 客户端（`/admin/news-workbench/*`）。
 *
 * <p>后端 {@code JsonConfig} 全局把 {@code Long}/{@code long} 序列化为字符串：
 * 所有雪花/记录主键保留为 {@code string}，计数/额度/万分比字段一律通过 {@code num}/{@code optNum} 归一为数字。
 */

import type { Risk } from "./domain"
import { request, toPage, type Page, type RawPage } from "./http"

function num(value: unknown, fallback = 0): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function optNum(value: unknown): number | null {
  if (value === undefined || value === null || value === "") return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function reqStr(value: unknown): string {
  return value === undefined || value === null ? "" : String(value)
}

function optStr(value: unknown): string | null {
  if (value === undefined || value === null || value === "") return null
  return String(value)
}

export type BudgetSummary = {
  budgetDate: string
  scope: string
  maxCalls: number
  usedCalls: number
  reservedCalls: number
  maxTokens: number
  usedTokens: number
  reservedTokens: number
  maxCostMicros: number
  usedCostMicros: number
  reservedCostMicros: number
  circuitBroken: boolean
  circuitReason: string | null
  updatedAt: string | null
}

export type BenchRunSummary = {
  runId: string
  datasetVersion: string
  datasetSha256: string
  policyVersion: string
  mode: string
  totalCases: number
  passedCases: number
  precisionBps: number
  recallBps: number
  evidenceCatchBps: number
  licenseComplianceBps: number
  injectionCatchBps: number
  totalCalls: number
  totalTokens: number
  totalCostMicros: number
  gatePassed: boolean
  createdAt: string | null
}

export type WorkbenchOverview = {
  sources: { totalSources: number; enabledSources: number }
  tasks: {
    pendingCount: number
    leasedCount: number
    retryScheduledCount: number
    completedCount: number
    failedCount: number
    expiredLeaseCount: number
  }
  materials: { visibleMaterialCount: number; visibleRevisionCount: number }
  candidates: {
    acceptedCount: number
    needsReviewCount: number
    rejectedCount: number
    evidenceViolationCount: number
    needsReconciliationCount: number
    draftedNewsCount: number
  }
  receipts: {
    unknownOutcomeCount: number
    inFlightCount: number
    staleInFlightCount: number
    succeededCount: number
  }
  todayBudget: BudgetSummary | null
  curationEnabled: boolean
  latestBenchRun: BenchRunSummary | null
}

export type SourceCursor = {
  sourceConfigVersion: string
  queryText: string
  committedCursor: string | null
  updatedAt: string | null
}

export type SourceDetail = {
  source: string
  enabled: boolean
  updatedAt: string | null
  visibleMaterialCount: number
  pendingTaskCount: number
  leasedTaskCount: number
  failedTaskCount: number
  completedTaskCount: number
  cursors: SourceCursor[]
}

export type CollectionTask = {
  taskId: string
  taskType?: "AUTO" | "MANUAL"
  source: string
  sourceConfigVersion: string
  queryText: string
  windowStartOn: string
  windowEndBefore: string
  status: string
  generation: number
  attemptCount: number
  maxAttempts: number
  leaseWorkerId: string | null
  leaseExpiresAt: string | null
  leaseExpired: boolean
  nextAttemptAt: string | null
  lastFailureCode: string | null
  committedBatchCount: number | null
  committedRecordCount: number | null
  committedCursor: string | null
  completedAt: string | null
  createdAt: string | null
  updatedAt: string | null
  /** 阻塞原因：为什么这个窗口还轮不到 Worker / 需要人工处理。 */
  blockingReason: string | null
  /** 阻塞本窗口的更早任务 ID。 */
  blockedByTaskId: string | null
  /** 是否是「失败窗口」（可走失败窗口重试入口重新打开）。 */
  retryable: boolean
}

export type CollectionSchedule = {
  ingestionEnabled: boolean
  scheduleEnabled: boolean
  sources: {
    source: string
    sourceEnabled: boolean
    policyEnabled: boolean
    configuredVersion: string | null
    activeVersion: string | null
    committedCursor: string | null
    status: string
    lastRunAt: string | null
    nextRunAt: string | null
    relatedTaskId: string | null
    backlog: string
    failed: string
    retryScheduled: string
    lastFailureCode: string | null
    lastCompletedStartOn: string | null
    lastCompletedEndBefore: string | null
    lastCompletedRecordCount: number | null
  }[]
  workers: {
    workerId: string
    lastPollAt: string
    lastClaimAt: string | null
    lastTaskId: string | null
  }[]
}

export async function getCollectionSchedule(): Promise<CollectionSchedule> {
  return request<CollectionSchedule>("/admin/news-workbench/scheduling", { method: "GET" })
}

/** 采集任务的操作审计记录。 */
export type TaskEvent = {
  id: number
  taskId: string
  action: string
  fromStatus: string | null
  toStatus: string | null
  actorId: number | null
  actorRole: string | null
  note: string | null
  createdAt: string | null
}

export type LeaseRecord = {
  taskId: string
  generation: number
  workerId: string
  leaseExpiresAt: string | null
  claimedAt: string | null
}

export type CurationClaim = {
  id: string
  field: string
  statement: string
  quote: string
}

export type EvidenceViolation = {
  code: string
  target: string
  message: string
}

export type CallReceipt = {
  callId: string
  idempotencyKey: string
  materialRevisionId: string
  policyVersion: string
  attemptNo: number
  budgetDate: string | null
  budgetScope: string
  provider: string
  modelName: string
  promptSha256: string
  state: string
  reservedTokens: number
  reservedCostMicros: number
  promptTokens: number | null
  completionTokens: number | null
  actualCostMicros: number | null
  httpStatus: number | null
  providerRequestId: string | null
  responseSha256: string | null
  errorCode: string | null
  latencyMs: number | null
  reconciledBy: string | null
  reconcileNote: string | null
  reconciledAt: string | null
  reservedAt: string | null
  dispatchedAt: string | null
  deadlineAt: string | null
  completedAt: string | null
}

export type CandidateSummary = {
  id: string
  materialId: string
  materialRevisionId: string
  source: string
  sourceRecordId: string
  contentDigest: string
  policyVersion: string
  callId: string | null
  status: string
  decision: string | null
  evidenceTier: string | null
  relevanceScore: number | null
  suggestedTitle: string | null
  suggestedSummary: string | null
  category: string | null
  claimsCount: number
  violationsCount: number
  evidenceSnapshotSha256: string
  linkedNewsId: string | null
  linkedNewsStatus: number | null
  createdAt: string | null
  updatedAt: string | null
}

export type CandidateDetail = {
  id: string
  materialId: string
  materialRevisionId: string
  source: string
  sourceRecordId: string
  contentDigest: string
  policyVersion: string
  callId: string | null
  status: string
  decision: string | null
  evidenceTier: string | null
  relevanceScore: number | null
  suggestedTitle: string | null
  suggestedSummary: string | null
  category: string | null
  limitations: string[]
  claims: CurationClaim[]
  violations: EvidenceViolation[]
  evidenceSnapshotSha256: string
  evidenceSnapshotJson: string
  callReceipt: CallReceipt | null
  linkedNewsId: string | null
  linkedNewsStatus: number | null
  createdAt: string | null
  updatedAt: string | null
}

export type StaleReclaimResult = {
  abortedPreIoCount: number
  markedUnknownCount: number
}

function toBudget(raw: Record<string, unknown>): BudgetSummary {
  return {
    budgetDate: reqStr(raw.budgetDate),
    scope: reqStr(raw.scope),
    maxCalls: num(raw.maxCalls),
    usedCalls: num(raw.usedCalls),
    reservedCalls: num(raw.reservedCalls),
    maxTokens: num(raw.maxTokens),
    usedTokens: num(raw.usedTokens),
    reservedTokens: num(raw.reservedTokens),
    maxCostMicros: num(raw.maxCostMicros),
    usedCostMicros: num(raw.usedCostMicros),
    reservedCostMicros: num(raw.reservedCostMicros),
    circuitBroken: Boolean(raw.circuitBroken),
    circuitReason: optStr(raw.circuitReason),
    updatedAt: optStr(raw.updatedAt),
  }
}

function toBenchRun(raw: Record<string, unknown>): BenchRunSummary {
  return {
    runId: reqStr(raw.runId),
    datasetVersion: reqStr(raw.datasetVersion),
    datasetSha256: reqStr(raw.datasetSha256),
    policyVersion: reqStr(raw.policyVersion),
    mode: reqStr(raw.mode),
    totalCases: num(raw.totalCases),
    passedCases: num(raw.passedCases),
    precisionBps: num(raw.precisionBps),
    recallBps: num(raw.recallBps),
    evidenceCatchBps: num(raw.evidenceCatchBps),
    licenseComplianceBps: num(raw.licenseComplianceBps),
    injectionCatchBps: num(raw.injectionCatchBps),
    totalCalls: num(raw.totalCalls),
    totalTokens: num(raw.totalTokens),
    totalCostMicros: num(raw.totalCostMicros),
    gatePassed: Boolean(raw.gatePassed),
    createdAt: optStr(raw.createdAt),
  }
}

function toTask(raw: Record<string, unknown>): CollectionTask {
  return {
    taskId: reqStr(raw.taskId),
    taskType: raw.taskType === "AUTO" ? "AUTO" : "MANUAL",
    source: reqStr(raw.source),
    sourceConfigVersion: reqStr(raw.sourceConfigVersion),
    queryText: reqStr(raw.queryText),
    windowStartOn: reqStr(raw.windowStartOn),
    windowEndBefore: reqStr(raw.windowEndBefore),
    status: reqStr(raw.status),
    generation: num(raw.generation),
    attemptCount: num(raw.attemptCount),
    maxAttempts: num(raw.maxAttempts),
    leaseWorkerId: optStr(raw.leaseWorkerId),
    leaseExpiresAt: optStr(raw.leaseExpiresAt),
    leaseExpired: Boolean(raw.leaseExpired),
    nextAttemptAt: optStr(raw.nextAttemptAt),
    lastFailureCode: optStr(raw.lastFailureCode),
    committedBatchCount: optNum(raw.committedBatchCount),
    committedRecordCount: optNum(raw.committedRecordCount),
    committedCursor: optStr(raw.committedCursor),
    completedAt: optStr(raw.completedAt),
    createdAt: optStr(raw.createdAt),
    updatedAt: optStr(raw.updatedAt),
    blockingReason: optStr(raw.blockingReason),
    blockedByTaskId: optStr(raw.blockedByTaskId),
    retryable: Boolean(raw.retryable),
  }
}

function toReceipt(raw: Record<string, unknown>): CallReceipt {
  return {
    callId: reqStr(raw.callId),
    idempotencyKey: reqStr(raw.idempotencyKey),
    materialRevisionId: reqStr(raw.materialRevisionId),
    policyVersion: reqStr(raw.policyVersion),
    attemptNo: num(raw.attemptNo),
    budgetDate: optStr(raw.budgetDate),
    budgetScope: reqStr(raw.budgetScope),
    provider: reqStr(raw.provider),
    modelName: reqStr(raw.modelName),
    promptSha256: reqStr(raw.promptSha256),
    state: reqStr(raw.state),
    reservedTokens: num(raw.reservedTokens),
    reservedCostMicros: num(raw.reservedCostMicros),
    promptTokens: optNum(raw.promptTokens),
    completionTokens: optNum(raw.completionTokens),
    actualCostMicros: optNum(raw.actualCostMicros),
    httpStatus: optNum(raw.httpStatus),
    providerRequestId: optStr(raw.providerRequestId),
    responseSha256: optStr(raw.responseSha256),
    errorCode: optStr(raw.errorCode),
    latencyMs: optNum(raw.latencyMs),
    reconciledBy: optStr(raw.reconciledBy),
    reconcileNote: optStr(raw.reconcileNote),
    reconciledAt: optStr(raw.reconciledAt),
    reservedAt: optStr(raw.reservedAt),
    dispatchedAt: optStr(raw.dispatchedAt),
    deadlineAt: optStr(raw.deadlineAt),
    completedAt: optStr(raw.completedAt),
  }
}

function toCandidateSummary(raw: Record<string, unknown>): CandidateSummary {
  return {
    id: reqStr(raw.id),
    materialId: reqStr(raw.materialId),
    materialRevisionId: reqStr(raw.materialRevisionId),
    source: reqStr(raw.source),
    sourceRecordId: reqStr(raw.sourceRecordId),
    contentDigest: reqStr(raw.contentDigest),
    policyVersion: reqStr(raw.policyVersion),
    callId: optStr(raw.callId),
    status: reqStr(raw.status),
    decision: optStr(raw.decision),
    evidenceTier: optStr(raw.evidenceTier),
    relevanceScore: optNum(raw.relevanceScore),
    suggestedTitle: optStr(raw.suggestedTitle),
    suggestedSummary: optStr(raw.suggestedSummary),
    category: optStr(raw.category),
    claimsCount: num(raw.claimsCount),
    violationsCount: num(raw.violationsCount),
    evidenceSnapshotSha256: reqStr(raw.evidenceSnapshotSha256),
    linkedNewsId: optStr(raw.linkedNewsId),
    linkedNewsStatus: optNum(raw.linkedNewsStatus),
    createdAt: optStr(raw.createdAt),
    updatedAt: optStr(raw.updatedAt),
  }
}

export async function getWorkbenchOverview(): Promise<WorkbenchOverview> {
  const raw = await request<Record<string, unknown>>("/admin/news-workbench/overview", {
    method: "GET",
  })
  const sources = (raw.sources as Record<string, unknown> | undefined) ?? {}
  const tasks = (raw.tasks as Record<string, unknown> | undefined) ?? {}
  const materials = (raw.materials as Record<string, unknown> | undefined) ?? {}
  const candidates = (raw.candidates as Record<string, unknown> | undefined) ?? {}
  const receipts = (raw.receipts as Record<string, unknown> | undefined) ?? {}
  const budget = raw.todayBudget as Record<string, unknown> | null | undefined
  const bench = raw.latestBenchRun as Record<string, unknown> | null | undefined

  return {
    sources: {
      totalSources: num(sources.totalSources),
      enabledSources: num(sources.enabledSources),
    },
    tasks: {
      pendingCount: num(tasks.pendingCount),
      leasedCount: num(tasks.leasedCount),
      retryScheduledCount: num(tasks.retryScheduledCount),
      completedCount: num(tasks.completedCount),
      failedCount: num(tasks.failedCount),
      expiredLeaseCount: num(tasks.expiredLeaseCount),
    },
    materials: {
      visibleMaterialCount: num(materials.visibleMaterialCount),
      visibleRevisionCount: num(materials.visibleRevisionCount),
    },
    candidates: {
      acceptedCount: num(candidates.acceptedCount),
      needsReviewCount: num(candidates.needsReviewCount),
      rejectedCount: num(candidates.rejectedCount),
      evidenceViolationCount: num(candidates.evidenceViolationCount),
      needsReconciliationCount: num(candidates.needsReconciliationCount),
      draftedNewsCount: num(candidates.draftedNewsCount),
    },
    receipts: {
      unknownOutcomeCount: num(receipts.unknownOutcomeCount),
      inFlightCount: num(receipts.inFlightCount),
      staleInFlightCount: num(receipts.staleInFlightCount),
      succeededCount: num(receipts.succeededCount),
    },
    todayBudget: budget ? toBudget(budget) : null,
    curationEnabled: Boolean(raw.curationEnabled),
    latestBenchRun: bench ? toBenchRun(bench) : null,
  }
}

export async function listWorkbenchSources(): Promise<SourceDetail[]> {
  const rows = await request<Record<string, unknown>[]>("/admin/news-workbench/sources", {
    method: "GET",
  })
  return (rows ?? []).map((row) => ({
    source: reqStr(row.source),
    enabled: Boolean(row.enabled),
    updatedAt: optStr(row.updatedAt),
    visibleMaterialCount: num(row.visibleMaterialCount),
    pendingTaskCount: num(row.pendingTaskCount),
    leasedTaskCount: num(row.leasedTaskCount),
    failedTaskCount: num(row.failedTaskCount),
    completedTaskCount: num(row.completedTaskCount),
    cursors: ((row.cursors as Record<string, unknown>[] | undefined) ?? []).map((cursor) => ({
      sourceConfigVersion: reqStr(cursor.sourceConfigVersion),
      queryText: reqStr(cursor.queryText),
      committedCursor: optStr(cursor.committedCursor),
      updatedAt: optStr(cursor.updatedAt),
    })),
  }))
}

export async function toggleWorkbenchSource(
  source: string,
  enabled: boolean,
): Promise<SourceDetail> {
  const row = await request<Record<string, unknown>>(
    `/admin/news-workbench/sources/${encodeURIComponent(source)}/toggle`,
    {
      method: "POST",
      body: JSON.stringify({ enabled }),
    },
  )
  return {
    source: reqStr(row.source),
    enabled: Boolean(row.enabled),
    updatedAt: optStr(row.updatedAt),
    visibleMaterialCount: num(row.visibleMaterialCount),
    pendingTaskCount: num(row.pendingTaskCount),
    leasedTaskCount: num(row.leasedTaskCount),
    failedTaskCount: num(row.failedTaskCount),
    completedTaskCount: num(row.completedTaskCount),
    cursors: ((row.cursors as Record<string, unknown>[] | undefined) ?? []).map((cursor) => ({
      sourceConfigVersion: reqStr(cursor.sourceConfigVersion),
      queryText: reqStr(cursor.queryText),
      committedCursor: optStr(cursor.committedCursor),
      updatedAt: optStr(cursor.updatedAt),
    })),
  }
}

export async function registerWorkbenchSourceConfig(
  source: string,
  body: { sourceConfigVersion: number; query: string; initialCursor?: string },
): Promise<SourceDetail> {
  const row = await request<Record<string, unknown>>(
    `/admin/news-workbench/sources/${encodeURIComponent(source)}/configs`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
  )
  return {
    source: reqStr(row.source),
    enabled: Boolean(row.enabled),
    updatedAt: optStr(row.updatedAt),
    visibleMaterialCount: num(row.visibleMaterialCount),
    pendingTaskCount: num(row.pendingTaskCount),
    leasedTaskCount: num(row.leasedTaskCount),
    failedTaskCount: num(row.failedTaskCount),
    completedTaskCount: num(row.completedTaskCount),
    cursors: ((row.cursors as Record<string, unknown>[] | undefined) ?? []).map((cursor) => ({
      sourceConfigVersion: reqStr(cursor.sourceConfigVersion),
      queryText: reqStr(cursor.queryText),
      committedCursor: optStr(cursor.committedCursor),
      updatedAt: optStr(cursor.updatedAt),
    })),
  }
}

export async function listWorkbenchTasks(q: {
  source?: string
  status?: string
  current: number
  pageSize: number
}): Promise<Page<CollectionTask>> {
  const params = new URLSearchParams({
    current: String(q.current),
    pageSize: String(q.pageSize),
  })
  if (q.source) params.set("source", q.source)
  if (q.status) params.set("status", q.status)
  const raw = await request<RawPage<Record<string, unknown>>>(
    `/admin/news-workbench/tasks?${params.toString()}`,
    { method: "GET" },
  )
  const page = toPage(raw)
  return {
    ...page,
    records: (page.records ?? []).map(toTask),
  }
}

export async function enqueueWorkbenchTask(body: {
  taskId?: string
  source: string
  sourceConfigVersion: number
  query: string
  windowStartOn: string
  windowEndBefore: string
  maxAttempts?: number
}): Promise<CollectionTask> {
  const raw = await request<Record<string, unknown>>("/admin/news-workbench/tasks/enqueue", {
    method: "POST",
    body: JSON.stringify(body),
  })
  return toTask(raw)
}

export async function listTaskLeases(taskId: string): Promise<LeaseRecord[]> {
  const rows = await request<Record<string, unknown>[]>(
    `/admin/news-workbench/tasks/${encodeURIComponent(taskId)}/leases`,
    { method: "GET" },
  )
  return (rows ?? []).map((row) => ({
    taskId: reqStr(row.taskId),
    generation: num(row.generation),
    workerId: reqStr(row.workerId),
    leaseExpiresAt: optStr(row.leaseExpiresAt),
    claimedAt: optStr(row.claimedAt),
  }))
}

/**
 * 重新打开一个失败的采集窗口。服务端只接受 FAILED 状态的任务，
 * 重复提交会被明确拒绝（不会静默重开）。
 */
export async function retryWorkbenchTask(
  taskId: string,
  body: { maxAttempts?: number; note?: string } = {},
): Promise<CollectionTask> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/tasks/${encodeURIComponent(taskId)}/retry`,
    {
      method: "POST",
      body: JSON.stringify({
        ...(body.maxAttempts ? { maxAttempts: body.maxAttempts } : {}),
        ...(body.note ? { note: body.note } : {}),
      }),
    },
  )
  return toTask(raw)
}

export async function listTaskEvents(taskId: string): Promise<TaskEvent[]> {
  const rows = await request<Record<string, unknown>[]>(
    `/admin/news-workbench/tasks/${encodeURIComponent(taskId)}/events`,
    { method: "GET" },
  )
  return (rows ?? []).map((row) => ({
    id: num(row.id),
    taskId: reqStr(row.taskId),
    action: reqStr(row.action),
    fromStatus: optStr(row.fromStatus),
    toStatus: optStr(row.toStatus),
    actorId: row.actorId === null || row.actorId === undefined ? null : num(row.actorId),
    actorRole: optStr(row.actorRole),
    note: optStr(row.note),
    createdAt: optStr(row.createdAt),
  }))
}

export async function listWorkbenchCandidates(q: {
  status?: string
  source?: string
  evidenceTier?: string
  current: number
  pageSize: number
}): Promise<Page<CandidateSummary>> {
  const params = new URLSearchParams({
    current: String(q.current),
    pageSize: String(q.pageSize),
  })
  if (q.status) params.set("status", q.status)
  if (q.source) params.set("source", q.source)
  if (q.evidenceTier) params.set("evidenceTier", q.evidenceTier)
  const raw = await request<RawPage<Record<string, unknown>>>(
    `/admin/news-workbench/candidates?${params.toString()}`,
    { method: "GET" },
  )
  const page = toPage(raw)
  return {
    ...page,
    records: (page.records ?? []).map(toCandidateSummary),
  }
}

export async function getWorkbenchCandidateDetail(
  candidateId: string,
): Promise<CandidateDetail> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/candidates/${encodeURIComponent(candidateId)}`,
    { method: "GET" },
  )
  const receipt = raw.callReceipt as Record<string, unknown> | null | undefined
  return {
    id: reqStr(raw.id),
    materialId: reqStr(raw.materialId),
    materialRevisionId: reqStr(raw.materialRevisionId),
    source: reqStr(raw.source),
    sourceRecordId: reqStr(raw.sourceRecordId),
    contentDigest: reqStr(raw.contentDigest),
    policyVersion: reqStr(raw.policyVersion),
    callId: optStr(raw.callId),
    status: reqStr(raw.status),
    decision: optStr(raw.decision),
    evidenceTier: optStr(raw.evidenceTier),
    relevanceScore: optNum(raw.relevanceScore),
    suggestedTitle: optStr(raw.suggestedTitle),
    suggestedSummary: optStr(raw.suggestedSummary),
    category: optStr(raw.category),
    limitations: Array.isArray(raw.limitations) ? raw.limitations.map(String) : [],
    claims: Array.isArray(raw.claims)
      ? raw.claims.map((c: Record<string, unknown>) => ({
          id: reqStr(c.id),
          field: reqStr(c.field),
          statement: reqStr(c.statement),
          quote: reqStr(c.quote),
        }))
      : [],
    violations: Array.isArray(raw.violations)
      ? raw.violations.map((v: Record<string, unknown>) => ({
          code: reqStr(v.code),
          target: reqStr(v.target),
          message: reqStr(v.message),
        }))
      : [],
    evidenceSnapshotSha256: reqStr(raw.evidenceSnapshotSha256),
    evidenceSnapshotJson: reqStr(raw.evidenceSnapshotJson),
    callReceipt: receipt ? toReceipt(receipt) : null,
    linkedNewsId: optStr(raw.linkedNewsId),
    linkedNewsStatus: optNum(raw.linkedNewsStatus),
    createdAt: optStr(raw.createdAt),
    updatedAt: optStr(raw.updatedAt),
  }
}

export async function listWorkbenchBudgets(scope?: string, limit = 14): Promise<BudgetSummary[]> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (scope) params.set("scope", scope)
  const rows = await request<Record<string, unknown>[]>(
    `/admin/news-workbench/budgets?${params.toString()}`,
    { method: "GET" },
  )
  return (rows ?? []).map(toBudget)
}

export async function configureWorkbenchBudget(body: {
  budgetDate: string
  scope?: string
  maxCalls: number
  maxTokens: number
  maxCostMicros: number
}): Promise<BudgetSummary> {
  const raw = await request<Record<string, unknown>>("/admin/news-workbench/budgets", {
    method: "POST",
    body: JSON.stringify(body),
  })
  return toBudget(raw)
}

export async function resetWorkbenchCircuitBreaker(body: {
  budgetDate: string
  scope?: string
}): Promise<BudgetSummary> {
  const raw = await request<Record<string, unknown>>("/admin/news-workbench/budgets/reset-circuit", {
    method: "POST",
    body: JSON.stringify(body),
  })
  return toBudget(raw)
}

export async function listWorkbenchReceipts(q: {
  state?: string
  budgetScope?: string
  current: number
  pageSize: number
}): Promise<Page<CallReceipt>> {
  const params = new URLSearchParams({
    current: String(q.current),
    pageSize: String(q.pageSize),
  })
  if (q.state) params.set("state", q.state)
  if (q.budgetScope) params.set("budgetScope", q.budgetScope)
  const raw = await request<RawPage<Record<string, unknown>>>(
    `/admin/news-workbench/receipts?${params.toString()}`,
    { method: "GET" },
  )
  const page = toPage(raw)
  return {
    ...page,
    records: (page.records ?? []).map(toReceipt),
  }
}

export async function reclaimStaleWorkbenchCalls(): Promise<StaleReclaimResult> {
  const raw = await request<Record<string, unknown>>("/admin/news-workbench/receipts/reclaim-stale", {
    method: "POST",
  })
  return {
    abortedPreIoCount: num(raw.abortedPreIoCount),
    markedUnknownCount: num(raw.markedUnknownCount),
  }
}

export async function reconcileWorkbenchReceipt(
  callId: string,
  body: {
    resolution: "CONFIRMED_NOT_CHARGED" | "CONFIRMED_CHARGED_NO_OUTPUT" | "CONFIRMED_CHARGED_WITH_OUTPUT"
    note?: string
    recoveredRawJson?: string
    promptTokens?: number
    completionTokens?: number
    actualCostMicros?: number
    providerRequestId?: string
  },
): Promise<CallReceipt> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/receipts/${encodeURIComponent(callId)}/reconcile`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
  )
  return toReceipt(raw)
}

export async function listWorkbenchBenchRuns(limit = 20): Promise<BenchRunSummary[]> {
  const rows = await request<Record<string, unknown>[]>(
    `/admin/news-workbench/bench-runs?limit=${encodeURIComponent(String(limit))}`,
    { method: "GET" },
  )
  return (rows ?? []).map(toBenchRun)
}

// ---- N1：管理员生产精选入口 ----

/** 一个已准入资料修订的精选就绪状态（证据路由、活跃候选、最近回执）。 */
export type CurationMaterial = {
  materialRevisionId: string
  materialId: string
  source: string
  sourceRecordId: string
  title: string
  publishedOn: string | null
  pmid: string | null
  doi: string | null
  license: string | null
  abstractPresent: boolean
  evidenceTier: string | null
  eligibleType: string | null
  gateReason: string | null
  evidenceGateOpen: boolean
  activeEvidenceOverlaySha256: string
  candidateId: string | null
  candidateStatus: string | null
  latestCallId: string | null
  latestReceiptState: string | null
  latestErrorCode: string | null
  curatable: boolean
  profileKey: string | null
}

/** 一次管理员触发的生产 P05 运行结果。成功回执尚不是候选，必须显式转换。 */
export type CurationRun = {
  callId: string
  eligibleType: string | null
  state: string
  replayed: boolean
  provider: string
  modelName: string
  promptTokens: number | null
  completionTokens: number | null
  actualCostMicros: number | null
  httpStatus: number | null
  errorCode: string | null
  frozenOutputAvailable: boolean
  materializable: boolean
}

export type EvidenceVerification = {
  id: string
  materialRevisionId: string
  attribute: string
  value: string
  method: string
  evidenceUrl: string
  sourceVersion: string
  evidenceNote: string | null
  verifiedBy: string
  verifiedAt: string | null
  active: boolean
  revokedBy: string | null
  revokedAt: string | null
  revokeNote: string | null
}

function toCurationMaterial(raw: Record<string, unknown>): CurationMaterial {
  return {
    materialRevisionId: reqStr(raw.materialRevisionId),
    materialId: reqStr(raw.materialId),
    source: reqStr(raw.source),
    sourceRecordId: reqStr(raw.sourceRecordId),
    title: reqStr(raw.title),
    publishedOn: optStr(raw.publishedOn),
    pmid: optStr(raw.pmid),
    doi: optStr(raw.doi),
    license: optStr(raw.license),
    abstractPresent: Boolean(raw.abstractPresent),
    evidenceTier: optStr(raw.evidenceTier),
    eligibleType: optStr(raw.eligibleType),
    gateReason: optStr(raw.gateReason),
    evidenceGateOpen: Boolean(raw.evidenceGateOpen),
    activeEvidenceOverlaySha256: reqStr(raw.activeEvidenceOverlaySha256),
    candidateId: optStr(raw.candidateId),
    candidateStatus: optStr(raw.candidateStatus),
    latestCallId: optStr(raw.latestCallId),
    latestReceiptState: optStr(raw.latestReceiptState),
    latestErrorCode: optStr(raw.latestErrorCode),
    curatable: Boolean(raw.curatable),
    profileKey: optStr(raw.profileKey),
  }
}

function toEvidenceVerification(raw: Record<string, unknown>): EvidenceVerification {
  return {
    id: reqStr(raw.id),
    materialRevisionId: reqStr(raw.materialRevisionId),
    attribute: reqStr(raw.attribute),
    value: reqStr(raw.value),
    method: reqStr(raw.method),
    evidenceUrl: reqStr(raw.evidenceUrl),
    sourceVersion: reqStr(raw.sourceVersion),
    evidenceNote: optStr(raw.evidenceNote),
    verifiedBy: reqStr(raw.verifiedBy),
    verifiedAt: optStr(raw.verifiedAt),
    active: Boolean(raw.active),
    revokedBy: optStr(raw.revokedBy),
    revokedAt: optStr(raw.revokedAt),
    revokeNote: optStr(raw.revokeNote),
  }
}

export async function listCurationMaterials(q: {
  source?: string
  current: number
  pageSize: number
}): Promise<Page<CurationMaterial>> {
  const params = new URLSearchParams({
    current: String(q.current),
    pageSize: String(q.pageSize),
  })
  if (q.source) params.set("source", q.source)
  const raw = await request<RawPage<Record<string, unknown>>>(
    `/admin/news-workbench/materials?${params.toString()}`,
    { method: "GET", cache: "no-store" },
  )
  const page = toPage(raw)
  return {
    ...page,
    records: (page.records ?? []).map(toCurationMaterial),
  }
}

export async function runCuration(materialRevisionId: string): Promise<CurationRun> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/materials/${encodeURIComponent(materialRevisionId)}/curate`,
    { method: "POST" },
  )
  return {
    callId: reqStr(raw.callId),
    eligibleType: optStr(raw.eligibleType),
    state: reqStr(raw.state),
    replayed: Boolean(raw.replayed),
    provider: reqStr(raw.provider),
    modelName: reqStr(raw.modelName),
    promptTokens: optNum(raw.promptTokens),
    completionTokens: optNum(raw.completionTokens),
    actualCostMicros: optNum(raw.actualCostMicros),
    httpStatus: optNum(raw.httpStatus),
    errorCode: optStr(raw.errorCode),
    frozenOutputAvailable: Boolean(raw.frozenOutputAvailable),
    materializable: Boolean(raw.materializable),
  }
}

export async function listMaterialReceipts(materialRevisionId: string): Promise<CallReceipt[]> {
  const rows = await request<Record<string, unknown>[]>(
    `/admin/news-workbench/materials/${encodeURIComponent(materialRevisionId)}/receipts`,
    { method: "GET", cache: "no-store" },
  )
  return (rows ?? []).map(toReceipt)
}

export async function listEvidenceVerifications(
  materialRevisionId: string,
): Promise<EvidenceVerification[]> {
  const rows = await request<Record<string, unknown>[]>(
    `/admin/news-workbench/materials/${encodeURIComponent(materialRevisionId)}/evidence-verifications`,
    { method: "GET", cache: "no-store" },
  )
  return (rows ?? []).map(toEvidenceVerification)
}

export async function submitEvidenceVerification(
  materialRevisionId: string,
  body: {
    attribute: string
    value: string
    method: string
    evidenceUrl: string
    sourceVersion?: string
    evidenceNote?: string
  },
): Promise<EvidenceVerification> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/materials/${encodeURIComponent(materialRevisionId)}/evidence-verifications`,
    { method: "POST", body: JSON.stringify(body) },
  )
  return toEvidenceVerification(raw)
}

export async function revokeEvidenceVerification(
  verificationId: string,
  body: { note?: string } = {},
): Promise<EvidenceVerification> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/evidence-verifications/${encodeURIComponent(verificationId)}/revoke`,
    { method: "POST", body: JSON.stringify(body) },
  )
  return toEvidenceVerification(raw)
}

export async function materializeCurationCandidate(
  callId: string,
  body: { supersedesCandidateId?: string; reason?: string } = {},
): Promise<{
  candidateId: string
  status: string
  callId: string
  created: boolean
  evidenceVerificationSha256: string | null
  supersededCandidateId: string | null
}> {
  const raw = await request<Record<string, unknown>>(
    `/admin/news-workbench/receipts/${encodeURIComponent(callId)}/materialize`,
    { method: "POST", body: JSON.stringify(body) },
  )
  return {
    candidateId: reqStr(raw.candidateId),
    status: reqStr(raw.status),
    callId: reqStr(raw.callId),
    created: Boolean(raw.created),
    evidenceVerificationSha256: optStr(raw.evidenceVerificationSha256),
    supersededCandidateId: optStr(raw.supersededCandidateId),
  }
}

export function materialRouteView(eligibleType?: string | null): { label: string; risk: Risk } {
  switch (eligibleType) {
    case "RESEARCH_INSIGHT":
      return { label: "研究解析", risk: "normal" }
    case "RESEARCH_RADAR":
      return { label: "研究雷达", risk: "attention" }
    case "TRIAL_WATCH":
      return { label: "试验观察", risk: "normal" }
    case "SKIP":
      return { label: "不生成内容", risk: "critical" }
    default:
      return { label: eligibleType || "未判定", risk: "unknown" }
  }
}

export function curationGateReasonLabel(reason?: string | null): string {
  switch (reason) {
    case "INTEGRITY_RETRACTED":
      return "资料已撤稿，证据路由为 SKIP"
    case "INTEGRITY_CONCERN":
      return "资料完整性存疑，证据路由为 SKIP"
    case "TRIAL_NO_CHANGE":
      return "试验登记状态无变化，本次不生成内容"
    case "EVIDENCE_SKIP":
      return "证据路由判定为 SKIP"
    case "EVIDENCE_LICENSE_UNKNOWN":
      return "缺少可授权摘要或许可未知，无法升级为研究解析"
    case "EVIDENCE_LICENSE_CONFLICT":
      return "登记的许可与资料快照冲突"
    case "MODEL_PROFILE_NOT_CONFIGURED":
      return "服务端未配置精选模型 profile"
    case "TERMINAL_FAILURE_REPLAY_ONLY":
      return "上次为确定性失败；本入口只重放既有回执，不会再次调用模型"
    case "AWAITING_RECONCILIATION":
      return "存在待对账的模型调用；请先在回执台账完成对账"
    default:
      return reason || "—"
  }
}

export function evidenceAttributeLabel(attribute?: string | null): string {
  switch (attribute) {
    case "PEER_REVIEW":
      return "同行评审"
    case "INTEGRITY":
      return "完整性"
    case "STUDY_DESIGN":
      return "研究设计"
    case "CONTENT_SCOPE":
      return "授权全文范围"
    case "LICENSE":
      return "许可"
    default:
      return attribute || "—"
  }
}

export type NewsIssueArticle = {
  newsId: string
  revisionNo: number
  title: string
  summary: string | null
  publishTime: string
  materialRevisionId: string
  source: string
  sourceRecordId: string
  pmid: string | null
  doi: string | null
}

type NewsIssuePreview = {
  startOn: string
  endOn: string
  articleCount: number
  entries: { identifierConflict: boolean; articles: NewsIssueArticle[] }[]
}

export function getNewsIssuePreview(startOn: string, endOn: string) {
  const params = new URLSearchParams({ startOn, endOn })
  return request<NewsIssuePreview>(`/admin/news-workbench/issue-preview?${params}`, {
    method: "GET",
    cache: "no-store",
  })
}

export function formatMicrosUsd(micros?: number | null): string {
  if (micros === undefined || micros === null || !Number.isFinite(micros)) return "—"
  const usd = micros / 1_000_000
  return `$${usd.toFixed(4)}`
}

export function formatBpsPercent(bps?: number | null): string {
  if (bps === undefined || bps === null || !Number.isFinite(bps)) return "—"
  return `${(bps / 100).toFixed(1)}%`
}

export function candidateStatusView(status?: string | null): { label: string; risk: Risk } {
  switch (status) {
    case "CURATED_ACCEPTED":
      return { label: "已通过", risk: "normal" }
    case "CURATED_NEEDS_REVIEW":
      return { label: "需复核", risk: "attention" }
    case "CURATED_REJECTED":
      return { label: "已淘汰", risk: "unknown" }
    case "REJECTED_EVIDENCE_VIOLATION":
      return { label: "证据违规", risk: "critical" }
    case "NEEDS_RECONCILIATION":
      return { label: "待对账", risk: "attention" }
    default:
      return { label: status || "未知", risk: "unknown" }
  }
}

export function taskStatusView(status?: string | null): { label: string; risk: Risk } {
  switch (status) {
    case "COMPLETED":
      return { label: "已完成", risk: "normal" }
    case "PENDING":
      return { label: "待领取", risk: "attention" }
    case "LEASED":
      return { label: "租约中", risk: "attention" }
    case "RETRY_SCHEDULED":
      return { label: "待重试", risk: "attention" }
    case "FAILED":
      return { label: "已失败", risk: "critical" }
    default:
      return { label: status || "未知", risk: "unknown" }
  }
}

export function receiptStateView(state?: string | null): { label: string; risk: Risk } {
  switch (state) {
    case "SUCCEEDED":
      return { label: "已成功", risk: "normal" }
    case "OUTCOME_UNKNOWN":
      return { label: "结果未知", risk: "critical" }
    case "RESERVED":
      return { label: "已预扣", risk: "attention" }
    case "DISPATCHED":
      return { label: "在途", risk: "attention" }
    case "FAILED_DEFINITIVE":
    case "FAILED_CHARGED":
      return { label: "调用失败", risk: "critical" }
    case "ABORTED_PRE_IO":
    case "FAILED_UNCHARGED":
      return { label: "未扣费终止", risk: "unknown" }
    default:
      return { label: state || "未知", risk: "unknown" }
  }
}

export function evidenceTierLabel(tier?: string | null): string {
  switch (tier) {
    case "PEER_REVIEWED_ABSTRACT":
    case "LICENSED_ABSTRACT":
      return "开放许可摘要（同行评审未验证）"
    case "CLINICAL_TRIAL_REGISTRY":
      return "临床试验注册库"
    case "METADATA_ONLY":
      return "仅元数据"
    default:
      return tier || "—"
  }
}
