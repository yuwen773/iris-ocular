import { describe, expect, it } from 'vitest'
import { readStreamError, STREAM_ERROR_FALLBACK, STREAM_ERROR_NOT_LOGIN } from '@/lib/streamError'

describe('readStreamError', () => {
  it('读后端的 {code, msg}', () => {
    expect(readStreamError('{"code":50301,"msg":"模型服务鉴权失败：请联系管理员"}')).toEqual({
      code: 50301,
      message: '模型服务鉴权失败：请联系管理员',
    })
  })

  it('旧服务的纯文本原样展示', () => {
    expect(readStreamError('系统繁忙，请稍后重试')).toEqual({
      code: 50000,
      message: '系统繁忙，请稍后重试',
    })
  })

  it('登录失效的码能被调用方认出来', () => {
    expect(readStreamError('{"code":40101,"msg":"登录状态已失效，请重新登录"}').code).toBe(
      STREAM_ERROR_NOT_LOGIN,
    )
  })

  it('缺 msg 时用兜底文案，不把 JSON 原文给患者看', () => {
    expect(readStreamError('{"code":50303}').message).toBe(STREAM_ERROR_FALLBACK)
  })

  it('空帧与坏 JSON 都落到兜底，不抛异常', () => {
    expect(readStreamError('').message).toBe(STREAM_ERROR_FALLBACK)
    expect(readStreamError('{"code":').message).toBe('{"code":')
  })
})
