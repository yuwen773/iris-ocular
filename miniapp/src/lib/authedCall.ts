import { ensureLogin, relogin } from './auth'
import { ApiError, ERROR_CODE } from './raw'

/**
 * 登录态口径：先确保有令牌；服务端说 40100 就重登一次再试。
 *
 * <p>只重试一次——第二次还 40100 说明是身份问题（封禁 / 删号），再试没有意义。
 * 普通请求、文件上传、流式之外的所有带身份调用都走这里，避免每个入口各写一遍。
 */
export async function withAuthRetry<T>(call: () => Promise<T>): Promise<T> {
  await ensureLogin()
  try {
    return await call()
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== ERROR_CODE.NOT_LOGIN) {
      throw error
    }
    await relogin()
    return call()
  }
}
