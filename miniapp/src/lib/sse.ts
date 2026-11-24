/**
 * SSE 帧解析器（纯函数，可单测）。
 *
 * <p>后端帧格式（AgentController.toSse）：无名事件是 `data: <JSON>`，
 * 终止帧是 `event: done` + 空 data，错误帧是 `event: error` + 纯文本。
 * 这里只负责把字节流转成事件，不理解业务。
 */
export interface SseEvent {
  /** 未写 event: 时按 SSE 规范为 message。 */
  event: string
  data: string
}

export class SseParser {
  private buffer = ''

  push(text: string): SseEvent[] {
    // 分片可能把 \r\n 切开，统一在拼接后归一，避免边界处漏帧。
    this.buffer = (this.buffer + text).replace(/\r\n/g, '\n')
    return this.drain()
  }

  /** 流结束时把残留内容作为最后一帧（服务端正常会以空行收尾，这是兜底）。 */
  flush(): SseEvent[] {
    const remaining = this.buffer
    this.buffer = ''
    const event = parseBlock(remaining)
    return event ? [event] : []
  }

  private drain(): SseEvent[] {
    const events: SseEvent[] = []
    let boundary = this.buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = this.buffer.slice(0, boundary)
      this.buffer = this.buffer.slice(boundary + 2)
      const event = parseBlock(block)
      if (event) events.push(event)
      boundary = this.buffer.indexOf('\n\n')
    }
    return events
  }
}

function parseBlock(block: string): SseEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice('data:'.length).replace(/^ /, ''))
    }
  }
  if (data.length === 0) {
    // 有名字的事件即使没有 data 行也要交给调用方：终止帧 `event: done` 可能
    // 写成空 data 甚至省略 data 行，丢掉它会让流看起来永远没有结束。
    return event === 'message' ? null : { event, data: '' }
  }
  return { event, data: data.join('\n') }
}
