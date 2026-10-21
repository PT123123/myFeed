import { useLiveQuery } from 'dexie-react-hooks'
import {
  AlertCircle,
  Ban,
  CloudDownload,
  RefreshCw,
  RotateCcw,
  Sparkles,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { db } from '../db'
import { isVisible, unreadCount } from '../lib/feedQuery'
import {
  STATUS_LABELS,
  canCancel,
  canRetry,
  formatTokens,
  isActive,
  progressPercent,
  usageTotals,
} from '../lib/generation'
import { todayBounds } from '../lib/usage'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import {
  ApiError,
  cancelGenerationJob,
  createGenerationJob,
  getGenerationSettings,
  getGenerationUsage,
  listGenerationJobs,
  pullFeedToDexie,
  putGenerationSettings,
  retryGenerationJob,
} from '../services/api'
import type { GenerationSettingsDto, JobDto, UsageDto } from '../services/types'

const BATCH_PRESETS = [10, 25, 50, 100]

export function GenerationPage() {
  const online = useOnlineStatus()
  const [jobs, setJobs] = useState<JobDto[]>([])
  const [usage, setUsage] = useState<UsageDto | null>(null)
  const [settings, setServerSettings] = useState<GenerationSettingsDto | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pullState, setPullState] = useState<string | null>(null)
  const pollRef = useRef<number | null>(null)
  const settingsDirty = useRef(false)
  const editSettings = (next: GenerationSettingsDto) => {
    settingsDirty.current = true
    setServerSettings(next)
  }

  const localStats = useLiveQuery(async () => {
    const posts = await db.posts.toArray()
    const pending = await db.outbox.where('status').equals('pending').count()
    return {
      unread: unreadCount(posts),
      total: posts.filter((p) => isVisible(p)).length,
      pending,
    }
  }, [])

  const refresh = useCallback(async () => {
    try {
      const [jobList, usageData, settingsData] = await Promise.all([
        listGenerationJobs(20),
        // The cards below say 「今日」, so ask the server for today only.
        getGenerationUsage('day', todayBounds()),
        getGenerationSettings(),
      ])
      setJobs(jobList)
      setUsage(usageData)
      if (!settingsDirty.current) setServerSettings(settingsData)
      setError(null)
    } catch (err) {
      if (err instanceof ApiError) setError(`服务端不可用：${err.message}（本地阅读不受影响）`)
      else setError('服务端不可用')
    }
  }, [])

  useEffect(() => {
    if (!online) return
    void refresh()
  }, [online, refresh])

  // Poll while any job is active.
  useEffect(() => {
    const hasActive = jobs.some(isActive)
    if (!hasActive || !online) {
      if (pollRef.current) window.clearInterval(pollRef.current)
      pollRef.current = null
      return
    }
    pollRef.current = window.setInterval(() => {
      void refresh()
    }, 2000)
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current)
    }
  }, [jobs, online, refresh])

  const generate = async (count: number) => {
    setBusy(true)
    setMessage(null)
    setError(null)
    try {
      const job = await createGenerationJob({
        requested_count: count,
        mode: 'manual',
        client_key: undefined,
      })
      setMessage(`已创建任务 ${job.id.slice(0, 8)}（${count} 条），worker 处理中…`)
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '创建任务失败')
    } finally {
      setBusy(false)
    }
  }

  const onCancel = async (id: string) => {
    try {
      await cancelGenerationJob(id)
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '取消失败')
    }
  }

  const onRetry = async (id: string) => {
    try {
      await retryGenerationJob(id)
      setMessage('已重新入队')
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '重试失败')
    }
  }

  const onPull = async () => {
    setBusy(true)
    setPullState('拉取中…')
    try {
      const result = await pullFeedToDexie()
      setPullState(`拉取 ${result.fetched} 条：新增 ${result.added}，更新 ${result.updated}`)
      await refresh()
    } catch (err) {
      setPullState(err instanceof ApiError ? `拉取失败：${err.message}` : '拉取失败')
    } finally {
      setBusy(false)
    }
  }

  const saveSettings = async (patch: Partial<GenerationSettingsDto>) => {
    if (!patch.model?.trim()) { setError('请输入电脑端可用的模型名称'); return }
    try {
      const next = await putGenerationSettings(patch)
      settingsDirty.current = false
      setServerSettings(next)
      setMessage('生成设置已保存')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '保存失败')
    }
  }

  if (!online) {
    return (
      <EmptyState
        title="离线中"
        description="生成需要连接服务端。已缓存的本地内容仍可在 Feed 中阅读。"
        action={
          <Link to="/" className="paf-btn">
            去读本地 Feed
          </Link>
        }
      />
    )
  }

  const totals = usage ? usageTotals(usage) : null

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-lg font-bold">生成中心</h1>
        <p className="text-sm text-[var(--paf-muted)]">
          批量预生成 · 阅读 Feed 不会触发任何模型调用 · Mock Provider 无需密钥
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div className="paf-card">
          <p className="paf-section-title">本地未读</p>
          <p className="mt-1 text-3xl font-bold">{localStats?.unread ?? '—'}</p>
        </div>
        <div className="paf-card">
          <p className="paf-section-title">服务端库存</p>
          <p className="mt-1 text-3xl font-bold">
            {settings?.undelivered_inventory ?? '—'}
          </p>
        </div>
        <div className="paf-card">
          <p className="paf-section-title">今日 Token</p>
          <p className="mt-1 text-3xl font-bold">
            {totals ? formatTokens(totals.tokens) : '—'}
          </p>
        </div>
        <div className="paf-card">
          <p className="paf-section-title">今日费用</p>
          <p className="mt-1 text-3xl font-bold">{totals ? totals.costLabel : '—'}</p>
        </div>
      </div>

      {message ? <p className="text-sm text-emerald-600">{message}</p> : null}
      {error ? (
        <p className="flex items-center gap-1.5 text-sm text-red-500">
          <AlertCircle className="size-4" /> {error}
        </p>
      ) : null}

      <section className="paf-card space-y-3">
        <h2 className="paf-section-title">手动批量生成</h2>
        <div className="flex flex-wrap gap-2">
          {BATCH_PRESETS.map((n) => (
            <button
              key={n}
              type="button"
              disabled={busy || !!settings?.active_jobs}
              onClick={() => void generate(n)}
              className="inline-flex min-h-11 items-center gap-2 rounded-lg bg-[var(--paf-accent)] px-4 text-sm font-medium text-white disabled:opacity-50"
            >
              <Sparkles className="size-4" /> 生成 {n} 条
            </button>
          ))}
          <button
            type="button"
            disabled={busy}
            onClick={() => void refresh()}
            className="paf-btn border border-[var(--paf-border)]"
          >
            <RefreshCw className="size-4" /> 刷新状态
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void onPull()}
            className="paf-btn border border-[var(--paf-border)]"
          >
            <CloudDownload className="size-4" /> 拉取新内容到本地
          </button>
        </div>
        {pullState ? <p className="text-sm text-[var(--paf-muted)]">{pullState}</p> : null}
        <p className="text-xs text-[var(--paf-muted)]">
          Provider：{settings?.provider_id ?? '—'} / 模型：{settings?.model ?? '—'} ·
          每日限额 ¥{settings?.daily_cost_limit ?? '—'} · 单任务 ¥
          {settings?.per_job_cost_limit ?? '—'}
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="paf-section-title">任务队列</h2>
        {jobs.length === 0 ? (
          <EmptyState title="还没有任务" description="点击上方按钮创建第一个批量生成任务。" />
        ) : (
          <ul className="space-y-3">
            {jobs.map((job) => (
              <li key={job.id} className="paf-card space-y-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-medium">{STATUS_LABELS[job.status]}</span>
                    <span className="paf-chip">{job.mode}</span>
                    <span className="paf-chip">{job.provider_id}</span>
                    <span className="text-[var(--paf-muted)]">
                      {job.produced_count}/{job.requested_count}
                      {job.failed_count > 0 ? ` · 失败 ${job.failed_count}` : ''}
                    </span>
                  </div>
                  <div className="flex gap-1">
                    {canCancel(job) ? (
                      <button
                        type="button"
                        className="paf-btn"
                        onClick={() => void onCancel(job.id)}
                        aria-label="取消任务"
                      >
                        <Ban className="size-4" /> 取消
                      </button>
                    ) : null}
                    {canRetry(job) ? (
                      <button
                        type="button"
                        className="paf-btn"
                        onClick={() => void onRetry(job.id)}
                        aria-label="重试任务"
                      >
                        <RotateCcw className="size-4" /> 重试
                      </button>
                    ) : null}
                  </div>
                </div>
                <div
                  className="h-2 overflow-hidden rounded-full bg-black/10 dark:bg-white/10"
                  role="progressbar"
                  aria-valuenow={progressPercent(job)}
                  aria-valuemin={0}
                  aria-valuemax={100}
                >
                  <div
                    className="h-full rounded-full bg-[var(--paf-accent)] transition-all"
                    style={{ width: `${progressPercent(job)}%` }}
                  />
                </div>
                {job.error_code ? (
                  <p className="text-xs text-red-500">
                    {job.error_code}: {job.error_message}
                  </p>
                ) : null}
                <p className="text-xs text-[var(--paf-muted)]">任务 {job.id.slice(0, 12)}</p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="paf-card space-y-2">
        <h2 className="paf-section-title">今日用量合计</h2>
        <p className="text-sm text-[var(--paf-muted)]" data-usage="today">
          {usage
            ? `${formatTokens(totals!.tokens)} Token · ${totals!.calls} 次调用 · 估算 ${totals!.costLabel}`
            : '今日还没有用量记录。'}
        </p>
        {usage?.has_unknown_price ? (
          <p className="text-xs text-amber-600">
            含未登记定价的调用：总费用显示「未知」，不会按 0 计算。
          </p>
        ) : null}
        <Link to="/usage" className="paf-btn border border-[var(--paf-border)]">
          打开用量与费用页（按天 / 按模型 / 按批次、导出）
        </Link>
      </section>

      <section className="paf-card space-y-3">
        <h2 className="paf-section-title">调度与预算设置</h2>
        {settings ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block space-y-1 text-sm">生成提供方
              <select className="paf-input" value={settings.provider_id}
                onChange={(event) => editSettings({ ...settings, provider_id: event.target.value })}>
                <option value="mock">Mock 演示（不调用真实模型）</option>
                <option value="openai_compat">电脑端已配置的模型服务</option>
                {!['mock', 'openai_compat'].includes(settings.provider_id) && <option value={settings.provider_id}>{settings.provider_id}</option>}
              </select>
            </label>
            <label className="block space-y-1 text-sm">模型名称
              <input className="paf-input" value={settings.model} spellCheck={false} autoComplete="off"
                onChange={(event) => editSettings({ ...settings, model: event.target.value })} />
            </label>
            <label className="block space-y-1 text-sm">最大并发任务
              <input className="paf-input" type="number" min={1} max={8} value={settings.max_concurrency}
                onChange={(event) => editSettings({ ...settings, max_concurrency: Number(event.target.value) })} />
            </label>
            <label className="block space-y-1 text-sm">每分钟请求上限
              <input className="paf-input" type="number" min={1} max={600} value={settings.requests_per_minute}
                onChange={(event) => editSettings({ ...settings, requests_per_minute: Number(event.target.value) })} />
            </label>
            <label className="block space-y-1 text-sm">
              目标未读库存
              <input
                type="number"
                className="paf-input"
                value={settings.unread_target}
                min={10}
                max={2000}
                onChange={(e) =>
                  editSettings({ ...settings, unread_target: Number(e.target.value) })
                }
              />
            </label>
            <label className="block space-y-1 text-sm">
              低水位（低于此值触发补足）
              <input
                type="number"
                className="paf-input"
                value={settings.low_water}
                min={5}
                max={1000}
                onChange={(e) =>
                  editSettings({ ...settings, low_water: Number(e.target.value) })
                }
              />
            </label>
            <label className="block space-y-1 text-sm">
              每日检查时间（本地）
              <input
                type="time"
                className="paf-input"
                value={settings.daily_run_time}
                onChange={(e) =>
                  editSettings({ ...settings, daily_run_time: e.target.value })
                }
              />
            </label>
            <label className="block space-y-1 text-sm">
              每日费用上限（¥）
              <input
                type="number"
                step="0.1"
                className="paf-input"
                value={settings.daily_cost_limit}
                onChange={(e) =>
                  editSettings({ ...settings, daily_cost_limit: Number(e.target.value) })
                }
              />
            </label>
            <label className="block space-y-1 text-sm">
              每日 Token 上限
              <input
                type="number"
                step="1000"
                className="paf-input"
                value={settings.daily_token_limit}
                onChange={(e) =>
                  editSettings({ ...settings, daily_token_limit: Number(e.target.value) })
                }
              />
            </label>
            <label className="block space-y-1 text-sm">
              单任务费用上限（¥）
              <input
                type="number"
                step="0.1"
                className="paf-input"
                value={settings.per_job_cost_limit}
                onChange={(e) =>
                  editSettings({ ...settings, per_job_cost_limit: Number(e.target.value) })
                }
              />
            </label>
          </div>
        ) : (
          <p className="text-sm text-[var(--paf-muted)]">加载设置…</p>
        )}
        {settings ? (
          <button
            type="button"
            className="inline-flex min-h-11 items-center gap-2 rounded-lg bg-[var(--paf-accent)] px-4 text-sm font-medium text-white"
            onClick={() =>
              void saveSettings({
                unread_target: settings.unread_target,
                low_water: settings.low_water,
                daily_run_time: settings.daily_run_time,
                daily_cost_limit: settings.daily_cost_limit,
                daily_token_limit: settings.daily_token_limit,
                per_job_cost_limit: settings.per_job_cost_limit,
                provider_id: settings.provider_id,
                model: settings.model.trim(),
                max_concurrency: settings.max_concurrency,
                requests_per_minute: settings.requests_per_minute,
              })
            }
          >
            保存生成设置
          </button>
        ) : null}
        <p className="text-xs text-[var(--paf-muted)]">
          模型连接与密钥由电脑端保管，新设置用于后续任务。探索比例与主题配比在「发现页」调整；预算超限时会显示原因。
        </p>
      </section>

      <p className="text-sm text-[var(--paf-muted)]">
        本地库存构成可在设置页查看 · 想看的内容分布？
        <Link to="/discover" className="text-[var(--paf-accent)] underline">
          去发现页
        </Link>
      </p>
    </div>
  )
}
