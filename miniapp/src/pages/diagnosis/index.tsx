import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { AiBadge } from '@/components/Tags'
import { Disclaimer } from '@/components/Feedback'
import { PrivacyDialog } from '@/components/PrivacyDialog'
import { cancelDiagnosis, type CancelOutcome } from '@/lib/api'
import { getPrivacyStatus, openPrivacyContract, requirePrivacyAuthorize } from '@/lib/privacy'
import { ApiError, ERROR_CODE } from '@/lib/raw'
import { ImageTooLargeError, pickAndPrepareImage, uploadDiagnosis } from '@/lib/upload'
import './index.scss'

type Stage = 'idle' | 'picking' | 'running' | 'error'

function newRunId(): string {
  return `mp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/** 取消的结果必须如实说：服务端说「已经完成」时，用户不该以为白拍了。 */
function cancelMessage(outcome: CancelOutcome): string {
  if (outcome === 'CANCELLED') return '已取消本次判读'
  if (outcome === 'ALREADY_FINISHED') return '这次判读已经完成，结果已存入记录'
  return '服务端已没有这次判读，本次没有产生记录'
}

function describeError(error: unknown): string {
  if (error instanceof ImageTooLargeError) {
    return `${error.message}，请重拍或换一张更小的影像`
  }
  if (error instanceof ApiError) {
    if (error.code === ERROR_CODE.CANCELLED) return '判读已取消'
    if (error.code === ERROR_CODE.NOT_LOGIN) return '登录状态已失效，请重新进入小程序'
    return error.message || '判读失败，请重试'
  }
  if (error instanceof Error && error.message) return error.message
  return '判读失败，请重试'
}

export default function DiagnosisHome() {
  const [stage, setStage] = useState<Stage>('idle')
  const [message, setMessage] = useState('')
  const [preview, setPreview] = useState<string | null>(null)
  const [elapsed, setElapsed] = useState(0)
  // 隐私弹层：先把用途讲清楚，再走平台授权框。
  const [privacy, setPrivacy] = useState<{ visible: boolean; contractName?: string }>({ visible: false })
  // 每次判读一个运行标识；取消或换图后旧结果不落到新一次上。
  const runIdRef = useRef<string | null>(null)
  const timerRef = useRef<number | null>(null)

  useDidShow(() => {
    if (stage === 'running') return
    // 从结果页返回时回到可再次判读的状态，但保留最后一张预览。
    if (stage === 'error') {
      setStage('idle')
      setMessage('')
    }
  })

  function stopTimer() {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
  }

  function startTimer() {
    stopTimer()
    const startedAt = Date.now()
    setElapsed(0)
    timerRef.current = setInterval(() => {
      setElapsed(Math.floor((Date.now() - startedAt) / 1000))
    }, 1000) as unknown as number
  }

  async function start(skipPrivacyCheck = false) {
    if (stage === 'picking' || stage === 'running') return
    setMessage('')

    // 未授权时不发起选图：先弹自建说明层，用户同意后再走平台授权。
    if (!skipPrivacyCheck) {
      const status = await getPrivacyStatus()
      if (status.needAuthorization) {
        setStage('idle')
        setPrivacy({ visible: true, contractName: status.contractName })
        return
      }
    }

    setStage('picking')

    let filePath: string
    try {
      filePath = await pickAndPrepareImage()
    } catch (error) {
      if (error instanceof Error && error.message === 'cancelled') {
        setStage('idle')
        return
      }
      setStage('error')
      setMessage(describeError(error))
      return
    }

    setPreview(filePath)
    setStage('running')
    startTimer()

    const runId = newRunId()
    runIdRef.current = runId
    try {
      const result = await uploadDiagnosis(filePath, runId)
      if (runIdRef.current !== runId) return // 已被取消，结果丢弃
      runIdRef.current = null
      stopTimer()
      if (result.id) {
        setStage('idle')
        await Taro.navigateTo({ url: `/pages/diagnosis/result?id=${result.id}` })
      } else {
        setStage('error')
        setMessage('判读完成但服务端未返回记录编号')
      }
    } catch (error) {
      if (runIdRef.current !== runId) return // 主动取消后的失败不再提示
      runIdRef.current = null
      stopTimer()
      setStage('error')
      setMessage(describeError(error))
    }
  }

  async function cancel() {
    const runId = runIdRef.current
    if (!runId) return
    runIdRef.current = null
    stopTimer()
    setStage('idle')
    setMessage('正在通知服务端取消…')
    try {
      const { outcome } = await cancelDiagnosis(runId)
      setMessage(cancelMessage(outcome))
    } catch {
      // 没拿到确认就不替服务端宣布结果：本次判读可能仍在跑，结果会出现在记录里。
      setMessage('没有收到取消确认：本次判读可能仍在进行，结果会出现在记录里')
    }
  }

  async function agreePrivacy() {
    const contractName = privacy.contractName
    setPrivacy({ visible: false, contractName })
    const granted = await requirePrivacyAuthorize()
    if (!granted) {
      setStage('error')
      setMessage('需要先同意《小程序用户隐私保护指引》才能上传影像')
      return
    }
    await start(true)
  }

  function declinePrivacy() {
    setPrivacy((prev) => ({ ...prev, visible: false }))
    setStage('idle')
    setMessage('未同意隐私授权，暂不能上传影像')
  }

  const running = stage === 'running'
  const busy = stage === 'picking'

  return (
    <View className="mp-page">
      <View className="mp-card">
        <Text className="mp-label">眼底影像判读</Text>
        <View className="mp-title" style={{ marginTop: '12rpx' }}>
          拍一张眼底照片
        </View>
        <View className="mp-subtitle">
          请使用免散瞳眼底相机拍摄，保持光线均匀、视盘与黄斑完整入镜。单张上限 2MB。
        </View>

        {preview ? (
          <Image className="diag-preview" src={preview} mode="aspectFit" />
        ) : (
          <View className="diag-slot">
            <Text className="mp-note">还没有选择影像</Text>
          </View>
        )}

        <View className="mp-stack" style={{ marginTop: '24rpx' }}>
          {running ? (
            <>
              <View className="mp-row">
                <Text className="mp-note">正在判读，已等待</Text>
                <Text className="mp-mono">{elapsed}s</Text>
              </View>
              <Button className="mp-btn mp-btn--ghost" onClick={cancel}>
                取消本次判读
              </Button>
            </>
          ) : (
            <Button className="mp-btn" disabled={busy} onClick={() => void start()}>
              {busy ? '正在处理影像…' : '拍照 / 从相册选择'}
            </Button>
          )}
        </View>

        {message ? (
          <View className={`mp-fault${stage === 'running' ? ' mp-fault--info' : ''}`} style={{ marginTop: '24rpx' }}>
            {message}
          </View>
        ) : null}
      </View>

      <View className="mp-card">
        <View className="mp-stack">
          <Text className="mp-note">判读由 AI 模型完成，属于辅助参考，不能替代眼科医生的诊断。</Text>
          <Text className="mp-note">影像会加密存储在你的判读记录里，只有你本人能查看。</Text>
        </View>
        <View style={{ marginTop: '24rpx' }}>
          <AiBadge />
        </View>
      </View>

      <Disclaimer />

      <PrivacyDialog
        visible={privacy.visible}
        contractName={privacy.contractName}
        onAgree={() => void agreePrivacy()}
        onDecline={declinePrivacy}
        onOpenContract={openPrivacyContract}
      />
    </View>
  )
}
