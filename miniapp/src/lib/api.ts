import type { LoginUser } from './auth'
import { request } from './request'

/** 与后端 VO 对齐的最小字段集：只取患者端真正会展示的字段。 */
export interface Disease {
  name?: string
  probability?: number
}

export interface DiagnosisResult {
  id?: string
  url?: string
  result?: string
  probability?: number
  confidence?: number
  recommendations?: string
  details?: string
  severity?: string
  createTime?: string
  otherDisease?: Disease[]
  aiRejected?: boolean
  abstainReason?: string
  modelVersion?: string
  requiresReview?: boolean
  disclaimer?: string
  qualityScore?: number
  qualityReasons?: string
  calibratedProbability?: number
  calibrationStatus?: string
  classificationProbability?: number
  imageQualityState?: string
  patientConclusionAllowed?: boolean
  oodStatus?: string
  oodScore?: number
}

export interface DiagnosisSummary {
  id?: string
  url?: string
  result?: string
  confidence?: number
  classificationProbability?: number
  calibrationStatus?: string
  severity?: string
  createTime?: string
  aiRejected?: boolean
  abstainReason?: string
  requiresReview?: boolean
  patientConclusionAllowed?: boolean
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export interface AgentSession {
  sessionId: string
  title?: string
  lastMessage?: string
  messageCount?: number
  /** 后端序列化 Date 可能给 ISO 串，也可能给 epoch 毫秒。 */
  createdTime?: string | number
  updatedTime?: string | number
  isPinned?: boolean
}

/**
 * 历史行。`role` 落库为大写（`USER` / `ASSISTANT`），`subType` 是落库词汇表
 * （`text` / `reasoning` / `tool-call` / `tool-result` / `mcp-confirm` / `turn-end` /
 * `user-attachments-v1`），筛选规则见 `lib/agentHistory.ts`。
 */
export interface AgentMessage {
  id?: string | number
  role?: string
  /** 旧行没有子类型，服务端可能是 null。 */
  subType?: string | null
  content?: string
  isSummary?: boolean
  createdTime?: string
}

export interface ChatHistory {
  messages: AgentMessage[]
  hasMore: boolean
  nextCursor?: string | null
  hasNewer?: boolean
  newerCursor?: string | null
  uncommittedTerminal?: boolean
}

/** 会话资源（检索命中的知识库片段等）：只取端上判定证据要用的字段。 */
export interface ResourceRef {
  id?: string
  kind?: string
  title?: string
  source?: string
  url?: string
  documentId?: string
  chunkIndex?: number
}

/** SSE 无名事件里的数据（MessageDTO 的展示子集）。 */
export interface AgentChunk {
  content?: string
  messageType?: string
  toolStatus?: string
  toolCallId?: string
  toolName?: string
  stepLabel?: string
  finishReason?: string
  errorCategory?: string
  timestamp?: string
  /** 只在成功的工具终态帧上出现：检索真实命中的文档片段。 */
  resources?: ResourceRef[]
}

export function getDiagnosis(id: string | number): Promise<DiagnosisResult> {
  return request<DiagnosisResult>(`/diagnosis/get/${id}`)
}

/** 当前登录用户。放在这里而不是 auth.ts：它需要 request.ts 的重登口径，而 auth.ts 是它的下层。 */
export function fetchCurrentUser(): Promise<LoginUser> {
  return request<LoginUser>('/user/get/login')
}

export function listMyDiagnoses(current: number, pageSize = 10): Promise<PageResult<DiagnosisSummary>> {
  return request<PageResult<DiagnosisSummary>>('/diagnosis/list/page/my', {
    method: 'POST',
    data: { current, pageSize },
  })
}

/** 与服务端 `DiagnosisRunService.CancelOutcome` 同值：取消是否真的赢下了这次运行。 */
export type CancelOutcome = 'CANCELLED' | 'ALREADY_FINISHED' | 'NOT_FOUND'

export function cancelDiagnosis(runId: string): Promise<{ runId: string; outcome: CancelOutcome }> {
  return request<{ runId: string; outcome: CancelOutcome }>(`/diagnosis/cancel/${encodeURIComponent(runId)}`, {
    method: 'POST',
  })
}

export function listAgentSessions(): Promise<AgentSession[]> {
  return request<AgentSession[]>('/agent/sessions?limit=20')
}

export function getAgentHistory(threadId: string, beforeId?: string): Promise<ChatHistory> {
  let path = `/agent/history/${encodeURIComponent(threadId)}?limit=40`
  if (beforeId) path += `&beforeId=${encodeURIComponent(beforeId)}`
  return request<ChatHistory>(path)
}
