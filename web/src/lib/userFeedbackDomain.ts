/**
 * 用户反馈的前端领域层：枚举镜像 + 展示文案 + 纯函数校验。
 *
 * <p>真源在后端：`constant/UserFeedbackCategory`、`constant/UserFeedbackStatus`
 * 与 `UserFeedbackLifecycleService` 的校验。这里只做三件前端才该做的事——
 * 把枚举翻成中文、决定状态标签的语义档位、提交前先挡一次明显填错的输入。
 * 服务端仍是最终裁决者：这里放过的，后端仍可能以 40000 / 40900 拒绝。
 *
 * <p>反馈是产品意见通道，不是医疗记录（见 `GLOSSARY.md`）。状态标签用 RiskTag
 * 的既有词汇（等宽大写 + 方点、永远带文字），不与医学严重度共用同一个读数列。
 */
import type { Risk } from "@/lib/domain"
import type { UserFeedbackCategory, UserFeedbackStatus } from "@/lib/api"

/** 正文上限，与后端 `UserFeedbackLifecycleService.MAX_TEXT` 一致。 */
export const FEEDBACK_TEXT_LIMIT = 2000

/** 截图上限沿用全站单张图片口径（服务端硬限 2 MB）。 */
export const FEEDBACK_ATTACHMENT_TYPES = ["image/jpeg", "image/png"] as const

/** 分类：固定四项，与后端 `UserFeedbackCategory.isValid` 的闭集一一对应。 */
export const FEEDBACK_CATEGORIES = [
  { value: "SUGGESTION", label: "功能建议", hint: "希望新增或改进什么" },
  { value: "ISSUE", label: "使用问题", hint: "哪里点不动、报错或显示不对" },
  { value: "RESULT_DOUBT", label: "结果质疑", hint: "对某次判读或对话的结论有疑问" },
  { value: "OTHER", label: "其他", hint: "不属于上面三项的反馈" },
] as const satisfies ReadonlyArray<{
  value: UserFeedbackCategory
  label: string
  hint: string
}>

type CategoryOption = (typeof FEEDBACK_CATEGORIES)[number]

/** 分类文案索引用一张表：未知值回 undefined，由函数决定兜底话术。 */
const CATEGORY_INDEX = new Map<string, CategoryOption>(
  FEEDBACK_CATEGORIES.map((category) => [String(category.value), category]),
)

/** 分类中文名。未知值原样说明「未分类」，不猜成「其他」。 */
export function feedbackCategoryLabel(value?: string | null): string {
  return (value ? CATEGORY_INDEX.get(value)?.label : undefined) ?? "未分类"
}

/**
 * 状态档位。
 *
 * <p>待处理与处理中都吃 `--risk-attention`：两者都「还没有结论」，用文字区分
 * 先后；已解决用正常档，已忽略用中性的 unknown 档——忽略不是故障，只是关闭。
 * 颜色永远只是辅助，标签文字才是信息载体（DESIGN.md 的三色轴约定）。
 */
export const FEEDBACK_STATUSES = [
  { value: "PENDING", label: "待处理", risk: "attention", hint: "已收到，还没有人处理" },
  { value: "PROCESSING", label: "处理中", risk: "attention", hint: "管理员已受理，正在处理" },
  { value: "RESOLVED", label: "已解决", risk: "normal", hint: "已给出结论" },
  { value: "IGNORED", label: "已忽略", risk: "unknown", hint: "经评估不处理" },
] as const satisfies ReadonlyArray<{
  value: UserFeedbackStatus
  label: string
  risk: Risk
  hint: string
}>

type StatusOption = (typeof FEEDBACK_STATUSES)[number]

/** 状态文案索引用一张表：三处查询共用，未知值回 undefined 各自兜底。 */
const STATUS_INDEX = new Map<string, StatusOption>(
  FEEDBACK_STATUSES.map((status) => [String(status.value), status]),
)

export function feedbackStatusLabel(value?: string | null): string {
  return (value ? STATUS_INDEX.get(value)?.label : undefined) ?? "未记录"
}

export function feedbackStatusRisk(value?: string | null): Risk {
  return (value ? STATUS_INDEX.get(value)?.risk : undefined) ?? "unknown"
}

export function feedbackStatusHint(value?: string | null): string {
  return (value ? STATUS_INDEX.get(value)?.hint : undefined) ?? "状态未记录"
}

/**
 * 合法状态迁移的前端镜像（后端 `UserFeedbackStatus.ALLOWED` 是裁决者）。
 *
 * <p>界面上只列出当前状态下真能做的动作：摆一个必然 409 的按钮，等于教用户
 * 去踩「反馈状态已变化，请刷新后重试」。若两份矩阵漂移，后端仍会拒绝——
 * 这里决定的是「能不能点」，不是「能不能改」。
 */
const ALLOWED_TRANSITIONS: Record<string, readonly UserFeedbackStatus[]> = {
  PENDING: ["PROCESSING", "RESOLVED", "IGNORED"],
  PROCESSING: ["PENDING", "RESOLVED", "IGNORED"],
  RESOLVED: ["PROCESSING"],
  IGNORED: ["PENDING"],
}

/** 当前状态下可迁往的状态；未知状态返回空（先刷新再说）。 */
export function feedbackNextStatuses(current?: string | null): readonly UserFeedbackStatus[] {
  return current ? (ALLOWED_TRANSITIONS[current] ?? []) : []
}

/** 已结案（已解决 / 已忽略）不允许直接追加回复，后端会 409。 */
export function feedbackIsClosed(status?: string | null): boolean {
  return status === "RESOLVED" || status === "IGNORED"
}

/** 关联对象类型的中文名；未知类型原样返回，不冒充「无关联」。 */
export function feedbackTargetTypeLabel(type?: string | null): string {
  if (type === "DIAGNOSIS_TASK") return "判读任务"
  if (type === "AGENT_SESSION") return "Agent 会话"
  return type ?? ""
}

/** 关联对象的一句话描述；没有关联时给「未关联具体对象」。 */
export function feedbackTargetLabel(type?: string | null, id?: string | null): string {
  if (!type) return "未关联具体对象"
  const name = feedbackTargetTypeLabel(type) || type
  return id ? `${name} · ${id}` : name
}

/**
 * 判读任务状态的中文名（`/diagnosis-tasks` 返回的 `SafeStatus.status`）。
 *
 * <p>认不出的取值说「状态未知」：既不当成判读中，也不当成完成——
 * 与任务面板的口径一致，不替服务端猜。
 */
export function imageTaskStatusLabel(status?: string | null): string {
  switch (status) {
    case "PROCESSING":
      return "判读中"
    case "WAITING_REVIEW":
      return "等待复核"
    case "RESTRICTED":
      return "受限"
    default:
      return "状态未知"
  }
}

/** 提交前的正文校验：纯函数，便于单测与复用。 */
export type FeedbackValidation =
  | { ok: true; content: string }
  | { ok: false; message: string }

export function validateFeedbackContent(raw: string): FeedbackValidation {
  const content = raw.trim()
  if (!content) {
    return { ok: false, message: "写一句想说的再提交——空反馈管理员无从处理。" }
  }
  if (content.length > FEEDBACK_TEXT_LIMIT) {
    return {
      ok: false,
      message: `反馈正文最多 ${FEEDBACK_TEXT_LIMIT} 字，现在 ${content.length} 字，删减后再提交。`,
    }
  }
  return { ok: true, content }
}
