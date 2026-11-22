import { describe, expect, it } from "vitest"

import { indexRounds } from "./conversationRounds"

function msg(id: string, type: "user" | "assistant" | "reasoning") {
  return { id, type }
}

describe("indexRounds", () => {
  it("空会话没有任何回合", () => {
    const rounds = indexRounds([])
    expect(rounds.roundById.size).toBe(0)
    expect(rounds.roundBefore).toEqual([])
  })

  it("用户提问本身计入新回合，之前的消息属于回合 0", () => {
    const rounds = indexRounds([
      msg("preamble", "assistant"),
      msg("u1", "user"),
      msg("a1", "assistant"),
      msg("u2", "user"),
      msg("a2", "assistant"),
    ])
    expect(rounds.roundBefore).toEqual([0, 1, 1, 2, 2])
    expect(rounds.roundById.get("preamble")).toBe(0)
    expect(rounds.roundById.get("u1")).toBe(1)
    expect(rounds.roundById.get("a1")).toBe(1)
    expect(rounds.roundById.get("u2")).toBe(2)
    expect(rounds.roundById.get("a2")).toBe(2)
  })

  it("连续两条用户提问各自切一个回合", () => {
    const rounds = indexRounds([msg("u1", "user"), msg("u2", "user")])
    expect(rounds.roundBefore).toEqual([1, 2])
    expect([...rounds.roundById.values()]).toEqual([1, 2])
  })

  it("roundBefore 与输入逐条对齐（含过程事件）", () => {
    const rounds = indexRounds([
      msg("u1", "user"),
      msg("r1", "reasoning"),
      msg("r2", "reasoning"),
    ])
    expect(rounds.roundBefore).toHaveLength(3)
    expect(rounds.roundBefore).toEqual([1, 1, 1])
  })
})
