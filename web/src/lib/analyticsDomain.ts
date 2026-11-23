/**
 * 数据分析页的展示映射与指标口径元数据。
 *
 * <p>三条纪律，来自 B2 规格与 B0 契约：
 * <ol>
 *   <li>只读后端字段，不在前端推断业务状态（尤其不自行把 HISTORICAL_ONLY 改成「正常」）；</li>
 *   <li>比率/均值/时延为 null 时显示「—」，不补 0；</li>
 *   <li>口径文案与 `docs/design/iris22-admin-metric-registry.md` 对齐，改口径先改那儿。</li>
 * </ol>
 */

import type {
  AdminAnalytics,
  AnalyticsHistogramBucket,
  AnalyticsQualityScore,
  AnalyticsReasonStat,
  AnalyticsReviewerStat,
  AnalyticsStatusStat,
} from "@/lib/adminApi"
import { humanReviewStatusLabel } from "@/lib/adminDomain"

/* ── 窗口与分区 ───────────────────────────────────────────── */

/** 后端只认 `days`；这里列出的四个窗口是唯一入口。 */
export const ANALYTICS_RANGES = [7, 30, 90, 365] as const

export function parseAnalyticsDays(value: string | null): number {
  const parsed = Number(value)
  return (ANALYTICS_RANGES as readonly number[]).includes(parsed) ? parsed : 30
}

export const ANALYTICS_SECTIONS = [
  { id: "overview", label: "分析概览" },
  { id: "review", label: "判读与复核" },
  { id: "model", label: "模型与图像质量" },
  { id: "quality", label: "数据质量" },
] as const

export type AnalyticsSectionId = (typeof ANALYTICS_SECTIONS)[number]["id"]

export function parseAnalyticsSection(value: string | null): AnalyticsSectionId {
  const found = ANALYTICS_SECTIONS.find((section) => section.id === value)
  return found?.id ?? "overview"
}

/* ── 读数格式化 ───────────────────────────────────────────── */

export function formatCount(value: number): string {
  return Number.isFinite(value) ? Math.round(value).toLocaleString("zh-CN") : "—"
}

/** 0–1 的比率 → 百分比；null（分母不可用）显示破折号。 */
export function formatRate(value: number | null): string {
  return value == null || !Number.isFinite(value) ? "—" : `${(value * 100).toFixed(1)}%`
}

/**
 * 置信度按 0–100 量纲直出百分比（一位小数）。
 *
 * <p>后端已把量纲收敛到 0–100，这里只加 `%`，**不乘 100**——
 * 套旧的 0–1 兼容函数会把 90 读成 0.9，再乘回去才得到 90%。
 */
export function formatConfidence(value: number | null): string {
  return value == null || !Number.isFinite(value) ? "—" : `${value.toFixed(1)}%`
}

export function formatMinutes(value: number | null): string {
  return value == null || !Number.isFinite(value) ? "—" : `${value.toFixed(1)} 分钟`
}

/* ── 枚举文案 ─────────────────────────────────────────────── */

const REASON_LABELS: Record<string, string> = {
  QUALITY_FAIL: "图像质量不合格",
  OOD: "分布外影像（OOD）",
  LOW_CONFIDENCE: "置信度偏低",
  NOT_ASSESSED: "未评估",
  MODEL_NOT_LOADED: "模型未加载",
  UNCLASSIFIED: "未归类原因",
  UNRECORDED: "未记录原因",
}

export function reasonLabel(reason: string): string {
  return REASON_LABELS[reason] ?? reason
}

const OOD_LABELS: Record<string, string> = {
  NOT_EVALUATED: "尚未评估",
  IN_DISTRIBUTION: "分布内",
  OUT_OF_DISTRIBUTION: "分布外",
  EVALUATION_FAILED: "评估失败",
  UNRECORDED: "未记录",
}

export function oodStatusLabel(status: string): string {
  return OOD_LABELS[status] ?? `${status}（契约外）`
}

