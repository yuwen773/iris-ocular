/**
 * 配置中心（Control Center）API 客户端 —— WP-01B 子集：读取与草稿生命周期，
 * 以及 WP-01C 的发布申请 / 审批 / 回滚 / 版本历史。
 *
 * <p>读写分流是刻意的：
 * <ul>
 *   <li>读走 `request`（GET 不需要 CSRF）；</li>
 *   <li>写走 {@link csrfRequest}，自动带 `X-CSRF-Token`。第一条写端点上线的同一批
 *       交付里就有这条管道，不存在「后端有端点、前端只会 403」的中间状态。</li>
 * </ul>
 *
 * <p>后端全局把 Long 序列化成字符串，这里统一转回 number：`revision` 要参与
 * 比较与回传，留着 "7" 这种值迟早出问题。
 *
 * <p>发布/审批/回滚都必须带 `requestId`：这些动作不可重试（重试一次就是再发布一次），
 * 幂等由服务端按「操作人 + requestId + 内容摘要」判定。客户端**不要**自动重试——
 * 该不该重试取决于服务端返回的错误码（40908 说明号被别的内容占用，换号才行）。
 *
 * <p><b>每个动作必须是不同的 requestId</b>：服务端按 `(actorId, requestId)` 判重，
 * 一个号配两份内容会被 40908 拒绝——包括「申请发布」和「批准」这种相邻动作。
 * 草稿类写操作在调用方没给号时由本模块默认生成一个（见 {@link newRequestId}），
 * 不给号就等于关闭幂等：网络重试会变成第二次写入。
 */

import { request } from "./http"
import { csrfRequest } from "./csrf"

/** 与后端 `t_config_request.requestId VARCHAR(64)` 对齐的上限（按码点计）。 */
export const REQUEST_ID_MAX_LENGTH = 64

/**
 * 生成一个请求幂等键。
 *
 * <p>不能复用同一个号去发两个不同动作，因此这里每次调用生成新值；
 * 想让某次重试被服务端判成同一个请求，调用方必须显式传同一个 `requestId`。
 */
