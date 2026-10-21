import { describe, expect, it } from 'vitest'

import type { JobDto, UsageDto } from '../services/types'
import {
  canCancel,
  canRetry,
  formatCost,
  formatTokens,
  isActive,
  progressPercent,
  usageTotals,
} from './generation'

function job(overrides: Partial<JobDto> = {}): JobDto {
  return {
    id: 'j1',
    status: 'generating',
    mode: 'manual',
    provider_id: 'mock',
    model: 'mock-free',
    requested_count: 50,
    produced_count: 10,
    failed_count: 5,
    attempt_count: 1,
    error_code: null,
    error_message: null,
    started_at: null,
    finished_at: null,
    created_at: '2026-10-10T00:00:00Z',
    ...overrides,
  }
}

describe('generation helpers', () => {
  it('computes progress from produced + failed', () => {
    expect(progressPercent(job())).toBe(30)
    expect(progressPercent(job({ status: 'completed', produced_count: 50 }))).toBe(100)
    expect(progressPercent(job({ requested_count: 0 }))).toBe(0)
  })

  it('classifies active / retryable / cancellable states', () => {
    expect(isActive(job({ status: 'queued' }))).toBe(true)
    expect(isActive(job({ status: 'completed' }))).toBe(false)
    expect(canRetry(job({ status: 'failed' }))).toBe(true)
    expect(canRetry(job({ status: 'partial' }))).toBe(true)
    expect(canRetry(job({ status: 'completed' }))).toBe(false)
    expect(canCancel(job({ status: 'generating' }))).toBe(true)
    expect(canCancel(job({ status: 'completed' }))).toBe(false)
  })

  it('never renders unknown cost as zero', () => {
    expect(formatCost(null)).toBe('未知')
    expect(formatCost(undefined)).toBe('未知')
    expect(formatCost(0.0032)).toBe('¥0.0032')
    expect(formatCost(0, true)).toBe('未知')
  })

  it('formats token counts', () => {
    expect(formatTokens(999)).toBe('999')
    expect(formatTokens(1500)).toBe('1.5k')
    expect(formatTokens(2_500_000)).toBe('2.50M')
  })

  it('aggregates usage totals with unknown-price flag', () => {
    const usage: UsageDto = {
      rows: [
        {
          bucket: '2026-10-10',
          jobs: 1,
          calls: 3,
          input_tokens: 1000,
          output_tokens: 500,
          cost_estimate: null,
          has_unknown_price: true,
          missing_usage: 1,
        },
      ],
      total_input_tokens: 1000,
      total_output_tokens: 500,
      total_cost_estimate: null,
      has_unknown_price: true,
      total_missing_usage: 1,
      group_by: 'day',
      date_from: null,
      date_to: null,
    }
    const totals = usageTotals(usage)
    expect(totals.tokens).toBe(1500)
    expect(totals.calls).toBe(3)
    expect(totals.costLabel).toBe('未知')
  })
})
