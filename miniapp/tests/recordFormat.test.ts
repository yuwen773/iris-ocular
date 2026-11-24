import { describe, expect, it } from 'vitest'
import { dayLabel, monthLabel, splitCreateTime } from '@/lib/recordFormat'

describe('记录时间切分', () => {
  it('后端两种写法都认', () => {
    expect(splitCreateTime('2026-11-24 09:41:52')).toEqual({ ym: '2026-11', md: '11.24', hm: '09:41' })
    expect(splitCreateTime('2026-11-24T09:41:52')).toEqual({ ym: '2026-11', md: '11.24', hm: '09:41' })
  })

  it('只有日期没有时刻时，时分留空', () => {
    expect(splitCreateTime('2026-11-24')).toEqual({ ym: '2026-11', md: '11.24', hm: '' })
  })

  it('认不出来返回 null，版式上写「日期未知」，不拿今天顶替', () => {
    expect(splitCreateTime(undefined)).toBeNull()
    expect(splitCreateTime('')).toBeNull()
    expect(splitCreateTime('昨天')).toBeNull()
  })

  it('月份标题去掉前导零', () => {
    expect(monthLabel('2026-11')).toBe('2026 年 11 月')
    expect(monthLabel('2026-09')).toBe('2026 年 9 月')
    expect(monthLabel('乱码')).toBe('乱码')
  })

  it('会话时间支持 epoch 毫秒', () => {
    const stamp = new Date(2026, 10, 24, 9, 41).getTime()
    expect(dayLabel(stamp)).toBe('11.24')
    expect(dayLabel(undefined)).toBe('')
  })
})