const CALIBRATION_LABELS: Record<string, string> = {
  CALIBRATED: "已校准",
  NOT_CALIBRATED: "未校准",
  UNRECORDED: "未记录",
}

export function calibrationStatusLabel(status: string): string {
  return CALIBRATION_LABELS[status] ?? `${status}（契约外）`
}

/** 契约外复核状态必须把原值显示出来；已知状态复用全站同一份中文。 */
export function reviewStatusLabel(status: string): string {
  const known = humanReviewStatusLabel(status)
  return known === "未记录" ? `${status || "UNRECORDED"}（契约外）` : known
}

const QUALITY_REASON_LABELS: Record<string, string> = {
  blur: "模糊",
  brightness: "亮度",
  contrast: "对比度",
  fundus_coverage: "眼底覆盖不足",
}

export function qualityReasonLabel(reason: string): string {
  return QUALITY_REASON_LABELS[reason] ?? reason
}

export function reviewerLabel(reviewer: AnalyticsReviewerStat): string {
  if (reviewer.reviewerName) return reviewer.reviewerName
  return reviewer.reviewerId
    ? `复核人 #${reviewer.reviewerId}（未记录名称）`
    : "未记录复核人"
}

/** 数据状态只信后端；缺失或未知时明确说「无法确认」，不默认成运营数据。 */
export function reviewDataStatus(status: string): {
  label: string
  tone: "historical" | "unknown"
} {
  if (status === "HISTORICAL_ONLY") return { label: "历史数据", tone: "historical" }
  return { label: "无法确认统计状态", tone: "unknown" }
}

/* ── 分桶 ─────────────────────────────────────────────────── */

/** 与后端 SQL 标签逐字一致（含 en dash），顺序固定为 0–10 … 90–100。 */
export const CONFIDENCE_BUCKETS = [
  "0–10",
  "10–20",
  "20–30",
  "30–40",
  "40–50",
  "50–60",
  "60–70",
  "70–80",
  "80–90",
  "90–100",
] as const

export function confidenceHistogramRows(
  rows: AnalyticsHistogramBucket[],
): { bucket: string; count: number }[] {
  const byBucket = new Map(rows.map((row) => [row.bucket, row.count]))
  return CONFIDENCE_BUCKETS.map((bucket) => ({ bucket, count: byBucket.get(bucket) ?? 0 }))
}

/** 质量分契约值，展示顺序从差到好：0 / 25 / 50 / 75 / 100。 */
export const QUALITY_SCORE_ORDER = [0, 25, 50, 75, 100] as const

export function qualityScoreRows(
  rows: AnalyticsQualityScore[],
): { score: number; count: number }[] {
  const byScore = new Map<number, number>()
  for (const row of rows) {
    if (row.score === null) continue
    byScore.set(row.score, (byScore.get(row.score) ?? 0) + row.count)
  }
  return QUALITY_SCORE_ORDER.map((score) => ({ score, count: byScore.get(score) ?? 0 }))
}

export function qualityScoreLabel(score: number): string {
  const reasons = (100 - score) / 25
  return reasons <= 0 ? `${score} · 无质量原因` : `${score} · ${reasons} 项原因`
}

/**
 * OOD 能力状态：只有一条 `NOT_EVALUATED` 时不是「全部正常」，而是「检测器未启用」。
 * 空数组是「窗口内没有记录」，不是同一种情况。
 */
export function oodCapability(statuses: AnalyticsStatusStat[]): {
  disabled: boolean
  rows: AnalyticsStatusStat[]
} {
  const disabled =
    statuses.length > 0 && statuses.every((row) => row.status === "NOT_EVALUATED")
  return { disabled, rows: statuses }
}

/* ── 原因行 ───────────────────────────────────────────────── */

export type ReasonRow = {
  reason: string
  label: string
  count: number
  share: number | null
}

