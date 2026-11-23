import { collaborationFailure } from "./collaborationApi"
import { QUALITY_GATE_REASON_LABELS } from "@/lib/domain"

export type ImageTaskStatus =
  | "PROCESSING"
  | "WAITING_REVIEW"
  | "RESTRICTED"
  | "UNKNOWN"
export type ImageTask = {
  taskId: string
  status: ImageTaskStatus
  /**
   * 仅 RESTRICTED 时有值：准入拒绝的原因码（服务端受控枚举）。
   * 可缺省：老服务端不返回这个字段，缺省时界面只显示「受限」。
   */
  restrictReason?: string | null
}
const base = `${import.meta.env.VITE_API_BASE_URL ?? "/api"}/diagnosis-tasks`

async function request(path: string, signal: AbortSignal, body?: FormData) {
  const response = await fetch(`${base}${path}`, {
    method: body ? "POST" : "GET",
    credentials: "include",
    cache: "no-store",
    signal,
    headers: { Accept: "application/json" },
    ...(body ? { body } : {}),
  })
  // 错误响应体可能不是 JSON（代理错误页、空体）：按同一口径退回报错，
  // 不能让一次解析异常冒充成「网络故障」，也不能把它读成成功。
  let value: { code?: number; msg?: string; data?: unknown } | null = null
  try {
    value = await response.json()
  } catch {
    // 响应体不是 JSON：按同一口径退回报错，不冒充成功。
  }
  if (!response.ok || value?.code !== 0)
    throw collaborationFailure(
      response.status,
      value?.code,
      value?.msg || "TASK_UNAVAILABLE"
    )
  return value.data as unknown
}
const KNOWN_STATUSES: ImageTaskStatus[] = ["PROCESSING", "WAITING_REVIEW", "RESTRICTED"]
function task(value: unknown): ImageTask {
  if (
    !value ||
    typeof value !== "object" ||
    !("taskId" in value) ||
    typeof value.taskId !== "string" ||
    !value.taskId ||
    !("status" in value)
  )
    throw new Error("INVALID_TASK_RESPONSE")
  // 状态未知不等于就绪。降级成 UNKNOWN：形状合法但取值认不出时，
  // 既不当作推理中，也不当作已就绪——交给上层显式拦住受理。
  const raw = String(value.status)
  const status = KNOWN_STATUSES.includes(raw as ImageTaskStatus)
    ? (raw as ImageTaskStatus)
    : "UNKNOWN"
  const reason =
    "restrictReason" in value &&
    typeof value.restrictReason === "string" &&
    value.restrictReason
      ? value.restrictReason
      : null
  return { taskId: value.taskId, status, restrictReason: reason }
}

const RESTRICT_REASONS: Record<string, string> = {
  OOD_NOT_EVALUATED: "分布外检测尚未评估，暂不形成患者结论",
  OOD_EVALUATION_FAILED: "分布外检测失败，暂不形成患者结论",
  OOD_OUT_OF_DISTRIBUTION: "影像不在模型的适用分布内",
  PREDICTION_MISSING: "模型没有给出分类结果",
  IMAGE_UNREADABLE: "影像不可读或与登记摘要不一致",
  ATTEMPTS_EXHAUSTED: "多次推理均未成功",
  INFERENCE_FAILED: "推理服务返回失败",
  INFERENCE_UNAVAILABLE: "推理服务暂时不可用",
  STORAGE_VERSION_MISSING: "影像归档未成功",
}

// `ABSTAIN_` 后面的码与服务端 / 管理端分析共用一份措辞：见 domain.QUALITY_GATE_REASON_LABELS。
const ABSTAIN_REASONS: Record<string, string> = QUALITY_GATE_REASON_LABELS

/**
 * 受限原因 → 一句人话。服务端枚举之外的值原样说明，不猜：
 * 猜错原因比只说「受限」更糟——它会让人按错误的方向处理。
 */
export function restrictReasonLabel(reason: string | null | undefined): string {
  if (!reason) return ""
  if (reason.startsWith("ABSTAIN_")) {
    const inner = reason.slice("ABSTAIN_".length)
    return ABSTAIN_REASONS[inner] ?? `模型输出未通过准入（${inner}）`
  }
  return RESTRICT_REASONS[reason] ?? `准入条件未满足（${reason}）`
}
export const diagnosisTaskApi = {
  async create(file: File, key: string, signal: AbortSignal) {
    const body = new FormData()
    body.set("file", file)
    body.set("idempotencyKey", key)
    return task(await request("", signal, body))
  },
  async get(id: string, signal: AbortSignal) {
    return task(await request(`/${encodeURIComponent(id)}`, signal))
  },
  async list(signal: AbortSignal) {
    const value = await request("", signal)
    if (!Array.isArray(value)) throw new Error("INVALID_TASK_RESPONSE")
    return value.map(task)
  },
}
