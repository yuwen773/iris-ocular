/**
 * 管理域后端契约。端点与请求体对齐 Java Controller；统一 { code:0, data, msg } 信封、
 * 错误抛 ApiError、40100 广播未登录事件，与 lib/api.ts 保持同一套语义。
 *
 * 列表一律走非 /cache 端点——管理端改完要立刻看到新鲜数据，走缓存会让刚删/刚编辑的行
 * 还挂在表里（Vue 版踩过这个坑）。雪花 id 用 string，避免超出 JS 安全整数被截断。
 */

import type { DiagnosisResult } from "./api"
import { ApiError } from "./api-error"
import {
  BASE_URL,
  notifyAuthExpired,
  request,
  toPage,
  type Envelope,
  type Page,
  type RawPage,
} from "./http"

export type { Page } from "./http"

/**
 * 批量动作的回执：请求了几条、成了几条、败了几条、败的是哪些。
 *
 * 批量天然是「部分成功」形态，界面上要能把失败的条目如实报出来，
 * 而不是只回一个 boolean。
 */
export type BatchResult = {
  requested?: number; succeeded?: number; failed?: number
  failedIds?: string[]; message?: string
}

/* ── 用户管理 ─────────────────────────────────────────────── */

export type AdminUser = {
  id: string; userAccount?: string; userName?: string; userAvatar?: string
  userProfile?: string; userRole?: string; createTime?: string
}

/** 用户列表的筛选条件（空串 / undefined 表示不限）。 */
export type UserQuery = {
  current: number; pageSize: number
  userAccount?: string; userName?: string; userRole?: string
  startDate?: string; endDate?: string
  /** 服务端排序：id / userAccount / userName / userRole / createTime。 */
  sortField?: string
  sortOrder?: "ascend" | "descend"
}
export function listUsers(q: UserQuery): Promise<Page<AdminUser>> {
  return request<RawPage<AdminUser>>("/user/list/page/vo", { method: "POST", body: JSON.stringify(q) }).then(toPage)
}
export function deleteUser(id: string): Promise<boolean> {
  return request<boolean>(`/user/delete/${id}`, { method: "POST" })
}
/** 新建用户：账号必填、初始密码必填、角色限 user / ban / admin。返回落库后的 id。 */
export function createUser(body: { userAccount: string; userPassword: string; userName?: string; userProfile?: string; userRole?: string }): Promise<string> {
  return request<string>("/user/add", { method: "POST", body: JSON.stringify(body) })
}
/** 改用户资料（昵称 / 头像 / 简介 / 角色）。id 必填。 */
export function updateUser(body: { id: string; userName?: string; userAvatar?: string; userProfile?: string; userRole?: string }): Promise<boolean> {
  return request<boolean>("/user/update", { method: "POST", body: JSON.stringify(body) })
}
export function deleteUsers(ids: string[]): Promise<BatchResult> {
  return request<BatchResult>("/user/delete/batch", { method: "POST", body: JSON.stringify({ ids }) })
}
export function updateUserRole(ids: string[], userRole: string): Promise<BatchResult> {
  return request<BatchResult>("/user/update/role/batch", { method: "POST", body: JSON.stringify({ ids, userRole }) })
}

/* ── 新闻管理 ─────────────────────────────────────────────── */

