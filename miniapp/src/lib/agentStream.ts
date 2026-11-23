import { API_BASE } from './env'
import { getToken } from './token'
import { SseParser } from './sse'
import { Utf8StreamDecoder } from './utf8'
import type { AgentChunk } from './api'

export interface AgentStreamHandlers {
  onChunk: (chunk: AgentChunk) => void
  onDone: () => void
  onError: (message: string) => void
}

export interface AgentStreamHandle {
  abort: () => void
}

/** 两个分片之间的最长静默：超过就按断流处理（总超时仍是 120s）。 */
const IDLE_TIMEOUT_MS = 30000

interface ChunkedRequestTask {
  abort: () => void
  onChunkReceived?: (listener: (res: { data: ArrayBuffer }) => void) => void
}

type ChunkedRequestOption = WechatMiniprogram.RequestOption & {
  enableChunked?: boolean
  responseType?: 'text' | 'arraybuffer'
}

/**
 * 消费 `/api/agent/chat/stream` 的 SSE。
 *
 * <p>平台没有官方 SSE 支持：这里用 `enableChunked`（基础库 2.20.2）拿分片，
 * 自己拼事件。必须直接用 `wx.request` —— Taro 的 request 封装不透传
 * `enableChunked` / `onChunkReceived`。
 *
 * <p>失败语义与 web/ 一致：收到 `event: done` 才算正常结束；连接结束却没见到
 * done，按「连接中断」处理，而不是把半截回答当完整回答。
 */
export function streamAgentChat(
  input: { message: string; threadId: string },
  handlers: AgentStreamHandlers,
): AgentStreamHandle {
  const parser = new SseParser()
  const decoder = new Utf8StreamDecoder()
  let settled = false
  let task: ChunkedRequestTask | null = null
  let idleTimer: ReturnType<typeof setTimeout> | null = null

  const clearIdle = () => {
    if (idleTimer !== null) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  const settle = (action: () => void) => {
    if (settled) return
    settled = true
    clearIdle()
    action()
  }

  /**
   * 断流看门狗：只有总超时 120s 时，一个卡死的连接会让「正在思考…」停两分钟。
   * 每收到一个分片就重置；超过 `IDLE_TIMEOUT_MS` 没有新分片按断流处理并主动断开。
   */
  const armIdle = () => {
    clearIdle()
    idleTimer = setTimeout(() => {
      settle(() => handlers.onError('回答长时间没有新内容，已停止等待，请重试'))
      task?.abort()
    }, IDLE_TIMEOUT_MS)
  }

  const dispatch = (event: { event: string; data: string }) => {
    if (event.event === 'done') {
      settle(() => handlers.onDone())
      return
    }
    if (event.event === 'error') {
      settle(() => handlers.onError(event.data || '对话中断，请重试'))
      return
    }
    if (!event.data) return
    try {
      handlers.onChunk(JSON.parse(event.data) as AgentChunk)
    } catch {
      // 单帧解析失败不终止整条流：其它帧仍可能有效。
    }
  }

  const option: ChunkedRequestOption = {
    url: `${API_BASE}/agent/chat/stream`,
    method: 'POST',
    data: { message: input.message, threadId: input.threadId },
    header: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${getToken() ?? ''}`,
    },
    enableChunked: true,
    responseType: 'arraybuffer',
    timeout: 120000,
    success: (res) => {
      // 流已结束但没有 done：把残留缓冲解析完，仍没有 done 就按中断处理。
      parser.flush().forEach(dispatch)
      if (!settled) {
        settle(() =>
          handlers.onError(
            res.statusCode === 200 ? '连接中断，回答可能不完整，请重试' : `对话请求失败（HTTP ${res.statusCode}）`,
          ),
        )
      }
    },
    fail: () => {
      settle(() => handlers.onError('网络连接中断，请重试'))
    },
  }

  task = wx.request(option as WechatMiniprogram.RequestOption) as unknown as ChunkedRequestTask
  if (typeof task.onChunkReceived === 'function') {
    armIdle()
    task.onChunkReceived((res) => {
      armIdle()
      parser.push(decoder.decode(new Uint8Array(res.data))).forEach(dispatch)
    })
  } else {
    settle(() => handlers.onError('当前微信版本不支持流式回答，请升级微信后重试'))
  }

  return { abort: () => task?.abort() }
}
