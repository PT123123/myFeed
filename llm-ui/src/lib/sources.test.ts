import { describe, expect, it } from 'vitest'

import type { FetchLogDto, SourceDto } from '../services/types'
import { describeFetchLog, sourceStatusLabel, sourceTimeLabels } from './sources'

const baseLog: FetchLogDto = {
  id: 'l1',
  source_id: 's1',
  started_at: '2026-10-11T00:00:00Z',
  finished_at: '2026-10-11T00:00:01Z',
  status: 'ok',
  http_status: 200,
  items_found: 3,
  items_new: 2,
  error: null,
}

const baseSource = {
  last_status: null,
} as SourceDto

describe('sourceStatusLabel', () => {
  it('maps statuses to readable text', () => {
    expect(sourceStatusLabel('ok').text).toContain('成功')
    expect(sourceStatusLabel('not_modified').text).toContain('304')
    expect(sourceStatusLabel('error').tone).toBe('warn')
    expect(sourceStatusLabel(null).text).toBe('尚未抓取')
    expect(sourceStatusLabel(baseSource.last_status).tone).toBe('muted')
  })
})

describe('sourceTimeLabels', () => {
  it('never fabricates a missing published date', () => {
    const labels = sourceTimeLabels(null, '2026-10-11T08:00:00Z')
    expect(labels.published).toBe('来源时间未知')
    expect(labels.fetched).not.toBe('—')
  })

  it('formats present dates', () => {
    const labels = sourceTimeLabels('2026-10-06T08:00:00Z', '2026-10-11T08:00:00Z')
    expect(labels.published).toContain('2026')
    expect(labels.published).not.toContain('未知')
  })
})

describe('describeFetchLog', () => {
  it('summarizes ok / 304 / error logs', () => {
    expect(describeFetchLog(baseLog)).toBe('成功 · 新增 2 / 共 3 条')
    expect(describeFetchLog({ ...baseLog, status: 'not_modified' })).toContain('304')
    expect(
      describeFetchLog({ ...baseLog, status: 'error', error: 'TIMEOUT: 抓取超时' }),
    ).toContain('TIMEOUT')
  })
})
