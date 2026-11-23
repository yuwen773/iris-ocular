import { Text, View } from '@tarojs/components'
import { severityLabel, severityRisk } from '@/lib/domain'

/**
 * 严重度标记：颜色只是第二信息载体，标签文字永远在。
 */
export function SeverityTag({ severity }: { severity?: string | null }) {
  const risk = severityRisk(severity)
  const modifier = risk === 'unknown' ? '' : ` mp-tag--${risk}`
  return <Text className={`mp-tag${modifier}`}>{severityLabel(severity)}</Text>
}

/** 结果结局标记（已给出分类 / 拒识 / 待复核 / 不作患者结论）。 */
export function OutcomeTag({ label, tone }: { label: string; tone: 'normal' | 'attention' | 'critical' }) {
  return <Text className={`mp-tag mp-tag--${tone}`}>{label}</Text>
}

/** AI 生成内容标识（《人工智能生成合成内容标识办法》，2025-09-01 施行）。 */
export function AiBadge() {
  return (
    <View className="mp-row" style={{ justifyContent: 'flex-start' }}>
      <Text className="mp-tag mp-tag--signal">AI 生成</Text>
      <Text className="mp-note">内容由模型生成，仅供参考</Text>
    </View>
  )
}
