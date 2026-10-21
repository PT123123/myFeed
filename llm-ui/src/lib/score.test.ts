import { describe, expect, it } from 'vitest'

import type { InterestRow, PostRow, TopicRow } from '../db/types'
import {
  buildRecentCounts,
  clusterOf,
  diversityPenalty,
  effectiveWeight,
  interestScore,
  rankPosts,
  scorePost,
  freshnessScore,
} from './score'
import type { TopicCluster } from './curation'

function makePost(overrides: Partial<PostRow> = {}): PostRow {
  return {
    id: 'p',
    kind: 'synthetic_explainer',
    title: 'T',
    body: 'B',
    summary: 'B',
    topicId: 'llm-eng',
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
    demo: true,
    fingerprint: 'fp',
    createdAt: '2026-10-09T00:00:00Z',
    revision: 1,
    saved: false,
    hidden: false,
    likeState: 0,
    lastReadAt: null,
    readProgress: 0,
    ...overrides,
  }
}

function interest(topicId: string, weight: number, inferred = 0): InterestRow {
  return {
    topicId,
    explicitWeight: weight,
    inferredWeight: inferred,
    blocked: false,
    updatedAt: '2026-10-10T00:00:00Z',
  }
}

function topic(id: string, cluster: TopicCluster): TopicRow {
  return { id, slug: id, name: id, parentId: null, cluster }
}

const NOW = Date.parse('2026-10-10T00:00:00Z')

describe('scorePost', () => {
  it('uses original publication date for re-fetched sources and tolerates invalid dates', () => {
    const ctx = { interests: new Map(), now: NOW, recentTopicCounts: new Map(), recentClusterCounts: new Map() }
    const oldSource = makePost({ createdAt: new Date(NOW).toISOString(), publishedAt: '2020-01-01' })
    expect(scorePost(oldSource, ctx)).toBeLessThan(scorePost(makePost({ createdAt: new Date(NOW).toISOString() }), ctx))
    expect(freshnessScore('bad-date', NOW)).toBe(.5)
  })

  it('exploration bonus does not outweigh a clear explicit preference', () => {
    const ctx = { interests: new Map([['fav', interest('fav', .9)], ['other', interest('other', .6)]]),
      now: NOW, recentTopicCounts: new Map(), recentClusterCounts: new Map() }
    expect(scorePost(makePost({ topicId: 'fav' }), ctx))
      .toBeGreaterThan(scorePost(makePost({ topicId: 'other', tags: ['explore'] }), ctx))
  })
  it('rewards interest, quality and unread', () => {
    const ctx = {
      interests: new Map([
        ['llm-eng', interest('llm-eng', 1)],
        ['tea-culture', interest('tea-culture', 0)],
      ]),
      now: NOW,
      recentTopicCounts: new Map<string, number>(),
      recentClusterCounts: new Map<string, number>(),
    }
    const hot = scorePost(makePost(), ctx)
    const cold = scorePost(makePost({ topicId: 'tea-culture', qualityScore: 0.2 }), ctx)
    const read = scorePost(makePost({ lastReadAt: '2026-10-09T00:00:00Z' }), ctx)
    expect(hot).toBeGreaterThan(cold)
    expect(hot).toBeGreaterThan(read)
  })

  it('decays freshness over two weeks', () => {
    const ctx = {
      interests: new Map<string, InterestRow>(),
      now: NOW,
      recentTopicCounts: new Map<string, number>(),
      recentClusterCounts: new Map<string, number>(),
    }
    const fresh = scorePost(makePost({ createdAt: '2026-10-10T00:00:00Z' }), ctx)
    const stale = scorePost(makePost({ createdAt: '2026-09-01T00:00:00Z' }), ctx)
    expect(fresh).toBeGreaterThan(stale)
  })

  it('stays within [0, 1]', () => {
    const ctx = {
      interests: new Map<string, InterestRow>(),
      now: NOW,
      recentTopicCounts: new Map([['llm-eng', 5]]),
      recentClusterCounts: new Map<string, number>(),
    }
    const s = scorePost(makePost({ likeState: -1, lastReadAt: 'x' }), ctx)
    expect(s).toBeGreaterThanOrEqual(0)
    expect(s).toBeLessThanOrEqual(1)
  })
})

