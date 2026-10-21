import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { db } from '../db'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { STATUS_LABELS, isActive, progressPercent } from '../lib/generation'
import { todayBounds } from '../lib/usage'
import {
  ApiError,
  cancelGenerationJob,
  createGenerationJob,
  getGenerationSettings,
  getGenerationUsage,
  listGenerationJobs,
} from '../services/api'
import type { GenerationSettingsDto, JobDto, UsageDto } from '../services/types'
import { NoticeBar, type NoticeTone } from './NoticeBar'

const RAIL_POLL_MS = 20_000

interface RailState {
  settings: GenerationSettingsDto | null
  usage: UsageDto | null
  active: JobDto | null
}

/**
 * Right-rail generation status (docs/08 §2): inventory level, the running job
 * and today's spend, so the reader can see the pipeline without leaving Feed.
 * Reads the server only while online; local reading never depends on it.
 */
export function GenerationStatusRail() {
  const online = useOnlineStatus()
  const [state, setState] = useState<RailState | null>(null)
  const [unreachable, setUnreachable] = useState(false)
  const [notice, setNotice] = useState<{ tone: NoticeTone; message: string; jobId?: string } | null>(
    null,
  )
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [settings, usage, jobs] = await Promise.all([
        getGenerationSettings(),
        getGenerationUsage('day', todayBounds()),
        listGenerationJobs(5),
      ])
      setState({ settings, usage, active: jobs.find(isActive) ?? null })
      setUnreachable(false)
    } catch {
      setState(null)
      setUnreachable(true)
    }
  }, [])

  useEffect(() => {
    if (!online) {
      setUnreachable(true)
      return
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), RAIL_POLL_MS)
    return () => window.clearInterval(timer)
  }, [online, refresh])

  const localCount = useLiveQuery(() => db.posts.count(), [], 0)

  const onGenerate = async () => {
    setBusy(true)
    try {
      const job = await createGenerationJob({ requested_count: 25, mode: 'manual' })
      setNotice({
        tone: 'good',
        message: `已排队生成 25 条（任务 ${job.id.slice(0, 8)}），完成后到 Feed 拉取即可。`,
        jobId: job.id,
      })
      await refresh()
    } catch (err) {
      setNotice({
        tone: 'bad',
        message: err instanceof ApiError ? `创建任务失败：${err.code} ${err.message}` : '创建任务失败',
      })
    } finally {
      setBusy(false)
    }
  }

  const onCancel = async (jobId: string) => {
    setBusy(true)
    try {
      await cancelGenerationJob(jobId)
      setNotice({ tone: 'info', message: '已取消该生成任务，费用按已消耗部分记录。' })
      await refresh()
    } catch (err) {
      setNotice({
        tone: 'bad',
        message: err instanceof ApiError ? `取消失败：${err.message}` : '取消失败',
      })
    } finally {
      setBusy(false)
    }
  }

  if (!online || unreachable) {
    return (
      <section data-panel="generation-status" data-state="offline">
        <h2 className="paf-section-title">生成状态</h2>
        <p className="mt-2 text-sm text-[var(--paf-muted)]">
          未连接服务端，看不到生成进度。本地已缓存 {localCount} 条，可继续离线阅读。
        </p>
        <Link to="/generation" className="paf-btn mt-2 border border-[var(--paf-border)]">
          生成中心
        </Link>
      </section>
    )
  }

  const settings = state?.settings
  const usage = state?.usage
  const active = state?.active
  const low = settings ? settings.undelivered_inventory < settings.low_water : false
  const costLabel = usage
    ? usage.total_cost_estimate === null
      ? '未知'
      : `¥${usage.total_cost_estimate.toFixed(2)}`
    : '—'

  return (
    <section data-panel="generation-status" data-state="live">
      <h2 className="paf-section-title">生成状态</h2>
      <dl className="mt-2 space-y-1.5 text-sm">
        <div className="flex items-baseline justify-between gap-2">
          <dt className="text-[var(--paf-muted)]">服务端待读库存</dt>
          <dd data-field="inventory">{settings?.undelivered_inventory ?? '—'}</dd>
        </div>
        <div className="flex items-baseline justify-between gap-2">
          <dt className="text-[var(--paf-muted)]">今日已花费</dt>
          <dd data-field="today-cost">
            {costLabel} / ¥{settings?.daily_cost_limit ?? '—'}
          </dd>
        </div>
        <div className="flex items-baseline justify-between gap-2">
          <dt className="text-[var(--paf-muted)]">进行中任务</dt>
          <dd data-field="active-jobs">{settings?.active_jobs ?? '—'}</dd>
        </div>
      </dl>
      {low ? (
        <p className="mt-2 text-xs text-amber-600" data-state="low-water">
          低于 {settings?.low_water} 条就会自动补到 {settings?.unread_target} 条。
        </p>
      ) : null}
      {active ? (
        <div className="mt-3">
          <p className="text-xs text-[var(--paf-muted)]">
            {STATUS_LABELS[active.status]}：{active.produced_count + active.failed_count}/
            {active.requested_count}
          </p>
          <div
            className="mt-1 h-1.5 overflow-hidden rounded-full bg-black/10 dark:bg-white/10"
            role="progressbar"
            aria-valuenow={progressPercent(active)}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="当前任务进度"
          >
            <div
              className="h-full rounded-full bg-[var(--paf-accent)] transition-all"
              style={{ width: `${progressPercent(active)}%` }}
            />
          </div>
        </div>
      ) : null}
      <div className="mt-3 flex flex-wrap gap-2">
        <button
          type="button"
          className="paf-btn"
          onClick={() => void onGenerate()}
          disabled={busy || (settings?.active_jobs ?? 0) > 0}
        >
          生成 25 条
        </button>
        {active ? (
          <button
            type="button"
            className="paf-btn border border-[var(--paf-border)]"
            onClick={() => void onCancel(active.id)}
            disabled={busy}
          >
            取消任务
          </button>
        ) : null}
      </div>
      <Link to="/usage" className="mt-2 block text-xs text-[var(--paf-accent)] underline">
        查看用量与费用
      </Link>

      {notice ? (
        <NoticeBar
          tone={notice.tone}
          message={notice.message}
          actionLabel={notice.jobId ? '撤销：取消该任务' : undefined}
          onAction={notice.jobId ? () => void onCancel(notice.jobId!) : undefined}
          onClose={() => setNotice(null)}
        />
      ) : null}
    </section>
  )
}
