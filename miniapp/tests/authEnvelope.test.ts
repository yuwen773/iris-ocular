import { describe, expect, it } from 'vitest'
import { decodeResponseBody, isAuthEnvelope } from '@/lib/authEnvelope'

function toArrayBuffer(text: string): ArrayBuffer {
  const bytes = new TextEncoder().encode(text)
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}

describe('令牌失效业务信封的识别', () => {
  it('认出过滤器层回的 40100 信封', () => {
    expect(isAuthEnvelope('{"code":40100,"data":null,"msg":"登录状态已失效，请重新进入小程序"}')).toBe(true)
    expect(isAuthEnvelope('  {"code" : 40100 }')).toBe(true)
  })

  it('普通业务信封与 SSE 帧都不能被认成令牌失效', () => {
    expect(isAuthEnvelope('{"code":0,"data":{"token":"x"},"msg":"ok"}')).toBe(false)
    expect(isAuthEnvelope('{"code":50000,"data":null,"msg":"系统错误"}')).toBe(false)
    expect(isAuthEnvelope('data: {"content":"你好"}')).toBe(false)
    expect(isAuthEnvelope('event: done')).toBe(false)
    expect(isAuthEnvelope('')).toBe(false)
  })

  it('响应体是 ArrayBuffer 时也能解出文本', () => {
    const body = decodeResponseBody(toArrayBuffer('{"code":40100,"data":null,"msg":"登录状态已失效"}'))
    expect(body).toContain('"code":40100')
    expect(isAuthEnvelope(body)).toBe(true)
  })

  it('字符串响应体原样返回，其它类型当空', () => {
    expect(decodeResponseBody('{"code":40100}')).toBe('{"code":40100}')
    expect(decodeResponseBody(undefined)).toBe('')
    expect(decodeResponseBody(null)).toBe('')
  })
})
