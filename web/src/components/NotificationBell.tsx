import "@/styles/notification.css"

import { Bell } from "lucide-react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"

import { getUnreadNotificationCount } from "@/lib/api"
import { queryKeys } from "@/lib/queryKeys"
import { useSession } from "@/lib/session"

/** 未读数的轮询间隔：一分钟一次，切回窗口时再刷一次（refetchOnWindowFocus）。 */
const UNREAD_POLL_MS = 60_000

/**
 * 顶栏铃铛：站内通知的唯一入口。
 *
 * <p>未登录不渲染，**也不挂轮询**：公开页（首页、资讯）对访客没有「你的通知」这回事，
 * 更不该替未登录的访客每分钟打一次个人接口。所以拆成两层——外层只读会话，
 * 内层才持有查询，登录后才挂载。
 *
 * <p>未读数为 0 时只显示铃铛本体，不显示「0」：没有数字才表示没有事。
 */
export function NotificationBell({ variant = "product" }: { variant?: "product" | "admin" }) {
  const { status } = useSession()
  if (status !== "authed") return null
  return <AuthedNotificationBell variant={variant} />
}

function AuthedNotificationBell({ variant }: { variant: "product" | "admin" }) {
  const { scope } = useSession()

  const unreadQuery = useQuery({
    queryKey: queryKeys.notifications.unread(scope),
    queryFn: () => getUnreadNotificationCount(),
    refetchInterval: UNREAD_POLL_MS,
    refetchOnWindowFocus: true,
  })

  const unread = unreadQuery.data ?? 0
  const label = unread > 0 ? `通知，${unread} 条未读` : "通知"

  return (
    <Link
      to="/notifications"
      className={`notif-bell notif-bell--${variant}`}
      aria-label={label}
      title={label}
      data-cursor
    >
      <Bell size={16} strokeWidth={1.75} aria-hidden="true" />
      {unread > 0 ? (
        <span className="notif-bell__badge num" aria-hidden="true">
          {unread > 99 ? "99+" : unread}
        </span>
      ) : null}
    </Link>
  )
}