export type AdminNews = {
  id?: string; title?: string; author?: string; summary?: string
  content?: string; coverImage?: string; publishTime?: string
  /** 发布状态：0 草稿 / 3 待审核 / 1 已发布 / 2 下架（NewsVO 自 2026-11-09 起暴露）。 */
  status?: number | null
  /** 下架原因（后端在状态转 2 且带 note 时写入；NewsVO 只读暴露）。 */
  offlineReason?: string | null
}
export type NewsQuery = {
  current: number; pageSize: number
  title?: string; status?: number; category?: string
  startDate?: string; endDate?: string
  /** 服务端排序：id / title / status / publishTime / editTime。 */
  sortField?: string
  sortOrder?: "ascend" | "descend"
}
export function listAdminNews(q: NewsQuery): Promise<Page<AdminNews>> {
  return request<RawPage<AdminNews>>("/news/list/page/vo", { method: "POST", body: JSON.stringify(q) }).then(toPage)
}
export function deleteNews(id: string): Promise<boolean> {
  return request<boolean>(`/news/delete/${id}`, { method: "POST" })
}
/** 批量软删资讯：逐条执行，部分失败如实回执。 */
export function deleteNewsBatch(ids: string[]): Promise<BatchResult> {
  return request<BatchResult>("/news/delete/batch", { method: "POST", body: JSON.stringify({ ids }) })
}
/** 批量改发布状态：逐条按状态迁移表校验，非法迁移的那条不执行。 */
export function changeNewsStatusBatch(ids: string[], status: number, note?: string): Promise<BatchResult> {
  return request<BatchResult>("/news/status/batch", { method: "POST", body: JSON.stringify({ ids, status, note }) })
}
export function getNews(id: string): Promise<AdminNews> {
  return request<AdminNews>(`/news/get/${id}`, { method: "GET" })
}
/**
 * 创建或编辑新闻。id 缺席即创建——后端返回落库后的 id（Long 全局序列化成 String），
 * 调用方据此回填路由；编辑时返回同一个 id。
 * note 是审核意见 / 下架原因，仅状态迁移时写入审核留痕。
 */
export function saveNews(body: { id?: string; title: string; content: string; status?: number; coverImage?: string; note?: string }): Promise<string> {
  return request<string>("/news/edit", { method: "POST", body: JSON.stringify(body) })
}

/**
 * 封面上传：id 落在 query、file 走 multipart。`request<T>` 本来就支持
 * FormData，只是路径里自带 query——不需要再直连 fetch 手写信封。
 */
export function uploadNewsCover(id: string, file: File): Promise<string> {
  const form = new FormData()
  form.append("file", file)
  return request<string>(`/news/uploadCover?id=${encodeURIComponent(id)}`, {
    method: "POST",
    body: form,
  })
}

/* ── 诊断管理 ─────────────────────────────────────────────── */

export type AdminDiagnosis = {
  id: string; url?: string; username?: string; result?: string | null
  /**
   * 只有真正校准过才有值（后端按 CALIBRATED 门控）；AI 拒识的记录同样为 null——
   * 读数要显示「—」，不能画成 0%。未标定时改用 {@link classificationProbability}。
   */
  confidence?: number | null
  /** 校准状态：CALIBRATED / NOT_CALIBRATED；老记录为 null，按「未标定」处理。 */
  calibrationStatus?: string | null
  /** 原始 softmax top-1（0–1）：模型输出强度，不是校准概率，也不是可靠性。 */
  classificationProbability?: number | null
  severity?: string | null; reexamineStatus?: number; createTime?: string
  aiRejected?: boolean; abstainReason?: string; requiresReview?: boolean
}
/** 诊断列表的筛选条件：疾病名 + 复核状态 + AI 拒识 + 判读时间区间。 */
export type DiagnosisQuery = {
  current: number; pageSize: number; result?: string
  /** 复诊状态：0 未复审（历史值 1 同义）/ 2 复审中 / 3 已复审 */
  reexamineStatus?: number
  /** 人工复核状态：AWAITING_REVIEW / REVIEWED / OVERRIDDEN … */
  humanReviewStatus?: string
  /** 只看 AI 拒识的记录 */
  aiRejected?: boolean
  /** 只看需要人工复核的记录 */
  requiresReview?: boolean
  startDate?: string; endDate?: string
  /** 服务端排序：id / result / confidence / severity / createTime。 */
  sortField?: string
  sortOrder?: "ascend" | "descend"
}
export function listDiagnoses(q: DiagnosisQuery): Promise<Page<AdminDiagnosis>> {
  return request<RawPage<AdminDiagnosis>>("/diagnosis/list/page/vo", { method: "POST", body: JSON.stringify(q) }).then(toPage)
}
export function getDiagnosis(id: string): Promise<DiagnosisResult> {
  return request<DiagnosisResult>(`/diagnosis/get/${id}`, { method: "GET" })
}
/**
 * 正式临床复核写入**已安全关闭**（D-01b）：服务端恒拒绝，且理由是「系统尚无核验过的
 * 医生资质」，不是「你没有权限」。这里保留导出只是为了历史调用点可追溯；
 * **不要**在新代码里使用——页面上已无调用方。
 *
 * @deprecated 写入路径已在服务层安全关闭，调用必然失败。
 */
