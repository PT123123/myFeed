import { useLiveQuery } from 'dexie-react-hooks'
import { Link } from 'react-router-dom'
import {
  ChevronDown,
  ChevronUp,
  ExternalLink,
  Link2,
  Plus,
  Power,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'

import { EmptyState } from '../components/EmptyState'
import { db } from '../db'
import { relativeTime } from '../lib/time'
import { describeFetchLog, sourceStatusLabel, sourceTimeLabels } from '../lib/sources'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import {
  ApiError,
  createSource,
  deleteSource,
  getSourceItems,
  getSourceLogs,
  listSources,
  patchSource,
  refreshSource,
} from '../services/api'
import type { SourceDto, SourceItemDto } from '../services/types'

export function SourcesPage() {
  const online = useOnlineStatus()
  const topics = useLiveQuery(() => db.topics.toArray(), [])

  const [sources, setSources] = useState<SourceDto[]>([])
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [url, setUrl] = useState('')
  const [title, setTitle] = useState('')
  const [topicId, setTopicId] = useState('')
  const [intervalMin, setIntervalMin] = useState(360)

  const [busyId, setBusyId] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [items, setItems] = useState<SourceItemDto[]>([])
  const [lastLog, setLastLog] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setSources(await listSources())
      setError(null)
    } catch (err) {
      setError(
        err instanceof ApiError ? `${err.code}: ${err.message}` : '服务端不可用，无法加载来源',
      )
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (online) void refresh()
  }, [online, refresh])

  const onAdd = async () => {
    if (!url.trim()) return
    try {
      const created = await createSource({
        url: url.trim(),
        title: title.trim() || null,
        topic_id: topicId || null,
        fetch_interval_minutes: intervalMin,
      })
      setSources((prev) => [...prev, created])
      setUrl('')
      setTitle('')
      setMessage(`已添加「${created.title}」`)
      setError(null)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '添加失败')
    }
  }

  const onToggle = async (source: SourceDto) => {
    try {
      const updated = await patchSource(source.id, { enabled: !source.enabled })
      setSources((prev) => prev.map((s) => (s.id === updated.id ? updated : s)))
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '更新失败')
    }
  }

  const onRefresh = async (source: SourceDto) => {
    setBusyId(source.id)
    setError(null)
    try {
      const out = await refreshSource(source.id)
      setSources((prev) => prev.map((s) => (s.id === out.source.id ? out.source : s)))
      setMessage(`「${source.title}」${describeFetchLog(out.log)}`)
      if (expandedId === source.id) await openItems(source.id)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '刷新失败（网络不可用）')
    } finally {
      setBusyId(null)
    }
  }

  const onRefreshAll = async () => {
    for (const s of sources) {
      if (s.enabled) await onRefresh(s)
    }
  }

  const openItems = async (id: string) => {
    try {
      const [itemList, logs] = await Promise.all([getSourceItems(id), getSourceLogs(id)])
      setItems(itemList)
      setLastLog(logs[0] ? describeFetchLog(logs[0]) : null)
    } catch {
      setItems([])
      setLastLog(null)
    }
  }

  const onToggleExpand = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null)
      return
    }
    setExpandedId(id)
    await openItems(id)
  }

  const onDelete = async (source: SourceDto) => {
    try {
      await deleteSource(source.id)
      setSources((prev) => prev.filter((s) => s.id !== source.id))
      if (expandedId === source.id) setExpandedId(null)
      setMessage(`已删除「${source.title}」及其抓取条目`)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '删除失败')
    }
  }

  if (!online) {
    return (
      <EmptyState
        title="离线中"
        description="来源管理需要连接服务端。已采集的内容仍可通过 Feed 阅读。"
        action={
          <Link to="/" className="paf-btn">
            去读 Feed
          </Link>
        }
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-bold">真实来源</h1>
          <p className="text-sm text-[var(--paf-muted)]">
            RSS/Atom 采集 → 存为 source_item → 只有摘录能进入 sourced 生成（B05）
          </p>
        </div>
        <div className="flex gap-1">
          <button
            type="button"
            className="paf-btn"
            disabled={busyId !== null || sources.length === 0}
            onClick={() => void onRefreshAll()}
          >
            <RefreshCw className="size-4" /> 全部刷新
          </button>
          <button type="button" className="paf-btn" onClick={() => void refresh()}>
            重新加载
          </button>
        </div>
      </div>

      {message ? <p className="text-sm text-emerald-600">{message}</p> : null}
      {error ? <p className="text-sm text-red-500">{error}</p> : null}

      <section className="paf-card space-y-2">
        <h2 className="paf-section-title flex items-center gap-1.5">
          <Plus className="size-4" /> 添加 RSS 源
        </h2>
        <label className="block space-y-1 text-sm">
          Feed URL（http/https）
          <input
            className="paf-input"
            placeholder="https://example.com/feed.xml"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
        </label>
        <div className="grid gap-2 sm:grid-cols-3">
          <label className="block space-y-1 text-sm">
            显示名（可选）
            <input
              className="paf-input"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </label>
          <label className="block space-y-1 text-sm">
            归入主题
            <select
              className="paf-input"
              value={topicId}
              onChange={(e) => setTopicId(e.target.value)}
            >
              <option value="">（按权重自动）</option>
              {(topics ?? []).map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
          <label className="block space-y-1 text-sm">
            抓取频率（分钟）
            <input
              type="number"
              min={5}
              className="paf-input"
              value={intervalMin}
              onChange={(e) => setIntervalMin(Number(e.target.value))}
            />
          </label>
        </div>
        <button
          type="button"
          className="paf-btn"
          disabled={!url.trim()}
          onClick={() => void onAdd()}
        >
          <Link2 className="size-4" /> 添加
        </button>
      </section>

      {loading ? (
        <p className="paf-card text-sm text-[var(--paf-muted)]">加载来源…</p>
      ) : sources.length === 0 ? (
        <EmptyState
          title="还没有来源"
          description="添加至少 2 个 RSS 源，采集去重后即可生成带真实链接的 sourced 帖子。"
        />
      ) : null}

      <ul className="space-y-3">
        {sources.map((s) => {
          const status = sourceStatusLabel(s.last_status)
          const isExpanded = expandedId === s.id
          return (
            <li key={s.id} className="paf-card space-y-2">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    <span className="truncate">{s.title}</span>
                    <span
                      className={`paf-chip ${
                        status.tone === 'warn'
                          ? 'border-red-400 text-red-500'
                          : status.tone === 'ok'
                            ? 'border-emerald-400 text-emerald-600'
                            : ''
                      }`}
                    >
                      {status.text}
                    </span>
                    {!s.enabled ? <span className="paf-chip opacity-60">已停用</span> : null}
                  </p>
                  <p className="mt-0.5 truncate text-xs text-[var(--paf-muted)]">
                    <a
                      href={s.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 hover:underline"
                    >
                      {s.url} <ExternalLink className="size-3" />
                    </a>
                  </p>
                  <p className="mt-0.5 text-xs text-[var(--paf-muted)]">
                    条目 {s.item_count} 条 · 每 {s.fetch_interval_minutes} 分钟 ·{' '}
                    {s.last_fetched_at ? `上次抓取 ${relativeTime(s.last_fetched_at)}` : '从未抓取'}
                  </p>
                  {s.last_error ? (
                    <p className="mt-0.5 text-xs text-red-500">{s.last_error}</p>
                  ) : null}
                </div>
                <div className="flex shrink-0 gap-1">
                  <button
                    type="button"
                    className="paf-btn"
                    disabled={busyId === s.id}
                    title="立即抓取"
                    onClick={() => void onRefresh(s)}
                  >
                    <RefreshCw className={`size-4 ${busyId === s.id ? 'animate-spin' : ''}`} />
                    {busyId === s.id ? '抓取中' : '刷新'}
                  </button>
                  <button
                    type="button"
                    className="paf-btn"
                    title={s.enabled ? '停用' : '启用'}
                    onClick={() => void onToggle(s)}
                  >
                    <Power className="size-4" />
                  </button>
                  <button
                    type="button"
                    className="paf-btn"
                    title="查看条目"
                    onClick={() => void onToggleExpand(s.id)}
                  >
                    {isExpanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
                    条目
                  </button>
                  <button
                    type="button"
                    className="paf-btn"
                    title="删除来源"
                    onClick={() => void onDelete(s)}
                  >
                    <Trash2 className="size-4" />
                  </button>
                </div>
              </div>

              {isExpanded ? (
                <div className="space-y-2 border-t border-[var(--paf-border)] pt-2">
                  {lastLog ? (
                    <p className="text-xs text-[var(--paf-muted)]">最近日志：{lastLog}</p>
                  ) : null}
                  {items.length === 0 ? (
                    <p className="text-sm text-[var(--paf-muted)]">
                      还没有条目，点「刷新」抓取一次。
                    </p>
                  ) : (
                    <ul className="space-y-2">
                      {items.map((item) => {
                        const labels = sourceTimeLabels(item.published_at, item.fetched_at)
                        return (
                          <li key={item.id} className="text-sm">
                            <a
                              href={item.url}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="inline-flex items-start gap-1 font-medium hover:underline"
                            >
                              {item.title}
                              <ExternalLink className="mt-0.5 size-3 shrink-0" />
                            </a>
                            <p className="text-xs text-[var(--paf-muted)]">
                              {labels.published} · 抓取 {labels.fetched}
                              {item.used_by_post_id ? ' · 已生成 sourced 帖' : ' · 未使用'}
                            </p>
                            <p className="text-xs text-[var(--paf-muted)] line-clamp-2">
                              {item.body_excerpt}
                            </p>
                            {item.body_excerpt ? (
                              <details className="mt-1">
                                <summary className="cursor-pointer text-xs text-[var(--paf-accent)]">
                                  阅读已采集摘录
                                </summary>
                                <p className="mt-2 whitespace-pre-wrap text-sm leading-relaxed">
                                  {item.body_excerpt}
                                </p>
                              </details>
                            ) : null}
                          </li>
                        )
                      })}
                    </ul>
                  )}
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
