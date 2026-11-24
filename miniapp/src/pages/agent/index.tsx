import { Button, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Disclaimer } from '@/components/Feedback'
import { getAgentHistory, listAgentSessions, type AgentSession } from '@/lib/api'
import { prependHistoryItems, toChatItems, type ChatItem as HistoryItem } from '@/lib/agentHistory'
import { ensureLogin } from '@/lib/auth'
import { streamAgentChat, type AgentStreamHandle } from '@/lib/agentStream'
import './index.scss'

const THREAD_KEY = 'iris.miniapp.threadId'

interface ChatItem extends HistoryItem {
  pending?: boolean
}

const SUGGESTIONS = ['眼底照片拍得不清楚会怎样？', '糖尿病视网膜病变需要注意什么？', '判读结果里的置信度怎么理解？']

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

export default function AgentPage() {
  const [messages, setMessages] = useState<ChatItem[]>([])
  const [sessions, setSessions] = useState<AgentSession[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [fault, setFault] = useState('')
  const [historyLoading, setHistoryLoading] = useState<'latest' | 'older' | null>(null)
  const [olderHistory, setOlderHistory] = useState<{ threadId: string; cursor: string } | null>(null)
  const [olderFault, setOlderFault] = useState('')
  const handleRef = useRef<AgentStreamHandle | null>(null)
  // 历史请求序号：晚到的旧会话历史不能盖掉当前会话（与 web 端同一口径）。
  const historySeq = useRef(0)

  const loadHistory = useCallback(async (threadId: string, beforeId?: string, restoring = false) => {
    const seq = ++historySeq.current
    const isCurrent = () => seq === historySeq.current && Taro.getStorageSync<string>(THREAD_KEY) === threadId
    setHistoryLoading(beforeId ? 'older' : 'latest')
    setOlderFault('')
    try {
      let cursor = beforeId
      let history = await getAgentHistory(threadId, cursor)
      let items = toChatItems(history)
      // 一整页可能只有推理、工具事件或摘要。继续找患者可见的问答。
      while (isCurrent() && items.length === 0 && history.hasMore && history.nextCursor
        && history.nextCursor !== cursor) {
        cursor = history.nextCursor
        history = await getAgentHistory(threadId, cursor)
        items = toChatItems(history)
      }
      if (!isCurrent()) return
      setOlderHistory(history.hasMore && history.nextCursor ? { threadId, cursor: history.nextCursor } : null)
      if (beforeId) {
        setMessages((current) => prependHistoryItems(current, items))
      } else {
        setMessages(items)
      }
      if (history.uncommittedTerminal) setFault('有消息尚未保存，这段历史可能不完整')
    } catch {
      if (!isCurrent()) return
      if (beforeId) setOlderFault('更早消息加载失败，请重试')
      else if (!restoring) setFault('历史对话加载失败，请重试')
    } finally {
      if (seq === historySeq.current) setHistoryLoading(null)
    }
  }, [])

  useEffect(() => {
    let alive = true
    const stored = Taro.getStorageSync<string>(THREAD_KEY)
    if (typeof stored === 'string' && stored) void loadHistory(stored, undefined, true)
    const loadSessions = async () => {
      try {
        const list = await listAgentSessions()
        if (!alive) return
        setSessions(list)
      } catch {
        // 历史列表拿不到不影响当前对话，静默跳过。
      }
    }
    void loadSessions()
    return () => {
      alive = false
      historySeq.current++
      handleRef.current?.abort()
    }
  }, [loadHistory])

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
    setHistoryLoading(null)
    setOlderHistory(null)
    setOlderFault('')
    setStreaming(false)
    setFault('')
    setMessages([])
    Taro.setStorageSync(THREAD_KEY, newThreadId())
  }

  /** 从历史列表切到某个会话：换 threadId 并恢复它的消息。 */
  async function openSession(sessionId: string) {
    if (streaming) return
    handleRef.current?.abort()
    handleRef.current = null
    setFault('')
    setOlderHistory(null)
    setMessages([])
    Taro.setStorageSync(THREAD_KEY, sessionId)
    await loadHistory(sessionId)
  }

  async function send() {
    const text = input.trim()
    if (!text || streaming || historyLoading === 'latest') return
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
      { id: assistantId, role: 'assistant', content: '', pending: true },
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

  const lastId = messages.length > 0 ? messages[messages.length - 1].id : ''

  return (
    <View className="agent-page">
      <ScrollView className="agent-scroll" scrollY scrollIntoView={`msg-${lastId}`} scrollWithAnimation>
        <View className="agent-inner">
          {historyLoading === 'latest' ? <View className="mp-note">正在读取历史对话…</View> : null}
          {messages.length === 0 && historyLoading !== 'latest' && !olderHistory ? (
            <View className="agent-intro">
              <Text className="mp-title">可以问我眼科相关的问题</Text>
              <Text className="mp-subtitle">
                回答由 AI 生成，只能作参考。涉及用药、手术和诊断，请听眼科医生的。
              </Text>
              <View className="agent-suggestions">
                {SUGGESTIONS.map((item) => (
                  <View className="agent-chip" key={item} onClick={() => setInput(item)}>
                    <Text className="mp-note">{item}</Text>
                  </View>
                ))}
              </View>

              {sessions.length > 0 ? (
                <View className="agent-sessions">
                  <Text className="mp-label">最近的对话</Text>
                  {sessions.map((session) => (
                    <View
                      className="agent-session"
                      key={session.sessionId}
                      onClick={() => void openSession(session.sessionId)}
                    >
                      <Text className="agent-session__title">{session.title || '未命名对话'}</Text>
                      {session.lastMessage ? (
                        <Text className="agent-session__preview">{session.lastMessage}</Text>
                      ) : null}
                    </View>
                  ))}
                </View>
              ) : null}
            </View>
          ) : null}

          {olderHistory ? (
            <View className="agent-older">
              <Button
                className="mp-btn mp-btn--ghost"
                disabled={historyLoading !== null}
                onClick={() => void loadHistory(olderHistory.threadId, olderHistory.cursor)}
              >
                {historyLoading === 'older' ? '正在加载…' : '加载更早消息'}
              </Button>
              {olderFault ? <View className="mp-fault">{olderFault}</View> : null}
            </View>
          ) : null}

          {messages.map((item) => (
            <View className={`agent-msg agent-msg--${item.role}`} id={`msg-${item.id}`} key={item.id}>
              {item.role === 'assistant' ? <Text className="mp-label">Iris</Text> : null}
              <Text className="agent-msg__text">
                {item.content || (item.pending ? '正在思考…' : '（没有返回内容）')}
              </Text>
              {item.role === 'assistant' && item.content ? (
                <Text className="agent-msg__badge">AI 生成，仅供参考</Text>
              ) : null}
            </View>
          ))}

          {fault ? <View className="mp-fault">{fault}</View> : null}
        </View>
      </ScrollView>

      <View className="agent-bar">
        <Input
          className="agent-input"
          value={input}
          placeholder="描述你的问题…"
          confirmType="send"
          disabled={streaming || historyLoading === 'latest'}
          onInput={(event) => setInput(event.detail.value)}
          onConfirm={send}
        />
        <Button
          className="mp-btn agent-send"
          disabled={streaming || historyLoading === 'latest' || !input.trim()}
          onClick={() => void send()}
        >
          {streaming ? '回答中' : '发送'}
        </Button>
      </View>

      <Button className="mp-btn mp-btn--ghost agent-new" onClick={startNewSession} disabled={streaming}>
        开始新对话
      </Button>
      <View className="agent-disclaimer">
        <Disclaimer />
      </View>
    </View>
  )
}
