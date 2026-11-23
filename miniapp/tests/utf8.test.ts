import { describe, expect, it } from 'vitest'
import { Utf8StreamDecoder } from '@/lib/utf8'

const encoder = new TextEncoder()

describe('Utf8StreamDecoder', () => {
  it('ASCII 原样解码', () => {
    const decoder = new Utf8StreamDecoder()
    expect(decoder.decode(encoder.encode('hello'))).toBe('hello')
  })

  it('一个汉字的三字节被切成两段也能拼回来', () => {
    const bytes = encoder.encode('判读')
    const decoder = new Utf8StreamDecoder()
    const first = decoder.decode(bytes.subarray(0, 2))
    const second = decoder.decode(bytes.subarray(2))
    expect(first + second).toBe('判读')
  })

  it('四字节字符跨分片不丢', () => {
    const bytes = encoder.encode('👁️成像')
    const decoder = new Utf8StreamDecoder()
    let text = ''
    for (let i = 0; i < bytes.length; i += 1) {
      text += decoder.decode(bytes.subarray(i, i + 1))
    }
    expect(text).toBe('👁️成像')
  })

  it('空分片不产生输出也不破坏缓冲', () => {
    const bytes = encoder.encode('眼')
    const decoder = new Utf8StreamDecoder()
    expect(decoder.decode(new Uint8Array(0))).toBe('')
    expect(decoder.decode(bytes)).toBe('眼')
  })

  it('flush 丢弃不完整尾字节而不是输出乱码', () => {
    const bytes = encoder.encode('眼')
    const decoder = new Utf8StreamDecoder()
    expect(decoder.decode(bytes.subarray(0, 2))).toBe('')
    expect(decoder.flush()).toBe('')
  })
})
