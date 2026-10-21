import type { InterestRow, PostRow } from '../db/types'
import { rankPosts, type ScoreContext } from './score'

export type FeedFilterKey =
  | 'all'
  | 'unread'
  | 'sourced'
  | 'fiction'
  | 'explainer'
  | 'serial'
  | 'explore'

export const FEED_FILTERS: { key: FeedFilterKey; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'unread', label: '未读' },
  { key: 'sourced', label: '真实' },
  { key: 'fiction', label: '虚构' },
  { key: 'explainer', label: 'AI 知识' },
  { key: 'serial', label: '连载' },
  { key: 'explore', label: '探索' },
]

export function isVisible(post: PostRow): boolean {
  return post.status === 'published' && !post.hidden
}

export function matchesFilter(post: PostRow, filter: FeedFilterKey): boolean {
  switch (filter) {
    case 'all':
      return true
    case 'unread':
      return !post.lastReadAt
    case 'sourced':
      return post.kind === 'sourced'
    case 'fiction':
      return post.kind === 'fiction'
    case 'explainer':
      return post.kind === 'synthetic_explainer'
    case 'serial':
      return post.kind === 'serial'
    case 'explore':
      return post.tags.includes('explore')
  }
}

export function searchPosts(posts: PostRow[], query: string): PostRow[] {
  const q = query.trim().toLowerCase()
  if (!q) return posts
  return posts.filter(
    (p) =>
      p.title.toLowerCase().includes(q) ||
      p.body.toLowerCase().includes(q) ||
      p.topicId.toLowerCase().includes(q),
  )
}

export interface FeedQueryOptions {
  filter: FeedFilterKey
  sortMode: 'new' | 'hot'
  interests: InterestRow[]
  now?: number
  /** B09 session recency — wired from the local read history by the caller. */
  recentTopicCounts?: ScoreContext['recentTopicCounts']
  recentClusterCounts?: ScoreContext['recentClusterCounts']
  topicClusters?: ScoreContext['topicClusters']
}

/** The same reading corpus powers the feed, unread badges and topic rail. */
export function readingCorpus(posts: PostRow[], interests: InterestRow[] = []): PostRow[] {
  const blocked = new Set(interests.filter((i) => i.blocked).map((i) => i.topicId))
  const eligible = posts.filter((p) => isVisible(p) && !blocked.has(p.topicId))
  return posts.some((p) => !p.demo) ? eligible.filter((p) => !p.demo) : eligible
}

/** Pure feed query: visibility → blocked topics → filter → sort. No network, no LLM. */
export function queryFeed(posts: PostRow[], opts: FeedQueryOptions): PostRow[] {
  const visible = readingCorpus(posts, opts.interests).filter((p) => matchesFilter(p, opts.filter))
  if (opts.sortMode === 'new') {
    return [...visible].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
  }
  const interests = new Map(opts.interests.map((i) => [i.topicId, i]))
  return rankPosts(visible, {
    interests,
    now: opts.now ?? Date.now(),
    recentTopicCounts: opts.recentTopicCounts ?? new Map(),
    recentClusterCounts: opts.recentClusterCounts ?? new Map(),
    topicClusters: opts.topicClusters,
  })
}

export function unreadCount(posts: PostRow[], interests: InterestRow[] = []): number {
  return readingCorpus(posts, interests).filter((p) => !p.lastReadAt).length
}

export function savedCount(posts: PostRow[]): number {
  return posts.filter((p) => p.saved && p.status === 'published').length
}
