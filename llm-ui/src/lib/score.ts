import type { InterestRow, PostRow, TopicRow } from '../db/types'
import { FALLBACK_CLUSTER, type TopicCluster } from './curation'
import { parseTime } from './time'

export interface ScoreContext {
  interests: Map<string, InterestRow>
  /** evaluation time; omit to use the current time (avoids impure Date.now in render) */
  now?: number
  /** how many posts of each topic the reader already saw in this session */
  recentTopicCounts: Map<string, number>
  /** how many posts of each interest cluster the reader already saw */
  recentClusterCounts: Map<string, number>
  /** topic id → cluster id; missing topics fall back to ``general`` */
  topicClusters?: Map<string, TopicCluster>
}

export const SCORE_WEIGHTS = {
  interest: 0.45,
  quality: 0.2,
  novelty: 0.05,
  diversity: 0.15,
  freshness: 0.1,
  unread: 0.05,
} as const

/** Per-topic repetition weight (unchanged from the B04 MVP score). */
const TOPIC_PENALTY = 0.12
/** Gentler cross-cluster penalty — stops a whole subject area flooding the feed. */
const CLUSTER_PENALTY = 0.06

/**
 * Server ``effective_weight`` (profile.py): explicit weight dominates, a
 * decayed behaviour term nudges it by at most ±0.5·inferred, clamped to
 * 0.01–1.0. The interest DTO carries the already-decayed inferred value.
 */
export function effectiveWeight(explicit: number, inferred: number): number {
  return Math.min(1, Math.max(0.01, explicit + 0.5 * inferred))
}

export function clusterOf(post: PostRow, ctx: ScoreContext): TopicCluster {
  return ctx.topicClusters?.get(post.topicId) ?? FALLBACK_CLUSTER
}

export function interestScore(post: PostRow, ctx: ScoreContext): number {
  const row = ctx.interests.get(post.topicId)
  if (!row) return 0.5
  return effectiveWeight(row.explicitWeight, row.inferredWeight)
}

export function freshnessScore(createdAt: string, now: number): number {
  const timestamp = parseTime(createdAt)
  if (!Number.isFinite(timestamp)) return 0.5
  const ageDays = Math.max(0, (now - timestamp) / 86_400_000)
  return Math.max(0, 1 - ageDays / 14)
}

export function noveltyScore(post: PostRow): number {
  return post.tags.includes('explore') ? 1 : 0.35
}

export function diversityPenalty(post: PostRow, ctx: ScoreContext): number {
  const topic = ctx.recentTopicCounts.get(post.topicId) ?? 0
  const cluster = ctx.recentClusterCounts.get(clusterOf(post, ctx)) ?? 0
  return -TOPIC_PENALTY * topic - CLUSTER_PENALTY * cluster
}

export function penaltyScore(post: PostRow): number {
  let penalty = 0
  if (post.lastReadAt) penalty += 0.15
  if (post.likeState === -1) penalty += 0.25
  return penalty
}

/**
 * MVP score from docs/07:
 * 0.45*interest + 0.20*quality + 0.05*novelty + 0.15*diversity + 0.10*freshness
 * + 0.05*unread_bonus - penalties
 */
export function scorePost(post: PostRow, ctx: ScoreContext): number {
  const interest = interestScore(post, ctx)
  const quality = Math.min(1, Math.max(0, post.qualityScore))
  const novelty = noveltyScore(post)
  const diversity = 0.5 + diversityPenalty(post, ctx)
  const freshness = freshnessScore(post.publishedAt ?? post.createdAt, ctx.now ?? Date.now())
  const unread = post.lastReadAt ? 0 : 1
  const raw =
    SCORE_WEIGHTS.interest * interest +
    SCORE_WEIGHTS.quality * quality +
    SCORE_WEIGHTS.novelty * novelty +
    SCORE_WEIGHTS.diversity * Math.max(0, diversity) +
    SCORE_WEIGHTS.freshness * freshness +
    SCORE_WEIGHTS.unread * unread -
    penaltyScore(post)
  return Math.max(0, Math.min(1, raw))
}

/** Rank posts with the explainable score, keeping diversity under control. */
export function rankPosts(posts: PostRow[], ctx: ScoreContext): PostRow[] {
  const remaining = [...posts].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
  const ranked: PostRow[] = []
  const counts = new Map(ctx.recentTopicCounts)
  const clusterCounts = new Map(ctx.recentClusterCounts)
  while (remaining.length) {
    let bestIndex = 0
    let bestScore = -Infinity
    for (let i = 0; i < remaining.length; i++) {
      const s = scorePost(remaining[i], {
        ...ctx,
        recentTopicCounts: counts,
        recentClusterCounts: clusterCounts,
      })
      if (s > bestScore) {
        bestScore = s
        bestIndex = i
      }
    }
    const [picked] = remaining.splice(bestIndex, 1)
    // Keep repetition local. Lifetime accumulation saturates every cluster's
    // penalty and makes diversity disappear halfway down a large feed.
    for (const [key, value] of counts) counts.set(key, value * 0.8)
    for (const [key, value] of clusterCounts) clusterCounts.set(key, value * 0.8)
    counts.set(picked.topicId, (counts.get(picked.topicId) ?? 0) + 1)
    const cluster = clusterOf(picked, ctx)
    clusterCounts.set(cluster, (clusterCounts.get(cluster) ?? 0) + 1)
    ranked.push(picked)
  }
  return ranked
}

/**
 * Build the recency maps the dead ``new Map()`` used to stub out (B09).
 * Derived from the reader's own read history in the local DB, so repetition
 * control works entirely offline. Also resolves topic → cluster for scoring.
 */
export function buildRecentCounts(
  posts: PostRow[],
  topics: TopicRow[],
  limit = 8,
): {
  topicClusters: Map<string, TopicCluster>
  recentTopicCounts: Map<string, number>
  recentClusterCounts: Map<string, number>
} {
  const topicClusters = new Map<string, TopicCluster>()
  for (const t of topics) topicClusters.set(t.id, t.cluster ?? FALLBACK_CLUSTER)

  const recentTopicCounts = new Map<string, number>()
  const recentClusterCounts = new Map<string, number>()
  const read = posts
    .filter((p) => p.lastReadAt)
    .sort((a, b) => (a.lastReadAt! < b.lastReadAt! ? 1 : -1))
    .slice(0, limit)
  for (const p of read) {
    recentTopicCounts.set(p.topicId, (recentTopicCounts.get(p.topicId) ?? 0) + 1)
    const cluster = topicClusters.get(p.topicId) ?? FALLBACK_CLUSTER
    recentClusterCounts.set(cluster, (recentClusterCounts.get(cluster) ?? 0) + 1)
  }
  return { topicClusters, recentTopicCounts, recentClusterCounts }
}
