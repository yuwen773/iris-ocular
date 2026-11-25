import Taro from '@tarojs/taro'

const TOKEN_KEY = 'iris.miniapp.token'

/**
 * 令牌只存本机 storage。
 *
 * <p>小程序的 storage 按小程序维度隔离，但仍然是明文——所以这里只存令牌本身，
 * 不存 openid、不存影像地址，泄了也只是一段可被服务端一键吊销的凭据。
 */
export function getToken(): string | null {
  try {
    const value = Taro.getStorageSync<string>(TOKEN_KEY)
    return typeof value === 'string' && value ? value : null
  } catch {
    return null
  }
}

export function setToken(token: string): void {
  Taro.setStorageSync(TOKEN_KEY, token)
}

export function clearToken(): void {
  try {
    Taro.removeStorageSync(TOKEN_KEY)
  } catch {
    // storage 不可用时没有可清的东西
  }
}
