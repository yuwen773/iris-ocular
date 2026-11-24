import { parseServerTime, type StreamMessage } from "@/lib/agentStream"

/**
 * 会话历史的合并与送达核对：AgentPage 里最容易写错、也最难在浏览器里复查的两段规则。
 *
 * <p>三件事都只做一次、只写一处：
 * <ul>
 *   <li>「历史尾条是不是本轮之后才落库」——合并与送达核对共用同一条时钟门槛；</li>
 *   <li>「请求历史期间用户又发了消息」时怎么把本地这一轮接上去；</li>
 *   <li>「发送结果未知」时怎么用历史接口升级成 accepted，且永不证伪。</li>
 * </ul>
 */

export const HISTORY_PAGE_SIZE = 100

/**
 * 历史核对时允许的时钟偏差。服务端时间不带时区、按本地解析，客户端与服务端
 * 也可能有偏差；10 分钟足够宽，又能把「用户半小时前问过一模一样的话」排除掉。
 */
export const RECONCILE_CLOCK_SKEW_MS = 10 * 60 * 1000

/** 送达核对只需要首屏的消息内容与时间。 */
export type HistoryPageLike = {
  messages?: { role?: string; content?: string; createdTime?: string }[]
}

/**
 * 历史尾条是否「本轮之后才落库」。
 *
 * <p>只比内容会把「用户十分钟前问过一模一样的一句话」当成副本删掉，而删的是
 * 带 delivery / images 的本地气泡；时间戳缺失时按「新鲜」处理（宁留不删）。
 */
export function historyTailIsFresh(
  tail: { createdTime?: string } | undefined,
  requestedAt: number,
): boolean {
  const at = parseServerTime(tail?.createdTime)
  return at === null || at >= requestedAt - RECONCILE_CLOCK_SKEW_MS
}

/**
 * 请求历史期间用户又发了消息：历史里没有这一轮，整表替换会把它抹掉，
 * 丢弃这段历史又会让它**永远**加载不上。所以把本地这一轮接在历史后面。
 *
 * <p>本地回合从**第一条带 delivery 的用户气泡**开始，之后才是流里长出来的
 * 助手内容、推理与工具卡——只挑用户气泡会把回答一起扔掉。
 *
 * <p>这一页偶尔已经包含刚发的那条（新会话的第一页就是整段会话）：此时本地第一条
 * 用户气泡是它的副本，只丢这一条，后面的照接。
 */
export function mergeHistoryWithLocalTurn(
  prev: StreamMessage[],
  history: StreamMessage[],
  freshTail: boolean,
): StreamMessage[] {
  const localStart = prev.findIndex((message) => message.type === "user" && message.delivery)
  if (localStart < 0) return history
  const local = prev.slice(localStart)
  const tail = history[history.length - 1]
  const first = local[0]
  const duplicated =
    freshTail &&
    first?.type === "user" &&
    tail?.type === "user" &&
    tail.content === first.content
  return [...history, ...(duplicated ? local.slice(1) : local)]
}

/** 只改这一条气泡的送达状态，不动其它消息；没有变化时返回原引用。 */
export function applyDelivery(
  messages: StreamMessage[],
  id: string,
  delivery: NonNullable<StreamMessage["delivery"]>,
): StreamMessage[] {
  const index = messages.findIndex((message) => message.id === id)
  if (index < 0) return messages
  const current = messages[index]
  if (current.delivery === delivery) return messages
  const next = [...messages]
  next[index] = { ...current, delivery }
  return next
}

/**
 * 用历史接口核对这条提问到底有没有落库。
 *
 * <p>不带游标时返回最新一页。只认「最后一条 USER 且内容一致、时间不早于
 * 发起时刻减去时钟偏差」。
 *
 * <p>只能「升级」不能「证伪」：查不到 ≠ 没送到（落库还在队列里），
 * 所以这里只回答 true / false，绝不产生「未送达」。
 */
export function reconcileAcceptedUser(
  page: HistoryPageLike,
  draft: { text: string; sentAt: number },
): boolean {
  const lastUser = [...(page.messages ?? [])]
    .reverse()
    .find((message) => (message.role ?? "").toUpperCase() === "USER")
  if (!lastUser || lastUser.content !== draft.text) return false
  const at = parseServerTime(lastUser.createdTime)
  if (at !== null && at < draft.sentAt - RECONCILE_CLOCK_SKEW_MS) return false
  return true
}
