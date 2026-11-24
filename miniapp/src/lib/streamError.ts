/**
 * 读流式对话的 `event: error` 帧。
 *
 * <p>后端现在给的是 `{code, msg}`：50301 模型服务鉴权失败、50302 额度不足、
 * 50303 暂时不可用、40101 未登录。旧服务只给一句人话。两种都收下——码留给
 * 需要分支处置的调用方，默认展示一律用后端给的 `msg`：配置与医学措辞只在
 * 服务端维护一份，端上不要重写。
 *
 * <p>纯函数，改动必须补 `tests/streamError.test.ts`。
 */

/** 没有可读文案时的兜底：与旧行为一致，不让患者看到空提示。 */
export const STREAM_ERROR_FALLBACK = '对话中断，请重试'

/** 登录状态失效的机器码（后端 `ErrorCode.NOT_LOGIN_ERROR`）。 */
export const STREAM_ERROR_NOT_LOGIN = 40101

export interface StreamError {
  /** 机器可读码；旧服务（纯文本）按 50000 处理。 */
  code: number
  /** 给人看的一句话。 */
  message: string
}

export function readStreamError(data: string | null | undefined): StreamError {
  const text = (data ?? '').trim()
  if (!text) {
    return { code: 50000, message: STREAM_ERROR_FALLBACK }
  }
  if (text.startsWith('{')) {
    try {
      const payload = JSON.parse(text) as { code?: unknown; msg?: unknown }
      const code = typeof payload.code === 'number' ? payload.code : 50000
      const msg = typeof payload.msg === 'string' ? payload.msg.trim() : ''
      return { code, message: msg || STREAM_ERROR_FALLBACK }
    } catch {
      // 不是 JSON 就按纯文本处理
    }
  }
  return { code: 50000, message: text }
}
