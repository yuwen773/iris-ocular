import { Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useReachBottom } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { EmptyState, ErrorState, LoadingState } from '@/components/Feedback'
import { listMyDiagnoses, type DiagnosisSummary } from '@/lib/api'
import {
  outcomeLabel,
  outcomeOf,
  probabilityDisplay,
  recordHeadline,
  severityRisk,
} from '@/lib/domain'
import { monthLabel, splitCreateTime } from '@/lib/recordFormat'
import { ApiError } from '@/lib/raw'
import './index.scss'

const PAGE_SIZE = 10

interface RecordRow {
  item: DiagnosisSummary
  time: ReturnType<typeof splitCreateTime>
}

interface MonthGroup {
  ym: string
  rows: RecordRow[]
}

function groupByMonth(items: DiagnosisSummary[]): MonthGroup[] {
  const groups: MonthGroup[] = []
  for (const item of items) {
    const time = splitCreateTime(item.createTime)
    const ym = time?.ym ?? 'unknown'
    const last = groups[groups.length - 1]
    if (last && last.ym === ym) {
      last.rows.push({ item, time })
    } else {
      groups.push({ ym, rows: [{ item, time }] })
    }
  }
  return groups
}

export default function RecordsPage() {
  const [items, setItems] = useState<DiagnosisSummary[]>([])
  const [current, setCurrent] = useState(0)
  const [total, setTotal] = useState(0)
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [fault, setFault] = useState('')
  // 只看已给出分类：客户端过滤已加载的记录。
  const [onlyClassified, setOnlyClassified] = useState(false)
  // 同一页不重复请求：上拉触发的加载还没回来时再触一次，不能把同一页追加两遍。
  const pendingPage = useRef<number | null>(null)
  // 请求序号：只认最后一次请求的结果，慢响应不能盖掉刚拉回来的新数据。
  const requestSeq = useRef(0)

  async function loadPage(next: number) {
    if (pendingPage.current === next) return
    pendingPage.current = next
    const seq = ++requestSeq.current
    if (next === 1) setState('loading')
    try {
      const page = await listMyDiagnoses(next, PAGE_SIZE)
      if (seq !== requestSeq.current) return
      setItems((prev) => (next === 1 ? page.records : [...prev, ...page.records]))
      setCurrent(next)
      setTotal(page.total)
      setState('ready')
      setFault('')
    } catch (error) {
      if (seq !== requestSeq.current) return
      setState('error')
      setFault(error instanceof ApiError ? error.message : '读取记录失败，请重试')
    } finally {
      if (pendingPage.current === next) pendingPage.current = null
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

  const visible = onlyClassified ? items.filter((item) => outcomeOf(item) === 'classified') : items
  const groups = groupByMonth(visible)
  // 最新一条（后端按时间倒序返回）：给它一个当前态的记号。
  const latestId = items[0]?.id

  return (
    <View className="mp-page records-page">
      <View className="dochead">
        <Text>眼底影像 · 判读记录</Text>
        <Text>共 {total} 次</Text>
      </View>

      <View className="filter-tabs" role="tablist">
        <View
          className={`filter-tab${!onlyClassified ? ' is-active' : ''}`}
          onClick={() => setOnlyClassified(false)}
        >
          <Text className="filter-tab__label">全部记录</Text>
          {total > 0 ? <Text className="filter-tab__count">{total}</Text> : null}
        </View>
        <View
          className={`filter-tab${onlyClassified ? ' is-active' : ''}`}
          onClick={() => setOnlyClassified(true)}
        >
          <Text className="filter-tab__label">仅看已分类</Text>
        </View>
      </View>

      {state === 'loading' && items.length === 0 ? <LoadingState text="正在读取判读记录…" /> : null}

      {state === 'error' ? <ErrorState text={fault} onRetry={() => loadPage(1)} /> : null}

      {state === 'ready' && items.length === 0 ? (
        <EmptyState text="还没有判读记录。回到「判读」拍一张眼底影像试试。" />
      ) : null}

      {state === 'ready' && items.length > 0 && visible.length === 0 ? (
        <EmptyState text="已加载的记录里还没有给出分类的判读。" />
      ) : null}

      {groups.map((group) => (
        <View className="mgroup" key={group.ym}>
          <View className="mgh">
            <Text className="mgh__m">{group.ym === 'unknown' ? '日期未知' : monthLabel(group.ym)}</Text>
            <Text className="mgh__n">{group.rows.length} 次</Text>
          </View>
          <View className="vrows">
            {group.rows.map((row) => (
              <RecordLine
                key={row.item.id ?? `${row.item.createTime}-${row.item.result}`}
                item={row.item}
                date={row.time?.md ?? ''}
                clock={row.time?.hm ?? ''}
                now={row.item.id != null && row.item.id === latestId}
                onOpen={() => open(row.item.id)}
              />
            ))}
          </View>
        </View>
      ))}

      {items.length > 0 && items.length < total ? (
        <View className="records-foot">
          <Text>上拉加载更多记录</Text>
        </View>
      ) : null}
    </View>
  )
}

/** 一条判读：时间 · 竖轴刻度 · 浮层单据卡（结局、结论、严重度、读数、影像缩略图）。 */
function RecordLine(props: {
  item: DiagnosisSummary
  date: string
  clock: string
  now: boolean
  onOpen: () => void
}) {
  const outcome = outcomeOf(props.item)
  const headline = recordHeadline(props.item)
  const classified = outcome === 'classified'
  const prob = classified ? probabilityDisplay(props.item) : null
  const risk = severityRisk(props.item.severity)
  const showSeverity = Boolean(
    classified && headline.className && headline.severity && headline.severity !== headline.className,
  )

  return (
    <View className={`vrow${props.now ? ' is-now' : ''}`} onClick={props.onOpen}>
      <View className="ad">
        <Text className="ad__d">{props.date || '未知'}</Text>
        {props.clock ? <Text className="ad__t">{props.clock}</Text> : null}
      </View>

      <View className="ax" />

      <View className="am">
        <View className="am-card">
          <View className="am-meta">
            <View className="am-outcome">
              <View className={`am-outcome__mark${classified ? '' : ' is-hollow'}`} />
              <Text className="am-outcome__text">{outcomeLabel(outcome)}</Text>
            </View>
            <View className="am-chevron" />
          </View>

          <View className="am-body">
            <View className="am-main">
              <View className="am-head">
                <Text className="al">{headline.className ?? headline.title}</Text>
                {showSeverity ? (
                  <Text className={`am-sev is-${risk}`}>{headline.severity}</Text>
                ) : null}
              </View>

              {prob ? (
                <View className="am-reading">
                  <Text className="am-reading__k">{prob.label}</Text>
                  <Text className="am-reading__v">{prob.percent}</Text>
                </View>
              ) : headline.detail ? (
                <Text className="as">{headline.detail}</Text>
              ) : null}
            </View>

            {props.item.url ? (
              <View className="am-thumb-wrap">
                <Image className="am-thumb" src={props.item.url} mode="aspectFit" />
              </View>
            ) : null}
          </View>
        </View>
      </View>
    </View>
  )
}
