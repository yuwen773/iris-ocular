import Taro from '@tarojs/taro'
import { API_BASE } from './env'

/** 与后端 BaseResponse 同构：HTTP 200 + 业务码。 */
export interface BaseResponse<T> {
  code: number
  data: T
  msg: string
}

/**
 * 业务码与本地归一码。
 *
 * <p>`40100` / `40300` 与后端 `ErrorCode.NOT_LOGIN_ERROR` / `FORBIDDEN_ERROR` 同值；
 * `-1`…`-3` 是客户端自己的归一码（网络断开 / 非 200 / 信封异常），服务端不会产生负数。
 * 比较这些数字的地方一律用这里的名字，不再各写一遍魔数。
 */
export const ERROR_CODE = {
  NOT_LOGIN: 40100,
  FORBIDDEN: 40300,
  NETWORK: -1,
  HTTP: -2,
  MALFORMED: -3,
} as const

export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface RawRequestOptions {
  path: string
  method?: HttpMethod
  data?: unknown
  token?: string | null
  timeout?: number
}

/** HTTP 状态码归一：403 与服务端同源校验/权限拒绝对应，非 200 一律「服务暂不可用」。 */
export function errorForStatus(statusCode: number): ApiError | null {
  if (statusCode === 403) {
    return new ApiError(ERROR_CODE.FORBIDDEN, '请求被服务端拒绝（同源校验未通过）')
  }
  if (statusCode !== 200) {
    return new ApiError(ERROR_CODE.HTTP, `服务暂不可用（HTTP ${statusCode}）`)
  }
  return null
}

/** 解包 BaseResponse：信封异常或业务码非 0 都抛 ApiError，成功时返回 data。 */
export function unpack<T>(body: unknown): T {
  if (!body || typeof (body as BaseResponse<T>).code !== 'number') {
    throw new ApiError(ERROR_CODE.MALFORMED, '服务端返回格式异常')
  }
  const response = body as BaseResponse<T>
  if (response.code !== 0) {
    throw new ApiError(response.code, response.msg)
  }
  return response.data
}

/**
 * 不带登录态语义的请求：负责 URL 拼接、错误信封解包与网络异常归一。
 * 令牌与重登策略在 request.ts 里，避免这一层依赖 auth。
 */
export async function rawRequest<T>(options: RawRequestOptions): Promise<BaseResponse<T>> {
  const header: Record<string, string> = { 'Content-Type': 'application/json' }
  if (options.token) {
    header.Authorization = `Bearer ${options.token}`
  }
  let response
  try {
    response = await Taro.request({
      url: API_BASE + options.path,
      method: options.method ?? 'GET',
      data: options.data as Record<string, unknown> | undefined,
      header,
      timeout: options.timeout ?? 30000,
    })
  } catch {
    throw new ApiError(ERROR_CODE.NETWORK, '网络连接失败，请检查网络后重试')
  }
  const statusError = errorForStatus(response.statusCode)
  if (statusError) {
    throw statusError
  }
  const body = response.data as BaseResponse<T> | undefined
  if (!body || typeof body.code !== 'number') {
    throw new ApiError(ERROR_CODE.MALFORMED, '服务端返回格式异常')
  }
  return body
}
