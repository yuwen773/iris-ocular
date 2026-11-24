import { Utf8StreamDecoder } from './utf8'

/**
 * 令牌失效时，后端在**过滤器层**直接回的业务信封（HTTP 200 + 40100）——
 * 它不是 SSE 帧，所以走流式解析器是认不出来的。
 *
 * <p>两条路径都要认：分片到达时在分片里嗅探；服务端没走分块时（这个信封是很短的
 * 响应，通常整包落在 `success` 回调里）从响应体里认。漏掉任何一条，表现都是
 * 「过期令牌被报成连接中断」，客户端不会去静默重登。
 */
const AUTH_ENVELOPE = /^\s*\{\s*"code"\s*:\s*40100\b/

/** 这段文本是不是「令牌失效」业务信封。 */
export function isAuthEnvelope(text: string): boolean {
  return AUTH_ENVELOPE.test(text)
}

/**
 * 把 `success` 回调里的响应体解成文本。
 *
 * <p>`responseType: 'arraybuffer'` 时 `res.data` 是 ArrayBuffer；用增量解码器而不是
 * `TextDecoder`——小程序运行时没有可靠的 `TextDecoder`，分片边界落在汉字中间时
 * 逐片解码会出替换字符。
 */
export function decodeResponseBody(data: unknown): string {
  if (typeof data === 'string') return data
  if (data instanceof ArrayBuffer) {
    return new Utf8StreamDecoder().decode(new Uint8Array(data))
  }
  return ''
}