/** 后端已把契约外/空原因归组，这里只做文案与排序（计数降序，同数按代码）。 */
export function reasonRows(rows: AnalyticsReasonStat[]): ReasonRow[] {
  return [...rows]
    .sort((a, b) => b.count - a.count || a.reason.localeCompare(b.reason))
    .map((row) => ({
      reason: row.reason,
      label: reasonLabel(row.reason),
      count: row.count,
      share: row.share,
    }))
}

/* ── 数据质量清单 ─────────────────────────────────────────── */

export type QualityIssue = {
  key: string
  title: string
  count: number
  detail: string
  metricId: string
}

/**
 * 数据质量页的唯一计数来源是 `dataQuality` 与各领域的「未归类」字段。
 * 不做「各图表总量相减」——不同图表的分母与时间轴不同，减出来是假数。
 */
export function buildQualityIssues(data: AdminAnalytics): QualityIssue[] {
  const issues: QualityIssue[] = []
  const push = (issue: QualityIssue) => {
    if (issue.count > 0) issues.push(issue)
  }

  push({
    key: "ambiguous-confidence",
    title: "置信度量纲歧义",
    count: data.dataQuality.ambiguousConfidence,
    detail: "置信度落在 (0,1]，无法确认是概率还是百分数；不参与均值与直方图，也不会自动乘 100。",
    metricId: "B-03",
  })
  push({
    key: "confidence-out-of-range",
    title: "置信度越界",
    count: data.dataQuality.confidenceOutOfRange,
    detail: "置信度 ≤0 或 >100，超出 0–100 契约；不进入任何聚合。",
    metricId: "B-03",
  })
  push({
    key: "confidence-missing",
    title: "置信度缺失",
    count: data.dataQuality.confidenceMissing,
    detail: "置信度为空（常见于拒识记录）；单独计数，不当成 0 分。",
    metricId: "B-03",
  })
  push({
    key: "quality-out-of-contract",
    title: "质量分越界",
    count: data.dataQuality.qualityOutOfContract,
    detail: "质量分不在 {100,75,50,25,0} 内；不四舍五入进最近的档，单独计数。",
    metricId: "B-05",
  })
  push({
    key: "reviewed-without-time",
    title: "已复核但缺复核时间",
    count: data.dataQuality.reviewedWithoutTime,
    detail: "状态为 REVIEWED/OVERRIDDEN 但 humanReviewedAt 为空；不进入时延统计。",
    metricId: "A-10",
  })
  push({
    key: "rejection-unclassified",
    title: "未出结论原因未归类",
    count: data.rejection.unclassifiedReasonCount,
    detail: "abstainReason 不在契约枚举内（可能来自旧版或演示数据）。",
    metricId: "A-05",
  })
  push({
    key: "rejection-unknown",
    title: "未出结论原因未记录",
    count: data.rejection.unknownReasonCount,
    detail: "AI 拒识但 abstainReason 为空。",
    metricId: "A-05",
  })
  push({
    key: "review-unclassified",
    title: "触发复核原因未归类",
    count: data.review.unclassifiedReasonCount,
    detail: "进入复核队列的记录带了契约外原因值。",
    metricId: "A-12",
  })
  push({
    key: "review-unknown",
    title: "触发复核原因未记录",
    count: data.review.unknownReasonCount,
    detail: "进入复核队列但 abstainReason 为空；在触发原因图里作为独立桶可见。",
    metricId: "A-12",
  })
  push({
    key: "quality-reason-unclassified",
    title: "质量原因未归类",
    count: data.model.unclassifiedQualityReasonCount,
    detail: "qualityReasons 中出现契约外标签（多标签按出现次数计）。",
    metricId: "B-06",
  })

  for (const row of data.dataQuality.unknownReviewStatuses) {
    if (row.count <= 0) continue
    issues.push({
      key: `review-status-${row.status}`,
      title: `不计入复核统计的状态 · ${row.status}`,
      count: row.count,
      detail:
        "不在复核队列三态（AWAITING_REVIEW / REVIEWED / OVERRIDDEN）内，不计入完成率分子分母。这类状态不是脏数据，只是不参与复核效率统计。",
      metricId: "A-08",
    })
  }
  for (const row of data.dataQuality.unknownOodStatuses) {
    if (row.count <= 0) continue
    issues.push({
      key: `ood-status-${row.status}`,
      title: `契约外 OOD 状态 · ${row.status}`,
      count: row.count,
      detail: "oodStatus 取值不在契约枚举内；原值保留，不强行归类。",
      metricId: "B-07",
    })
  }
  for (const row of data.dataQuality.unknownCalibrationStatuses) {
    if (row.count <= 0) continue
    issues.push({
      key: `calibration-status-${row.status}`,
      title: `契约外校准状态 · ${row.status}`,
      count: row.count,
      detail: "calibrationStatus 取值不在契约枚举内；原值保留。",
      metricId: "B-08",
    })
  }

  return issues
}

