import { describe, expect, it } from "vitest"

import type { StreamMessage } from "./agentStream"
import { deriveResources } from "./agentResources"
import { projectConversation } from "./conversationProjection"

function msg(partial: Partial<StreamMessage> & { id: string; type: StreamMessage["type"] }): StreamMessage {
  return { content: "", ...partial }
}

/**
 * 两轮对话：回合 1 有推理与一次工具调用，回合 2 只有一次回答。
 * 第 0 回合是开场白（还没有用户提问）。
 */
function conversation(): StreamMessage[] {
  return [
    msg({ id: "preamble", type: "assistant", content: "你好" }),
    msg({ id: "u1", type: "user", content: "第一问" }),
    msg({ id: "r1", type: "reasoning", content: "thinking" }),
    msg({ id: "t1", type: "tool-request", toolName: "searchDocuments", toolCallId: "c1" }),
    msg({
      id: "tr1",
      type: "tool-response",
      toolName: "searchDocuments",
      toolCallId: "c1",
      toolStatus: "success",
      resources: [
        { id: "doc#0", kind: "document", title: "指南", source: "tool" },
      ] as StreamMessage["resources"],
    }),
    msg({ id: "a1", type: "assistant", content: "第一答" }),
    msg({ id: "u2", type: "user", content: "第二问" }),
    msg({ id: "a2", type: "assistant", content: "第二答" }),
  ]
}

describe("projectConversation", () => {
  it("回合号只数一次，线程与查表共用同一份映射", () => {
    const projection = projectConversation(conversation(), false)

    expect(projection.roundById.get("preamble")).toBe(0)
    expect(projection.roundById.get("u1")).toBe(1)
    expect(projection.roundById.get("tr1")).toBe(1)
    expect(projection.roundById.get("u2")).toBe(2)
    expect(projection.roundById.get("a2")).toBe(2)

    const process = projection.thread.find((item) => item.type === "process")
    expect(process?.type === "process" ? process.round : null).toBe(1)
  })

  it("资源派生使用同一份回合映射：工具返回的证据落在回合 1", () => {
    const projection = projectConversation(conversation(), false)
    const resources = deriveResources(conversation(), projection.roundById)
    expect(resources.documents).toHaveLength(1)
    expect(resources.documents[0].round).toBe(1)
  })

  it("版本条的位置按回合查表：每回合最后一条提问与最后一项", () => {
    const projection = projectConversation(conversation(), false)
    const items = projection.thread

    expect(projection.lastRound).toBe(2)
    expect(projection.turnUserByRound.get(1)?.id).toBe("u1")
    expect(projection.turnUserByRound.get(2)?.id).toBe("u2")

    const round1Last = projection.lastIndexByRound.get(1)
    const round2Last = projection.lastIndexByRound.get(2)
    expect(round1Last).toBeTypeOf("number")
    expect(round2Last).toBe(items.length - 1)

    const itemAtRound1End = items[round1Last as number]
    expect(itemAtRound1End.type === "message" ? itemAtRound1End.message.id : null).toBe("a1")
  })

  it("流式状态只让最后一个过程块保持「执行中」", () => {
    const streaming = projectConversation(conversation(), true)
    const processBlocks = streaming.thread.filter((item) => item.type === "process")
    expect(processBlocks).toHaveLength(1)
    // 工具已经在回合 1 拿到结果，且后面还有消息：不是最后一块，不保留执行中。
    expect(processBlocks[0].type === "process" ? processBlocks[0].active : null).toBe(false)
  })

  it("空会话：没有回合、没有版本条", () => {
    const projection = projectConversation([], false)
    expect(projection.thread).toEqual([])
    expect(projection.roundById.size).toBe(0)
    expect(projection.turnUserByRound.size).toBe(0)
    expect(projection.lastRound).toBe(0)
  })
})
