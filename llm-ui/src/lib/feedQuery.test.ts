import { describe, expect, it } from 'vitest'

import type { InterestRow, PostRow } from '../db/types'
import { matchesFilter, queryFeed, unreadCount, isVisible } from './feedQuery'

function makePost(overrides: Partial<PostRow> = {}): PostRow {
  return {
    id: 'p1',
    kind: 'fiction',
    title: 'Title',
    body: 'Body',
    summary: 'Body',
    topicId: 'rust-gui',
    personaId: null,
    sourceId: null,
    sourceUrl: null,
    sourceTitle: null,
    publishedAt: null,
    fetchedAt: null,
    seriesId: null,
    status: 'published',
    qualityScore: 0.8,
    tags: ['rust-gui'],
    demo: true,
    fingerprint: 'fp1',
    createdAt: '2026-10-10T00:00:00Z',
    revision: 1,
    saved: false,
    hidden: false,
    likeState: 0,
    lastReadAt: null,
    readProgress: 0,
    ...overrides,
  }
}

function interest(topicId: string, weight: number, blocked = false): InterestRow {
  return {
    topicId,
    explicitWeight: weight,
    inferredWeight: 0,
    blocked,
    updatedAt: '2026-10-10T00:00:00Z',
  }
}

describe('feedQuery', () => {
  it('keeps demo content out of a populated personal feed without deleting it', () => {
    const posts = [makePost({ id: 'demo' }), makePost({ id: 'real', demo: false })]
    expect(queryFeed(posts, { filter: 'all', sortMode: 'hot', interests: [] }).map(p => p.id)).toEqual(['real'])
    expect(posts).toHaveLength(2)
    expect(queryFeed([posts[0]], { filter: 'all', sortMode: 'hot', interests: [] })).toHaveLength(1)
  })

  it('unread badges ignore demos and blocked topics, without reviving demos when all real posts are blocked', () => {
    const posts = [makePost({ id: 'demo' }), makePost({ id: 'real', demo: false }),
      makePost({ id: 'blocked', topicId: 'tea-culture', demo: false })]
    expect(unreadCount(posts, [interest('tea-culture', 1, true)])).toBe(1)
    expect(queryFeed(posts, { filter: 'all', sortMode: 'hot',
      interests: [interest('tea-culture', 1, true), interest('rust-gui', 1, true)] })).toEqual([])
  })
  it('filters hidden and deleted posts from the visible feed', () => {
    const posts = [
      makePost({ id: 'a' }),
      makePost({ id: 'b', hidden: true }),
      makePost({ id: 'c', status: 'deleted' }),
    ]
    const result = queryFeed(posts, {
      filter: 'all',
      sortMode: 'new',
      interests: [],
    })
    expect(result.map((p) => p.id)).toEqual(['a'])
  })

  it('supports unread / kind / explore filters', () => {
    const posts = [
      makePost({ id: 'read', lastReadAt: '2026-10-10T01:00:00Z' }),
      makePost({ id: 'sourced', kind: 'sourced' }),
      makePost({ id: 'explore', tags: ['explore'] }),
    ]
    expect(matchesFilter(posts[0], 'unread')).toBe(false)
    expect(matchesFilter(posts[1], 'sourced')).toBe(true)
    expect(matchesFilter(posts[2], 'explore')).toBe(true)
  })

  it('sorts by createdAt desc in new mode', () => {
    const posts = [
      makePost({ id: 'old', createdAt: '2026-10-01T00:00:00Z' }),
      makePost({ id: 'new', createdAt: '2026-10-09T00:00:00Z' }),
    ]
    const result = queryFeed(posts, { filter: 'all', sortMode: 'new', interests: [] })
    expect(result.map((p) => p.id)).toEqual(['new', 'old'])
  })

  it('ranks higher-interest unread posts first in hot mode', () => {
    const posts = [
      makePost({ id: 'low', topicId: 'tea-culture', createdAt: '2026-10-09T00:00:00Z' }),
      makePost({ id: 'high', topicId: 'rust-gui', createdAt: '2026-10-09T00:00:00Z' }),
    ]
    const result = queryFeed(posts, {
      filter: 'all',
      sortMode: 'hot',
      interests: [interest('rust-gui', 1), interest('tea-culture', 0.1)],
      now: Date.parse('2026-10-10T00:00:00Z'),
    })
    expect(result[0].id).toBe('high')
  })

  it('excludes blocked topics from the feed (B04)', () => {
    const posts = [
      makePost({ id: 'ok', topicId: 'rust-gui' }),
      makePost({ id: 'blocked', topicId: 'tea-culture' }),
    ]
    const result = queryFeed(posts, {
      filter: 'all',
      sortMode: 'new',
      interests: [interest('tea-culture', 1, true)],
    })
    expect(result.map((p) => p.id)).toEqual(['ok'])
  })

  it('unreadCount ignores hidden posts', () => {
    const posts = [makePost({ id: 'a' }), makePost({ id: 'b', hidden: true })]
    expect(unreadCount(posts)).toBe(1)
    expect(isVisible(posts[1])).toBe(false)
  })
})