export function submitReview(body: { id: string; humanResult: string; humanSeverity?: string; humanRecommendations?: string; humanReviewNote?: string }): Promise<boolean> {
  return request<boolean>("/diagnosis/update", { method: "POST", body: JSON.stringify(body) })
}
export function deleteDiagnosis(id: string): Promise<boolean> {
  return request<boolean>(`/diagnosis/delete/${id}`, { method: "POST" })
}
/** 批量删除判读记录：逐条执行，部分失败如实回执。 */
export function deleteDiagnoses(ids: string[]): Promise<BatchResult> {
  return request<BatchResult>("/diagnosis/delete/batch", { method: "POST", body: JSON.stringify({ ids }) })
}

/* ── 知识库 ───────────────────────────────────────────────── */

// 后端 JsonConfig 全局 Long→String，KnowledgeDoc 字段线上均为 string（含非雪花 id）。
export type KnowledgeDoc = {
  id: string; userId: string; title: string; fileName: string; fileUrl: string
  fileSize: string; fileType: string; chunkCount: number
  status: number; createTime: string; updateTime: string
}

/** 知识库列表的筛选条件。 */
export type KnowledgeQuery = {
  current: number; pageSize: number
  fileName?: string; title?: string; status?: number; fileType?: string
  startDate?: string; endDate?: string
}

/** 一条切片：序号 + 正文 + 字符数。 */
export type KnowledgeChunk = { index: number; text: string; chars: number }

/** 文档详情：元信息 + 预览链接 + 切片清单。 */
export type KnowledgeDetail = {
  id: string; title: string; fileName: string; fileType: string
  /** 后端 JsonConfig 全局 Long→String，fileSize 线上是字符串 */
  fileSize: number | string; chunkCount: number; status: number; userId: string
  createTime: string; updateTime: string
  /** 限时签名链接（10 分钟有效） */
  previewUrl?: string | null
  /** 预览方式：pdf / text / none */
  previewKind?: string | null
  /** 纯文本直读内容（text 类文档） */
  textPreview?: string | null
  chunks: KnowledgeChunk[]
  chunksReadable: boolean
  /** 切片是否因数量上限被截断（真值时界面必须写明「最多显示 N 块」） */
  chunksTruncated?: boolean
  chunksMessage?: string | null
}

/** 后端 KnowledgeUploadRequest 收 MultipartFile[] files + 可选 title，字段名照 Vue 先例逐个 append。 */
/** 线上返回的是 id 数组，JsonConfig 全局 Long→String，所以是 string[]。 */
export function uploadKnowledge(files: File[], title?: string): Promise<string[]> {
  const form = new FormData()
  files.forEach((file) => form.append("files", file))
  if (title) form.append("title", title)
  return request<string[]>("/knowledge/upload", { method: "POST", body: form })
}
export function listKnowledge(q: KnowledgeQuery): Promise<Page<KnowledgeDoc>> {
  return request<RawPage<KnowledgeDoc>>("/knowledge/list/page", { method: "POST", body: JSON.stringify(q) }).then(toPage)
}
/** DELETE 带 body，后端 DeleteRequest 收 {id}；不能塞进 query。 */
export function deleteKnowledge(id: string): Promise<boolean> {
  return request<boolean>("/knowledge/delete", { method: "DELETE", body: JSON.stringify({ id }) })
}
/** 批量删除知识文档：逐条鉴权与执行，部分失败如实回执。 */
export function deleteKnowledgeBatch(ids: string[]): Promise<BatchResult> {
  return request<BatchResult>("/knowledge/delete/batch", { method: "DELETE", body: JSON.stringify({ ids }) })
}
/** 改文档标题（只改人读的名字，不动文件本体与切片）。 */
export function updateKnowledgeTitle(id: string, title: string): Promise<boolean> {
  return request<boolean>("/knowledge/update", { method: "POST", body: JSON.stringify({ id, title }) })
}
/** 文档详情：元信息 + 限时预览链接 + 切片清单。 */
export function getKnowledgeDetail(id: string): Promise<KnowledgeDetail> {
  return request<KnowledgeDetail>(`/knowledge/get/${id}`, { method: "GET" })
}

