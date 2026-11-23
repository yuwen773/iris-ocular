import { Button, Image, Text, View } from '@tarojs/components'
import { useRouter } from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { Disclaimer, ErrorState, LoadingState } from '@/components/Feedback'
import { AiBadge, OutcomeTag, SeverityTag } from '@/components/Tags'
import { getDiagnosis, type DiagnosisResult } from '@/lib/api'
import {
  abstainReasonLabel,
  canonicalClassName,
  classCode,
  confidenceBand,
  normalizeConfidence,
  outcomeLabel,
  outcomeOf,
  probabilityReading,
} from '@/lib/domain'
import { ApiError } from '@/lib/raw'
import './result.scss'

function probabilityText(result: DiagnosisResult): string {
  const value =
    result.calibratedProbability ??
    result.classificationProbability ??
    (typeof result.probability === 'number' ? result.probability / 100 : undefined)
  if (typeof value !== 'number') return '—'
  return `${(normalizeConfidence(value) * 100).toFixed(1)}%`
}

function confidenceText(result: DiagnosisResult): string {
  if (typeof result.confidence !== 'number') {
    return '未校准，不按置信度解读'
  }
  const band = confidenceBand(result.confidence)
  return `${(normalizeConfidence(result.confidence) * 100).toFixed(1)}%（${band.label}）`
}

export default function DiagnosisResultPage() {
  const router = useRouter()
  const id = router.params.id
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [fault, setFault] = useState('')
  const [result, setResult] = useState<DiagnosisResult | null>(null)
  const [showDetail, setShowDetail] = useState(false)
  const reading = result ? probabilityReading(result) : null

  async function load() {
    if (!id) {
      setState('error')
      setFault('缺少记录编号，无法打开这条判读')
      return
    }
    setState('loading')
    try {
      const data = await getDiagnosis(id)
      setResult(data)
      setState('ready')
    } catch (error) {
      setState('error')
      setFault(error instanceof ApiError ? error.message : '读取判读结果失败，请重试')
    }
  }

  useEffect(() => {
    void load()
  }, [id])

  if (state === 'loading') {
    return (
      <View className="mp-page">
        <LoadingState text="正在读取判读结果…" />
      </View>
    )
  }

  if (state === 'error' || !result) {
    return (
      <View className="mp-page">
        <ErrorState text={fault || '读取判读结果失败'} onRetry={load} />
      </View>
    )
  }

  const outcome = outcomeOf(result)
  const classified = outcome === 'classified'
  const className = canonicalClassName(result.result)
  const tone = classified ? 'normal' : outcome === 'rejected' ? 'critical' : 'attention'

  return (
    <View className="mp-page">
      <View className="mp-card">
        <View className="mp-row">
          <Text className="mp-label">判读结果</Text>
          {result.createTime ? <Text className="mp-mono mp-note">{result.createTime.slice(0, 16).replace('T', ' ')}</Text> : null}
        </View>

        {result.url ? <Image className="result-image" src={result.url} mode="aspectFit" /> : null}

        <View className="mp-row" style={{ marginTop: '24rpx' }}>
          <OutcomeTag label={outcomeLabel(outcome)} tone={tone} />
          {classified ? <SeverityTag severity={result.severity} /> : null}
        </View>

        {classified ? (
          <>
            <View className="result-headline">
              {className ?? result.result ?? '未能识别的类别'}
            </View>
            <Text className="mp-note">
              类别短码 <Text className="mp-mono">{classCode(className)}</Text> · 置信度{' '}
              <Text className="mp-mono">{confidenceText(result)}</Text>
            </Text>
            {result.recommendations ? (
              <View className="result-block">
                <Text className="mp-label">建议</Text>
                <Text className="result-text">{result.recommendations}</Text>
              </View>
            ) : null}
          </>
        ) : (
          <View className="result-block">
            <View className="result-headline result-headline--muted">本次未给出分类结论</View>
            <Text className="result-text">{abstainReasonLabel(result.abstainReason)}</Text>
            {outcome === 'review' ? (
              <Text className="mp-note">
                这次判读的置信度不足以给出结论。体验版没有医生在线复核，请携原图到正规医疗机构由眼科医生判断。
              </Text>
            ) : null}
          </View>
        )}

        <View className="mp-hairline" />
        <View style={{ marginTop: '8rpx' }}>
          <AiBadge />
        </View>

        <Button className="mp-btn mp-btn--ghost" style={{ marginTop: '24rpx' }} onClick={() => setShowDetail((v) => !v)}>
          {showDetail ? '收起专业详情' : '展开专业详情'}
        </Button>

        {showDetail ? (
          <View className="result-detail">
            {/* 没有分类结论时不给模型概率：拒识/待复核的原始概率会被当成「其实还是判出来了」。 */}
            {classified && reading ? <DetailRow label={reading.label} value={probabilityText(result)} /> : null}
            <DetailRow label="校准状态" value={result.calibrationStatus || '未评估'} />
            <DetailRow label="图像质量" value={result.imageQualityState || '未评估'} />
            <DetailRow label="质量评分" value={typeof result.qualityScore === 'number' ? result.qualityScore.toFixed(2) : '—'} />
            <DetailRow label="分布外评估" value={result.oodStatus || '未评估'} />
            <DetailRow label="模型版本" value={result.modelVersion || '—'} />
            {result.qualityReasons ? <DetailRow label="质量说明" value={result.qualityReasons} /> : null}
          </View>
        ) : null}
      </View>

      <Disclaimer text={result.disclaimer} />
    </View>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <View className="result-detail__row">
      <Text className="mp-note">{label}</Text>
      <Text className="mp-mono result-detail__value">{value}</Text>
    </View>
  )
}
