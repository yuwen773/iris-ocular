import { Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useReachBottom } from '@tarojs/taro'
import { useState } from 'react'
import { EmptyState, ErrorState, LoadingState } from '@/components/Feedback'
import { OutcomeTag } from '@/components/Tags'
import { listMyDiagnoses, type DiagnosisSummary } from '@/lib/api'
import { canonicalClassName, outcomeLabel, outcomeOf } from '@/lib/domain'
import { ApiError } from '@/lib/raw'
import './index.scss'

const PAGE_SIZE = 10

export default function RecordsPage() {
  const [items, setItems] = useState<DiagnosisSummary[]>([])
  const [current, setCurrent] = useState(0)
  const [total, setTotal] = useState(0)
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [fault, setFault] = useState('')

  async function loadPage(next: number) {
    if (next === 1) setState('loading')
    try {
      const page = await listMyDiagnoses(next, PAGE_SIZE)
      setItems((prev) => (next === 1 ? page.records : [...prev, ...page.records]))
      setCurrent(next)
      setTotal(page.total)
      setState('ready')
      setFault('')
    } catch (error) {
      setState('error')
      setFault(error instanceof ApiError ? error.message : '读取记录失败，请重试')
    }
  }

  useDidShow(() => {
    void loadPage(1)
  })

  useReachBottom(() => {
    if (state === 'error' || items.length >= total) return
    void loadPage(current + 1)
  })

  function open(id?: string) {
    if (!id) return
    void Taro.navigateTo({ url: `/pages/diagnosis/result?id=${id}` })
  }

  return (
    <View className="mp-page">
      {state === 'loading' && items.length === 0 ? <LoadingState text="正在读取判读记录…" /> : null}

      {state === 'error' ? <ErrorState text={fault} onRetry={() => loadPage(1)} /> : null}

      {state === 'ready' && items.length === 0 ? (
        <EmptyState text="还没有判读记录。回到「判读」拍一张眼底影像试试。" />
      ) : null}

      {items.map((item) => {
        const outcome = outcomeOf(item)
        return (
          <View className="record-card" key={item.id ?? `${item.createTime}-${item.result}`} onClick={() => open(item.id)}>
            <View className="record-card__head">
              <Text className="mp-label">{item.createTime ? item.createTime.slice(0, 10) : '日期未知'}</Text>
              <OutcomeTag
                label={outcomeLabel(outcome)}
                tone={outcome === 'classified' ? 'normal' : outcome === 'rejected' ? 'critical' : 'attention'}
              />
            </View>
            <View className="record-card__body">
              {item.url ? <Image className="record-card__thumb" src={item.url} mode="aspectFill" /> : null}
              <View className="record-card__text">
                <Text className="record-card__title">
                  {outcome === 'classified'
                    ? (canonicalClassName(item.result) ?? item.result ?? '已给出分类')
                    : outcomeLabel(outcome)}
                </Text>
                <Text className="mp-note">
                  {outcome === 'classified' ? '置信度见详情' : '详情里有具体原因与下一步建议'}
                </Text>
              </View>
            </View>
          </View>
        )
      })}

      {items.length > 0 && items.length < total ? (
        <View className="mp-empty">
          <Text>上拉加载更多</Text>
        </View>
      ) : null}
    </View>
  )
}
