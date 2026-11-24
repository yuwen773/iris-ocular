import "@/styles/notification.css"

import { useState } from "react"
import { ArrowUpRight, MailOpen } from "lucide-react"
import { Link, useNavigate } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"

import {
  describeError,
  getUnreadNotificationCount,
  listMyNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type UserNotification,
} from "@/lib/api"
import { formatTimestamp } from "@/lib/domain"
import { faultFromError, infoFault, type Fault } from "@/lib/faults"
import {
  NOTIFICATION_TYPES,
  notificationIsUnread,
  notificationTargetPath,
  notificationTypeLabel,
} from "@/lib/notificationDomain"
import { queryKeys } from "@/lib/queryKeys"
import { useSession } from "@/lib/session"
import { useFault } from "@/components/fault"
import { EmptyFrame } from "@/components/EmptyFrame"
import { FaultPanel } from "@/components/FaultPanel"
import { SectionHead } from "@/components/SectionHead"
import { DisclaimerBar } from "@/components/DisclaimerBar"
import { ProductHeader } from "@/components/shell/ProductHeader"
import { IrisFormAlert } from "@/components/system/IrisFormAlert"

const PAGE_SIZE = 20

type Tab = "unread" | "all"

/**
 * 站内通知（Clinical）。
 *
 * <p>一条消息只回答「发生了什么、去哪里看」：正文由服务端在发送时定稿，
 * 结论与免责声明留在记录页 / 反馈页，不在通知里复述。
 *
 * <p>点击即已读：标记失败也照常跳转——用户想看的是那条记录，不是「标记成功」。
 */
