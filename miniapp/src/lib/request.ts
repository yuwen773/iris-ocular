import { ensureLogin, relogin } from './auth'
import { ApiError, ERROR_CODE, rawRequest, type HttpMethod } from './raw'
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
 * 重登只重试一次——第二次还 40100 说明是身份问题（封禁 / 删号），再试没有意义。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const useAuth = options.auth !== false
  if (useAuth) {
    await ensureLogin()
  }

  let response = await rawRequest<T>({
    path,
    method: options.method,
    data: options.data,
    token: useAuth ? getToken() : null,
    timeout: options.timeout,
  })

  if (useAuth && response.code === ERROR_CODE.NOT_LOGIN) {
    await relogin()
    response = await rawRequest<T>({
      path,
      method: options.method,
      data: options.data,
      token: getToken(),
      timeout: options.timeout,
    })
  }

  if (response.code !== 0) {
    throw new ApiError(response.code, response.msg)
  }
  return response.data
}
