import { useLiveQuery } from 'dexie-react-hooks'
import { Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { PostWaterfall } from '../components/PostWaterfall'
import { db } from '../db'
import type { CommentRow } from '../db/types'
import { commentDigest } from '../lib/comments'
import { searchPosts } from '../lib/feedQuery'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePersonaNames } from '../hooks/usePersonaNames'

export function SavedPage() {
  const [query, setQuery] = useState('')
  const online = useOnlineStatus()
  const authors = usePersonaNames()

  const data = useLiveQuery(
    async () => {
      const [posts, topics, comments] = await Promise.all([
        db.posts.toArray(),
        db.topics.toArray(),
        db.comments.toArray(),
      ])
      return { posts, topics, comments }
    },
    [],
    null,
  )

  const saved = useMemo(() => {
    if (!data) return []
    const list = data.posts.filter((p) => p.saved && p.status === 'published')
    return searchPosts(list, query)
  }, [data, query])

  const digests = useMemo(() => {
    const rows = new Map<string, CommentRow[]>()
    for (const c of data?.comments ?? []) {
      rows.set(c.postId, [...(rows.get(c.postId) ?? []), c])
    }
    return new Map([...rows].map(([postId, list]) => [postId, commentDigest(list)]))
  }, [data?.comments])

  const topics = useMemo(() => new Map((data?.topics ?? []).map((t) => [t.id, t])), [data])

  if (data === null) {
    return <p className="paf-card text-sm text-[var(--paf-muted)]">加载收藏…</p>
  }

  return (
    <div className="space-y-3">
      <div>
        <h1 className="text-lg font-bold">收藏</h1>
        <p className="text-sm text-[var(--paf-muted)]">
          收藏保存在本地 IndexedDB，离线可用 · 共 {saved.length} 条
          {!online ? '（离线中）' : ''}
        </p>
      </div>

      <label className="relative block">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-[var(--paf-muted)]" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜索标题或正文"
          className="paf-input pl-9"
          aria-label="搜索收藏"
        />
      </label>

      {saved.length === 0 ? (
        query ? (
          <EmptyState title="没有匹配的收藏" description="试试其它关键词。" />
        ) : (
          <EmptyState
            title="还没有收藏"
            description="在 Feed 或详情页点击“收藏”，内容会立刻保存到本地。"
            action={
              <Link to="/" className="paf-btn">
                去刷 Feed
              </Link>
            }
          />
        )
      ) : (
        <PostWaterfall
          posts={saved}
          topics={topics}
          authors={authors}
          commentsOf={(post) => digests.get(post.id)}
        />
      )}
    </div>
  )
}
