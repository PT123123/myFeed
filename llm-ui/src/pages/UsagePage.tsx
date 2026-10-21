import { Download, FileJson, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { NoticeBar, type NoticeTone } from '../components/NoticeBar'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { formatCost, formatTokens } from '../lib/generation'
import { downloadAsFile } from '../lib/native'
import {
  DIMENSIONS,
  dateBounds,
  dimensionOf,
  localDate,
  usageCsv,
  usageFileName,
  usageJson,
} from '../lib/usage'
import { ApiError, getGenerationUsage } from '../services/api'
import type { UsageDto, UsageGroupBy } from '../services/types'

const RANGE_PRESETS: { label: string; days: number | null }[] = [
  { label: '近 7 天', days: 7 },
  { label: '近 30 天', days: 30 },
  { label: '全部', days: null },
]

/**
 * Usage page (docs/08 §3): the three groupings the contract offers, an
 * explicit time range, and CSV/JSON exports. Unknown model pricing is shown
 * as 「未知」 and never summed as ¥0.
 */
export function UsagePage() {
  const online = useOnlineStatus()
  const [groupBy, setGroupBy] = useState<UsageGroupBy>('day')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [usage, setUsage] = useState<UsageDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: NoticeTone; message: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setBusy(true)
    try {
      setUsage(await getGenerationUsage(groupBy, dateBounds(from, to)))
      setError(null)
    } catch (err) {
      setError(
        err instanceof ApiError
          ? `服务端不可用：${err.message}（用量数据只在服务端，离线时看不了）`
          : '服务端不可用',
      )
      setUsage(null)
    } finally {
      setBusy(false)
    }
  }, [groupBy, from, to])

  useEffect(() => {
    if (online) void load()
  }, [online, load])

  const onExport = (kind: 'csv' | 'json') => {
    if (!usage) return
    const text = kind === 'csv' ? usageCsv(usage) : usageJson(usage)
    const name = usageFileName(usage, kind)
    downloadAsFile(name, text, kind === 'csv' ? 'text/csv' : 'application/json')
    setNotice({
      tone: 'good',
      message: `已导出 ${usage.rows.length} 行${kind === 'csv' ? '表格' : '原始数据'}：${name}`,
    })
  }

  const dimension = dimensionOf(groupBy)
  const totalTokens = usage ? usage.total_input_tokens + usage.total_output_tokens : 0

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-lg font-bold">用量与费用</h1>
        <p className="text-sm text-[var(--paf-muted)]">
          服务端记录每次模型调用的 Token 与估算费用；阅读行为不产生任何用量。
        </p>
      </div>

      <div className="flex flex-wrap gap-2" role="tablist" aria-label="统计维度">
        {DIMENSIONS.map((d) => (
          <button
            key={d.value}
            type="button"
            role="tab"
            aria-selected={groupBy === d.value}
            data-dimension={d.value}
            className={`paf-btn border ${
              groupBy === d.value
                ? 'border-[var(--paf-accent)] font-semibold text-[var(--paf-accent)]'
                : 'border-[var(--paf-border)]'
            }`}
            onClick={() => setGroupBy(d.value)}
          >
            {d.label}
          </button>
        ))}
      </div>

      <section className="paf-card space-y-3">
        <h2 className="paf-section-title">时间范围</h2>
        <div className="flex flex-wrap items-end gap-3">
          <label className="block space-y-1 text-sm">
            起始日期
            <input
              type="date"
              className="paf-input"
              value={from}
              data-field="from"
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label className="block space-y-1 text-sm">
            结束日期（含当天）
            <input
              type="date"
              className="paf-input"
              value={to}
              data-field="to"
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          {RANGE_PRESETS.map((p) => (
            <button
              key={p.label}
              type="button"
              className="paf-btn border border-[var(--paf-border)]"
              onClick={() => {
                setFrom(p.days === null ? '' : localDate(p.days - 1))
                setTo(p.days === null ? '' : localDate())
              }}
            >
              {p.label}
            </button>
          ))}
          <button
            type="button"
            className="paf-btn border border-[var(--paf-border)]"
            onClick={() => void load()}
            disabled={busy}
          >
            <RefreshCw className="size-4" /> {busy ? '统计中…' : '重新统计'}
          </button>
        </div>
        <p className="text-xs text-[var(--paf-muted)]">
          按本机日期计算，起止都算在内；留空表示不限制该侧。
        </p>
      </section>

      {!online ? (
        <EmptyState
          title="离线中"
          description="用量与费用统计存在服务端，联网后回到本页即可刷新。"
          action={
            <Link to="/" className="paf-btn">
              去读本地 Feed
            </Link>
          }
        />
      ) : error ? (
        <p className="paf-card text-sm text-red-500" data-state="unreachable">
          {error}
        </p>
      ) : !usage || usage.rows.length === 0 ? (
        <EmptyState
          title="这个范围内没有用量记录"
          description="换一段时间，或到「生成」页创建一次批量生成任务。"
          action={
            <Link to="/generation" className="paf-btn">
              去生成页
            </Link>
          }
        />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div className="paf-card">
              <p className="paf-section-title">总 Token</p>
              <p className="mt-1 text-2xl font-bold" data-field="total-tokens">
                {formatTokens(totalTokens)}
              </p>
            </div>
            <div className="paf-card">
              <p className="paf-section-title">总费用</p>
              <p className="mt-1 text-2xl font-bold" data-field="total-cost">
                {formatCost(usage.total_cost_estimate, usage.has_unknown_price)}
              </p>
            </div>
            <div className="paf-card">
              <p className="paf-section-title">调用次数</p>
              <p className="mt-1 text-2xl font-bold">
                {usage.rows.reduce((s, r) => s + r.calls, 0)}
              </p>
            </div>
            <div className="paf-card">
              <p className="paf-section-title">{dimension.column}数</p>
              <p className="mt-1 text-2xl font-bold">{usage.rows.length}</p>
            </div>
          </div>

          <section className="paf-card space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="paf-section-title">{dimension.label}明细</h2>
              <div className="flex gap-2">
                <button
                  type="button"
                  className="paf-btn border border-[var(--paf-border)]"
                  onClick={() => onExport('csv')}
                  data-export="csv"
                >
                  <Download className="size-4" /> 导出 CSV
                </button>
                <button
                  type="button"
                  className="paf-btn border border-[var(--paf-border)]"
                  onClick={() => onExport('json')}
                  data-export="json"
                >
                  <FileJson className="size-4" /> 导出 JSON
                </button>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <caption className="sr-only">
                  按{dimension.column}统计的 Token 与估算费用
                </caption>
                <thead>
                  <tr className="text-left text-xs text-[var(--paf-muted)]">
                    <th className="py-1 pr-3">{dimension.column}</th>
                    <th className="py-1 pr-3">任务</th>
                    <th className="py-1 pr-3">调用</th>
                    <th className="py-1 pr-3">输入</th>
                    <th className="py-1 pr-3">输出</th>
                    <th className="py-1 pr-3">估算费用</th>
                    <th className="py-1">未回传用量</th>
                  </tr>
                </thead>
                <tbody>
                  {usage.rows.map((row) => (
                    <tr key={row.bucket} className="border-t border-[var(--paf-border)]">
                      <td className="py-1.5 pr-3 break-all">{row.bucket}</td>
                      <td className="py-1.5 pr-3">{row.jobs}</td>
                      <td className="py-1.5 pr-3">{row.calls}</td>
                      <td className="py-1.5 pr-3">{formatTokens(row.input_tokens)}</td>
                      <td className="py-1.5 pr-3">{formatTokens(row.output_tokens)}</td>
                      <td className="py-1.5 pr-3">
                        {formatCost(row.cost_estimate, row.has_unknown_price)}
                      </td>
                      <td className="py-1.5">{row.missing_usage}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {usage.has_unknown_price ? (
              <p className="text-xs text-amber-600" data-state="unknown-price">
                有模型没有登记定价：这些调用只显示「未知」，不会按 0 计入总费用。
              </p>
            ) : null}
            {usage.total_missing_usage > 0 ? (
              <p className="text-xs text-[var(--paf-muted)]" data-state="missing-usage">
                {usage.total_missing_usage} 次调用没回传 Token 数，上面的 Token 合计比真实消耗少。
              </p>
            ) : null}
          </section>
        </>
      )}

      <p className="text-sm text-[var(--paf-muted)]">
        想控制消耗？预算与库存水位在
        <Link to="/generation" className="text-[var(--paf-accent)] underline">
          生成页
        </Link>
        调整。
      </p>

      {notice ? (
        <NoticeBar tone={notice.tone} message={notice.message} onClose={() => setNotice(null)} />
      ) : null}
    </div>
  )
}
