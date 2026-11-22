/** 回合切分的输入：只需要 id 与消息类型（历史回放的 type 是宽松字符串）。 */
export type RoundSource = { id: string; type: string }

export type RoundIndex = {
  /** 消息 id → 它所属的回合号（第一条用户提问之前为 0）。 */
  roundById: Map<string, number>
  /**
   * 与输入等长的逐条回合号（用户提问本身已计入新回合）。
   *
   * `buildThread` 需要按位置取回合号，`deriveResources` 与
   * `messageRoundById` 需要按 id 取；两者现在出自同一次遍历。
   */
  roundBefore: number[]
}

/**
 * 对话回合的唯一计数处：每条用户提问切一个回合。
 *
 * <p>在此之前 `buildThread`、`deriveResources`、`messageRoundById` 各数了一遍，
 * 三份实现靠注释约定「口径一致」。回合号决定引用核验、版本条与过程块归属，
 * 口径分叉会直接让角标落到错误的回合上。
 */
export function indexRounds(messages: readonly RoundSource[]): RoundIndex {
  const roundById = new Map<string, number>()
  const roundBefore: number[] = new Array(messages.length)
  let round = 0
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]
    if (message.type === "user") round += 1
    roundBefore[index] = round
    roundById.set(message.id, round)
  }
  return { roundById, roundBefore }
}
