/**
 * 记录列表的时间切分。
 *
 * <p>后端给的时间有两种写法（`2026-11-24 09:41:52` 与 ISO 的 `2026-11-24T09:41:52`，
 * 会话时间还可能是 epoch 毫秒）。这里统一切出「月份 / 月日 / 时分」三段，
 * 切不出来就返回空——版式上写「日期未知」，不拿今天的时间顶替。
 *
 * <p>纯函数，改动必须补 `tests/recordFormat.test.ts`。
 */

export interface RecordTime {
  /** '2026-11' */
  ym: string
  /** '11.24' */
  md: string
  /** '09:41' */
  hm: string
}

function normalize(value?: string | number | null): string {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return ''
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ''
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  if (typeof value !== 'string') return ''
  return value.trim().replace('T', ' ')
}

/** 后端时间 → 三段；认不出来返回 null。 */
export function splitCreateTime(value?: string | number | null): RecordTime | null {
  const text = normalize(value)
  const matched = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/.exec(text)
  if (!matched) return null
  const [, year, month, day, hour, minute] = matched
  return {
    ym: `${year}-${month}`,
    md: `${month}.${day}`,
    hm: hour && minute ? `${hour}:${minute}` : '',
  }
}

/** '2026-11' → '2026 年 11 月'。 */
export function monthLabel(ym: string): string {
  const matched = /^(\d{4})-(\d{2})$/.exec(ym)
  if (!matched) return ym
  return `${matched[1]} 年 ${Number(matched[2])} 月`
}

/** 会话时间 → '11.24'；没有就留空。 */
export function dayLabel(value?: string | number | null): string {
  const time = splitCreateTime(value)
  return time ? time.md : ''
}
