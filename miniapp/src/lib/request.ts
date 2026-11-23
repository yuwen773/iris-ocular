import { withAuthRetry } from './authedCall'
import { rawRequest, unpack, type HttpMethod } from './raw'
import { getToken } from './token'

export interface RequestOptions {
  method?: HttpMethod
  data?: unknown
  /** 默认 true。登录类接口显式传 false。 */
  auth?: boolean
  timeout?: number
}

/**
 * 带登录态的请求：缺令牌先静默登录；服务端说 40100 就重登一次再试。
 * 重登策略在 `authedCall.ts` 里，上传与其它带身份调用共用同一份。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const send = () =>
    rawRequest<T>({
      path,
      method: options.method,
      data: options.data,
      token: options.auth === false ? null : getToken(),
      timeout: options.timeout,
    })

  const raw = options.auth === false ? await send() : await withAuthRetry(send)
  return unpack<T>(raw)
}
