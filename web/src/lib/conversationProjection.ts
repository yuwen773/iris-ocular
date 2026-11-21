import type { StreamMessage } from "./agentStream"
import { buildThread, type ThreadItem } from "./agentSteps"
import { indexRounds } from "./conversationRounds"

/**
 * 一次对话在界面上的投影：线程结构 + 回合索引。
 *
 * <p>消息数组是唯一输入。`thread` 负责「消息 / 过程块」交替，其余字段都是按回合
 * 索引的查表结果——版本条挂在哪个回合、哪一项、「重新生成」只给最后一轮，全部
 * 由这里一次算完，渲染层不再各自数回合。
 *
 * <p>**引用表不在投影里**：`citationsByRound` 的数组身份必须随证据内容稳定，
 * 否则流式每片 token 都会让所有历史行的 `memo` 失效；那份按内容签名缓存属于
 * 渲染层的身份优化，不属于纯投影。
 */
export type ConversationProjection = {
  thread: ThreadItem[]
  /** 消息 id → 回合号；与 `buildThread`、资源派生共用同一次遍历。 */
  roundById: Map<string, number>
  /** 每个回合最后一条用户提问（版本条挂在它后面）。 */
  turnUserByRound: Map<number, StreamMessage>
  /** 最后一个回合号；空会话为 0。 */
  lastRound: number
  /** 每个回合最后一条线程项的下标；版本条只挂在那里。 */
  lastIndexByRound: Map<number, number>
}

/** 纯函数：同一份消息在任意时刻只投影一次，回合号只有一个来源。 */
export function projectConversation(
  messages: readonly StreamMessage[],
  streaming: boolean,
): ConversationProjection {
  const rounds = indexRounds(messages)
  const thread = buildThread(messages as StreamMessage[], streaming, rounds)

  const turnUserByRound = new Map<number, StreamMessage>()
  for (const message of messages) {
    if (message.type !== "user") continue
    const round = rounds.roundById.get(message.id)
    if (round !== undefined) turnUserByRound.set(round, message)
  }

  const lastRound = Math.max(0, ...turnUserByRound.keys())
  const lastIndexByRound = new Map<number, number>()
  thread.forEach((item, index) => {
    const round =
      item.type === "process"
        ? (item.round ?? 0)
        : (rounds.roundById.get(item.message.id) ?? 0)
    lastIndexByRound.set(round, index)
  })

  return {
    thread,
    roundById: rounds.roundById,
    turnUserByRound,
    lastRound,
    lastIndexByRound,
  }
}
