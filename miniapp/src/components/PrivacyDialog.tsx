import { Button, Text, View } from '@tarojs/components'

/**
 * 隐私授权前的自建说明弹层。
 *
 * <p>平台授权框只问「同意/不同意」，不讲用途；健康影像属于敏感信息，
 * 先在纸面弹层里把「读什么、为什么、只用于什么」讲清楚，再走平台授权。
 * 样式全部来自 `base.scss` 的 `.mp-sheet*` 原语，不新增色值。
 */
export interface PrivacyDialogProps {
  visible: boolean
  contractName?: string
  onAgree: () => void
  onDecline: () => void
  onOpenContract: () => void
}

export function PrivacyDialog({ visible, contractName, onAgree, onDecline, onOpenContract }: PrivacyDialogProps) {
  if (!visible) return null
  return (
    <View className="mp-sheet-mask">
      <View className="mp-sheet">
        <Text className="mp-label">使用前需要你的授权</Text>
        <View className="mp-title" style={{ marginTop: '12rpx' }}>
          关于眼底影像的使用
        </View>
        <View className="mp-stack" style={{ marginTop: '24rpx' }}>
          <Text className="mp-note">为完成 AI 判读，我们会读取你拍摄或选择的眼底照片，并上传到服务器分析。</Text>
          <Text className="mp-note">影像只用于本次判读和你本人的判读记录，不用于其他用途。</Text>
          <Text className="mp-note">判读结果由 AI 模型生成，只是辅助参考，不构成医学诊断。</Text>
        </View>
        <View className="mp-row" style={{ marginTop: '24rpx' }}>
          <Text className="mp-note">完整说明见{contractName || '《小程序用户隐私保护指引》'}</Text>
          <Text className="mp-note" onClick={onOpenContract}>
            查看
          </Text>
        </View>
        <View className="mp-sheet__actions">
          <Button className="mp-btn mp-btn--ghost" onClick={onDecline}>
            不同意
          </Button>
          <Button className="mp-btn" onClick={onAgree}>
            同意并继续
          </Button>
        </View>
      </View>
    </View>
  )
}
