import { describe, expect, it } from 'vitest'
import { parseTime, relativeTime } from './time'

describe('server UTC timestamps', () => {
  it('reads legacy SQLite timestamps as UTC instead of the device timezone', () => {
    const expected = Date.UTC(2026, 9, 20, 15, 0, 0)
    expect(parseTime('2026-10-20T15:00:00')).toBe(expected)
    expect(relativeTime('2026-10-20T15:00:00', expected + 120_000)).toBe('2 分钟前')
  })

  it('preserves explicit offsets and fractional seconds', () => {
    expect(parseTime('2026-10-20T23:00:00+08:00')).toBe(parseTime('2026-10-20T15:00:00Z'))
    expect(parseTime('2026-10-20T15:00:00.123456')).toBe(Date.UTC(2026, 9, 20, 15, 0, 0, 123))
  })

  it('keeps malformed dates visibly unknown', () => {
    expect(relativeTime('not-a-date')).toBe('未知时间')
  })
})
