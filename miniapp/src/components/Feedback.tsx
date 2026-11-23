import { Button, Text, View } from '@tarojs/components'

/**
 * 免责声明条。文案与 web/ 保持同一口径：AI 结果不是诊断。
 */
export function Disclaimer({ text }: { text?: string | null }) {
  return (
    <View className="mp-disclaimer">
      {text || '本结果由 AI 模型生成，仅用于辅助参考，不构成医学诊断。如有视力变化或眼部不适，请前往正规医疗机构就诊。'}
    </View>
  )
}

export function EmptyState({ text }: { text: string }) {
  return (
    <View className="mp-empty">
      <Text>{text}</Text>
    </View>
  )
}

export function ErrorState({ text, onRetry }: { text: string; onRetry?: () => void }) {
  return (
    <View className="mp-stack">
      <View className="mp-fault">{text}</View>
      {onRetry ? (
        <Button className="mp-btn mp-btn--ghost" onClick={onRetry}>
          重试
        </Button>
      ) : null}
    </View>
  )
}

export function LoadingState({ text = '加载中…' }: { text?: string }) {
  return (
    <View className="mp-empty">
      <Text>{text}</Text>
    </View>
  )
}
