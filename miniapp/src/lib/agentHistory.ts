import type { ChatHistory, AgentMessage } from './api'

/** 页面上的一条消息（历史恢复与实时流共用同一种形状）。 */
export interface ChatItem {
  id: string
  role: 'user' | 'assistant'
  content: string
}

/**
 * 落库词汇表（后端 `MessagePart`）：只有 `text` 是最终回答正文。
 *
 * <p>历史接口原样返回 `reasoning` / `tool-call` / `tool-result` / `mcp-confirm` /
 * `turn-end` 等行。把它们当回答渲染，患者会看到模型的思考过程和工具 JSON。
 */
const ANSWER_SUBTYPE = 'text'
const USER_ATTACHMENTS_SUBTYPE = 'user-attachments-v1'

/** 后端 role 落库为大写；历史恢复必须按大写比对，否则患者自己的提问会被画成 AI 回答。 */
function toRole(message: AgentMessage): 'user' | 'assistant' | null {
  if (message.role === 'USER') return 'user'
  if (message.role === 'ASSISTANT') return 'assistant'
  return null
}

function isPatientVisible(message: AgentMessage, role: 'user' | 'assistant'): boolean {
  // 压缩摘要行是给模型看的，不是对话内容。
  if (message.isSummary) return false
  if (typeof message.content !== 'string' || message.content.length === 0) return false
  const subType = message.subType
  if (role === 'user') return subType == null || subType === USER_ATTACHMENTS_SUBTYPE
  return subType === ANSWER_SUBTYPE
}

/**
 * 服务端历史 → 页面消息：只保留患者能读懂的问答正文，按原顺序。
 *
 * <p>纯函数，改动必须补 `tests/agentHistory.test.ts`。
 */
export function toChatItems(history: ChatHistory): ChatItem[] {
  const items: ChatItem[] = []
  for (const message of history.messages ?? []) {
    const role = toRole(message)
    if (!role || !isPatientVisible(message, role)) continue
    items.push({
      id: `h-${message.id ?? Math.random().toString(36).slice(2)}`,
      role,
      content: message.content ?? '',
    })
  }
  return items
}