/**
 * 原文代理地址（管理端阅读器用）。
 *
 * 后端按 owner / 管理员规则鉴权后把 COS 私有对象流出来；浏览器不接触签名、
 * 也没有长期直链。PDF 直接塞进 iframe，文本与 DOCX 由前端 fetch 成字节再渲染。
 */
export function knowledgeContentUrl(id: string, download = false): string {
  const suffix = download ? "?download=1" : ""
  return `${BASE_URL}/knowledge/${encodeURIComponent(id)}/content${suffix}`
}

/**
 * 取原文字节。返回 type 让调用方按格式分支：
 * - text：文本（Markdown / TXT）
 * - bytes：二进制（DOCX）
 *
 * 鉴权失败时后端仍走 { code, msg } 信封（HTTP 200 + JSON），这里按 content-type
 * 识别并抛 ApiError；401xx 与全站一致广播未登录事件。
 */
async function fetchKnowledgeContent(
  id: string,
  as: "text" | "bytes",
  signal?: AbortSignal,
): Promise<string | ArrayBuffer> {
  let response: Response
  try {
    response = await fetch(knowledgeContentUrl(id), { credentials: "include", signal })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new ApiError(-1, "网络不可用，请检查连接后重试")
  }

  const contentType = response.headers.get("content-type") ?? ""
  if (contentType.includes("application/json")) {
    // 「响应是 JSON」不等于「出错了」：错误信封是 { code, msg }，但端点将来也可能
    // 合法返回 JSON 正文。按 http.ts 的口径——只有数值 code 且不等于 0 才算错误。
    const raw = await response.text()
    let payload: Envelope<unknown> | undefined
    try {
      payload = JSON.parse(raw) as Envelope<unknown>
    } catch {
      payload = undefined
    }
    const code = payload?.code
    if (typeof code === "number" && code !== 0) {
      if (code === 40100 || code === 40101) notifyAuthExpired(code)
      throw new ApiError(code, payload?.msg || "原文读取失败")
    }
    if (!response.ok) {
      throw new ApiError(response.status, `服务返回 ${response.status}`)
    }
    return as === "text" ? raw : (new TextEncoder().encode(raw).buffer as ArrayBuffer)
  }
  if (!response.ok) {
    throw new ApiError(response.status, `服务返回 ${response.status}`)
  }
  return as === "text" ? response.text() : response.arrayBuffer()
}

/** 读取 Markdown / TXT 原文。 */
export function fetchKnowledgeText(id: string, signal?: AbortSignal): Promise<string> {
  return fetchKnowledgeContent(id, "text", signal) as Promise<string>
}

/** 读取 DOCX 字节，交给浏览器内的 mammoth 解析。 */
export function fetchKnowledgeDocx(id: string, signal?: AbortSignal): Promise<ArrayBuffer> {
  return fetchKnowledgeContent(id, "bytes", signal) as Promise<ArrayBuffer>
}

/* ── 统计 ─────────────────────────────────────────────────── */

/**
 * 统计总览（仪表盘）。数据分析页自 B2 起走 {@link getAdminAnalytics}，
 * 两页不再共用同一份响应。
 */
