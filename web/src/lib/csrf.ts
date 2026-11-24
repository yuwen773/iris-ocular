/**
 * 配置中心（Control Center）HTTP 通道：CSRF Token 的获取、缓存与提交。
 *
 * <p>服务端要求 `/api/admin/control-center/**` 的每个写请求带 `X-CSRF-Token`，
 * 值与当前 Session 绑定的 token 一致（见 `ControlCenterCsrfFilter`）。这里把
 * 三件事收在一处，页面层不再自己拼头：
 * <ol>
 *   <li><b>获取</b>：`GET /admin/control-center/csrf`；</li>
 *   <li><b>缓存</b>：token 在同一会话内稳定，内存缓存 + 单飞（并发首屏只发一次请求）；</li>
 *   <li><b>失效</b>：会话变化（登出 / 401xx 广播）后清空，下一次写请求重新获取。</li>
 * </ol>
 *
 * <p>不落 localStorage / sessionStorage：token 是会话凭据，持久化只会把
 * 它暴露给同源脚本的持久化攻击面。
 */

import { ApiError } from "./api-error"
import { AUTH_EXPIRED_EVENT, request } from "./http"

export const CSRF_HEADER = "X-CSRF-Token"

const CSRF_PATH = "/admin/control-center/csrf"

export type CsrfTokenResponse = {
  token: string
  headerName: string
}

let cachedToken: string | null = null
let inFlight: Promise<string> | null = null

/** 清空缓存（登出、会话失效、CSRF 校验失败后调用）。 */
export function resetCsrfToken() {
  cachedToken = null
  inFlight = null
}

// 会话失效广播到达时立刻作废，避免拿旧会话的 token 去重试。
if (typeof window !== "undefined") {
  window.addEventListener(AUTH_EXPIRED_EVENT, () => resetCsrfToken())
}

/**
 * 取当前会话的 CSRF token（带缓存与单飞）。
 *
 * <p>失败时清空单飞占位，下一次调用会真正重试——否则一次网络抖动会让
 * 整个页面在所有后续写请求里都拿到同一个失败。
 */
export async function getCsrfToken(): Promise<string> {
  if (cachedToken) return cachedToken
  if (inFlight) return inFlight

  inFlight = request<CsrfTokenResponse>(CSRF_PATH, {}, { silentAuth: true })
    .then((payload) => {
      if (!payload?.token) throw new ApiError(-1, "未取得 CSRF Token")
      cachedToken = payload.token
      return payload.token
    })
    .catch((error) => {
      inFlight = null
      throw error
    })
    .finally(() => {
      inFlight = null
    })

  return inFlight
}

/**
 * 控制中心写请求：自动带 CSRF 头。
 *
 * <p>服务端返回 `CSRF_MISMATCH` 时清空缓存再抛出：下一次用户操作会拿到新 token，
 * 而不是一直沿用这个已失效的值。这里不自动重试——写请求是否可重试由幂等键
 * （requestId）决定，交给调用方判断。
 */
export async function csrfRequest<T>(
  path: string,
  init: RequestInit = {},
  options: { silentAuth?: boolean } = {},
): Promise<T> {
  const token = await getCsrfToken()
  try {
    return await request<T>(
      path,
      { ...init, headers: { ...init.headers, [CSRF_HEADER]: token } },
      options,
    )
  } catch (error) {
    if (error instanceof ApiError && error.message.includes("CSRF")) {
      resetCsrfToken()
    }
    throw error
  }
}
