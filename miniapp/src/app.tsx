import type { PropsWithChildren } from 'react'
import { useEffect } from 'react'
import { ensureLogin } from '@/lib/auth'
import './app.scss'

export default function App({ children }: PropsWithChildren) {
  // 启动即静默登录一次。失败不在这里提示：页面在真正需要身份时会拿到
  // 明确错误（含「小程序登录未配置」这类后端原因），比启动时一句笼统的失败更有用。
  useEffect(() => {
    void ensureLogin().catch(() => undefined)
  }, [])

  return children
}