export type AdminStats = {
  days: number; rangeStart: string; rangeEnd: string
  /** 计数缺失时保持 undefined：界面显示「—」，不把未知补成 0。 */
  userTotal?: number; userNew?: number; previousUserNew?: number; userAdmin?: number
  diagnosisTotal?: number; diagnosisInRange?: number; previousDiagnosisInRange?: number
  diagnosisToday?: number
  diagnosisAiRejected?: number; diagnosisAwaitingReview?: number; diagnosisReviewed?: number
  /** 待人工复核积压（全站累计）：B3 起不再作为当前待办展示 */
  diagnosisAwaitingReviewTotal?: number
  /** 平均置信度（0–100 量纲，模型自评；无样本时为 null）；B3 起不在仪表盘展示 */
  avgConfidence: number | null
  knowledgeTotal?: number; knowledgeChunks?: number; knowledgeFailed?: number
  knowledgeByStatus?: StatBucket[]
  newsTotal?: number; newsByStatus?: StatBucket[]
  trendDays?: string[]
  diagnosisTrend?: StatDayCount[]
  /** 上一等长窗口的日序列（与 diagnosisTrend 按天对齐） */
  previousDiagnosisTrend?: StatDayCount[]
  previousUserTrend?: StatDayCount[]
  userTrend?: StatDayCount[]
  diagnosisDistribution?: StatBucket[]
  reviewDistribution?: StatBucket[]
  diagnosisTrendByResult?: StatDayNameCount[]
}
export type StatBucket = { name: string; value: number }
export type StatDayCount = { day: string; value: number }
export type StatDayNameCount = { day: string; name: string; value: number }

export function getAdminStats(days: number): Promise<AdminStats> {
  return request<RawAdminStats>(`/stats/admin/overview?days=${days}`, { method: "GET" }).then(toStats)
}

/**
 * 统计读数的数字归一。
 *
 * <p>后端 JsonConfig 全局把 Long / long 序列化成 String（为了雪花 id 不丢精度），
 * 所以这里的计数全是 "1234" 这种字符串形态。若不归一，rankDistribution 里的
 * `sum + row.value` 会变成字符串拼接（"0" + "12" = "012"），占比算出来是错的。
 * 这里统一转成 number，null / 非法值按 0 处理。
 */
function num(value: unknown): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

/**
 * 计数归一：缺失 / null / 非法值保持 undefined，让界面能说「未知」。
 * 真实 0 仍然返回 0——「读到是零」和「字段没来」不能混成一个数。
 */
