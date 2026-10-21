import { fullTime } from './time'
import type { FetchLogDto, SourceDto } from '../services/types'

/** Status line for a source row (docs/08: 状态清楚、失败可读). */
export function sourceStatusLabel(
  status: SourceDto['last_status'],
): { text: string; tone: 'muted' | 'ok' | 'warn' } {
  switch (status) {
    case 'ok':
      return { text: '最近抓取成功', tone: 'ok' }
    case 'not_modified':
      return { text: '内容未变化（304）', tone: 'muted' }
    case 'error':
      return { text: '最近抓取失败', tone: 'warn' }
    default:
      return { text: '尚未抓取', tone: 'muted' }
  }
}

/**
 * Published time may be missing in external feeds — never fabricate it
 * (docs/03: 显示“来源时间未知”).
 */
export function sourceTimeLabels(
  publishedAt: string | null | undefined,
  fetchedAt: string | null | undefined,
): { published: string; fetched: string } {
  return {
    published: publishedAt ? fullTime(publishedAt) : '来源时间未知',
    fetched: fetchedAt ? fullTime(fetchedAt) : '—',
  }
}

/** One-line fetch log summary: "成功 · 新增 2 / 共 3 条" etc. */
export function describeFetchLog(log: FetchLogDto): string {
  if (log.status === 'ok') {
    return `成功 · 新增 ${log.items_new} / 共 ${log.items_found} 条`
  }
  if (log.status === 'not_modified') {
    return '内容未变化（304）'
  }
  return `失败 · ${log.error ?? '未知错误'}`
}
