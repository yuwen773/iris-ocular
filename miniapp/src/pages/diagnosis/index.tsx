import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { AI_DISCLAIMER } from '@/components/Feedback'
import { PrivacyDialog } from '@/components/PrivacyDialog'
import { cancelDiagnosis, type CancelOutcome } from '@/lib/api'
import { getPrivacyStatus, openPrivacyContract, requirePrivacyAuthorize } from '@/lib/privacy'
import { ApiError, ERROR_CODE } from '@/lib/raw'
import { ImageTooLargeError, pickAndPrepareImage, uploadDiagnosis, type ImageSource } from '@/lib/upload'
import demoFundus from '@/images/fundus-demo.jpg'
import './index.scss'

type Stage = 'idle' | 'picking' | 'running' | 'canceled' | 'error'

/** 刻度条总格数：一格一秒，画满 15 秒后不再加格——它不是进度，只是把等过的时间显出来。 */
const TICKS = 15

function newRunId(): string {
  return `mp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function pad2(value: number): string {
  return String(Math.max(0, Math.floor(value))).padStart(2, '0')
}

function todayLabel(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`
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
  const [canceled, setCanceled] = useState<{ text: string; finished: boolean } | null>(null)
  // 隐私弹层：先把用途讲清楚，再走平台授权框。
  const [privacy, setPrivacy] = useState<{ visible: boolean; contractName?: string }>({ visible: false })
  // 每次判读一个运行标识；取消或换图后旧结果不落到新一次上。
  const runIdRef = useRef<string | null>(null)
  const timerRef = useRef<number | null>(null)
  // 点了哪个按钮：隐私授权回来要接着走同一条路（拍照 ≠ 相册）。
  const sourceRef = useRef<ImageSource>('any')

  useDidShow(() => {
    if (stage === 'running') return
    // 从结果页返回时回到可再次判读的状态，但保留最后一张预览。
    if (stage === 'error') {
      setStage('idle')
      setMessage('')
    }
  })

  // 页面被销毁时停掉计时器：否则秒数会一直跑在已经卸载的页面上。
  useUnload(() => {
    stopTimer()
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

  async function start(source: ImageSource, skipPrivacyCheck = false) {
    if (stage === 'picking' || stage === 'running') return
    sourceRef.current = source
    setMessage('')
    setCanceled(null)

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
      filePath = await pickAndPrepareImage(source)
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
    // 先按「已通知取消、等服务端确认」落到取消页，确认结果回来再改口径：
    // 没拿到确认之前不替服务端宣布「没有产生记录」。
    setCanceled({ text: '正在通知服务端取消…', finished: false })
    setStage('canceled')
    try {
      const { outcome } = await cancelDiagnosis(runId)
      setCanceled({ text: cancelMessage(outcome), finished: outcome === 'ALREADY_FINISHED' })
    } catch {
      setCanceled({
        text: '没有收到取消确认：本次判读可能仍在进行，结果会出现在记录里',
        finished: false,
      })
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
    await start(sourceRef.current, true)
  }

  function declinePrivacy() {
    setPrivacy((prev) => ({ ...prev, visible: false }))
    setStage('idle')
    setMessage('未同意隐私授权，暂不能上传影像')
  }

  const busy = stage === 'picking'
  const running = stage === 'running'

  // 底栏在每种状态下都在，内容统一给它让出高度（260rpx）。
  return (
    <View className="mp-page has-fbar">
      <View className="dochead">
        <Text>送检单 · 眼底影像</Text>
        <Text>{todayLabel()}</Text>
      </View>

      {running ? (
        <RunningBody preview={preview} elapsed={elapsed} />
      ) : stage === 'canceled' ? (
        <CanceledBody info={canceled} />
      ) : (
        <IdleBody
          preview={preview}
          busy={busy}
          message={message}
          fault={stage === 'error'}
          onPick={() => void start('any')}
        />
      )}

      {running ? (
        <View className="fbar">
          <Button className="fbtn fbtn--ghost" onClick={cancel}>
            取消本次判读
          </Button>
        </View>
      ) : stage === 'canceled' ? (
        <View className="fbar">
          <Button className="fbtn fbtn--primary" onClick={() => void start('camera')}>
            拍照
          </Button>
          <Button className="fbtn fbtn--ghost" onClick={() => void start('album')}>
            从相册选择
          </Button>
        </View>
      ) : (
        <View className="fbar">
          <Button className="fbtn fbtn--primary" disabled={busy} onClick={() => void start('camera')}>
            {busy ? '正在处理影像…' : '拍照'}
          </Button>
          <Button className="fbtn fbtn--ghost" disabled={busy} onClick={() => void start('album')}>
            从相册选择
          </Button>
        </View>
      )}

      {!running && stage !== 'canceled' ? (
        <View style={{ marginTop: '32rpx' }}>
          <Text className="mp-note">判读由 AI 模型完成，属于辅助参考，不能替代眼科医生的诊断。</Text>
          <View style={{ marginTop: '12rpx' }}>
            <Text className="mp-note">影像会加密存储在你的判读记录里，只有你本人能查看。</Text>
          </View>
        </View>
      ) : null}

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

/** 判读空态：一格取景框 + 一张送检单。 */
function IdleBody(props: {
  preview: string | null
  busy: boolean
  message: string
  fault: boolean
  onPick: () => void
}) {
  return (
    <>
      <View className="h-page">拍一张眼底照片</View>
      <View className="lede">
        单眼、清晰、光照均匀。单张上限 2MB。系统先过质量门，不合格的影像不会给出结论。
      </View>

      <View className="slot" onClick={props.onPick} aria-label="选择眼底影像">
        <Image className="slot__img" src={props.preview ?? demoFundus} mode="aspectFill" />
        <View className="slot__veil" />
        <View className="cn tl" />
        <View className="cn tr" />
        <View className="cn bl" />
        <View className="cn br" />
        <Text className="slot__tag">{props.preview ? '换一张影像' : '点这里选择影像'}</Text>
      </View>
      <Text className="caption">{props.preview ? '这张影像还没上传' : '示例影像 · 非真实患者'}</Text>

      <View className="ledger">
        <View className="ledger__r">
          <Text className="ledger__k">检查项目</Text>
          <Text className="ledger__v">眼底彩照 · 单眼</Text>
        </View>
      </View>

      {props.message ? (
        <View className={`mp-fault${props.fault ? '' : ' mp-fault--info'}`} style={{ marginTop: '24rpx' }}>
          {props.message}
        </View>
      ) : null}
    </>
  )
}

/** 运行中：等待时长 + 刻度轴 + 影像 + 送检单状态。 */
function RunningBody(props: { preview: string | null; elapsed: number }) {
  const lit = Math.min(TICKS, props.elapsed)
  return (
    <>
      <View className="run-head">
        <View className="run-head__dot" />
        <View className="run-head__title">正在判读</View>
      </View>

      <View className="wait">
        <Text className="wait__k">等待时长</Text>
        <Text className="wait__w">已等待 {pad2(props.elapsed)} 秒</Text>
      </View>

      <View className="ticks">
        {Array.from({ length: TICKS }, (_, index) => (
          <View key={index} className={`ticks__i${index < lit ? ' is-on' : ''}`} />
        ))}
      </View>
      <Text className="tickcap">一格一秒 · 画的是已经过去的秒数，不是进度</Text>

      {props.preview ? (
        <View className="scan">
          <Image className="scan__img" src={props.preview} mode="aspectFit" />
        </View>
      ) : null}
      <Text className="caption">影像已上传，等待模型返回结论</Text>

      <View className="ledger">
        <View className="ledger__r">
          <Text className="ledger__k">影像</Text>
          <Text className="ledger__v">已上传</Text>
        </View>
        <View className="ledger__r">
          <Text className="ledger__k">质量门</Text>
          <Text className="ledger__v">等待中</Text>
        </View>
        <View className="ledger__r">
          <Text className="ledger__k">结论</Text>
          <Text className="ledger__v">等待中</Text>
        </View>
      </View>
    </>
  )
}

/** 取消后：影像删不删、记录有没有，按服务端的回答说。 */
function CanceledBody(props: { info: { text: string; finished: boolean } | null }) {
  return (
    <>
      <View className="h-page">{props.info?.finished ? '这次判读已经完成' : '已取消本次判读'}</View>
      <View className="lede">{props.info?.text ?? '已停止等待'}</View>

      <View className="ledger">
        <View className="ledger__r">
          <Text className="ledger__k">影像</Text>
          <Text className="ledger__v">已上传</Text>
        </View>
        <View className="ledger__r">
          <Text className="ledger__k">判读记录</Text>
          <Text className="ledger__v">{props.info?.finished ? '已生成，见记录' : '未生成'}</Text>
        </View>
      </View>

      <View className="mp-note" style={{ marginTop: '24rpx' }}>
        {AI_DISCLAIMER}
      </View>
    </>
  )
}