function optNum(value: unknown): number | undefined {
  if (value === undefined || value === null || value === "") return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

type RawAdminStats = Omit<AdminStats, "userTotal" | "userNew" | "userAdmin" | "diagnosisTotal"
  | "diagnosisInRange" | "diagnosisToday" | "diagnosisAiRejected" | "diagnosisAwaitingReview"
  | "diagnosisReviewed" | "diagnosisAwaitingReviewTotal" | "knowledgeTotal" | "knowledgeChunks"
  | "knowledgeFailed" | "newsTotal" | "previousUserNew" | "previousDiagnosisInRange"
  | "knowledgeByStatus" | "newsByStatus" | "diagnosisTrend" | "userTrend"
  | "previousDiagnosisTrend" | "previousUserTrend"
  | "diagnosisDistribution" | "reviewDistribution" | "diagnosisTrendByResult"> & {
  userTotal?: number | string; userNew?: number | string; previousUserNew?: number | string
  userAdmin?: number | string
  diagnosisTotal?: number | string; diagnosisInRange?: number | string
  previousDiagnosisInRange?: number | string
  diagnosisToday?: number | string; diagnosisAiRejected?: number | string
  diagnosisAwaitingReview?: number | string; diagnosisReviewed?: number | string
  diagnosisAwaitingReviewTotal?: number | string
  knowledgeTotal?: number | string; knowledgeChunks?: number | string
  knowledgeFailed?: number | string; newsTotal?: number | string
  knowledgeByStatus?: { name: string; value: number | string }[]
  newsByStatus?: { name: string; value: number | string }[]
  diagnosisTrend?: { day: string; value: number | string }[]
  userTrend?: { day: string; value: number | string }[]
  previousDiagnosisTrend?: { day: string; value: number | string }[]
  previousUserTrend?: { day: string; value: number | string }[]
  diagnosisDistribution?: { name: string; value: number | string }[]
  reviewDistribution?: { name: string; value: number | string }[]
  diagnosisTrendByResult?: { day: string; name: string; value: number | string }[]
}

function toStats(raw: RawAdminStats): AdminStats {
  const buckets = (rows?: { name: string; value: number | string }[]) =>
    rows?.map((row) => ({ name: row.name, value: num(row.value) }))
  const days = (rows?: { day: string; value: number | string }[]) =>
    rows?.map((row) => ({ day: row.day, value: num(row.value) }))
  return {
    days: num(raw.days),
    rangeStart: raw.rangeStart ?? "",
    rangeEnd: raw.rangeEnd ?? "",
    userTotal: optNum(raw.userTotal),
    userNew: optNum(raw.userNew),
    previousUserNew: optNum(raw.previousUserNew),
    userAdmin: optNum(raw.userAdmin),
    diagnosisTotal: optNum(raw.diagnosisTotal),
    diagnosisInRange: optNum(raw.diagnosisInRange),
    previousDiagnosisInRange: optNum(raw.previousDiagnosisInRange),
    diagnosisToday: optNum(raw.diagnosisToday),
    diagnosisAiRejected: optNum(raw.diagnosisAiRejected),
    diagnosisAwaitingReview: optNum(raw.diagnosisAwaitingReview),
    diagnosisReviewed: optNum(raw.diagnosisReviewed),
    diagnosisAwaitingReviewTotal: optNum(raw.diagnosisAwaitingReviewTotal),
    avgConfidence: raw.avgConfidence == null ? null : num(raw.avgConfidence),
    knowledgeTotal: optNum(raw.knowledgeTotal),
    knowledgeChunks: optNum(raw.knowledgeChunks),
    knowledgeFailed: optNum(raw.knowledgeFailed),
    knowledgeByStatus: buckets(raw.knowledgeByStatus),
    newsTotal: optNum(raw.newsTotal),
    newsByStatus: buckets(raw.newsByStatus),
    trendDays: raw.trendDays,
    diagnosisTrend: days(raw.diagnosisTrend),
    userTrend: days(raw.userTrend),
    previousDiagnosisTrend: days(raw.previousDiagnosisTrend),
    previousUserTrend: days(raw.previousUserTrend),
    diagnosisDistribution: buckets(raw.diagnosisDistribution),
    reviewDistribution: buckets(raw.reviewDistribution),
    diagnosisTrendByResult: (raw.diagnosisTrendByResult ?? []).map((row) => ({
      day: row.day,
      name: row.name,
      value: num(row.value),
    })),
  }
}

/* ── 数据分析（B2） ───────────────────────────────────────── */

/**
 * 数据分析读数。字段与口径对照见
 * `docs/design/iris22-admin-analytics-b2-field-mapping.md`。
 *
 * <p>与总览的关键差异：这里**没有**逐日趋势、病种构成、知识库/资讯读数。
 * 页面不得拿总览数据补齐这些缺口——那会让两页重新耦合。
 */
export type AnalyticsReasonStat = {
  reason: string
  count: number
  /** 后端给定分母算出的占比；分母不可用时为 null */
  share: number | null
}
export type AnalyticsStatusStat = { status: string; count: number }
export type AnalyticsReviewerStat = {
  /** 雪花 id 经全局 Long→String，可能为 null（历史数据缺复核人） */
  reviewerId: string | null
  reviewerName: string | null
  reviewed: number
  overridden: number
  avgMinutes: number | null
}
export type AnalyticsReview = {
  /** 后端只对复核领域给出数据状态；当前恒为 HISTORICAL_ONLY */
  dataStatus: string
  awaitingTotal: number
  queuedInWindow: number
  completedInWindow: number
  completionRate: number | null
  reviewedInWindow: number
  overriddenInWindow: number
  overrideRate: number | null
  latencySamples: number
  avgMinutes: number | null
  p50Minutes: number | null
  p90Minutes: number | null
  maxMinutes: number | null
  reviewers: AnalyticsReviewerStat[]
  queueReasons: AnalyticsReasonStat[]
  unclassifiedReasonCount: number
  unknownReasonCount: number
}
export type AnalyticsRejection = {
  total: number
  rejected: number
  rate: number | null
  reasons: AnalyticsReasonStat[]
  unclassifiedReasonCount: number
  unknownReasonCount: number
}
export type AnalyticsModelVersion = {
  modelVersion: string
  count: number
  rejected: number
  rejectionRate: number | null
  /** 0–100 量纲；只由 1 < confidence <= 100 的样本计算；无样本为 null */
  avgConfidence: number | null
  confidenceSamples: number
  ambiguousConfidence: number
}
export type AnalyticsConfidence = {
  samples: number
  avg: number | null
  ambiguous: number
  outOfRange: number
  missing: number
}
export type AnalyticsHistogramBucket = { bucket: string; count: number }
export type AnalyticsQualityScore = { score: number | null; count: number }
export type AnalyticsModel = {
  versions: AnalyticsModelVersion[]
  confidence: AnalyticsConfidence
  confidenceHistogram: AnalyticsHistogramBucket[]
  qualityScores: AnalyticsQualityScore[]
  qualityOutOfContract: number
  qualityUnknown: number
  qualityReasons: AnalyticsReasonStat[]
  unclassifiedQualityReasonCount: number
  oodStatuses: AnalyticsStatusStat[]
  calibrationStatuses: AnalyticsStatusStat[]
}
export type AnalyticsDataQuality = {
  unknownReviewStatuses: AnalyticsStatusStat[]
  unknownOodStatuses: AnalyticsStatusStat[]
  unknownCalibrationStatuses: AnalyticsStatusStat[]
  qualityOutOfContract: number
  ambiguousConfidence: number
  confidenceOutOfRange: number
  confidenceMissing: number
  reviewedWithoutTime: number
}
export type AdminAnalytics = {
  days: number
  rangeStart: string
  rangeEnd: string
  review: AnalyticsReview
  rejection: AnalyticsRejection
  model: AnalyticsModel
  dataQuality: AnalyticsDataQuality
}

export function getAdminAnalytics(days: number): Promise<AdminAnalytics> {
  return request<Record<string, unknown>>(`/stats/admin/analytics?days=${days}`, {
    method: "GET",
  }).then(toAnalytics)
}

/* 转换层的小工具：响应字段可能缺失或类型漂移，读不到时给安全的「未知」，
   不把缺失读成 0。 */

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function asRows(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.map(asRecord) : []
}

function textOf(value: unknown): string {
  return typeof value === "string" ? value : ""
}

/** 可空数值：null / undefined / 非数字 → null（「不适用」），绝不变成 0。 */
function optionalNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 顶层领域必须存在：缺了整个对象时抛错，让页面进错误态，
 * 而不是把「响应坏了一半」渲染成一排 0。
 */
/**
 * 统计响应残缺的本地错误码：不与传输失败（-1）混用，
 * 否则「响应少了字段」会被读成「连不上服务」。
 */
const STATS_PAYLOAD_CODE = -2

function requireDomain(raw: Record<string, unknown>, key: string): Record<string, unknown> {
  const value = raw[key]
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiError(STATS_PAYLOAD_CODE, `统计响应缺少 ${key} 领域`)
  }
  return asRecord(value)
}

