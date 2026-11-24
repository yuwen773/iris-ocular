import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { AI_DISCLAIMER, ErrorState, LoadingState } from '@/components/Feedback'
import { OutcomeTag } from '@/components/Tags'
import { getDiagnosis, type DiagnosisResult } from '@/lib/api'
import {
  abstainReasonLabel,
  canonicalClassName,
  classCode,
  confidenceBand,
  outcomeLabel,
  outcomeOf,
  outcomeTone,
  probabilityDisplay,
  probabilityReading,
  severityLabel,
  severityRisk,
} from '@/lib/domain'
import { ApiError } from '@/lib/raw'
import './result.scss'

function timeLabel(result: DiagnosisResult): string {
  if (!result.createTime) return ''
  return result.createTime.slice(0, 16).replace('T', ' ')
}

export default function DiagnosisResultPage() {
  const router = useRouter()
  const id = router.params.id
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [fault, setFault] = useState('')
  const [result, setResult] = useState<DiagnosisResult | null>(null)
  const [showDetail, setShowDetail] = useState(false)

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
  const reading = classified ? probabilityReading(result) : null
  const probability = classified ? probabilityDisplay(result) : null
  // 「可信/偏低/低」只对真正校准过的 confidence 说；未校准的输出强度不套这组词。
  const band = reading?.calibrated && typeof result.confidence === 'number'
    ? confidenceBand(result.confidence)
    : null
  const risk = severityRisk(result.severity)

  return (
    <View className="mp-page">
      <View className="report">
        <View className="dochead">
          <Text>判读报告 · 眼底影像</Text>
          <Text>{timeLabel(result)}</Text>
        </View>

        <View className="tagline">
          <OutcomeTag label={outcomeLabel(outcome)} tone={outcomeTone(outcome)} />
          {classified && className ? <Text className="tagline__code">{classCode(className)}</Text> : null}
        </View>

        {classified ? (
          <>
            <View className="cat">{className ?? result.result ?? '未能识别的类别'}</View>

            <View className="sev">
              <Text className="sev__k">严重度</Text>
              <Text className={`sev__word is-${risk}`}>{severityLabel(result.severity)}</Text>
            </View>

            {probability ? (
              <View className="gauge">
                <View className="gauge__top">
                  <Text className="gauge__k">{reading?.label ?? '模型输出概率'}</Text>
                  {band ? (
                    <Text className={`trust is-${band.risk}`}>
                      <Text className="trust__dot" />
                      {band.label}
                    </Text>
                  ) : null}
                </View>
                <View className="readout">
                  {probability.text}
                  <Text className="readout__pct">%</Text>
                </View>
                <View className="scale">
                  <View className="scale__fill" style={{ width: probability.percent }} />
                </View>
                <Text className="note">
                  {reading?.calibrated
                    ? '这是校准后的置信度：模型对这一分类的把握程度，不是患病概率，也不等于确诊。'
                    : '这个数字是模型输出的强度，没有经过真实数据标定，不能当置信度读，更不是患病概率。'}
                </Text>
              </View>
            ) : null}

            {result.recommendations ? (
              <View className="advice">
                <Text className="advice__k">建议</Text>
                <Text className="advice__p">{result.recommendations}</Text>
              </View>
            ) : null}
          </>
        ) : (
          <>
            <View className="cat">本次未给出分类结论</View>

            <View className="void-box">
              <Text className="void-box__k">读数</Text>
              <Text className="void-box__dash">— —</Text>
              <Text className="void-box__note">本次判读不提供读数，也不显示任何概率。</Text>
            </View>

            <View className="advice">
              <Text className="advice__k">原因</Text>
              <Text className="advice__p">{abstainReasonLabel(result.abstainReason)}</Text>
            </View>

            {outcome === 'review' ? (
              <Text className="note" style={{ marginTop: '28rpx' }}>
                这次判读的置信度不足以给出结论。体验版没有医生在线复核，请携原图到正规医疗机构由眼科医生判断。
              </Text>
            ) : null}
          </>
        )}

        <Button className="fold" onClick={() => setShowDetail((v) => !v)}>
          <Text>{showDetail ? '收起专业详情' : '展开专业详情'}</Text>
          <View className={`fold__mark${showDetail ? ' is-open' : ''}`} />
        </Button>

        {showDetail ? (
          <View className="fold-body">
            {/* 没有分类结论时不给模型概率：拒识/待复核的原始概率会被当成「其实还是判出来了」。 */}
            {probability ? <DetailRow label={reading?.label ?? '模型输出概率'} value={`${probability.text}%`} /> : null}
            <DetailRow label="校准状态" value={result.calibrationStatus || '未评估'} />
            <DetailRow label="图像质量" value={result.imageQualityState || '未评估'} />
            <DetailRow label="质量评分" value={typeof result.qualityScore === 'number' ? result.qualityScore.toFixed(2) : '—'} />
            <DetailRow label="分布外评估" value={result.oodStatus || '未评估'} />
            <DetailRow label="模型版本" value={result.modelVersion || '—'} />
            {result.qualityReasons ? <DetailRow label="质量说明" value={result.qualityReasons} /> : null}
          </View>
        ) : null}

        {result.url ? (
          <Button
            className="linkbtn"
            onClick={() => {
              if (!result.url) return
              void Taro.previewImage({ urls: [result.url] })
            }}
          >
            查看本次影像
          </Button>
        ) : null}

        {result.id ? <Text className="repno">记录编号 {result.id}</Text> : null}
      </View>

      <View className="disc">
        <Text className="disc__ai">AI</Text>
        <Text className="disc__text">{result.disclaimer || AI_DISCLAIMER}</Text>
      </View>
    </View>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <View className="fold-body__r">
      <Text className="fold-body__k">{label}</Text>
      <Text className="fold-body__v">{value}</Text>
    </View>
  )
}
