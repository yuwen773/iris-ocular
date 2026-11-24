import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import {
  getAgentHistory,
  listAgentSessions,
  listMyDiagnoses,
  type AgentSession,
  type DiagnosisSummary,
  type ResourceRef,
} from '@/lib/api'
import { collectHistoryCitations, toChatItems, type ChatItem as HistoryItem } from '@/lib/agentHistory'
import { ensureLogin } from '@/lib/auth'
import { documentCitationMap, renderCitationText } from '@/lib/citations'
import { streamAgentChat, type AgentStreamHandle } from '@/lib/agentStream'
import { recordHeadline, severityRisk } from '@/lib/domain'
import { dayLabel, splitCreateTime } from '@/lib/recordFormat'
import './index.scss'

const THREAD_KEY = 'iris.miniapp.threadId'
const PREVIEW_SESSION_COUNT = 2

interface ChatItem extends HistoryItem {
  pending?: boolean
  /** 患者手动停了生成：已出的半截不是完整回答。 */
  stopped?: boolean
  /** 本条回答可核验的证据：证据键 → 文档标题。 */
  citations?: Map<string, string>
}

const NO_CITATIONS: Map<string, string> = new Map()

/** 首屏的建议问题：点一下只是把问题放进输入框，不替患者发出去。 */
const CHIPS: { label: string; question: string }[] = [
  { label: '结果怎么看', question: '这次判读的结果应该怎么看？' },
  { label: '多久复查', question: '这种情况多久需要复查一次？' },
  { label: '要注意什么', question: '平时需要特别注意什么？' },
]