/** 供可用性摘要用：有几个类别存在异常（不是异常记录条数，记录会跨类别重复）。 */
export function qualityIssueCategories(data: AdminAnalytics): number {
  return buildQualityIssues(data).length
}

/* ── 指标口径元数据（B0 注册表的前端只读副本） ─────────────── */

export type MetricDefinition = {
  /** B0 注册表 ID */
  id: string
  title: string
  question: string
  formula: string
  denominator?: string
  timeField: string
  unit: string
  exclusions: string[]
}

/**
 * 抽屉里的静态口径。运行时计数必须来自 `AdminAnalytics`，由调用方传入；
 * 这里的文字只是定义，不参与计算。
 */
export const METRIC_DEFINITIONS: Record<string, MetricDefinition> = {
  "A-04": {
    id: "A-04",
    title: "AI 拒识率",
    question: "多少判读没有给出结论？",
    formula: "SUM(aiRejected = 1) ÷ COUNT(*)",
    denominator: "窗口内全部判读记录",
    timeField: "createTime（判读创建时间）",
    unit: "比例",
    exclusions: ["已软删记录（isDelete = 1）", "低置信度不算拒识（aiRejected = false）"],
  },
  "A-05": {
    id: "A-05",
    title: "未出结论原因构成",
    question: "为什么没有给出结论？",
    formula: "GROUP BY abstainReason WHERE aiRejected = 1",
    denominator: "窗口内 AI 拒识数",
    timeField: "createTime",
    unit: "条 / 占比",
    exclusions: ["契约外取值合并为「未归类原因」", "空值合并为「未记录原因」，都不丢弃"],
  },
  "A-06": {
    id: "A-06",
    title: "待复核积压",
    question: "现在还有多少等待复核？",
    formula: "COUNT(*) WHERE humanReviewStatus = 'AWAITING_REVIEW'",
    denominator: "无（全站快照）",
    timeField: "当前状态，不受窗口限制",
    unit: "条",
    exclusions: ["与「窗口内入队量」不是同一个数，不能互相替代"],
  },
  "A-08": {
    id: "A-08",
    title: "复核完成率",
    question: "进入队列后完成了多少？",
    formula: "(REVIEWED + OVERRIDDEN) ÷ 进入队列集合",
    denominator: "窗口内创建、当前处于复核队列的记录",
    timeField: "createTime",
    unit: "比例",
    exclusions: ["APPROVED / PENDING_AI 等契约外状态不计入分子分母", "无队列记录时显示「—」"],
  },
  "A-09": {
    id: "A-09",
    title: "复核改写率",
    question: "复核推翻了多大比例的 AI 结论？",
    formula: "OVERRIDDEN ÷ (REVIEWED + OVERRIDDEN)",
    denominator: "窗口内完成的复核（按复核时间）",
    timeField: "humanReviewedAt（复核完成时间）",
    unit: "比例",
    exclusions: ["REVIEWED 与 OVERRIDDEN 互斥，不得相加当总数", "复核写入选入已安全关闭（D-01b）"],
  },
  "A-10": {
    id: "A-10",
    title: "复核时延",
    question: "从判读到复核完成用了多久？",
    formula: "humanReviewedAt − createTime；avg / p50 / p90 / max",
    denominator: "humanReviewedAt 非空且落在窗口内的复核",
    timeField: "humanReviewedAt",
    unit: "分钟",
    exclusions: ["缺复核时间的记录单独计数", "这是端到端时延，不是复核处理时长（没有入队时间字段）"],
  },
  "A-11": {
    id: "A-11",
    title: "复核人工作量",
    question: "谁完成了多少复核？",
    formula: "GROUP BY humanReviewerId",
    denominator: "窗口内完成的复核",
    timeField: "humanReviewedAt",
    unit: "条",
    exclusions: ["姓名缺失显示「复核人 #id（未记录名称）」", "一律称「复核人」，不写「医生」"],
  },
  "A-12": {
    id: "A-12",
    title: "触发复核原因构成",
    question: "哪些原因把记录推进了复核队列？",
    formula: "GROUP BY abstainReason WHERE humanReviewStatus IN ('AWAITING_REVIEW','REVIEWED','OVERRIDDEN')",
    denominator: "窗口内进入过复核队列的记录",
    timeField: "createTime",
    unit: "条 / 占比",
    exclusions: [
      "包含 LOW_CONFIDENCE（它 rejected = false，不出现在拒识原因里）",
      "原因为空作为「未记录原因」独立显示",
    ],
  },
  "B-01": {
    id: "B-01",
    title: "模型版本运行量",
    question: "各模型版本处理了多少判读？",
    formula: "GROUP BY modelVersion",
    denominator: "窗口内判读记录",
    timeField: "createTime",
    unit: "条",
    exclusions: ["版本为空归入 UNRECORDED", "拒识率 = 该版本拒识数 ÷ 该版本运行量"],
  },
  "B-03": {
    id: "B-03",
    title: "模型自评置信度",
    question: "模型平均有多自信？",
    formula: "AVG(confidence) WHERE 1 < confidence ≤ 100",
    denominator: "量纲可靠（0–100）的样本",
    timeField: "createTime",
    unit: "0–100",
    exclusions: [
      "不是准确率：系统没有真实标签数据源",
      "(0,1] 视为量纲未知，单独计数，不自动 ×100",
      "≤0 / >100 / NULL 均不参与均值",
    ],
  },
  "B-04": {
    id: "B-04",
    title: "置信度分布",
    question: "自评置信度的分布形状？",
    formula: "0–100 十档直方图（0–10 … 90–100）",
    denominator: "量纲可靠的样本",
    timeField: "createTime",
    unit: "条",
    exclusions: ["量纲未知、越界与空值不进入任何桶，只在数据质量区计数"],
  },
  "B-05": {
    id: "B-05",
    title: "图像质量分档",
    question: "质量分落在哪一档？",
    formula: "按精确契约值 {100,75,50,25,0} 分档（100 − 25 × 原因数）",
    denominator: "窗口内判读记录",
    timeField: "createTime",
    unit: "条",
    exclusions: ["不按百分位重切", "契约外值单独计数，不就近归类", "空值单列「未记录」"],
  },
  "B-06": {
    id: "B-06",
    title: "质量原因构成",
    question: "图像为什么不合格？",
    formula: "拆分 qualityReasons 按标签计数（多标签）",
    denominator: "出现的原因标签次数（不是记录数）",
    timeField: "createTime",
    unit: "次",
    exclusions: ["一行可计多个原因，合计可超过记录数", "契约外标签单独计数"],
  },
  "B-07": {
    id: "B-07",
    title: "OOD 状态",
    question: "有多少影像在分布外？",
    formula: "GROUP BY oodStatus",
    denominator: "窗口内判读记录",
    timeField: "createTime",
    unit: "条",
    exclusions: [
      "OOD 检测器当前有意停用，记录值恒为 NOT_EVALUATED",
      "「尚未评估」不等于「全部安全」，不画 100% 分布图",
    ],
  },
  "B-08": {
    id: "B-08",
    title: "校准状态",
    question: "有多少结论经过概率校准？",
    formula: "GROUP BY calibrationStatus",
    denominator: "窗口内判读记录",
    timeField: "createTime",
    unit: "条",
    exclusions: ["空值归入「未记录」", "契约外取值原样保留并进入数据质量"],
  },
  "DQ-01": {
    id: "DQ-01",
    title: "数据质量自检",
    question: "哪些记录不符合统计契约？",
    formula: "对每组异常单独计数（不求和）",
    denominator: "无（各项自有分母）",
    timeField: "createTime（复核时间缺失项按当前状态）",
    unit: "条",
    exclusions: [
      "不同异常可能落在同一条记录上，把各项相加会重复计数",
      "计数不参与任何比率的分母",
    ],
  },
}

