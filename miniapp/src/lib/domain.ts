/**
 * 判读域的展示口径（与 web/src/lib/domain.ts 同一套规则，这里是手机端可用的子集）。
 *
 * <p>不新增、不修改后端契约：后端给什么写法，都归到同一类；认不出就原样显示，不猜。
 */
export type Risk = 'normal' | 'attention' | 'critical' | 'unknown'

export interface DiagnosisClass {
  code: string
  name: string
  desc: string
}

/** 模型覆盖的八类眼底改变。 */
export const CLASSES: readonly DiagnosisClass[] = [
  { code: 'N', name: '正常', desc: '未见明显异常，血管走行与视盘边界正常。' },
  { code: 'AMD', name: '年龄相关性黄斑变性', desc: '黄斑区玻璃膜疣、色素改变或新生血管。' },
  { code: 'CAT', name: '白内障', desc: '晶状体混浊，影像对比度下降。' },
  { code: 'DR', name: '糖尿病视网膜病变', desc: '微血管瘤、出血、硬性渗出与棉絮斑。' },
  { code: 'GLA', name: '青光眼', desc: '视杯扩大、神经纤维层缺损。' },
  { code: 'HR', name: '高血压性视网膜病变', desc: '动脉变细、动静脉交叉压迹。' },
  { code: 'PM', name: '病理性近视', desc: '豹纹状眼底、后巩膜葡萄肿、漆裂纹。' },
  { code: 'OTH', name: '其他', desc: '存在异常但无法归入以上七类。' },
]

const CODE_BY_NAME: Record<string, string> = Object.fromEntries(
  CLASSES.map((item) => [item.name, item.code]),
)

const CLASS_ALIASES: Record<string, string> = {
  normal: '正常',
  amd: '年龄相关性黄斑变性',
  cataract: '白内障',
  diabetes: '糖尿病视网膜病变',
  glaucoma: '青光眼',
  hypertension: '高血压性视网膜病变',
  myopia: '病理性近视',
  other: '其他',
  糖尿病: '糖尿病视网膜病变',
  高血压: '高血压性视网膜病变',
  近视: '病理性近视',
}

export function canonicalClassName(name?: string | null): string | null {
  if (!name) return null
  const trimmed = name.trim()
  if (!trimmed) return null
  const alias = CLASS_ALIASES[trimmed.toLowerCase()]
  if (alias) return alias
  if (CODE_BY_NAME[trimmed]) return trimmed
  const hit = CLASSES.find((item) => trimmed.includes(item.name) || item.name.includes(trimmed))
  return hit ? hit.name : null
}

export function classCode(name?: string | null): string {
  if (!name) return '—'
  const canonical = canonicalClassName(name)
  return canonical ? (CODE_BY_NAME[canonical] ?? '—') : '—'
}

const SEVERITY_LABEL: Record<string, string> = {
  normal: '正常',
  mild: '轻度',
  moderate: '中度',
  severe: '严重',
  low: '低风险',
  medium: '中风险',
  high: '高风险',
  not_assessed: '未评估',
}

export function severityLabel(severity?: string | null): string {
  if (!severity) return '未评估'
  return SEVERITY_LABEL[severity] ?? SEVERITY_LABEL[severity.toLowerCase()] ?? severity
}

export function severityRisk(severity?: string | null): Risk {
  const key = severity?.toUpperCase()
  switch (key) {
    case 'NORMAL':
      return 'normal'
    case 'MILD':
    case 'LOW':
    case 'MODERATE':
    case 'MEDIUM':
      return 'attention'
    case 'SEVERE':
    case 'HIGH':
      return 'critical'
    default:
      return 'unknown'
  }
}

/** 可能是百分数（0–100）或概率（0–1），统一归一为 0–1。 */
export function normalizeConfidence(confidence?: number | null): number {
  if (typeof confidence !== 'number' || Number.isNaN(confidence)) return 0
  const value = confidence > 1 ? confidence / 100 : confidence
  return Math.min(1, Math.max(0, value))
}

export interface ConfidenceBandResult {
  label: string
  risk: Risk
}

/**
 * 置信度分档。注意两件事：
 * 1) 只有真正校准过的 `confidence` 才有值，未校准的 `classificationProbability`
 *    是模型输出强度，不能套用「可信/偏低」这组词；
 * 2) 因此调用方必须先判断有没有校准值，本函数只负责分档。
 */
export function confidenceBand(confidence?: number | null): ConfidenceBandResult {
  if (typeof confidence !== 'number' || Number.isNaN(confidence)) {
    return { label: '未评估', risk: 'unknown' }
  }
  const value = normalizeConfidence(confidence)
  if (value >= 0.75) return { label: '可信', risk: 'normal' }
  if (value >= 0.6) return { label: '偏低', risk: 'attention' }
  return { label: '低', risk: 'critical' }
}

/** 结果的四种结局必须分开说，不能混成一句「完成」。 */
export type Outcome = 'classified' | 'rejected' | 'review' | 'blocked'

export function outcomeOf(result: {
  aiRejected?: boolean | null
  requiresReview?: boolean | null
  patientConclusionAllowed?: boolean | null
}): Outcome {
  if (result.aiRejected) return 'rejected'
  if (result.requiresReview) return 'review'
  if (result.patientConclusionAllowed === false) return 'blocked'
  return 'classified'
}

export function outcomeLabel(outcome: Outcome): string {
  switch (outcome) {
    case 'rejected':
      return '模型未给出结论'
    case 'review':
      return '已转入人工复核'
    case 'blocked':
      return '本次不提供面向患者的结论'
    default:
      return '已给出分类'
  }
}

/** 拒识 / 准入原因码 → 一句人话；认不出的原因码原样显示，不吞。 */
const ABSTAIN_REASON_LABEL: Record<string, string> = {
  ABSTAIN_QUALITY_FAIL: '影像质量未通过，请重新拍摄清晰的眼底照片。',
  ABSTAIN_OOD: '图像特征超出模型适用范围，无法给出可靠分类。',
  OOD_NOT_EVALUATED: '本次输入未做分布外评估，为避免误导暂不给出结论。',
  ABSTAIN_INSUFFICIENT_EVIDENCE: '可依据的受控资料不足，暂不提供结论。',
}

export function abstainReasonLabel(reason?: string | null): string {
  if (!reason) return '本次分析暂无法提供结果。'
  return ABSTAIN_REASON_LABEL[reason] ?? reason
}
