import { useLiveQuery } from 'dexie-react-hooks'
import { Check, Save } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { db } from '../db'
import type { InterestRow, PostRow, TopicRow } from '../db/types'
import {
  CLUSTER_ORDER,
  FALLBACK_CLUSTER,
  clusterLabel,
  type TopicCluster,
} from '../lib/curation'
import { isVisible } from '../lib/feedQuery'
import { PREVIEW_BATCH, mixNotes, mixSlots } from '../lib/mix'
import { cardPreview } from '../lib/preview'
import { effectiveWeight } from '../lib/score'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { putGenerationSettings } from '../services/api'
import { runSync } from '../services/sync'
import { queueInterestOp } from '../store/interactions'

interface DraftInterest {
  explicitWeight: number
  blocked: boolean
}

interface DraftSettings {
  explorationRatio: number
  mixSourced: number
  mixOriginal: number
}

export function DiscoverPage() {
  const online = useOnlineStatus()
  const data = useLiveQuery(
    async () => {
      const [topics, interests, posts, settingsRows] = await Promise.all([
        db.topics.toArray(),
        db.interests.toArray(),
        db.posts.toArray(),
        db.settings.toArray(),
      ])
      return { topics, interests, posts, settingsRows }
    },
    [],
    null,
  )

  const [draft, setDraft] = useState<Map<string, DraftInterest> | null>(null)
  const [draftSettings, setDraftSettings] = useState<DraftSettings | null>(null)
  const [savedFlash, setSavedFlash] = useState(false)
  const [syncNote, setSyncNote] = useState<string | null>(null)

  useEffect(() => {
    if (!data || draft) return
    setDraft(
      new Map(
        data.topics.map((t) => {
          const row = data.interests.find((i) => i.topicId === t.id)
          return [
            t.id,
            {
              explicitWeight: row?.explicitWeight ?? 0.5,
              blocked: row?.blocked ?? false,
            },
          ]
        }),
      ),
    )
    const get = (key: string, fallback: number) => {
      const v = data.settingsRows.find((s) => s.key === key)?.value
      return typeof v === 'number' ? v : fallback
    }
    setDraftSettings({
      explorationRatio: get('explorationRatio', 0.2),
      mixSourced: get('mixSourced', 0.5),
      mixOriginal: get('mixOriginal', 0.3),
    })
  }, [data, draft])

  const topicStats = useMemo(() => {
    if (!data) return new Map<string, { unread: number; total: number }>()
    const map = new Map<string, { unread: number; total: number }>()
    for (const p of data.posts) {
      if (!isVisible(p)) continue
      const cur = map.get(p.topicId) ?? { unread: 0, total: 0 }
      cur.total += 1
      if (!p.lastReadAt) cur.unread += 1
      map.set(p.topicId, cur)
    }
    return map
  }, [data])

  const exploreSample = useMemo(
    () => (data?.posts ?? []).filter((p: PostRow) => p.tags.includes('explore') && isVisible(p)).slice(0, 3),
    [data],
  )

  const interestByTopic = useMemo(
    () => new Map((data?.interests ?? []).map((i) => [i.topicId, i])),
    [data],
  )

  // Profile imports may add topics while this page is open. Derive untouched
  // values immediately, preserving the user's other in-progress draft edits.
  const draftFor = (id: string): DraftInterest => draft?.get(id) ?? {
    explicitWeight: interestByTopic.get(id)?.explicitWeight ?? 0.5,
    blocked: interestByTopic.get(id)?.blocked ?? false,
  }

  // B09: group topics under the 9 interest clusters so the reader sees the
  // spread of the feed, not a flat list that hides a filter bubble.
  const topicsByCluster = useMemo(() => {
    if (!data) return [] as [TopicCluster, TopicRow[]][]
    const grouped = new Map<TopicCluster, TopicRow[]>()
    for (const t of data.topics) {
      const c = t.cluster ?? FALLBACK_CLUSTER
      grouped.set(c, [...(grouped.get(c) ?? []), t])
    }
    const ordered: [TopicCluster, TopicRow[]][] = []
    for (const c of CLUSTER_ORDER) if (grouped.has(c)) ordered.push([c, grouped.get(c)!])
    for (const [c, list] of grouped) if (!CLUSTER_ORDER.includes(c)) ordered.push([c, list])
    return ordered
  }, [data])

  if (!data || !draft || !draftSettings) {
    return <p className="paf-card text-sm text-[var(--paf-muted)]">加载兴趣数据…</p>
  }

  const ratios = {
    sourced: draftSettings.mixSourced,
    original: draftSettings.mixOriginal,
    exploration: draftSettings.explorationRatio,
  }
  const slots = mixSlots(PREVIEW_BATCH, ratios)
  const notes = mixNotes(ratios)

  const save = async () => {
    const now = new Date().toISOString()
    const rows: InterestRow[] = data.topics.map((t) => ({
      topicId: t.id,
      explicitWeight: draftFor(t.id).explicitWeight,
      inferredWeight:
        data.interests.find((i) => i.topicId === t.id)?.inferredWeight ?? 0,
      depthBias: data.interests.find((i) => i.topicId === t.id)?.depthBias,
      blocked: draftFor(t.id).blocked,
      revision: data.interests.find((i) => i.topicId === t.id)?.revision ?? 0,
      updatedAt: now,
    }))
    await db.transaction('rw', db.interests, db.settings, async () => {
      await db.interests.bulkPut(rows)
      await db.settings.put({ key: 'explorationRatio', value: draftSettings.explorationRatio })
      await db.settings.put({ key: 'mixSourced', value: draftSettings.mixSourced })
      await db.settings.put({ key: 'mixOriginal', value: draftSettings.mixOriginal })
    })
    setSavedFlash(true)
    setTimeout(() => setSavedFlash(false), 1500)

    // B06: interest edits go through the outbox (works offline, idempotent,
    // revision-checked). Background sync pushes them; kick one round now.
    for (const r of rows) {
      await queueInterestOp(r.topicId, r.explicitWeight, r.blocked)
    }
    let settingsOk = true
    try {
      await putGenerationSettings({
        mix_sourced: draftSettings.mixSourced,
        mix_original: draftSettings.mixOriginal,
        exploration_ratio: draftSettings.explorationRatio,
      })
    } catch {
      settingsOk = false
    }
    if (online) void runSync({ force: true })
    setSyncNote(
      !online
        ? '已本地保存，待联网后自动同步'
        : settingsOk
          ? '已本地保存并加入同步队列（含混合比例）'
          : '已本地保存并加入同步队列；混合比例同步失败，稍后自动重试',
    )
  }

  return (
    <div className="grid gap-4 split:grid-cols-[minmax(0,1fr)_320px] split:items-start">
      <section className="space-y-3">
        <div>
          <h1 className="text-lg font-bold">兴趣主题</h1>
          <p className="text-sm text-[var(--paf-muted)]">
            显式权重优先于行为推断；屏蔽的主题会从 Feed 中过滤。
          </p>
        </div>

        {topicsByCluster.map(([cluster, clusterTopics]) => (
          <div key={cluster} className="space-y-2" data-cluster={cluster}>
            <h2 className="px-1 text-sm font-semibold text-[var(--paf-muted)]">
              {clusterLabel(cluster)}
            </h2>
            {clusterTopics.map((t: TopicRow) => {
              const d = draftFor(t.id)
              const stats = topicStats.get(t.id)
              const inferred = interestByTopic.get(t.id)?.inferredWeight ?? 0
              const overall = effectiveWeight(d.explicitWeight, inferred)
              return (
                <div key={t.id} className="paf-card space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium">{t.name}</span>
                    <label className="flex items-center gap-2 text-sm text-[var(--paf-muted)]">
                      <input
                        type="checkbox"
                        checked={d.blocked}
                        onChange={(e) =>
                          setDraft((prev) => {
                            const next = new Map(prev)
                            next.set(t.id, { ...d, blocked: e.target.checked })
                            return next
                          })
                        }
                      />
                      屏蔽
                    </label>
                  </div>
                  <div className="flex items-center gap-3">
                    <input
                      type="range"
                      min={0}
                      max={1}
                      step={0.1}
                      value={d.explicitWeight}
                      disabled={d.blocked}
                      aria-label={`${t.name} 权重`}
                      onChange={(e) =>
                        setDraft((prev) => {
                          const next = new Map(prev)
                          next.set(t.id, { ...d, explicitWeight: Number(e.target.value) })
                          return next
                        })
                      }
                      className="h-11 flex-1 accent-[var(--paf-accent)]"
                    />
                    <span className="w-8 text-right text-sm tabular-nums">
                      {d.explicitWeight.toFixed(1)}
                    </span>
                  </div>
                  {/* Numeric readout, not colour-only: each answers a question. */}
                  <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs tabular-nums text-[var(--paf-muted)]">
                    <div className="flex items-center gap-1">
                      <dt>你设定</dt>
                      <dd className="font-medium text-[var(--paf-fg,inherit)]">
                        {d.explicitWeight.toFixed(1)}
                      </dd>
                    </div>
                    <div className="flex items-center gap-1">
                      <dt>你的操作推断</dt>
                      <dd className="font-medium">{inferred.toFixed(1)}</dd>
                    </div>
                    <div className="flex items-center gap-1">
                      <dt>实际用于排序</dt>
                      <dd className="font-medium">{overall.toFixed(1)}</dd>
                    </div>
                  </dl>
                  <div className="flex items-center justify-between border-t border-[var(--paf-border)] pt-2 text-xs text-[var(--paf-muted)]">
                    <span>{stats ? `${stats.unread} 未读 / ${stats.total} 条` : '暂无内容'}</span>
                    <span>{d.blocked ? '已屏蔽' : '显示中'}</span>
                  </div>
                </div>
              )
            })}
          </div>
        ))}
        {data.topics.length === 0 ? (
          <EmptyState title="没有主题" description="先回到 Feed 完成 Demo 数据初始化。" />
        ) : null}
      </section>

      {/* 右列：配比面板与探索样本跟着页面吸顶，主题列表长也不走失。 */}
      <div className="space-y-4 split:sticky split:top-4">
      <section className="paf-card space-y-3">
        <h2 className="text-lg font-bold">内容配比</h2>
        <p className="text-sm text-[var(--paf-muted)]">
          每生成 100 条时的分配：
          <span data-mix="preview">
            真实来源 {slots.sourced} 条 · AI 原创 {slots.original} 条 · 探索 {slots.exploration} 条
          </span>
        </p>
        {(
          [
            ['mixSourced', '真实来源扩展（基于已抓取的文章）'],
            ['mixOriginal', 'AI 原创（模型自己写的知识型内容）'],
          ] as const
        ).map(([key, label]) => (
          <label key={key} className="block space-y-1">
            <span className="text-sm">
              {label}：{(draftSettings[key] * 100).toFixed(0)}%
            </span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={draftSettings[key]}
              onChange={(e) =>
                setDraftSettings({ ...draftSettings, [key]: Number(e.target.value) })
              }
              className="h-11 w-full accent-[var(--paf-accent)]"
              aria-label={label}
            />
          </label>
        ))}
        <label className="block space-y-1">
          <span className="text-sm">
            探索比例（虚构与连载，主题偏向你少读的方向）：
            {(draftSettings.explorationRatio * 100).toFixed(0)}%
          </span>
          <input
            type="range"
            min={0}
            max={0.5}
            step={0.05}
            value={draftSettings.explorationRatio}
            onChange={(e) =>
              setDraftSettings({ ...draftSettings, explorationRatio: Number(e.target.value) })
            }
            className="h-11 w-full accent-[var(--paf-accent)]"
            aria-label="探索比例"
          />
        </label>
        {notes.length > 0 ? (
          <ul className="space-y-1">
            {notes.map((n) => (
              <li
                key={n.text}
                data-mix-note={n.tone}
                className={`text-xs ${
                  n.tone === 'warn' ? 'text-amber-600' : 'text-[var(--paf-muted)]'
                }`}
              >
                {n.text}
              </li>
            ))}
          </ul>
        ) : null}
        <button
          type="button"
          onClick={() => void save()}
          className="inline-flex min-h-11 items-center gap-2 rounded-lg bg-[var(--paf-accent)] px-4 text-sm font-medium text-white"
        >
          {savedFlash ? <Check className="size-4" /> : <Save className="size-4" />}
          {savedFlash ? '已保存' : '保存兴趣设置'}
        </button>
        {syncNote ? <p className="text-xs text-[var(--paf-muted)]">{syncNote}</p> : null}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-bold">探索样本</h2>
        <p className="text-sm text-[var(--paf-muted)]">
          探索内容默认 20%，标记“不感兴趣”后会在下一轮降权。
        </p>
        {exploreSample.map((p) => (
          <Link key={p.id} to={`/post/${p.id}`} className="paf-card block">
            <p className="paf-chip mb-1">
              探索 · {data.topics.find((t) => t.id === p.topicId)?.name ?? '未归类主题'}
            </p>
            <p className="font-medium">{p.title}</p>
            <p className="mt-1 line-clamp-3 text-sm text-[var(--paf-muted)]">
              {cardPreview(p).text}
            </p>
          </Link>
        ))}
      </section>
      </div>
    </div>
  )
}