/* ── 抽屉里的运行时计数 ───────────────────────────────────── */

/**
 * 指标口径抽屉下半部分的实时读数。
 *
 * <p>只从当前 `AdminAnalytics` 取值：定义可以写死在元数据里，计数不行。
 * 没有对应字段的指标返回空列表，抽屉会显示「当前响应没有可展示的计数」。
 */
export function metricLiveRows(
  id: string,
  data: AdminAnalytics,
): { label: string; value: string }[] {
  const count = formatCount
  switch (id) {
    case "A-04":
      return [
        { label: "窗口内判读总量", value: count(data.rejection.total) },
        { label: "AI 拒识数", value: count(data.rejection.rejected) },
        { label: "拒识率", value: formatRate(data.rejection.rate) },
      ]
    case "A-05":
      return [
        { label: "AI 拒识数（分母）", value: count(data.rejection.rejected) },
        { label: "未归类原因", value: count(data.rejection.unclassifiedReasonCount) },
        { label: "未记录原因", value: count(data.rejection.unknownReasonCount) },
      ]
    case "A-06":
      return [{ label: "全站待复核积压", value: count(data.review.awaitingTotal) }]
    case "A-08":
      return [
        { label: "窗口内入队", value: count(data.review.queuedInWindow) },
        { label: "窗口内完成", value: count(data.review.completedInWindow) },
        { label: "完成率", value: formatRate(data.review.completionRate) },
      ]
    case "A-09":
      return [
        { label: "已复核（按复核时间）", value: count(data.review.reviewedInWindow) },
        { label: "其中改写", value: count(data.review.overriddenInWindow) },
        { label: "改写率", value: formatRate(data.review.overrideRate) },
      ]
    case "A-10":
      return [
        { label: "有效时延样本", value: count(data.review.latencySamples) },
        { label: "平均", value: formatMinutes(data.review.avgMinutes) },
        { label: "P50", value: formatMinutes(data.review.p50Minutes) },
        { label: "P90", value: formatMinutes(data.review.p90Minutes) },
        { label: "最大值", value: formatMinutes(data.review.maxMinutes) },
        { label: "已复核但缺复核时间", value: count(data.dataQuality.reviewedWithoutTime) },
      ]
    case "A-11":
      return [
        { label: "复核人数", value: count(data.review.reviewers.length) },
        {
          label: "合计完成复核",
          value: count(
            data.review.reviewers.reduce((sum, reviewer) => sum + reviewer.reviewed, 0),
          ),
        },
      ]
    case "A-12":
      return [
        { label: "窗口内进入队列（分母）", value: count(data.review.queuedInWindow) },
        { label: "未归类原因", value: count(data.review.unclassifiedReasonCount) },
        { label: "未记录原因", value: count(data.review.unknownReasonCount) },
      ]
    case "B-01":
      return [
        { label: "模型版本数", value: count(data.model.versions.length) },
        {
          label: "合计运行量",
          value: count(data.model.versions.reduce((sum, version) => sum + version.count, 0)),
        },
        {
          label: "合计拒识",
          value: count(data.model.versions.reduce((sum, version) => sum + version.rejected, 0)),
        },
      ]
    case "B-03":
      return [
        { label: "有效置信度样本", value: count(data.model.confidence.samples) },
        { label: "平均置信度（0–100）", value: formatConfidence(data.model.confidence.avg) },
        { label: "量纲歧义 (0,1]", value: count(data.model.confidence.ambiguous) },
        { label: "越界（≤0 或 >100）", value: count(data.model.confidence.outOfRange) },
        { label: "缺失", value: count(data.model.confidence.missing) },
      ]
    case "B-04":
      return [
        { label: "有效置信度样本", value: count(data.model.confidence.samples) },
        {
          label: "直方图记录合计",
          value: count(
            data.model.confidenceHistogram.reduce((sum, bucket) => sum + bucket.count, 0),
          ),
        },
      ]
    case "B-05":
      return [
        {
          label: "五档记录合计",
          value: count(data.model.qualityScores.reduce((sum, score) => sum + score.count, 0)),
        },
        { label: "契约外质量分", value: count(data.model.qualityOutOfContract) },
        { label: "质量分缺失", value: count(data.model.qualityUnknown) },
      ]
    case "B-06":
      return [
        {
          label: "原因标签出现次数",
          value: count(
            data.model.qualityReasons.reduce((sum, reason) => sum + reason.count, 0),
          ),
        },
        { label: "契约外标签次数", value: count(data.model.unclassifiedQualityReasonCount) },
      ]
    case "B-07":
      return data.model.oodStatuses.map((row) => ({
        label: oodStatusLabel(row.status),
        value: count(row.count),
      }))
    case "B-08":
      return data.model.calibrationStatuses.map((row) => ({
        label: calibrationStatusLabel(row.status),
        value: count(row.count),
      }))
    case "DQ-01":
      return buildQualityIssues(data).map((issue) => ({
        label: issue.title,
        value: count(issue.count),
      }))
    default:
      return []
  }
}

