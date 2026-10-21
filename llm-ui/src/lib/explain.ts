import type { InterestRow, PostRow } from '../db/types'
import type { ScoreContext } from './score'
import { clusterOf, effectiveWeight, freshnessScore, interestScore } from './score'
import { clusterLabel } from './curation'

export interface ExplainContext extends ScoreContext {
  /** topics the reader recently liked/disliked — behavior feedback (docs/07 §3) */
  recentFeedback?: Map<string, 1 | -1>
}

/**
 * Human-readable "why recommended" reasons (docs/07 §5).
 * Ordered from strongest signal to weakest; never empty for a visible post.
 */
export function explainRecommendation(post: PostRow, ctx: ExplainContext): string[] {
  const reasons: string[] = []
  const row = ctx.interests.get(post.topicId) as InterestRow | undefined

  if (row?.blocked) {
    return ['主题已屏蔽，不会进入推荐']
  }

  if (row) {
    const weight = effectiveWeight(row.explicitWeight, row.inferredWeight)
    if (row.explicitWeight >= 0.7) {
      reasons.push(`当前主题的显式权重为 ${row.explicitWeight.toFixed(1)}`)
    } else if (weight > 0) {
      reasons.push(`匹配你的兴趣权重 ${weight.toFixed(1)}`)
    }
  } else {
    reasons.push('尚未设置该主题的兴趣，使用默认权重')
  }

  // Cluster readout (B09) — only when we can actually resolve the topic.
  if (ctx.topicClusters?.has(post.topicId)) {
    reasons.push(`这条归入「${clusterLabel(clusterOf(post, ctx))}」方向`)
    const clusterSeen = ctx.recentClusterCounts.get(clusterOf(post, ctx)) ?? 0
    if (clusterSeen >= 2) {
      reasons.push(`同一方向近期已出现 ${clusterSeen} 条（已跨簇降权，避免同温层）`)
    }
  }

  // Depth preference readout (B09): the number answers "够不够深".
  const bias = row?.depthBias
  if (bias != null && Math.abs(bias) >= 0.1) {
    reasons.push(
      bias > 0
        ? '你反馈过内容偏浅，已偏向更详细'
        : '你反馈过内容太专业，已偏向更通俗',
    )
  }

  const feedback = ctx.recentFeedback?.get(post.topicId)
  if (feedback === 1) reasons.push('你最近给同主题内容点过赞')
  if (feedback === -1) reasons.push('你不喜欢过同主题内容（已降权）')

  if (post.tags.includes('explore')) {
    reasons.push('探索配额：给你看一个较少刷到的主题')
  }

  const seen = ctx.recentTopicCounts.get(post.topicId) ?? 0
  if (seen >= 2) reasons.push(`同主题近期已读 ${seen} 条（已适度降权防刷屏）`)

  const freshness = freshnessScore(post.publishedAt ?? post.createdAt, ctx.now ?? Date.now())
  if (freshness > 0.85) reasons.push('刚刚发布的新内容')
  if (!post.lastReadAt) reasons.push('还未读过')
  if (post.qualityScore >= 0.8) reasons.push('质量分较高')

  if (reasons.length === 0) {
    reasons.push(`综合评分 ${(interestScore(post, ctx) * 0.35).toFixed(2)} · 默认推荐`)
  }
  return reasons
}

/** Short single-line summary for feed cards. */
export function topReason(post: PostRow, ctx: ExplainContext): string {
  return explainRecommendation(post, ctx)[0] ?? ''
}