export function NotificationsPage() {
  const { status, scope } = useSession()
  const { push } = useFault()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const authed = status === "authed"

  const [tab, setTab] = useState<Tab>("unread")
  const [type, setType] = useState("")
  const [page, setPage] = useState(1)
  const [marking, setMarking] = useState(false)
  const [actionFault, setActionFault] = useState<Fault | null>(null)

  const filters = {
    current: page,
    pageSize: PAGE_SIZE,
    ...(type ? { type } : {}),
    ...(tab === "unread" ? { unreadOnly: true } : {}),
  }

  const listQuery = useQuery({
    queryKey: queryKeys.notifications.list(scope, filters),
    queryFn: ({ signal }) => listMyNotifications(filters, signal),
    enabled: authed,
  })

  /** 未读数与本页列表分开取：铃铛与这里共用一份缓存，不用列表推算。 */
  const unreadQuery = useQuery({
    queryKey: queryKeys.notifications.unread(scope),
    queryFn: () => getUnreadNotificationCount(),
    enabled: authed,
  })

  const data = listQuery.data
  const items = data?.records ?? []
  const loading = listQuery.isPending
  // 后台重取失败保留已有列表；只有拿不到数据才整块出故障面板
  const fault =
    !loading && data === undefined && listQuery.error ? describeError(listQuery.error) : null
  const pages = Math.max(1, data?.pages ?? 1)
  const unread = unreadQuery.data ?? 0

  function switchTab(next: Tab) {
    setTab(next)
    setPage(1)
  }

  function switchType(next: string) {
    setType(next)
    setPage(1)
  }

  async function open(item: UserNotification) {
    if (notificationIsUnread(item)) {
      try {
        await markNotificationRead(item.id)
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.notifications.listAll(scope) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.notifications.unread(scope) }),
        ])
      } catch (cause) {
        // 标记失败不挡路：用户想看的是那条记录
        push(faultFromError(cause, { intent: "generic" }))
      }
    }
    const path = notificationTargetPath(item.refType)
    if (path) navigate(path)
  }

  async function markAll() {
    setMarking(true)
    setActionFault(null)
    try {
      const marked = await markAllNotificationsRead(type || undefined)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.notifications.listAll(scope) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.notifications.unread(scope) }),
      ])
      push(
        infoFault(marked > 0 ? `已把 ${marked} 条通知标为已读。` : "当前筛选下没有未读通知。"),
      )
    } catch (cause) {
      setActionFault(faultFromError(cause, { intent: "generic" }))
    } finally {
      setMarking(false)
    }
  }

  if (status === "anon") {
    return (
      <div className="is-field is-brand-page">
        <ProductHeader scene="clinical" />
        <main id="main-content" tabIndex={-1} className="wrap notif">
          <SectionHead code="07" kicker="通知" en="NOTIFICATIONS · WHAT CHANGED" />
          <div className="notif__empty">
            <EmptyFrame label="未登录" />
            <p className="notif__empty-title">登录后才能查看通知</p>
            <p className="notif__empty-body">通知跟着账号走：判读结果、复核结论与反馈回复都在登录后可见。</p>
            <Link className="btn" to="/login?redirect=%2Fnotifications">
              去登录 <ArrowUpRight className="btn__key" size={13} strokeWidth={2} aria-hidden="true" />
            </Link>
          </div>
        </main>
        <DisclaimerBar />
      </div>
    )
  }

  return (
    <div className="is-field is-brand-page">
      <ProductHeader scene="clinical" />

      <main id="main-content" tabIndex={-1} className="wrap notif">
        <SectionHead code="07" kicker="通知" en="NOTIFICATIONS · WHAT CHANGED" />

        <div className="notif__intro">
          <p className="overline">Notifications · 通知</p>
          <h1 className="notif__title">通知</h1>
          <p className="lead">
            判读结果、医生复核与反馈回复都会出现在这里。通知只告诉你「发生了什么」，
            具体内容点进去看——那里的结论带完整的免责声明。
          </p>
        </div>

        <section className="notif__panel" aria-labelledby="notif-list-title">
          <div className="notif__panel-head">
            <h2 className="notif__panel-title" id="notif-list-title">
              收件箱 · INBOX
            </h2>
            <span className="notif__panel-note num">
              {unreadQuery.isPending ? "读取中" : unread > 0 ? `${unread} 条未读` : "全部已读"}
            </span>
          </div>

          <div className="notif__toolbar">
            <div className="notif__tabs" role="tablist" aria-label="通知范围">
              <button
                type="button"
                role="tab"
                aria-selected={tab === "unread"}
                className="notif__tab"
                onClick={() => switchTab("unread")}
              >
                未读
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "all"}
                className="notif__tab"
                onClick={() => switchTab("all")}
              >
                全部
              </button>
            </div>

            <div className="notif__types" role="group" aria-label="按类型筛选">
              <button
                type="button"
                className="notif__chip"
                aria-pressed={type === ""}
                onClick={() => switchType("")}
              >
                全部类型
              </button>
              {NOTIFICATION_TYPES.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className="notif__chip"
                  aria-pressed={type === option.value}
                  title={option.hint}
                  onClick={() => switchType(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>

            <button
              type="button"
              className="btn btn--sm"
              disabled={marking}
              onClick={() => void markAll()}
            >
              <MailOpen size={14} strokeWidth={2} aria-hidden="true" />
              {marking ? "处理中…" : "全部已读"}
            </button>
          </div>

          <p className="notif__hint">
            「全部已读」只清当前筛选（{tab === "unread" ? "未读" : "全部"}
            {type ? ` · ${notificationTypeLabel(type)}` : " · 全部类型"}）下的未读通知。
          </p>

          {actionFault ? (
            <IrisFormAlert
              severity={actionFault.severity}
              title={actionFault.title}
              detail={actionFault.detail}
            />
          ) : null}

          {fault ? (
            <FaultPanel
              code={fault.code}
              title="通知没有读出来"
              body={fault.text}
              endpoint="GET /notifications"
              onRetry={() => void listQuery.refetch()}
            />
          ) : null}

          {!fault && loading ? <p className="notif__state">读取中…</p> : null}

          {!fault && !loading && items.length === 0 ? (
            <div className="notif__empty">
              <EmptyFrame label="没有通知" />
              <p className="notif__empty-title">
                {tab === "unread" ? "没有未读通知" : "还没有通知"}
              </p>
              <p className="notif__empty-body">
                {tab === "unread"
                  ? "切到「全部」可以回看已读的消息。"
                  : "判读结果、复核结论与反馈回复会出现在这里。"}
              </p>
            </div>
          ) : null}

          {!fault && !loading && items.length > 0 ? (
            <>
              <ul className="notif__list" aria-label="通知列表">
                {items.map((item) => {
                  const isUnread = notificationIsUnread(item)
                  const path = notificationTargetPath(item.refType)
                  return (
                    <li className="notif__item" key={item.id} data-unread={isUnread}>
                      <button
                        type="button"
                        className="notif__item-body"
                        onClick={() => void open(item)}
                      >
                        <span className="notif__item-head">
                          <span className="notif__dot" aria-hidden="true" />
                          <span className="notif__item-title">{item.title || "通知"}</span>
                          <span className="notif__item-type">
                            {notificationTypeLabel(item.type)}
                          </span>
                          <span className="notif__item-time num">
                            {formatTimestamp(item.createTime)}
                          </span>
                        </span>
                        <span className="notif__item-text">{item.body}</span>
                        <span className="notif__item-link">
                          {path
                            ? item.refType === "USER_FEEDBACK"
                              ? "去「我的反馈」查看回复"
                              : "去「判读记录」查看"
                            : "已记录"}
                          {path ? (
                            <ArrowUpRight size={12} strokeWidth={2} aria-hidden="true" />
                          ) : null}
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>

              <nav className="notif__pager" aria-label="通知分页">
                <button
                  type="button"
                  className="notif__pager-step"
                  disabled={page <= 1}
                  onClick={() => setPage(page - 1)}
                >
                  ← 上一页
                </button>
                <span className="notif__pager-state num">
                  {page} / {pages}
                </span>
                <button
                  type="button"
                  className="notif__pager-step"
                  disabled={page >= pages}
                  onClick={() => setPage(page + 1)}
                >
                  下一页 →
                </button>
              </nav>
            </>
          ) : null}
        </section>
      </main>

      <DisclaimerBar />
    </div>
  )
}
