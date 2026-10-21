import { describe, expect, it } from 'vitest'

import type { InterestRow, PostRow } from '../db/types'
import { explainRecommendation, topReason } from './explain'

function post(overrides: Partial<PostRow> = {}): PostRow {
  return {
    id: 'p1',
    kind: 'fiction',
    title: '标题',
    body: '正文',
    summary: '摘要',
    topicId: 'rust-gui',
    personaId: null,
    sourceId: null,
    sourceUrl: null,
    sourceTitle: null,
    publishedAt: null,
    fetchedAt: null,
    seriesId: null,
    status: 'published',
    qualityScore: 0.7,
    tags: [],
    demo: false,
    fingerprint: 'fp',
    createdAt: new Date().toISOString(),
    revision: 1,
    saved: false,
    hidden: false,
    likeState: 0,
    lastReadAt: null,
    readProgress: 0,
    ...overrides,
  }
}

function interest(overrides: Partial<InterestRow> = {}): InterestRow {
  return {
    topicId: 'rust-gui',
    explicitWeight: 0.5,
    inferredWeight: 0,
    blocked: false,
    updatedAt: new Date().toISOString(),
    ...overrides,
  }
}

function ctx(
  interests: InterestRow[],
  extra: Partial<{ recentTopicCounts: Map<string, number>; recentFeedback: Map<string, 1 | -1>; now: number }> = {},
): Parameters<typeof explainRecommendation>[1] {
  return {
    interests: new Map(interests.map((i) => [i.topicId, i])),
    now: extra.now ?? Date.now(),
    recentTopicCounts: extra.recentTopicCounts ?? new Map(),
    recentClusterCounts: new Map<string, number>(),
    recentFeedback: extra.recentFeedback,
  }
}

describe('explainRecommendation', () => {
  it('explains explicit high weight', () => {
    const reasons = explainRecommendation(post(), ctx([interest({ explicitWeight: 0.9 })]))
    expect(reasons[0]).toContain('0.9')
    expect(reasons[0]).toContain('权重')
  })

  it('flags exploration quota posts', () => {
    const reasons = explainRecommendation(
      post({ tags: ['explore'] }),
      ctx([interest()]),
    )
    expect(reasons.some((r) => r.includes('探索'))).toBe(true)
  })

  it('mentions recent behavior feedback', () => {
    const reasons = explainRecommendation(
      post(),
      ctx([interest()], { recentFeedback: new Map([['rust-gui', 1 as const]]) }),
    )
    expect(reasons.some((r) => r.includes('赞'))).toBe(true)
  })

  it('explains diversity penalty for repeated topics', () => {
    const reasons = explainRecommendation(
      post(),
      ctx([interest()], { recentTopicCounts: new Map([['rust-gui', 3]]) }),
    )
    expect(reasons.some((r) => r.includes('近期已读 3 条'))).toBe(true)
  })

  it('blocked topic is explained as filtered out', () => {
    const reasons = explainRecommendation(post(), ctx([interest({ blocked: true })]))
    expect(reasons).toEqual(['主题已屏蔽，不会进入推荐'])
  })

  it('always yields at least one reason', () => {
    const reasons = explainRecommendation(post(), ctx([]))
    expect(reasons.length).toBeGreaterThan(0)
    expect(topReason(post(), ctx([]))).toBe(reasons[0])
  })

  it('marks fresh unread content', () => {
    const reasons = explainRecommendation(post(), ctx([]))
    expect(reasons.some((r) => r.includes('未读'))).toBe(true)
  })
})
