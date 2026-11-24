import { describe, expect, it } from "vitest"

import type { StreamMessage } from "@/lib/agentStream"
import {
  RECONCILE_CLOCK_SKEW_MS,
  applyDelivery,
  historyTailIsFresh,
  mergeHistoryWithLocalTurn,
  reconcileAcceptedUser,
} from "@/lib/conversationHistory"

function user(id: string, content: string, delivery?: StreamMessage["delivery"]): StreamMessage {
  return { id, type: "user", content, delivery }
}

function assistant(id: string, content: string): StreamMessage {
  return { id, type: "assistant", content }
}

/** 新 API 的带偏移 ISO 时间；无偏移旧格式会被当成 UTC 墙钟，这里避开那个歧义。 */
function serverTime(at: number): string {
  return new Date(at).toISOString()
}

describe("historyTailIsFresh", () => {
  it("尾条时间缺失时按新鲜处理——宁留不删", () => {
    expect(historyTailIsFresh(undefined, Date.now())).toBe(true)
    expect(historyTailIsFresh({}, Date.now())).toBe(true)
  })

  it("本轮之后（含时钟偏差窗口内）落库的尾条才算新鲜", () => {
    const now = Date.now()
    expect(historyTailIsFresh({ createdTime: serverTime(now) }, now)).toBe(true)
    expect(
      historyTailIsFresh({ createdTime: serverTime(now - RECONCILE_CLOCK_SKEW_MS + 1_000) }, now),
    ).toBe(true)
  })

  it("明显更早的旧消息不算新鲜——用户以前问过一模一样的话也不能删本地气泡", () => {
    const now = Date.now()
    expect(
      historyTailIsFresh({ createdTime: serverTime(now - RECONCILE_CLOCK_SKEW_MS - 60_000) }, now),
    ).toBe(false)
  })
})

describe("mergeHistoryWithLocalTurn", () => {
  it("本地没有带 delivery 的用户气泡时直接用历史", () => {
    const history = [user("h1", "旧问题")]
    expect(mergeHistoryWithLocalTurn([assistant("a1", "流里的回答")], history, true)).toEqual(history)
  })

  it("把本地这一轮（用户气泡之后的回答与工具卡）整段接在历史后面", () => {
    const prev = [user("l1", "新问题", "sending"), assistant("l2", "流式回答")]
    const history = [user("h1", "旧问题"), assistant("h2", "旧回答")]

    expect(mergeHistoryWithLocalTurn(prev, history, true)).toEqual([...history, user("l1", "新问题", "sending"), assistant("l2", "流式回答")])
  })

  it("这一页已经包含刚发的那条时，只丢掉重复的那一条", () => {
    const prev = [user("l1", "新问题", "sending"), assistant("l2", "流式回答")]
    const history = [user("h1", "旧问题"), assistant("h2", "旧回答"), user("h3", "新问题")]

    expect(mergeHistoryWithLocalTurn(prev, history, true)).toEqual([...history, assistant("l2", "流式回答")])
  })

  it("尾条不新鲜时不做副本判定——内容相同也不丢本地气泡", () => {
    const prev = [user("l1", "同一个问题", "sending")]
    const history = [user("h1", "同一个问题")]

    expect(mergeHistoryWithLocalTurn(prev, history, false)).toEqual([...history, user("l1", "同一个问题", "sending")])
  })
})

describe("applyDelivery", () => {
  it("只改命中的那一条", () => {
    const messages = [user("l1", "问", "sending"), user("l2", "再问", "sending")]
    const next = applyDelivery(messages, "l1", "accepted")

    expect(next[0].delivery).toBe("accepted")
    expect(next[1].delivery).toBe("sending")
    expect(messages[0].delivery).toBe("sending")
  })

  it("没有变化时返回原引用，避免无谓重渲染", () => {
    const messages = [user("l1", "问", "accepted")]
    expect(applyDelivery(messages, "l1", "accepted")).toBe(messages)
  })

  it("找不到这条消息时也返回原引用", () => {
    const messages = [user("l1", "问", "sending")]
    expect(applyDelivery(messages, "missing", "accepted")).toBe(messages)
  })
})

describe("reconcileAcceptedUser", () => {
  const sentAt = Date.now()

  it("最新一页有吻合的提问时可以核对", () => {
    expect(
      reconcileAcceptedUser(
        { messages: [{ role: "USER", content: "问", createdTime: serverTime(sentAt) }] },
        { text: "问", sentAt },
      ),
    ).toBe(true)
  })

  it("最后一条 USER 内容一致且在时钟窗口内 → 已送达", () => {
    expect(
      reconcileAcceptedUser(
        {
          messages: [
            { role: "USER", content: "问", createdTime: serverTime(sentAt - 60_000) },
            { role: "ASSISTANT", content: "答", createdTime: serverTime(sentAt) },
          ],
        },
        { text: "问", sentAt },
      ),
    ).toBe(true)
  })

  it("角色大小写不敏感", () => {
    expect(
      reconcileAcceptedUser(
        { messages: [{ role: "user", content: "问", createdTime: serverTime(sentAt) }] },
        { text: "问", sentAt },
      ),
    ).toBe(true)
  })

  it("最后一条 USER 内容不同 → 不升级（不猜）", () => {
    expect(
      reconcileAcceptedUser(
        { messages: [{ role: "USER", content: "另一句", createdTime: serverTime(sentAt) }] },
        { text: "问", sentAt },
      ),
    ).toBe(false)
  })

  it("同样的内容但明显更早（用户以前问过）→ 不升级", () => {
    expect(
      reconcileAcceptedUser(
        {
          messages: [
            { role: "USER", content: "问", createdTime: serverTime(sentAt - RECONCILE_CLOCK_SKEW_MS - 60_000) },
          ],
        },
        { text: "问", sentAt },
      ),
    ).toBe(false)
  })

  it("没有 USER 消息 → 不升级", () => {
    expect(
      reconcileAcceptedUser(
        { messages: [{ role: "ASSISTANT", content: "答", createdTime: serverTime(sentAt) }] },
        { text: "问", sentAt },
      ),
    ).toBe(false)
  })
})
