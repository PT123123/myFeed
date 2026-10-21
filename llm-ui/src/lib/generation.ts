import type { JobDto, UsageDto } from '../services/types'

export const ACTIVE_STATUSES = new Set([
  'queued',
  'planning',
  'generating',
  'validating',
  'publishing',
])

export const STATUS_LABELS: Record<JobDto['status'], string> = {
  queued: '排队中',
  planning: '规划中',
  generating: '生成中',
  validating: '校验中',
  publishing: '发布中',
  completed: '已完成',
  partial: '部分成功',
  failed: '失败',
  cancelled: '已取消',
}

export function isActive(job: JobDto): boolean {
  return ACTIVE_STATUSES.has(job.status)
}

export function progressPercent(job: JobDto): number {
  if (job.requested_count <= 0) return 0
  const done = job.produced_count + job.failed_count
  if (job.status === 'completed') return 100
  return Math.max(0, Math.min(100, Math.round((done / job.requested_count) * 100)))
}

export function canRetry(job: JobDto): boolean {
  return job.status === 'failed' || job.status === 'partial' || job.status === 'cancelled'
}

export function canCancel(job: JobDto): boolean {
  return isActive(job)
}

/** Cost display: never render unknown price as ¥0. */
export function formatCost(cost: number | null | undefined, unknownFlag?: boolean): string {
  const unknown = unknownFlag || cost === null || cost === undefined
  if (unknown) return '未知'
  return `¥${cost.toFixed(4)}`
}

export function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

export function usageTotals(usage: UsageDto): {
  tokens: number
  costLabel: string
  calls: number
} {
  return {
    tokens: usage.total_input_tokens + usage.total_output_tokens,
    costLabel: formatCost(usage.total_cost_estimate, usage.has_unknown_price),
    calls: usage.rows.reduce((sum, r) => sum + r.calls, 0),
  }
}
