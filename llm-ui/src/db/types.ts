import { initialBaseUrl, isMobileHost } from '../mobile/transport'
import type {
  ContentType,
  EvidenceGrade,
  Priority,
  SuggestedAction,
  TopicCluster,
} from '../lib/curation'

export const POST_KINDS = [
  'fiction',
  'synthetic_explainer',
  'sourced',
  'serial',
] as const

export type PostKind = (typeof POST_KINDS)[number]

export const KIND_LABELS: Record<PostKind, string> = {
  fiction: '虚构',
  synthetic_explainer: 'AI 知识',
  sourced: '真实来源',
  serial: '连载',
}

export const INTERACTION_KINDS = [
  'view',
  'like',
  'dislike',
  'hide',
  'save',
  'unsave',
  'comment',
  'follow_topic',
] as const

export type InteractionKind = (typeof INTERACTION_KINDS)[number]

/** Feed post row. Server fields + local-first interaction state. */
export interface PostRow {
  id: string
  kind: PostKind
  title: string
  body: string
  summary: string
  topicId: string
  personaId: string | null
  sourceId: string | null
  sourceUrl: string | null
  sourceTitle: string | null
  publishedAt: string | null
  fetchedAt: string | null
  seriesId: string | null
  status: 'published' | 'deleted'
  qualityScore: number
  tags: string[]
  demo: boolean
  fingerprint: string
  createdAt: string
  revision: number
  /** local interaction state */
  saved: boolean
  hidden: boolean
  /** 1 = like, -1 = dislike, 0 = neutral */
  likeState: 1 | -1 | 0
  lastReadAt: string | null
  readProgress: number
  /**
   * B09 curation fields. OPTIONAL on purpose: rows cached before B09 stay
   * truthfully absent (rendered blank / "未分级"), never coerced to a fake grade.
   */
  recommendReason?: string
  evidenceGrade?: EvidenceGrade
  contentType?: ContentType
  suggestedAction?: SuggestedAction
  priority?: Priority
}

export interface TopicRow {
  id: string
  slug: string
  name: string
  parentId: string | null
  /** B09 interest cluster; absent on rows cached before B09. */
  cluster?: TopicCluster
}

export interface InterestRow {
  topicId: string
  explicitWeight: number
  inferredWeight: number
  blocked: boolean
  /** B09 depth preference (≈ −1..1; + = 太浅, − = 太专业); absent before B09. */
  depthBias?: number
  /** Server optimistic-concurrency revision (B06); absent = not yet from server. */
  revision?: number
  updatedAt: string
}

export interface InteractionRow {
  id: string
  postId: string
  kind: InteractionKind
  payload: Record<string, unknown> | null
  deviceId: string
  clientSeq: number
  occurredAt: string
}

export interface OutboxRow {
  id: string
  opId: string
  deviceId: string
  clientSeq: number
  /** B06 sync target; legacy rows may omit it (treated as 'post'). */
  objectType?: 'post' | 'comment' | 'interest'
  /** object_id for the sync protocol (post id / topic id / parent id). */
  objectId?: string
  postId: string
  /** 'set' is the interest op (B06); interaction kinds cover post/comment ops. */
  action: InteractionKind | 'set'
  payload: Record<string, unknown> | null
  baseRevision: number
  occurredAt: string
  status: 'pending' | 'synced' | 'failed'
  createdAt: string
  error: string | null
}

export interface CommentRow {
  id: string
  postId: string
  parentId: string | null
  personaId: string | null
  authorType: 'user' | 'persona'
  body: string
  /** cached persona display name (not indexed) */
  personaName?: string | null
  createdAt: string
  revision: number
}

export interface GenerationJobRow {
  id: string
  status: string
  requestedCount: number
  producedCount: number
  attemptCount: number
  nextRunAt: string | null
  startedAt: string | null
  finishedAt: string | null
  errorCode: string | null
}

export interface AppSettings {
  apiBaseUrl: string
  /** Optional bearer token for a remote server (PAF_AUTH_TOKEN). */
  authToken: string
  theme: 'light' | 'dark' | 'system'
  sortMode: 'new' | 'hot'
  seededVersion: number
  explorationRatio: number
  mixSourced: number
  mixOriginal: number
  unreadTarget: number
}

export const DEFAULT_SETTINGS: AppSettings = {
  apiBaseUrl: initialBaseUrl() || (isMobileHost() ? '' : import.meta.env.VITE_PAF_API_BASE ?? 'http://127.0.0.1:8000'),
  authToken: '',
  theme: 'system',
  sortMode: 'new',
  seededVersion: 0,
  explorationRatio: 0.2,
  mixSourced: 0.5,
  mixOriginal: 0.3,
  unreadTarget: 200,
}

export const CURRENT_SEED_VERSION = 1
