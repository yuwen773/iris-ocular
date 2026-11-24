import { describe, expect, it, vi } from 'vitest'
import { prependHistoryItems, toChatItems } from '@/lib/agentHistory'
import { getAgentHistory, type ChatHistory } from '@/lib/api'
import { request } from '@/lib/request'

vi.mock('@/lib/request', () => ({ request: vi.fn() }))

function history(messages: ChatHistory['messages']): ChatHistory {
  return { messages, hasMore: false }
}

describe('历史会话 → 页面消息', () => {
  it('恢复不带游标，加载更早消息时原样传回字符串游标', async () => {
    const cursor = '2120565650562129921'
    vi.mocked(request).mockResolvedValue({ messages: [], hasMore: true, nextCursor: cursor })
    const latest = await getAgentHistory('thread-a')
    expect(request).toHaveBeenLastCalledWith('/agent/history/thread-a?limit=40')
    await getAgentHistory('thread-a', latest.nextCursor ?? undefined)
    expect(request).toHaveBeenLastCalledWith(`/agent/history/thread-a?limit=40&beforeId=${cursor}`)
  })

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

  it('更早一页可重复合并，保留顺序与后续对话，隐藏内部消息', () => {
    const newestId = '2120565650562129923'
    const latest = toChatItems(history([{ id: newestId, role: 'USER', content: '较新的问题' }]))
    const older = toChatItems(history([
      { id: '2120565650562129921', role: 'USER', content: '更早的问题' },
      { id: '2120565650562129922', role: 'ASSISTANT', subType: 'reasoning', content: '内部过程' },
    ]))
    const pending = { id: 'local', role: 'assistant' as const, content: '实时回复', pending: true }
    const merged = prependHistoryItems([...latest, pending], older)
    expect(merged.map((item) => item.content)).toEqual(['更早的问题', '较新的问题', '实时回复'])
    expect(merged[2]).toBe(pending)
    expect(prependHistoryItems(merged, older)).toEqual(merged)
  })
})
