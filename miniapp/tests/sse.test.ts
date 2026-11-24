import { describe, expect, it } from 'vitest'
import { SseParser } from '@/lib/sse'

describe('SseParser', () => {
  it('把无名事件读成 message', () => {
    const parser = new SseParser()
    const events = parser.push('data: {"content":"你好"}\n\n')
    expect(events).toEqual([{ event: 'message', data: '{"content":"你好"}' }])
  })

  it('一次分片里的多帧按顺序返回', () => {
    const parser = new SseParser()
    const events = parser.push('data: a\n\ndata: b\n\n')
    expect(events.map((event) => event.data)).toEqual(['a', 'b'])
  })

  it('一帧被切成两个分片也能拼回来', () => {
    const parser = new SseParser()
    expect(parser.push('data: {"content":"判')).toEqual([])
    expect(parser.push('读"}\n\n')).toEqual([{ event: 'message', data: '{"content":"判读"}' }])
  })

  it('回车换行与单个换行等价', () => {
    const parser = new SseParser()
    const events = parser.push('event: done\r\ndata: \r\n\r\n')
    expect(events).toEqual([{ event: 'done', data: '' }])
  })

  it('终止帧只有 event 行时也不会被丢掉', () => {
    const parser = new SseParser()
    const events = parser.push('event: done\n\n')
    expect(events).toEqual([{ event: 'done', data: '' }])
  })

  it('错误帧保留纯文本内容', () => {
    const parser = new SseParser()
    const events = parser.push('event: error\ndata: 系统繁忙，请稍后重试\n\n')
    expect(events).toEqual([{ event: 'error', data: '系统繁忙，请稍后重试' }])
  })

  it('忽略注释行且报文里的冒号不被误当成字段', () => {
    const parser = new SseParser()
    const events = parser.push(': keepalive\ndata: {"content":"a:b"}\n\n')
    expect(events).toEqual([{ event: 'message', data: '{"content":"a:b"}' }])
  })

  it('多行 data 按规范用换行拼接', () => {
    const parser = new SseParser()
    const events = parser.push('data: line1\ndata: line2\n\n')
    expect(events[0].data).toBe('line1\nline2')
  })

  it('流结束时的残留内容由 flush 兜底', () => {
    const parser = new SseParser()
    expect(parser.push('data: tail')).toEqual([])
    expect(parser.flush()).toEqual([{ event: 'message', data: 'tail' }])
  })
})