export function newRequestId(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  if (uuid) return uuid
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** 与后端 ConfigRegistry.SCHEMA_VERSION 对齐。 */
export const CONFIG_SCHEMA_VERSION = 1

export type ConfigValue = string | null

export type ConfigValues = Record<string, ConfigValue>

export type ActiveConfig = {
  releaseId: number | null
  releaseVersion: number | null
  schemaVersion: number
  digest: string | null
  source: "DEFAULT" | "RELEASE"
  values: ConfigValues
  updatedTime: string | null
}

export type RegistryKey = {
  key: string
  type: "STRING" | "ASSET_REF"
  defaultValue: string | null
  minLength: number
  maxLength: number
}

export type Registry = {
  schemaVersion: number
  keys: RegistryKey[]
}

export type DraftStatus = "NONE" | "DRAFT" | "VALIDATED"

export type Draft = {
  id: number
  revision: number
  status: DraftStatus
  values: ConfigValues
  validationRevision: number | null
  validationResult: unknown
  updatedBy: number | null
  updateTime: string | null
}

export type ValidationResult = {
  id: number
  revision: number
  valid: boolean
  fieldErrors: Record<string, string[]>
}

type RawDraft = Omit<Draft, "id" | "revision" | "validationRevision" | "updatedBy"> & {
  id: number | string
  revision: number | string
  validationRevision: number | string | null
  updatedBy: number | string | null
}

type RawActive = Omit<ActiveConfig, "releaseId" | "releaseVersion"> & {
  releaseId: number | string | null
  releaseVersion: number | string | null
}

const num = (value: number | string | null | undefined): number | null =>
  value === null || value === undefined ? null : Number(value)

function normalizeDraft(raw: RawDraft): Draft {
  return {
    ...raw,
    id: Number(raw.id),
    revision: Number(raw.revision),
    validationRevision: num(raw.validationRevision),
    updatedBy: num(raw.updatedBy),
  }
}

export async function fetchActiveConfig(): Promise<ActiveConfig> {
  const raw = await request<RawActive>("/admin/control-center/active")
  return { ...raw, releaseId: num(raw.releaseId), releaseVersion: num(raw.releaseVersion) }
}

export function fetchConfigRegistry(): Promise<Registry> {
  return request<Registry>("/admin/control-center/registry")
}

export async function fetchDraft(): Promise<Draft> {
  return normalizeDraft(await request<RawDraft>("/admin/control-center/draft"))
}

export async function createDraft(
  values: ConfigValues,
  options: { requestId?: string } = {},
): Promise<Draft> {
  return normalizeDraft(
    await csrfRequest<RawDraft>("/admin/control-center/drafts", {
      method: "POST",
      body: JSON.stringify({
        schemaVersion: CONFIG_SCHEMA_VERSION,
        values,
        requestId: options.requestId ?? newRequestId(),
      }),
    }),
  )
}

export async function updateDraft(
  id: number,
  values: ConfigValues,
  expectedRevision: number,
  options: { requestId?: string } = {},
): Promise<Draft> {
  return normalizeDraft(
    await csrfRequest<RawDraft>(`/admin/control-center/drafts/${id}`, {
      method: "PUT",
      body: JSON.stringify({
        schemaVersion: CONFIG_SCHEMA_VERSION,
        values,
        expectedRevision,
        requestId: options.requestId ?? newRequestId(),
      }),
    }),
  )
}

export async function validateDraft(
  id: number,
  expectedRevision: number,
  options: { requestId?: string } = {},
): Promise<ValidationResult> {
  const raw = await csrfRequest<ValidationResult & { id: number | string; revision: number | string }>(
    `/admin/control-center/drafts/${id}/validate`,
    {
      method: "POST",
      body: JSON.stringify({ expectedRevision, requestId: options.requestId ?? newRequestId() }),
    },
  )
  return { ...raw, id: Number(raw.id), revision: Number(raw.revision) }
}

// ── WP-01C：发布审批 / 发布 / 回滚 / 版本历史 ────────────────────────────────

export type PublishRequestStatus = "PENDING" | "APPROVED" | "REJECTED" | "STALE"

export type PublishRequest = {
  id: number
  status: PublishRequestStatus
  draftRevision: number
  /** 申请时草稿载荷的 SHA-256；审批人与申请人对账用。 */
  payloadDigest: string
  validationDigest: string
  /** 申请时的活跃 release id；null 表示当时还没发布过。 */
  expectedActiveReleaseId: number | null
  note: string | null
  requestedBy: number | null
  requestedTime: string | null
  decidedBy: number | null
  decidedTime: string | null
  decisionReason: string | null
  /** 批准后生成的 release id；其余状态为 null。 */
  releaseId: number | null
}

export type Release = {
  id: number
  releaseVersion: number
  schemaVersion: number
  digest: string
  /** 非空表示这条 release 由回滚生成，指向被回滚到的来源。 */
  rollbackFromReleaseId: number | null
  createdBy: number | null
  createdTime: string | null
  values: ConfigValues
}

export type PageResult<T> = {
  records: T[]
  total: number
  page: number
  pageSize: number
}

type RawPublishRequest = Omit<
  PublishRequest,
  "id" | "draftRevision" | "expectedActiveReleaseId" | "requestedBy" | "decidedBy" | "releaseId"
> & {
  id: number | string
  draftRevision: number | string
  expectedActiveReleaseId: number | string | null
  requestedBy: number | string | null
  decidedBy: number | string | null
  releaseId: number | string | null
}

type RawRelease = Omit<Release, "id" | "releaseVersion" | "rollbackFromReleaseId" | "createdBy"> & {
  id: number | string
  releaseVersion: number | string
  rollbackFromReleaseId: number | string | null
  createdBy: number | string | null
}

function normalizePublishRequest(raw: RawPublishRequest): PublishRequest {
  return {
    ...raw,
    id: Number(raw.id),
    draftRevision: Number(raw.draftRevision),
    expectedActiveReleaseId: num(raw.expectedActiveReleaseId),
    requestedBy: num(raw.requestedBy),
    decidedBy: num(raw.decidedBy),
    releaseId: num(raw.releaseId),
  }
}

function normalizeRelease(raw: RawRelease): Release {
  return {
    ...raw,
    id: Number(raw.id),
    releaseVersion: Number(raw.releaseVersion),
    rollbackFromReleaseId: num(raw.rollbackFromReleaseId),
    createdBy: num(raw.createdBy),
  }
}

/**
 * 申请发布一版草稿。
 *
 * <p>`expectedActiveReleaseId` 是申请人的「我看到线上是这一版」声明：审批通过时
 * 服务端会拿它和当刻指针比较，不一致就 40900——不会把别人的改动顺手覆盖掉。
 */
export async function requestPublish(
  draftId: number,
  input: {
    revision: number
    expectedActiveReleaseId: number | null
    note?: string
    requestId: string
  },
): Promise<PublishRequest> {
  return normalizePublishRequest(
    await csrfRequest<RawPublishRequest>(
      `/admin/control-center/drafts/${draftId}/publish-requests`,
      {
        method: "POST",
        body: JSON.stringify({
          revision: input.revision,
          expectedActiveReleaseId: input.expectedActiveReleaseId,
          note: input.note,
          requestId: input.requestId,
        }),
      },
    ),
  )
}

/** 批准一条发布申请；返回新生成的不可变 release。 */
export async function approvePublishRequest(id: number, requestId: string): Promise<Release> {
  return normalizeRelease(
    await csrfRequest<RawRelease>(`/admin/control-center/publish-requests/${id}/approve`, {
      method: "POST",
      body: JSON.stringify({ requestId }),
    }),
  )
}

/** 拒绝一条发布申请。拒绝不会产生 release，审批单进入终态。 */
export async function rejectPublishRequest(
  id: number,
  requestId: string,
  reason?: string,
): Promise<PublishRequest> {
  return normalizePublishRequest(
    await csrfRequest<RawPublishRequest>(`/admin/control-center/publish-requests/${id}/reject`, {
      method: "POST",
      body: JSON.stringify({ requestId, reason }),
    }),
  )
}

/**
 * 回滚到某个历史 release。
 *
 * <p>回滚不修改历史：服务端会**新建**一条 release（带 `rollbackFromReleaseId`），
 * 因此返回值的 id/version 都不是目标版本。
 */
export async function rollbackRelease(
  releaseId: number,
  input: { expectedActiveReleaseId: number | null; requestId: string; reason?: string },
): Promise<Release> {
  return normalizeRelease(
    await csrfRequest<RawRelease>(`/admin/control-center/releases/${releaseId}/rollback`, {
      method: "POST",
      body: JSON.stringify({
        expectedActiveReleaseId: input.expectedActiveReleaseId,
        requestId: input.requestId,
        reason: input.reason,
      }),
    }),
  )
}

export async function fetchReleases(page = 1, pageSize = 20): Promise<PageResult<Release>> {
  const raw = await request<PageResult<RawRelease>>(
    `/admin/control-center/releases?page=${page}&pageSize=${pageSize}`,
  )
  return { ...raw, records: raw.records.map(normalizeRelease) }
}

export async function fetchRelease(id: number): Promise<Release> {
  return normalizeRelease(await request<RawRelease>(`/admin/control-center/releases/${id}`))
}

export async function fetchPublishRequests(options: {
  status?: PublishRequestStatus
  page?: number
  pageSize?: number
} = {}): Promise<PageResult<PublishRequest>> {
  const page = options.page ?? 1
  const pageSize = options.pageSize ?? 20
  const status = options.status ? `&status=${options.status}` : ""
  const raw = await request<PageResult<RawPublishRequest>>(
    `/admin/control-center/publish-requests?page=${page}&pageSize=${pageSize}${status}`,
  )
  return { ...raw, records: raw.records.map(normalizePublishRequest) }
}

export async function fetchPublishRequest(id: number): Promise<PublishRequest> {
  return normalizePublishRequest(
    await request<RawPublishRequest>(`/admin/control-center/publish-requests/${id}`),
  )
}