function reasonRows(value: unknown): AnalyticsReasonStat[] {
  return asRows(value).map((row) => ({
    // 后端已把空原因并成 UNRECORDED；这里的兜底只为防御半截响应。
    reason: textOf(row.reason) || "UNRECORDED",
    count: num(row.count),
    share: optionalNumber(row.share),
  }))
}

function statusRows(value: unknown): AnalyticsStatusStat[] {
  return asRows(value).map((row) => ({
    status: textOf(row.status) || "UNRECORDED",
    count: num(row.count),
  }))
}

function toAnalytics(raw: Record<string, unknown>): AdminAnalytics {
  // `code: 0` 但 `data: null` 也是坏响应：必须先挡住，否则下面读
  // `raw[key]` 会抛原生 TypeError，绕过 ApiError 码，页面认不出是载荷问题。
  if (raw === null || raw === undefined || typeof raw !== "object" || Array.isArray(raw)) {
    throw new ApiError(STATS_PAYLOAD_CODE, "统计响应为空")
  }
  const review = requireDomain(raw, "review")
  const rejection = requireDomain(raw, "rejection")
  const model = requireDomain(raw, "model")
  const dataQuality = requireDomain(raw, "dataQuality")
  const confidence = requireDomain(model, "confidence")

  return {
    days: num(raw.days),
    rangeStart: textOf(raw.rangeStart),
    rangeEnd: textOf(raw.rangeEnd),
    review: {
      // 缺失时给空串：页面据此显示「无法确认复核数据状态」，不冒充历史/当前。
      dataStatus: textOf(review.dataStatus),
      awaitingTotal: num(review.awaitingTotal),
      queuedInWindow: num(review.queuedInWindow),
      completedInWindow: num(review.completedInWindow),
      completionRate: optionalNumber(review.completionRate),
      reviewedInWindow: num(review.reviewedInWindow),
      overriddenInWindow: num(review.overriddenInWindow),
      overrideRate: optionalNumber(review.overrideRate),
      latencySamples: num(review.latencySamples),
      avgMinutes: optionalNumber(review.avgMinutes),
      p50Minutes: optionalNumber(review.p50Minutes),
      p90Minutes: optionalNumber(review.p90Minutes),
      maxMinutes: optionalNumber(review.maxMinutes),
      reviewers: asRows(review.reviewers).map((row) => ({
        reviewerId: row.reviewerId == null ? null : String(row.reviewerId),
        reviewerName: textOf(row.reviewerName) || null,
        reviewed: num(row.reviewed),
        overridden: num(row.overridden),
        avgMinutes: optionalNumber(row.avgMinutes),
      })),
      queueReasons: reasonRows(review.queueReasons),
      unclassifiedReasonCount: num(review.unclassifiedReasonCount),
      unknownReasonCount: num(review.unknownReasonCount),
    },
    rejection: {
      total: num(rejection.total),
      rejected: num(rejection.rejected),
      rate: optionalNumber(rejection.rate),
      reasons: reasonRows(rejection.reasons),
      unclassifiedReasonCount: num(rejection.unclassifiedReasonCount),
      unknownReasonCount: num(rejection.unknownReasonCount),
    },
    model: {
      versions: asRows(model.versions).map((row) => ({
        modelVersion: textOf(row.modelVersion) || "UNRECORDED",
        count: num(row.count),
        rejected: num(row.rejected),
        rejectionRate: optionalNumber(row.rejectionRate),
        avgConfidence: optionalNumber(row.avgConfidence),
        confidenceSamples: num(row.confidenceSamples),
        ambiguousConfidence: num(row.ambiguousConfidence),
      })),
      confidence: {
        samples: num(confidence.samples),
        avg: optionalNumber(confidence.avg),
        ambiguous: num(confidence.ambiguous),
        outOfRange: num(confidence.outOfRange),
        missing: num(confidence.missing),
      },
      confidenceHistogram: asRows(model.confidenceHistogram).map((row) => ({
        bucket: textOf(row.bucket),
        count: num(row.count),
      })),
      qualityScores: asRows(model.qualityScores).map((row) => ({
        score: optionalNumber(row.score),
        count: num(row.count),
      })),
      qualityOutOfContract: num(model.qualityOutOfContract),
      qualityUnknown: num(model.qualityUnknown),
      qualityReasons: reasonRows(model.qualityReasons),
      unclassifiedQualityReasonCount: num(model.unclassifiedQualityReasonCount),
      oodStatuses: statusRows(model.oodStatuses),
      calibrationStatuses: statusRows(model.calibrationStatuses),
    },
    dataQuality: {
      unknownReviewStatuses: statusRows(dataQuality.unknownReviewStatuses),
      unknownOodStatuses: statusRows(dataQuality.unknownOodStatuses),
      unknownCalibrationStatuses: statusRows(dataQuality.unknownCalibrationStatuses),
      qualityOutOfContract: num(dataQuality.qualityOutOfContract),
      ambiguousConfidence: num(dataQuality.ambiguousConfidence),
      confidenceOutOfRange: num(dataQuality.confidenceOutOfRange),
      confidenceMissing: num(dataQuality.confidenceMissing),
      reviewedWithoutTime: num(dataQuality.reviewedWithoutTime),
    },
  }
}
