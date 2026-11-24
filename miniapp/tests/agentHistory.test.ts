import { describe, expect, it } from 'vitest'
import { collectHistoryCitations, toChatItems } from '@/lib/agentHistory'
import type { ChatHistory } from '@/lib/api'

function history(messages: ChatHistory['messages']): ChatHistory {
  return { messages, hasMore: false }
}

describe('历史会话 → 页面消息', () => {
  it('只保留问答正文：思考过程、工具调用与回合终止都不展示', () => {
    const items = toChatItems(
      history([
        { id: 1, role: 'USER', subType: null, content: '我最近看东西有黑影' },
        { id: 2, role: 'ASSISTANT', subType: 'reasoning', content: '模型在想…' },
        { id: 3, role: 'ASSISTANT', subType: 'tool-call', content: '{"name":"doc_search"}' },
        { id: 4, role: 'ASSISTANT', subType: 'tool-result', content: '{"status":"SUCCEEDED"}' },
        { id: 5, role: 'ASSISTANT', subType: 'text', content: '这需要当面检查眼底才能判断。' },
        { id: 6, role: 'ASSISTANT', subType: 'turn-end', content: '{"finishReason":"stop"}' },
      ]),
    )

    expect(items.map((item) => item.content)).toEqual(['我最近看东西有黑影', '这需要当面检查眼底才能判断。'])
    expect(items.map((item) => item.role)).toEqual(['user', 'assistant'])
  })

  // 后端落库是大写的 USER / ASSISTANT；按小写比对会让患者的提问被画成 AI 回答。
  it('按后端的落库大小写识别角色', () => {
    const items = toChatItems(
      history([
        { id: 1, role: 'USER', content: '问题' },
        { id: 2, role: 'ASSISTANT', subType: 'text', content: '回答' },
      ]),
    )
    expect(items[0].role).toBe('user')
    expect(items[1].role).toBe('assistant')
  })

  it('带附件的用户消息仍算用户发言', () => {
    const items = toChatItems(
      history([{ id: 1, role: 'USER', subType: 'user-attachments-v1', content: '看看这张片子' }]),
    )
    expect(items).toHaveLength(1)
    expect(items[0].role).toBe('user')
  })

  it('压缩摘要、空内容与未知角色一律丢弃', () => {
    const items = toChatItems(
      history([
        { id: 1, role: 'ASSISTANT', subType: 'text', content: '早前对话的摘要', isSummary: true },
        { id: 2, role: 'ASSISTANT', subType: 'text', content: '' },
        { id: 3, role: 'SYSTEM', content: 'system prompt' },
        { id: 4, role: 'ASSISTANT', subType: 'text', content: '真正的回答' },
      ]),
    )
    expect(items.map((item) => item.content)).toEqual(['真正的回答'])
  })

  it('缺 messages 字段时不抛错', () => {
    expect(toChatItems({ hasMore: false } as ChatHistory)).toEqual([])
  })
})

describe('历史会话 → 可核验引用', () => {
  const toolResult = JSON.stringify({
    toolCallId: 't1',
    name: 'doc_search',
    status: 'SUCCEEDED',
    output: '命中 1 段',
    resources: [{ kind: 'document', documentId: '42', chunkIndex: 3, title: '干眼症诊疗共识' }],
  })

  it('工具终态里的文档算这一轮回答的依据', () => {
    const citations = collectHistoryCitations(
      history([
        { id: 1, role: 'USER', content: '眼睛干怎么办' },
        { id: 2, role: 'ASSISTANT', subType: 'tool-result', content: toolResult },
        { id: 3, role: 'ASSISTANT', subType: 'text', content: '建议按[[42#3]]处理。' },
      ]),
    )
    expect(citations.get('h-3')?.get('42#3')).toBe('干眼症诊疗共识')
  })

  it('证据不跨轮借：上一轮的检索结果不算到这一句上', () => {
    const citations = collectHistoryCitations(
      history([
        { id: 1, role: 'USER', content: '第一个问题' },
        { id: 2, role: 'ASSISTANT', subType: 'tool-result', content: toolResult },
        { id: 3, role: 'ASSISTANT', subType: 'text', content: '第一个回答' },
        { id: 4, role: 'USER', content: '第二个问题' },
        { id: 5, role: 'ASSISTANT', subType: 'text', content: '第二个回答' },
      ]),
    )
    expect(citations.get('h-3')?.size).toBe(1)
    expect(citations.get('h-5')?.size).toBe(0)
  })

  it('没有检索结果的回合没有证据', () => {
    const citations = collectHistoryCitations(
      history([{ id: 1, role: 'USER', content: '问题' }, { id: 2, role: 'ASSISTANT', subType: 'text', content: '回答' }]),
    )
    expect(citations.get('h-2')?.size).toBe(0)
  })
})
