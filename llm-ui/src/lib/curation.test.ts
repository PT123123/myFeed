import { describe, expect, it } from 'vitest'

import {
  CLUSTERS,
  CLUSTER_LABELS,
  CONTENT_TYPE_LABELS,
  DISLIKE_REASONS,
  EVIDENCE_LABELS,
  FEEDBACK_REASON_LABELS,
  LIKE_REASONS,
  PRIORITY_LABELS,
  SUGGESTED_ACTION_LABELS,
  clusterLabel,
  contentTypeLabel,
  evidenceLabel,
  gradeStrength,
  priorityLabel,
  reasonAction,
  suggestedActionLabel,
  type EvidenceGrade,
  type FeedbackReason,
} from './curation'

/**
 * These maps must stay byte-identical with `apps/server/app/services/profile.py`
 * (EVIDENCE_LABELS / CONTENT_TYPES / SUGGESTED_ACTIONS / CLUSTERS). The server
 * owns the values; this file is the drift gate — if a label changes there and
 * not here, the badge and the prompt stop telling the same story.
 */
const SERVER_EVIDENCE: Record<EvidenceGrade, string> = {
  systematic_review: '系统综述',
  human_study: '人体研究',
  animal_study: '动物/体外研究',
  mechanism: '机制推测',
  anecdote: '个案经验',
  marketing: '营销说法',
  not_applicable: '不涉及证据',
  ungraded: '未分级',
}

describe('curation vocabulary matches the server', () => {
  it('labels every evidence grade exactly as profile.py does', () => {
    expect(EVIDENCE_LABELS).toEqual(SERVER_EVIDENCE)
  })

  it('keeps the content-type and suggested-action enums closed', () => {
    expect(CONTENT_TYPE_LABELS).toEqual({
      research: '研究',
      tutorial: '技术教程',
      release: '产品发布',
      report: '深度报道',
      opinion: '观点',
      tool: '工具或开源项目',
      creative: '音乐或创作',
      knowledge: '日常知识',
      other: '其他',
    })
    expect(SUGGESTED_ACTION_LABELS).toEqual({
      read: '阅读',
      collect: '收藏',
      try: '试用',
      inspiration: '仅作灵感',
      skip: '可跳过',
    })
    expect(PRIORITY_LABELS).toEqual({ high: '高', medium: '中', low: '低' })
  })

  it('lists the nine clusters in the server order', () => {
    expect(CLUSTERS.map(([id]) => id)).toEqual([
      'mind-cognition',
      'brain-health',
      'ai-software',
      'knowledge-workflow',
      'life-finance',
      'philosophy-science',
      'music-language',
      'everyday-gear',
      'general',
    ])
    expect(Object.keys(CLUSTER_LABELS)).toHaveLength(CLUSTERS.length)
  })

  it('falls back to 其他 for a topic cached before the cluster column', () => {
    expect(clusterLabel(undefined)).toBe('其他')
  })

  it('shows nothing rather than a made-up value for pre-B09 rows', () => {
    expect(evidenceLabel(undefined)).toBeUndefined()
    expect(contentTypeLabel(undefined)).toBeUndefined()
    expect(suggestedActionLabel(undefined)).toBeUndefined()
    expect(priorityLabel(undefined)).toBeUndefined()
  })
})

describe('feedback reasons route by sign', () => {
  it('splits the chips between 喜欢 and 不喜欢', () => {
    expect(DISLIKE_REASONS.map(([v]) => v)).toEqual([
      'too_shallow',
      'too_deep',
      'too_repetitive',
      'not_interested',
    ])
    expect(LIKE_REASONS.map(([v]) => v)).toEqual(['want_more'])
    expect(reasonAction('want_more')).toBe('like')
    for (const [reason] of DISLIKE_REASONS) expect(reasonAction(reason)).toBe('dislike')
  })

  it('covers each reason once and gives it a label', () => {
    const all = [...DISLIKE_REASONS, ...LIKE_REASONS].map(([v]) => v) as FeedbackReason[]
    expect(new Set(all).size).toBe(all.length)
    expect(Object.keys(FEEDBACK_REASON_LABELS).sort()).toEqual([...all].sort())
    for (const value of all) expect(FEEDBACK_REASON_LABELS[value]).toBeTruthy()
  })
})

describe('evidence badges stay honest', () => {
  it('grades trust so a marketing claim cannot read as verified', () => {
    expect(gradeStrength('systematic_review')).toBe('trustworthy')
    expect(gradeStrength('human_study')).toBe('trustworthy')
    expect(gradeStrength('animal_study')).toBe('mixed')
    expect(gradeStrength('mechanism')).toBe('mixed')
    expect(gradeStrength('not_applicable')).toBe('mixed')
    expect(gradeStrength('marketing')).toBe('weak')
    expect(gradeStrength('anecdote')).toBe('weak')
    expect(gradeStrength('ungraded')).toBe('weak')
    expect(gradeStrength(undefined)).toBe('weak')
  })

})
