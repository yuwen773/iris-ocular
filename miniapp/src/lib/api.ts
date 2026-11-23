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
  createdTime?: string
  updatedTime?: string
  isPinned?: boolean
}

export interface AgentMessage {
  id?: number
  role?: string
  subType?: string
  content?: string
  createdTime?: string
}

export interface ChatHistory {
  messages: AgentMessage[]
  hasMore: boolean
  nextCursor?: number
  uncommittedTerminal?: boolean
}

/** SSE 无名事件里的数据（MessageDTO 的展示子集）。 */
export interface AgentChunk {
  content?: string
  messageType?: string
  toolStatus?: string
  stepLabel?: string
  finishReason?: string
  errorCategory?: string
  timestamp?: string
}

export function getDiagnosis(id: string | number): Promise<DiagnosisResult> {
  return request<DiagnosisResult>(`/diagnosis/get/${id}`)
}

export function listMyDiagnoses(current: number, pageSize = 10): Promise<PageResult<DiagnosisSummary>> {
  return request<PageResult<DiagnosisSummary>>('/diagnosis/list/page/my', {
    method: 'POST',
    data: { current, pageSize },
  })
}

export function cancelDiagnosis(runId: string): Promise<{ runId: string; outcome: string }> {
  return request(`/diagnosis/cancel/${encodeURIComponent(runId)}`, { method: 'POST' })
}

export function listAgentSessions(): Promise<AgentSession[]> {
  return request<AgentSession[]>('/agent/sessions?limit=20')
}

export function getAgentHistory(threadId: string): Promise<ChatHistory> {
  return request<ChatHistory>(`/agent/history/${encodeURIComponent(threadId)}?limit=40`)
}
