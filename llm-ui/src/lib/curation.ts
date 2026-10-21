/**
 * B09 curation vocabulary (docs/07 §6/§7).
 *
 * The server writes the four per-post judgements plus a deterministic policy;
 * the client only ever needs the enum values and their Chinese labels here.
 * These maps are the single source of truth so no component duplicates a
 * string; they mirror `apps/server/app/services/profile.py` exactly.
 *
 * Internal enum values stay in `data-*` attributes; only Chinese labels reach
 * the screen (project copy rule).
 */

export type EvidenceGrade =
  | 'systematic_review'
  | 'human_study'
  | 'animal_study'
  | 'mechanism'
  | 'anecdote'
  | 'marketing'
  | 'not_applicable'
  | 'ungraded'

export type ContentType =
  | 'research'
  | 'tutorial'
  | 'release'
  | 'report'
  | 'opinion'
  | 'tool'
  | 'creative'
  | 'knowledge'
  | 'other'

export type SuggestedAction = 'read' | 'collect' | 'try' | 'inspiration' | 'skip'

export type Priority = 'high' | 'medium' | 'low'

export type TopicCluster =
  | 'mind-cognition'
  | 'brain-health'
  | 'ai-software'
  | 'knowledge-workflow'
  | 'life-finance'
  | 'philosophy-science'
  | 'music-language'
  | 'everyday-gear'
  | 'general'

/** Structured feedback reasons the server recognises in ``payload.reason``. */
export type FeedbackReason =
  | 'too_shallow'
  | 'too_deep'
  | 'too_repetitive'
  | 'not_interested'
  | 'want_more'

export const EVIDENCE_LABELS: Record<EvidenceGrade, string> = {
  systematic_review: '系统综述',
  human_study: '人体研究',
  animal_study: '动物/体外研究',
  mechanism: '机制推测',
  anecdote: '个案经验',
  marketing: '营销说法',
  not_applicable: '不涉及证据',
  ungraded: '未分级',
}

export const CONTENT_TYPE_LABELS: Record<ContentType, string> = {
  research: '研究',
  tutorial: '技术教程',
  release: '产品发布',
  report: '深度报道',
  opinion: '观点',
  tool: '工具或开源项目',
  creative: '音乐或创作',
  knowledge: '日常知识',
  other: '其他',
}

export const SUGGESTED_ACTION_LABELS: Record<SuggestedAction, string> = {
  read: '阅读',
  collect: '收藏',
  try: '试用',
  inspiration: '仅作灵感',
  skip: '可跳过',
}

export const PRIORITY_LABELS: Record<Priority, string> = {
  high: '高',
  medium: '中',
  low: '低',
}

/** Ordered interest clusters (matches the server ``CLUSTERS`` tuple). */
export const CLUSTERS: readonly [TopicCluster, string][] = [
  ['mind-cognition', '心理与认知'],
  ['brain-health', '神经科学与健康'],
  ['ai-software', 'AI 与软件工程'],
  ['knowledge-workflow', '笔记与知识管理'],
  ['life-finance', '生活设计与财务'],
  ['philosophy-science', '哲学经济与社会'],
  ['music-language', '音乐创作与语言'],
  ['everyday-gear', '日常探索与选购'],
  ['general', '其他'],
]

export const CLUSTER_ORDER: TopicCluster[] = CLUSTERS.map(([id]) => id)

export const CLUSTER_LABELS: Record<TopicCluster, string> = Object.fromEntries(
  CLUSTERS,
) as Record<TopicCluster, string>

export const FALLBACK_CLUSTER: TopicCluster = 'general'

/**
 * Reason chips split by direction: the server replaces the state-transition
 * delta with the reason weight, so a reason must ride the op that matches its
 * sign. 想继续 is a positive signal — sending it on the dislike op would mark
 * the post disliked while asking for more of it.
 */
export const DISLIKE_REASONS: readonly [FeedbackReason, string][] = [
  ['too_shallow', '太浅'],
  ['too_deep', '太专业'],
  ['too_repetitive', '太重复'],
  ['not_interested', '不感兴趣'],
]

export const LIKE_REASONS: readonly [FeedbackReason, string][] = [['want_more', '想继续']]

/** Which thumbs op a reason belongs to (`LocalAction` name, not a new enum). */
export function reasonAction(reason: FeedbackReason): 'like' | 'dislike' {
  return reason === 'want_more' ? 'like' : 'dislike'
}

export const FEEDBACK_REASON_LABELS: Record<FeedbackReason, string> = Object.fromEntries([
  ...DISLIKE_REASONS,
  ...LIKE_REASONS,
]) as Record<FeedbackReason, string>

/**
 * Trust strength for an evidence grade, used so a badge stays visually honest:
 * a marketing claim or an ungraded (pre-B09) value must never read as verified.
 */
export type GradeStrength = 'trustworthy' | 'mixed' | 'weak'

const TRUSTWORTHY: ReadonlySet<EvidenceGrade> = new Set<EvidenceGrade>([
  'systematic_review',
  'human_study',
])
const WEAK: ReadonlySet<EvidenceGrade> = new Set<EvidenceGrade>([
  'anecdote',
  'marketing',
  'ungraded',
])

/** Missing (pre-B09) grades collapse to ``ungraded`` → ``weak`` (never green). */
export function gradeStrength(grade?: EvidenceGrade): GradeStrength {
  if (!grade || WEAK.has(grade)) return 'weak'
  if (TRUSTWORTHY.has(grade)) return 'trustworthy'
  return 'mixed'
}

/** Tailwind classes for the evidence bars live in `cardStyle.METER_FILL`. */

export function evidenceLabel(grade?: EvidenceGrade): string | undefined {
  return grade ? EVIDENCE_LABELS[grade] : undefined
}

export function contentTypeLabel(type?: ContentType): string | undefined {
  return type ? CONTENT_TYPE_LABELS[type] : undefined
}

export function suggestedActionLabel(action?: SuggestedAction): string | undefined {
  return action ? SUGGESTED_ACTION_LABELS[action] : undefined
}

export function priorityLabel(priority?: Priority): string | undefined {
  return priority ? PRIORITY_LABELS[priority] : undefined
}

export function clusterLabel(cluster?: TopicCluster): string {
  return CLUSTER_LABELS[cluster ?? FALLBACK_CLUSTER]
}
