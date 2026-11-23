import { withAuthRetry } from './authedCall'
import { rawRequest, unpack, type HttpMethod } from './raw'
import { getToken } from './token'

export interface RequestOptions {
  method?: HttpMethod
  data?: unknown
  timeout?: number
}

/**
 * 带登录态的请求：缺令牌先静默登录；服务端说 40100 就重登一次再试。
 * 重登策略在 `authedCall.ts` 里，上传与其它带身份调用共用同一份。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const raw = await withAuthRetry(() =>
    rawRequest<T>({
      path,
      method: options.method,
      data: options.data,
      token: getToken(),
      timeout: options.timeout,
    }),
  )
  return unpack<T>(raw)
}
