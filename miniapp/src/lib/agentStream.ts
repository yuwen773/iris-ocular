import { relogin } from './auth'
import { decodeResponseBody, isAuthEnvelope } from './authEnvelope'
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

/**
 * 只有最终回答才进正文。
 *
 * <p>`messageType` 与落库的 `subType` 不是同一套词：推送用 `assistant` / `reasoning` /
 * `tool-request` / `tool-result` / `tool-confirm`。缺字段时按 `assistant` 处理（后端
 * 承诺消费方要容忍缺字段），但显式的非回答类型一律不拼进答案——否则模型的思考过程
 * 会混进患者看到的正文。
 */
function isAnswerChunk(chunk: AgentChunk): boolean {
  return chunk.messageType == null || chunk.messageType === 'assistant'
}

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
 *
 * <p>令牌失效时过滤器层回的是业务信封而不是 SSE：这里认出 40100、静默重登一次
 * 再重开一条流（只重试一次），与 `request.ts` 同口径。
 */
export function streamAgentChat(
  input: { message: string; threadId: string },
  handlers: AgentStreamHandlers,
): AgentStreamHandle {
  let task: ChunkedRequestTask | null = null
  let finished = false
  let authRetried = false

  const startAttempt = () => {
    const parser = new SseParser()
    const decoder = new Utf8StreamDecoder()
    let settled = false
    let idleTimer: ReturnType<typeof setTimeout> | null = null
    // 只在流的开头嗅探一次「这是业务信封还是 SSE 帧」，认出 SSE 后就不再累积。
    let sniffing = true
    let prefix = ''

    const clearIdle = () => {
      if (idleTimer !== null) {
        clearTimeout(idleTimer)
        idleTimer = null
      }
    }

    const settle = (action: () => void) => {
      if (settled || finished) return
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
        const chunk = JSON.parse(event.data) as AgentChunk
        if (isAnswerChunk(chunk)) {
          handlers.onChunk(chunk)
        }
      } catch {
        // 单帧解析失败不终止整条流：其它帧仍可能有效。
      }
    }

    const restartAfterRelogin = () => {
      if (settled || finished) return
      settled = true
      clearIdle()
      task?.abort()
      if (authRetried) {
        finished = true
        handlers.onError('登录状态已失效，请到「我的」重新登录')
        return
      }
      authRetried = true
      // 重开一条流等于把同一条提问再 POST 一次，会不会在会话里留下两条用户消息？
      // 不会：40100 业务信封只在**落库之前**产生——令牌过滤器拦下的过期令牌，
      // 以及 AgentController.chatStream 里 getLoginUser / claimOrValidateSession
      // 的未登录拒绝，都发生在 saveUserMessage 之前。流开始之后的服务端错误走的是
      // SSE 的 event: error 帧，不会被这里当成 40100 重试。
      relogin().then(
        () => {
          if (!finished) startAttempt()
        },
        () => {
          finished = true
          handlers.onError('登录状态已失效，请到「我的」重新登录')
        },
      )
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
        // 服务端不一定走分块：短响应（比如令牌失效的 40100 信封）会整包落到 success。
        // 不在这里认一次，过期令牌就会被报成「连接中断」，客户端不会去静默重登。
        const body = decodeResponseBody(res.data)
        if (isAuthEnvelope(body)) {
          restartAfterRelogin()
          return
        }
        // 流已结束但没有 done：把残留缓冲解析完，仍没有 done 就按中断处理。
        parser.flush().forEach(dispatch)
        settle(() =>
          handlers.onError(
            res.statusCode === 200 ? '连接中断，回答可能不完整，请重试' : `对话请求失败（HTTP ${res.statusCode}）`,
          ),
        )
      },
      fail: () => {
        settle(() => handlers.onError('网络连接中断，请重试'))
      },
    }

    task = wx.request(option as WechatMiniprogram.RequestOption) as unknown as ChunkedRequestTask
    if (typeof task.onChunkReceived !== 'function') {
      settle(() => handlers.onError('当前微信版本不支持流式回答，请升级微信后重试'))
      return
    }
    armIdle()
    task.onChunkReceived((res) => {
      if (settled) return
      armIdle()
      const text = decoder.decode(new Uint8Array(res.data))
      if (sniffing) {
        prefix = (prefix + text).slice(0, 256)
        if (isAuthEnvelope(prefix)) {
          restartAfterRelogin()
          return
        }
        if (/^\s*(data:|event:|:)/.test(text) || prefix.includes('\n')) {
          sniffing = false
          prefix = ''
        }
      }
      parser.push(text).forEach(dispatch)
    })
  }

  startAttempt()

  return {
    abort: () => {
      finished = true
      task?.abort()
    },
  }
}