/* ── 可用性摘要 ───────────────────────────────────────────── */

export type AvailabilityItem = {
  section: AnalyticsSectionId
  title: string
  statusLabel: string
  statusTone: "live" | "historical" | "unknown"
  value: string
  caption: string
}

export function buildAvailability(data: AdminAnalytics): AvailabilityItem[] {
  const reviewStatus = reviewDataStatus(data.review.dataStatus)
  const issueCategories = qualityIssueCategories(data)
  return [
    {
      section: "review",
      title: "判读与拒识",
      statusLabel: "当前窗口统计",
      statusTone: "live",
      value: `${formatCount(data.rejection.total)} 条判读`,
      caption: `AI 拒识 ${formatCount(data.rejection.rejected)} 条 · 拒识率 ${formatRate(
        data.rejection.rate,
      )}`,
    },
    {
      section: "review",
      title: "复核效率",
      statusLabel: reviewStatus.label,
      statusTone: reviewStatus.tone === "historical" ? "historical" : "unknown",
      value: `${formatCount(data.review.reviewedInWindow)} 条已完成`,
      caption: `完成率 ${formatRate(data.review.completionRate)} · 时延样本 ${formatCount(
        data.review.latencySamples,
      )}`,
    },
    {
      section: "model",
      title: "模型与图像质量",
      statusLabel: "当前窗口统计",
      statusTone: "live",
      value: `${formatCount(data.model.confidence.samples)} 个有效置信度样本`,
      caption: `平均置信度 ${formatConfidence(data.model.confidence.avg)}（0–100）· 模型版本 ${
        data.model.versions.length
      } 个`,
    },
    {
      section: "quality",
      title: "数据质量",
      statusLabel: issueCategories > 0 ? "发现异常类别" : "未发现异常",
      statusTone: issueCategories > 0 ? "unknown" : "live",
      value: `${issueCategories} 类问题`,
      caption:
        issueCategories > 0
          ? "逐项计数见数据质量页；不把不同类别的条数相加"
          : "契约外、量纲未知与时间缺失计数均为 0",
    },
  ]
}
