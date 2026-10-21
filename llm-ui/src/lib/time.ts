/** SQLite's historical timezone-less API dates are UTC, never device-local. */
export function parseTime(iso: string): number {
  const legacyUtc = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/.test(iso)
  return Date.parse(legacyUtc ? `${iso}Z` : iso)
}

export function relativeTime(iso: string, now = Date.now()): string {
  const t = parseTime(iso)
  if (Number.isNaN(t)) return '未知时间'
  const diff = now - t
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function fullTime(iso: string | null): string {
  if (!iso) return '来源时间未知'
  const t = parseTime(iso)
  if (Number.isNaN(t)) return '来源时间未知'
  return new Date(t).toLocaleString()
}
