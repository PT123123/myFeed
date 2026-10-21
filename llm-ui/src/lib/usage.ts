import type { UsageDto, UsageGroupBy } from '../services/types'

export interface Dimension {
  value: UsageGroupBy
  /** Sidebar/tab label. */
  label: string
  /** What the first table column actually holds for this grouping. */
  column: string
}

export const DIMENSIONS: Dimension[] = [
  { value: 'day', label: '按天', column: '日期' },
  { value: 'model', label: '按模型', column: '模型' },
  { value: 'batch', label: '按批次', column: '批次编号' },
]

export function dimensionOf(value: UsageGroupBy): Dimension {
  return DIMENSIONS.find((d) => d.value === value) ?? DIMENSIONS[0]
}

/**
 * Date inputs give plain calendar days; the API takes UTC timestamps with an
 * exclusive upper bound, so "to" must cover the whole picked day.
 */
export function dateBounds(
  from: string,
  to: string,
): { from?: string; to?: string } {
  const bounds: { from?: string; to?: string } = {}
  if (from) bounds.from = new Date(`${from}T00:00:00`).toISOString()
  if (to) {
    const end = new Date(`${to}T00:00:00`)
    end.setDate(end.getDate() + 1)
    bounds.to = end.toISOString()
  }
  return bounds
}

/** `YYYY-MM-DD` in the reader's own timezone (UTC would flip the day at night). */
export function localDate(backDays = 0): string {
  const d = new Date()
  if (backDays > 0) d.setDate(d.getDate() - backDays)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

/** Inclusive start / exclusive end for the current local day. */
export function todayBounds(): { from?: string; to?: string } {
  return dateBounds(localDate(), localDate())
}

function cell(value: string | number): string {
  const s = String(value)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** Excel on a Chinese locale needs the BOM or the header row is mojibake. */
export function usageCsv(usage: UsageDto): string {
  const column = dimensionOf(usage.group_by).column
  const lines = [
    `${column},任务数,调用次数,输入 Token,输出 Token,估算费用,未回传用量次数`,
    ...usage.rows.map((r) =>
      [
        cell(r.bucket),
        r.jobs,
        r.calls,
        r.input_tokens,
        r.output_tokens,
        // Unknown pricing stays blank rather than pretending to be ¥0.
        r.cost_estimate === null ? '' : r.cost_estimate,
        r.missing_usage,
      ].join(','),
    ),
    `合计,,,${usage.total_input_tokens},${usage.total_output_tokens},${
      usage.total_cost_estimate === null ? '' : usage.total_cost_estimate
    },${usage.total_missing_usage}`,
  ]
  return `${lines.join('\r\n')}\r\n`
}

export function usageFileName(usage: UsageDto, ext: 'csv' | 'json'): string {
  const stamp = new Date().toISOString().slice(0, 10)
  const range =
    usage.date_from || usage.date_to
      ? `${(usage.date_from ?? '').slice(0, 10) || '开始'}-${(usage.date_to ?? '').slice(0, 10) || '今天'}`
      : '全部'
  return `paf-usage-${usage.group_by}-${range}-${stamp}.${ext}`
}

export function usageJson(usage: UsageDto): string {
  return JSON.stringify(usage, null, 2)
}
