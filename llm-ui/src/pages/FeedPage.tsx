import { useLiveQuery } from 'dexie-react-hooks'
import { Loader2, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { NoticeBar, type NoticeTone } from '../components/NoticeBar'
import { PostWaterfall } from '../components/PostWaterfall'
import { SkeletonList } from '../components/Skeleton'
import { db, setSetting } from '../db'
import { seedIfNeeded } from '../db/seed'
import type { CommentRow } from '../db/types'
import { commentDigest } from '../lib/comments'
import {
  FEED_FILTERS,
  queryFeed,
  readingCorpus,
  type FeedFilterKey,
} from '../lib/feedQuery'
import { topReason } from '../lib/explain'
import { buildRecentCounts } from '../lib/score'
import { relativeTime } from '../lib/time'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePersonaNames } from '../hooks/usePersonaNames'
import { FEED_PULL_LIMIT, refreshFeed, SYNC_INTERVAL_MS } from '../services/sync'

const PAGE_SIZE = 20

export function FeedPage() {
  const [filter, setFilter] = useState<FeedFilterKey>('all')
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)
  const [seeding, setSeeding] = useState(true)
  const sentinelRef = useRef<HTMLDivElement | null>(null)
  const online = useOnlineStatus()
  const authors = usePersonaNames()

  // 刷新：手动点一次拉最新 50 条，开着页面每 30s 自动来一轮同样的。
  const [refreshing, setRefreshing] = useState(false)
  const [lastRefresh, setLastRefresh] = useState<{ at: string; added: number } | null>(null)
  const [notice, setNotice] = useState<{ tone: NoticeTone; message: string } | null>(null)
  const busyRef = useRef(false)
  const lastErrorRef = useRef<string | null>(null)

  const doRefresh = useCallback(async (source: 'manual' | 'auto') => {
    if (busyRef.current) return
    busyRef.current = true
    setRefreshing(true)
    const result = await refreshFeed(source)
    busyRef.current = false
    setRefreshing(false)
    if (result.status === 'error') {
      const message = `${result.error?.message ?? '刷新失败'}（${result.error?.code ?? 'ERROR'}）`
      // 自动刷新重复撞同一个错就别每 30s 弹一次；手动点永远给反馈。
      if (source === 'manual' || message !== lastErrorRef.current) setNotice({ tone: 'bad', message })
      lastErrorRef.current = message
      return
    }
    lastErrorRef.current = null
    if (source === 'manual' && result.status === 'skipped') {
      setNotice({ tone: 'warn', message: '当前离线，只读本地缓存，没有发起刷新' })
      return
    }
    setLastRefresh({ at: new Date().toISOString(), added: result.added ?? 0 })
    if (source === 'manual' && (result.added ?? 0) > 0) {
      setNotice({
        tone: 'good',
        message: `刷新完成：本页拉回 ${result.limit} 条，其中 ${result.added} 条是新的`,
      })
    }
  }, [])

  useEffect(() => {
    if (!online) return
    const timer = setInterval(() => void doRefresh('auto'), SYNC_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [online, doRefresh])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      await seedIfNeeded()
      if (!cancelled) setSeeding(false)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const data = useLiveQuery(
    async () => {
      const [posts, interests, topics, settings, comments] = await Promise.all([
        db.posts.toArray(),
        db.interests.toArray(),
        db.topics.toArray(),
        db.settings.toArray(),
        db.comments.toArray(),
      ])
      const sortMode =
        (settings.find((s) => s.key === 'sortMode')?.value as 'new' | 'hot' | undefined) ?? 'new'
      return { posts, interests, topics, sortMode, comments }
    },
    [],
    null,
  )

  // 卡上的评论热度只读本地评论表：浏览 Feed 不发起任何生成调用。
  const digests = useMemo(() => {
    const rows = new Map<string, CommentRow[]>()
    for (const c of data?.comments ?? []) {
      rows.set(c.postId, [...(rows.get(c.postId) ?? []), c])
    }
    return new Map([...rows].map(([postId, list]) => [postId, commentDigest(list)]))
  }, [data?.comments])

  const recency = useMemo(
    () => (data ? buildRecentCounts(readingCorpus(data.posts, data.interests), data.topics) : null),
    [data],
  )

  const items = useMemo(() => {
    if (!data) return []
    return queryFeed(data.posts, {
      filter,
      sortMode: data.sortMode,
      interests: data.interests,
      recentTopicCounts: recency?.recentTopicCounts,
      recentClusterCounts: recency?.recentClusterCounts,
      topicClusters: recency?.topicClusters,
    })
  }, [data, filter, recency])

  useEffect(() => {
    const node = sentinelRef.current
    if (!node) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setVisibleCount((c) => (c < items.length ? Math.min(c + PAGE_SIZE, items.length) : c))
        }
      },
      { rootMargin: '600px 0px' },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [items.length])

  const topics = useMemo(
    () => new Map((data?.topics ?? []).map((t) => [t.id, t])),
    [data?.topics],
  )

  // Explainable ranking reasons (hot mode only): pure local computation.
  const explainCtx = useMemo(() => {
    if (!data || !recency) return null
    const recentFeedback = new Map<string, 1 | -1>()
    for (const p of data.posts) {
      if (p.likeState !== 0) recentFeedback.set(p.topicId, p.likeState as 1 | -1)
    }
    return {
      interests: new Map(data.interests.map((i) => [i.topicId, i])),
      recentTopicCounts: recency.recentTopicCounts,
      recentClusterCounts: recency.recentClusterCounts,
      topicClusters: recency.topicClusters,
      recentFeedback,
    }
  }, [data, recency])

  const withReason = data?.sortMode === 'hot' && explainCtx !== null

  const loaded = data !== null && !seeding
  const shown = items.slice(0, visibleCount)

  return (
    <div className="space-y-3">
      <div className="sticky top-0 z-20 -mx-3 border-b border-[var(--paf-border)] bg-[var(--paf-bg)]/95 px-3 backdrop-blur md:-mx-6 md:px-6">
        <div className="flex items-center gap-2">
          <div className="flex min-w-0 flex-1 gap-1 overflow-x-auto">
            {FEED_FILTERS.map((f) => (
              <button
                key={f.key}
                type="button"
                onClick={() => {
                  setFilter(f.key)
                  setVisibleCount(PAGE_SIZE)
                }}
                className={`paf-tab shrink-0 ${filter === f.key ? 'paf-tab-active' : ''}`}
                aria-pressed={filter === f.key}
              >
                {f.label}
              </button>
            ))}
          </div>
          {/* 推特式「最新 / 最热」：排序开关不参与横向滚动，375px 上也得一眼看见。 */}
          <div className="flex shrink-0 items-center gap-1">
            <Link to="/sources" className="paf-chip px-2.5 py-1" title="阅读订阅来源的原文摘录">
              原文
            </Link>
            {(
              [
                ['new', '最新'],
                ['hot', '为你'],
              ] as const
            ).map(([mode, label]) => (
              <button
                key={mode}
                type="button"
                aria-pressed={data?.sortMode === mode}
                onClick={() => void setSetting('sortMode', mode)}
                className={`paf-chip px-2.5 py-1 ${
                  data?.sortMode === mode
                    ? 'border-[var(--paf-accent)] font-semibold text-[var(--paf-accent)]'
                    : ''
                }`}
              >
                {label}
              </button>
            ))}
            <button
              type="button"
              className="paf-icon-btn shrink-0"
              aria-label="刷新最新内容"
              aria-busy={refreshing}
              data-testid="feed-refresh"
              data-pull-limit={FEED_PULL_LIMIT}
              disabled={refreshing || !online}
              onClick={() => void doRefresh('manual')}
            >
              <RefreshCw className={`size-4 ${refreshing ? 'animate-spin' : ''}`} />
            </button>
          </div>
        </div>
      </div>

      {!online ? (
        <p className="paf-chip border-amber-400 text-amber-600">当前离线，展示本地缓存</p>
      ) : null}

      {!loaded ? <SkeletonList count={4} /> : null}

      {loaded && items.length === 0 ? (
        filter === 'all' ? (
          <EmptyState
            title="本地还没有帖子"
            description="可以先浏览订阅原文，或到生成页整理与生成内容。"
            action={
              <button type="button" className="paf-btn" onClick={() => window.location.reload()}>
                <RefreshCw className="size-4" /> 重新加载
              </button>
            }
          />
        ) : (
          <EmptyState
            title="这个筛选下没有内容"
            description={filter === 'sourced'
              ? '已采集的原文可直接在来源页阅读；整理后的来源帖会出现在这里。'
              : '换一个筛选条件，或者去发现页调整兴趣权重。'}
            action={
              <Link to={filter === 'sourced' ? '/sources' : '/discover'} className="paf-btn">
                {filter === 'sourced' ? '浏览原文' : '去发现页'}
              </Link>
            }
          />
        )
      ) : null}

      <PostWaterfall
        posts={shown}
        topics={topics}
        authors={authors}
        reasonOf={withReason && explainCtx ? (post) => topReason(post, explainCtx) : undefined}
        commentsOf={(post) => digests.get(post.id)}
      />

      <p className="pt-1 text-center text-xs text-[var(--paf-muted)]" data-testid="feed-refresh-line">
        已显示 {shown.length} / 共 {items.length} 条 · 全部来自本地 IndexedDB
        {lastRefresh
          ? ` · 上次刷新 ${relativeTime(lastRefresh.at)}，本页拉回 ${FEED_PULL_LIMIT} 条、新增 ${lastRefresh.added} 条`
          : ''}
      </p>

      {visibleCount < items.length ? (
        <div ref={sentinelRef} className="flex items-center justify-center py-4">
          <Loader2 className="size-5 animate-spin text-[var(--paf-muted)]" aria-label="加载更多" />
        </div>
      ) : items.length > 0 ? (
        <p className="py-4 text-center text-sm text-[var(--paf-muted)]">已经到底了</p>
      ) : null}

      {notice ? (
        <NoticeBar tone={notice.tone} message={notice.message} onClose={() => setNotice(null)} />
      ) : null}
    </div>
  )
}