function newThreadId(): string {
  return `mp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function ensureThreadId(): string {
  const stored = Taro.getStorageSync<string>(THREAD_KEY)
  if (typeof stored === 'string' && stored) return stored
  const created = newThreadId()
  Taro.setStorageSync(THREAD_KEY, created)
  return created
}

function mergeCitations(
  base: Map<string, string> | undefined,
  next: Map<string, string>,
): Map<string, string> {
  const merged = new Map(base ?? NO_CITATIONS)
  for (const [key, title] of next) merged.set(key, title)
  return merged
}

export default function AgentPage() {
  const [messages, setMessages] = useState<ChatItem[]>([])
  const [sessions, setSessions] = useState<AgentSession[]>([])
  const [showAllSessions, setShowAllSessions] = useState(false)
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [fault, setFault] = useState('')
  // 最近一次判读：首屏的上下文卡（问的就是这一条）。
  const [latest, setLatest] = useState<DiagnosisSummary | null>(null)
  const handleRef = useRef<AgentStreamHandle | null>(null)
  // 历史请求序号：晚到的旧会话历史不能盖掉当前会话（与 web 端同一口径）。
  const historySeq = useRef(0)

  useEffect(() => {
    let alive = true
    const restore = async () => {
      const stored = Taro.getStorageSync<string>(THREAD_KEY)
      if (typeof stored !== 'string' || !stored) return
      const seq = ++historySeq.current
      try {
        const history = await getAgentHistory(stored)
        if (!alive || seq !== historySeq.current) return
        const citations = collectHistoryCitations(history)
        setMessages(toChatItems(history).map((item) => ({ ...item, citations: citations.get(item.id) })))
      } catch {
        // 历史恢复失败不阻塞新对话：threadId 仍有效，继续问即可。
      }
    }
    const loadSessions = async () => {
      try {
        const list = await listAgentSessions()
        if (!alive) return
        setSessions(list)
      } catch {
        // 历史列表拿不到不影响当前对话，静默跳过。
      }
    }
    void restore()
    void loadSessions()
    return () => {
      alive = false
      handleRef.current?.abort()
    }
  }, [])

  // 最近一次判读：进页面读一条，用来铺首屏的上下文卡。
  const latestSeq = useRef(0)
  useDidShow(() => {
    const seq = ++latestSeq.current
    void listMyDiagnoses(1, 1)
      .then((page) => {
        if (seq !== latestSeq.current) return
        setLatest(page.records[0] ?? null)
      })
      .catch(() => {
        // 读不到最近判读就不铺这张卡，不影响提问。
      })
  })

  async function refreshSessions() {
    try {
      setSessions(await listAgentSessions())
    } catch {
      // 列表刷新失败不影响当前对话。
    }
  }

  function startNewSession() {
    handleRef.current?.abort()
    handleRef.current = null
    // 让在途的历史请求作废：否则它回来后会把上一个会话的消息写进新会话。
    historySeq.current++
    setStreaming(false)
    setFault('')
    setMessages([])
    setShowAllSessions(false)
    Taro.setStorageSync(THREAD_KEY, newThreadId())
  }

  /** 从历史列表切到某个会话：换 threadId 并恢复它的消息。 */
  async function openSession(sessionId: string) {
    if (streaming) return
    handleRef.current?.abort()
    handleRef.current = null
    historySeq.current++
    setFault('')
    setMessages([])
    Taro.setStorageSync(THREAD_KEY, sessionId)
    const seq = ++historySeq.current
    try {
      const history = await getAgentHistory(sessionId)
      if (seq !== historySeq.current) return
      const citations = collectHistoryCitations(history)
      setMessages(toChatItems(history).map((item) => ({ ...item, citations: citations.get(item.id) })))
    } catch {
      setFault('历史对话加载失败，请重试')
    }
  }

  async function send() {
    const text = input.trim()
    if (!text || streaming) return
    setInput('')
    setFault('')

    // 流式请求不经过 request.ts：首启时令牌可能还没换到，先在这里补一次静默登录。
    try {
      await ensureLogin()
    } catch {
      setFault('登录状态已失效，请到「我的」重新登录')
      return
    }

    const assistantId = `a-${Date.now()}`
    setMessages((prev) => [
      ...prev,
      { id: `u-${Date.now()}`, role: 'user', content: text },
      { id: assistantId, role: 'assistant', content: '', pending: true, citations: new Map() },
    ])
    setStreaming(true)

    handleRef.current = streamAgentChat(
      { message: text, threadId: ensureThreadId() },
      {
        onChunk: (chunk) => {
          if (!chunk.content) return
          setMessages((prev) =>
            prev.map((item) =>
              item.id === assistantId ? { ...item, content: item.content + chunk.content } : item,
            ),
          )
        },
        // 检索到的证据挂在这一条回答上：核验得出来的引用才画得出角标。
        onResources: (resources: ResourceRef[]) => {
          const map = documentCitationMap(resources)
          if (map.size === 0) return
          setMessages((prev) =>
            prev.map((item) =>
              item.id === assistantId ? { ...item, citations: mergeCitations(item.citations, map) } : item,
            ),
          )
        },
        onDone: () => {
          setStreaming(false)
          setMessages((prev) => prev.map((item) => (item.id === assistantId ? { ...item, pending: false } : item)))
          void refreshSessions()
        },
        onError: (message) => {
          setStreaming(false)
          setFault(message)
          setMessages((prev) => prev.map((item) => (item.id === assistantId ? { ...item, pending: false } : item)))
        },
      },
    )
  }

  /** 停止生成：断掉流，已出的半截留下，并如实标成「已停止」。 */
  function stop() {
    handleRef.current?.abort()
    handleRef.current = null
    setStreaming(false)
    setMessages((prev) => prev.map((item) => (item.pending ? { ...item, pending: false, stopped: true } : item)))
  }

  const inThread = messages.length > 0
  const lastId = inThread ? messages[messages.length - 1].id : ''
  const visibleSessions = showAllSessions ? sessions : sessions.slice(0, PREVIEW_SESSION_COUNT)
  const canSend = !streaming && input.trim().length > 0

  return (
    <View className="agent-page">
      <View className="agent-top">
        <View className="dochead">
          <Text className="dochead__title">
            {inThread ? `咨询 · ${messages.length} 条消息` : '咨询 · 眼底问答'}
          </Text>
          {inThread ? (
            <View className="dochead__act" onClick={startNewSession}>
              <Text>新对话</Text>
            </View>
          ) : (
            <Text className="dochead__meta">体验版</Text>
          )}
        </View>
      </View>

      <ScrollView className="agent-scroll" scrollY scrollIntoView={lastId ? `msg-${lastId}` : ''} scrollWithAnimation>
        <View className="agent-inner">
          {!inThread ? (
            <View className="agent-hero">
              <Text className="h-page">可以问我眼科的问题</Text>
              <Text className="lede">AI 回答只能作参考，诊断请听眼科医生的。</Text>

              {latest ? (
                <ContextCard record={latest} onAsk={(question) => setInput(question)} />
              ) : null}

              <View className="chips">
                {CHIPS.map((chip) => (
                  <View className="chip" key={chip.label} onClick={() => setInput(chip.question)}>
                    <Text className="chip__text">{chip.label}</Text>
                  </View>
                ))}
              </View>

              <View className="sect">
                <Text>最近的对话</Text>
                <Text className="sect-n">{sessions.length} 个</Text>
              </View>

              {sessions.length > 0 ? (
                <View className="conv-panel">
                  {visibleSessions.map((session) => (
                    <View
                      className="conv"
                      key={session.sessionId}
                      onClick={() => void openSession(session.sessionId)}
                    >
                      <View className="conv__main">
                        <View className="conv__t">
                          <Text className="conv__title">{session.title || '未命名对话'}</Text>
                          <Text className="conv__n">
                            {dayLabel(session.updatedTime)}
                            {typeof session.messageCount === 'number' ? ` · ${session.messageCount} 条消息` : ''}
                          </Text>
                        </View>
                        {session.lastMessage ? <Text className="conv__s">{session.lastMessage}</Text> : null}
                      </View>
                      <View className="conv__chevron" />
                    </View>
                  ))}

                  {sessions.length > PREVIEW_SESSION_COUNT ? (
                    <View className="conv-toggle" onClick={() => setShowAllSessions((v) => !v)}>
                      <Text>
                        {showAllSessions ? '收起对话列表' : `查看全部 ${sessions.length} 个对话`}
                      </Text>
                    </View>
                  ) : null}
                </View>
              ) : (
                <View className="conv-empty">
                  <Text>还没有历史对话，直接在下方输入你的问题即可开始。</Text>
                </View>
              )}
            </View>
          ) : (
            <View className="thread-list">
              {messages.map((item) => (
                <Bubble key={item.id} item={item} />
              ))}
            </View>
          )}

          {fault ? <View className="mp-fault agent-fault">{fault}</View> : null}
        </View>
      </ScrollView>

      <View className="agent-bar">
        <Input
          className="agent-input"
          value={input}
          placeholder={inThread ? '接着问…' : '描述你的问题…'}
          placeholderClass="agent-input__ph"
          confirmType="send"
          disabled={streaming}
          onInput={(event) => setInput(event.detail.value)}
          onConfirm={() => void send()}
        />
        {streaming ? (
          <Button className="agent-send agent-send--stop" onClick={stop}>
            <View className="agent-send__sq" />
            <Text>停止</Text>
          </Button>
        ) : (
          <Button
            className={`agent-send${canSend ? ' agent-send--ready' : ' agent-send--idle'}`}
            disabled={!canSend}
            onClick={() => void send()}
          >
            发送
          </Button>
        )}
      </View>
    </View>
  )
}

/** 首屏的上下文卡：最近一次判读。点一下就把相关问题放进输入框。 */
function ContextCard(props: { record: DiagnosisSummary; onAsk: (question: string) => void }) {
  const headline = recordHeadline(props.record)
  const time = splitCreateTime(props.record.createTime)
  const risk = severityRisk(props.record.severity)
  const showSeverity = Boolean(
    headline.outcome === 'classified' &&
      headline.className &&
      headline.severity &&
      headline.severity !== headline.className,
  )
  const question =
    headline.outcome === 'classified'
      ? `我这次的结果是${headline.title}，严重吗？`
      : '我这次的结果没有给出结论，是什么原因？'

  return (
    <View className="ctx" onClick={() => props.onAsk(question)}>
      <View className="ctx-top">
        {props.record.url ? (
          <View className="ctx-img-wrap">
            <Image className="ctx-img" src={props.record.url} mode="aspectFit" />
          </View>
        ) : (
          <View className="ctx-img-wrap ctx-img-wrap--none" />
        )}
        <View className="ctx-m">
          <Text className="ctx-d">
            最近一次判读 · {time ? `${time.md} ${time.hm}`.trim() : '日期未知'}
          </Text>
          <View className="ctx-r">
            <Text className="ctx-r__name">{headline.className ?? headline.title}</Text>
            {showSeverity ? (
              <Text className={`ctx-r__sev is-${risk}`}>{headline.severity}</Text>
            ) : null}
          </View>
          {headline.detail ? <Text className="ctx-sub">{headline.detail}</Text> : null}
        </View>
      </View>
      <View className="ctx-go">
        <Text>就这次结果提问</Text>
        <View className="ctx-arrow" />
      </View>
    </View>
  )
}

/** 一问一答：用户消息靠右收紧，AI 回答以临床单据卡呈现在左侧并内嵌引用与声明。 */
function Bubble(props: { item: ChatItem }) {
  if (props.item.role === 'user') {
    return (
      <View className="bub bub--user" id={`msg-${props.item.id}`}>
        <View className="user-pill">
          <Text className="user-pill__text">{props.item.content ?? ''}</Text>
        </View>
      </View>
    )
  }

  // 只有 AI 回答才做引用标注：患者自己打的字原样显示，不替他改写。
  const rendered = renderCitationText(props.item.content ?? '', props.item.citations ?? NO_CITATIONS)
  const hasText = rendered.text.trim().length > 0
  const hasFooter = rendered.refs.length > 0 || rendered.unverified > 0 || Boolean(props.item.stopped)

  return (
    <View className="bub bub--assistant" id={`msg-${props.item.id}`}>
      <View className="ai-card">
        <View className="ai-card__head">
          <View className="ai-card__id">
            <View className={`ai-card__dot${props.item.pending ? ' is-live' : ''}`} />
            <Text className="ai-card__name">IRIS 助理</Text>
          </View>
          {props.item.pending ? (
            <Text className="ai-card__status is-live">正在回答</Text>
          ) : props.item.stopped ? (
            <Text className="ai-card__status">已停止</Text>
          ) : hasText ? (
            <Text className="ai-card__status">AI 生成 · 仅供参考</Text>
          ) : null}
        </View>

        <View className="ai-card__body">
          <Text className="ai-card__text">
            {hasText ? rendered.text : props.item.pending ? '正在思考…' : '（没有返回内容）'}
            {props.item.pending ? <Text className="caret" /> : null}
          </Text>
        </View>

        {hasFooter ? (
          <View className="ai-card__foot">
            {rendered.refs.length > 0 ? (
              <View className="refs">
                {rendered.refs.map((ref) => (
                  // 正文里的 [n] 与这里的编号一一对应。
                  <View className="refs__i" key={ref.key}>
                    <Text className="refs__idx">[{ref.n}]</Text>
                    <Text className="refs__t">{ref.title}</Text>
                  </View>
                ))}
              </View>
            ) : null}

            {rendered.unverified > 0 ? (
              <Text className="refs__unv">有 {rendered.unverified} 处引用没能核验</Text>
            ) : null}

            {props.item.stopped ? (
              <Text className="stopped">已停止生成，以上内容未完整输出</Text>
            ) : null}
          </View>
        ) : null}
      </View>
    </View>
  )
}
