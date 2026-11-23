import Taro from '@tarojs/taro'
import { ApiError, ERROR_CODE, rawRequest, unpack } from './raw'
import { clearToken, getToken, setToken } from './token'

export interface LoginUser {
  id: number
  userAccount: string
  userName?: string
  userAvatar?: string
  userRole: string
  createTime?: string
}

export interface LoginPayload {
  token: string
  expiresIn: number
  user: LoginUser
}

/** 同一时刻只允许一次静默登录在飞：多个页面同时启动时不会各换一次 code。 */
let inflight: Promise<void> | null = null

/**
 * 用户主动退出后，本次小程序运行内不再自动登录。
 *
 * <p>微信身份就是账号：不做这层抑制的话，「退出登录」下一秒就会被下一次请求
 * 静默登录回同一个账号，按钮等于没有。抑制只维持到小程序被重新打开。
 */
let suspended = false

export function isAutoLoginSuspended(): boolean {
  return suspended
}

export function resumeLogin(): Promise<void> {
  suspended = false
  return ensureLogin()
}

export function ensureLogin(): Promise<void> {
  if (suspended) {
    return Promise.reject(new ApiError(ERROR_CODE.NOT_LOGIN, '已退出登录'))
  }
  if (getToken()) {
    return Promise.resolve()
  }
  if (!inflight) {
    inflight = doLogin().finally(() => {
      inflight = null
    })
  }
  return inflight
}

export function relogin(): Promise<void> {
  clearToken()
  return ensureLogin()
}

export async function logout(): Promise<void> {
  const token = getToken()
  suspended = true
  try {
    await rawRequest({ path: '/mp/auth/logout', method: 'POST', token })
  } catch {
    // 服务端吊销失败也要清本地：令牌 30 天后自然过期，留着更容易被误用。
  }
  clearToken()
}

export async function fetchCurrentUser(): Promise<LoginUser> {
  await ensureLogin()
  return unpack<LoginUser>(
    await rawRequest<LoginUser>({
      path: '/user/get/login',
      method: 'GET',
      token: getToken(),
    }),
  )
}

async function doLogin(): Promise<void> {
  const login = await Taro.login()
  if (!login.code) {
    throw new ApiError(ERROR_CODE.MALFORMED, '微信登录失败，请重新进入小程序')
  }
  const payload = unpack<LoginPayload>(
    await rawRequest<LoginPayload>({
      path: '/mp/auth/login',
      method: 'POST',
      data: { code: login.code },
    }),
  )
  setToken(payload.token)
}