describe('rankPosts diversity', () => {
  it('spreads consecutive posts of the same topic', () => {
    const posts = [
      makePost({ id: 'a', topicId: 'x', createdAt: '2026-10-09T00:00:00Z' }),
      makePost({ id: 'b', topicId: 'x', createdAt: '2026-10-08T00:00:00Z' }),
      makePost({ id: 'c', topicId: 'y', createdAt: '2026-10-07T00:00:00Z' }),
    ]
    const ranked = rankPosts(posts, {
      interests: new Map([
        ['x', interest('x', 0.9)],
        ['y', interest('y', 0.9)],
      ]),
      now: NOW,
      recentTopicCounts: new Map(),
      recentClusterCounts: new Map(),
    })
    // After picking one of topic x, the other x gets a diversity penalty,
    // so topic y should be able to outrank it.
    expect(ranked).toHaveLength(3)
    expect(new Set(ranked.map((p) => p.id)).size).toBe(3)
    expect(ranked[2].id).toBe('b')
  })

  it('interleaves interest clusters instead of flooding one subject area', () => {
    const same = { createdAt: '2026-10-09T00:00:00Z', qualityScore: 0.7 }
    const posts = [
      makePost({ id: 'a1', topicId: 'x1', ...same }),
      makePost({ id: 'a2', topicId: 'x2', ...same }),
      makePost({ id: 'b1', topicId: 'y1', ...same }),
      makePost({ id: 'b2', topicId: 'y2', ...same }),
    ]
    const interests = new Map<string, InterestRow>(
      ['x1', 'x2', 'y1', 'y2'].map((id) => [id, interest(id, 0.5)]),
    )
    const clusters = new Map<string, TopicCluster>([
      ['x1', 'ai-software'],
      ['x2', 'ai-software'],
      ['y1', 'music-language'],
      ['y2', 'music-language'],
    ])
    const ctx = {
      interests,
      now: NOW,
      recentTopicCounts: new Map<string, number>(),
      recentClusterCounts: new Map<string, number>(),
      topicClusters: clusters,
    }
    // Every topic appears once, so only the cluster term can separate them:
    // the second post of a cluster must fall behind the first of the other.
    expect(rankPosts(posts, ctx).map((p) => clusterOf(p, ctx))).toEqual([
      'ai-software',
      'music-language',
      'ai-software',
      'music-language',
    ])
    // Without the topic→cluster map every post looks like the same cluster, so
    // the penalty cannot steer anything and the list stays in input order.
    const blind = rankPosts(posts, { ...ctx, topicClusters: undefined })
    expect(blind.map((p) => p.id)).toEqual(['a1', 'a2', 'b1', 'b2'])
  })
})

describe('effectiveWeight', () => {
  it('mirrors the server: explicit dominates, result is clamped', () => {
    expect(effectiveWeight(0.2, 0.4)).toBeCloseTo(0.4)
    expect(effectiveWeight(0.9, -0.4)).toBeCloseTo(0.7)
    expect(effectiveWeight(0.02, -0.4)).toBeCloseTo(0.01)
    expect(effectiveWeight(1, 0.4)).toBe(1)
  })

  it('carries the behaviour term into the feed score', () => {
    const ctx = {
      interests: new Map<string, InterestRow>([
        ['llm-eng', interest('llm-eng', 0.5, 0.4)],
        ['tea-culture', interest('tea-culture', 0.5, -0.4)],
      ]),
      now: NOW,
      recentTopicCounts: new Map<string, number>(),
      recentClusterCounts: new Map<string, number>(),
    }
    expect(interestScore(makePost(), ctx)).toBeCloseTo(0.7)
    expect(interestScore(makePost({ topicId: 'tea-culture' }), ctx)).toBeCloseTo(0.3)
    // A topic the reader never configured stays neutral.
    expect(interestScore(makePost({ topicId: 'unknown' }), ctx)).toBe(0.5)
  })
})

describe('diversityPenalty', () => {
  const clusters = new Map<string, TopicCluster>([['llm-eng', 'ai-software']])

  it('charges both the topic and its cluster', () => {
    const ctx = {
      interests: new Map<string, InterestRow>(),
      now: NOW,
      recentTopicCounts: new Map([['llm-eng', 1]]),
      recentClusterCounts: new Map([['ai-software', 1]]),
      topicClusters: clusters,
    }
    expect(diversityPenalty(makePost(), ctx)).toBeCloseTo(-0.18)
  })

  it('still charges a new topic that repeats a just-seen cluster', () => {
    const ctx = {
      interests: new Map<string, InterestRow>(),
      now: NOW,
      recentTopicCounts: new Map<string, number>(),
      recentClusterCounts: new Map([['ai-software', 3]]),
      topicClusters: clusters,
    }
    expect(diversityPenalty(makePost(), ctx)).toBeCloseTo(-0.18)
  })
})

describe('buildRecentCounts', () => {
  const posts = [
    makePost({ id: 'r1', topicId: 'x', lastReadAt: '2026-10-08T00:00:00Z' }),
    makePost({ id: 'r2', topicId: 'x', lastReadAt: '2026-10-09T00:00:00Z' }),
    makePost({ id: 'r3', topicId: 'z', lastReadAt: '2026-10-10T00:00:00Z' }),
    makePost({ id: 'unread', topicId: 'x', lastReadAt: null }),
  ]

  it('counts only what the reader actually read, newest first', () => {
    const counts = buildRecentCounts(posts, [topic('x', 'ai-software'), topic('z', 'general')])
    expect(counts.recentTopicCounts.get('x')).toBe(2)
    expect(counts.recentTopicCounts.get('z')).toBe(1)
    expect(counts.recentClusterCounts.get('ai-software')).toBe(2)
  })

  it('maps topic→cluster and folds unknown topics into 其他', () => {
    const counts = buildRecentCounts(posts, [topic('x', 'ai-software')])
    expect(counts.topicClusters.get('x')).toBe('ai-software')
    expect(counts.recentClusterCounts.get('general')).toBe(1)
  })

  it('caps the window so old reading history stops penalising', () => {
    const many = Array.from({ length: 12 }, (_, i) =>
      makePost({
        id: `p${i}`,
        topicId: 'x',
        lastReadAt: new Date(Date.parse('2026-10-01T00:00:00Z') + i * 3_600_000).toISOString(),
      }),
    )
    const counts = buildRecentCounts(many, [topic('x', 'ai-software')], 4)
    expect(counts.recentTopicCounts.get('x')).toBe(4)
  })
})
