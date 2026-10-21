/** Types mirroring the FastAPI OpenAPI schemas (packages/contracts from B04+). */

export interface PostDto {
  id: string
  kind: 'fiction' | 'synthetic_explainer' | 'sourced' | 'serial'
  title: string
  body: string
  summary: string | null
  topic_id: string
  persona_id: string | null
  source_id: string | null
  source_url: string | null
  source_title: string | null
  published_at: string | null
  fetched_at: string | null
  series_id: string | null
  quality_score: number
  // B09 curation: four model judgements + derived priority. The server always
  // sends them (with defaults), but empty strings are meaningful blanks, so the
  // client keeps them as free-form strings and coerces blanks on mapping.
  recommend_reason: string
  evidence_grade: string
  content_type: string
  suggested_action: string
  priority: string
  tags: string[]
  demo: boolean
  created_at: string
  revision: number
}

export interface FeedPageDto {
  items: PostDto[]
  next_cursor: string | null
}

export interface JobDto {
  id: string
  status:
    | 'queued'
    | 'planning'
    | 'generating'
    | 'validating'
    | 'publishing'
    | 'completed'
    | 'partial'
    | 'failed'
    | 'cancelled'
  mode: string
  provider_id: string
  model: string
  requested_count: number
  produced_count: number
  failed_count: number
  attempt_count: number
  error_code: string | null
  error_message: string | null
  started_at: string | null
  finished_at: string | null
  created_at: string
}

export interface UsageRowDto {
  bucket: string
  jobs: number
  calls: number
  input_tokens: number
  output_tokens: number
  cost_estimate: number | null
  has_unknown_price: boolean
  missing_usage: number
}

export interface UsageDto {
  rows: UsageRowDto[]
  total_input_tokens: number
  total_output_tokens: number
  total_cost_estimate: number | null
  has_unknown_price: boolean
  total_missing_usage: number
  group_by: UsageGroupBy
  date_from: string | null
  date_to: string | null
}

export type UsageGroupBy = 'day' | 'model' | 'batch'

// --- B08: docs/04 §1 endpoints that were promised but missing until now ---

export interface TopicDto {
  id: string
  slug: string
  name: string
  parent_id: string | null
  /** B09 interest cluster (server default "general"). */
  cluster: string
}

export type InteractionKind =
  | 'view'
  | 'like'
  | 'dislike'
  | 'hide'
  | 'save'
  | 'unsave'
  | 'comment'
  | 'follow_topic'

export interface InteractionInDto {
  op_id: string
  kind: InteractionKind
  payload?: Record<string, unknown>
  occurred_at?: string | null
  device_id?: string
}

export interface InteractionOutDto {
  accepted: boolean
  revision: number | null
}

// --- B08: data retention (docs/03 §6) ---

export interface RetentionSettingsDto {
  source_items_days: number
  client_ops_days: number
  tombstone_days: number
  demo_posts_days: number
  prune_read_unsaved: boolean
  read_unsaved_days: number
}

export interface RetentionRunDto {
  dropped: Record<string, number>
  settings: RetentionSettingsDto
}

export interface GenerationSettingsDto {
  provider_id: string
  model: string
  unread_target: number
  low_water: number
  daily_run_time: string
  daily_cost_limit: number
  daily_token_limit: number
  per_job_cost_limit: number
  max_concurrency: number
  requests_per_minute: number
  mix_sourced: number
  mix_original: number
  exploration_ratio: number
  topic_weights: Record<string, number>
  undelivered_inventory: number
  active_jobs: number
}

export interface ApiErrorBody {
  error?: { code?: string; message?: string; retryable?: boolean }
  detail?: unknown
}

// --- B04: personas / comments / interests ---

export interface PersonaDto {
  id: string
  name: string
  bio: string
  position: string
  style_prompt: string
  domains: string[]
  avoid_topics: string[]
  memory_summary: string
  reply_frequency: number
  enabled: boolean
  is_ai: boolean
  created_at: string
  updated_at: string
  revision: number
}

export interface PersonaInput {
  name: string
  bio?: string
  position?: string
  style_prompt?: string
  domains?: string[]
  avoid_topics?: string[]
  memory_summary?: string
  reply_frequency?: number
  enabled?: boolean
}

export interface CommentDto {
  id: string
  post_id: string
  parent_id: string | null
  persona_id: string | null
  persona_name: string | null
  author_type: 'persona' | 'user'
  is_ai: boolean
  body: string
  created_at: string
  revision?: number
}

export interface CommentsDto {
  items: CommentDto[]
  generated_revision: number
}

export interface GenerateCommentsDto {
  status: 'generated' | 'already' | 'in_progress'
  comments: CommentDto[]
  job_id: string | null
}

export interface InterestDto {
  topic_id: string
  explicit_weight: number
  inferred_weight: number
  /** B09 depth preference readout (≈ −1..1; + = 太浅, − = 太专业). */
  depth_bias: number
  blocked: boolean
  revision?: number
  updated_at: string
}

// --- B05 sources ---

export interface SourceDto {
  id: string
  kind: string
  url: string
  title: string
  topic_id: string | null
  enabled: boolean
  fetch_interval_minutes: number
  last_fetched_at: string | null
  last_status: 'ok' | 'not_modified' | 'error' | null
  last_error: string | null
  item_count: number
  created_at: string
  updated_at: string
  revision: number
}

export interface SourceInput {
  url: string
  title?: string | null
  kind?: string
  topic_id?: string | null
  enabled?: boolean
  fetch_interval_minutes?: number
}

export interface SourceItemDto {
  id: string
  source_id: string
  url: string
  title: string
  body_excerpt: string
  published_at: string | null
  fetched_at: string
  used_by_post_id: string | null
}

export interface FetchLogDto {
  id: string
  source_id: string
  started_at: string
  finished_at: string
  status: 'ok' | 'not_modified' | 'error'
  http_status: number | null
  items_found: number
  items_new: number
  error: string | null
}

export interface SourceRefreshDto {
  source: SourceDto
  log: FetchLogDto
}

// --- B06: sync protocol (docs/04 §5, docs/05) ---

export interface SyncOpDto {
  op_id: string
  client_seq: number
  object_type: 'post' | 'comment' | 'interest'
  object_id: string
  action: string
  payload: Record<string, unknown>
  base_revision: number | null
  occurred_at?: string | null
}

export interface SyncOpResultDto {
  op_id: string
  status: 'applied' | 'conflict' | 'error'
  revision?: number | null
  error?: { code: string; message: string; retryable: boolean }
}

export interface SyncOpsOut {
  results: SyncOpResultDto[]
  latest_seq: number
}

export interface SyncChangeDto {
  seq: number
  object_type: 'post' | 'post_state' | 'comment' | 'interest' | 'topic'
  object_id: string
  operation: 'upsert' | 'delete'
  revision: number
  payload: Record<string, unknown>
  changed_at: string
}

export interface SyncChangesOut {
  changes: SyncChangeDto[]
  next_seq: number
  has_more: boolean
}

export interface SyncStateDto {
  post_id: string
  saved: boolean
  like_state: number
  hidden: boolean
  read_progress: number
  last_read_at: string | null
  revision: number
  updated_at: string
}

export interface SyncSnapshotDto {
  latest_seq: number
  server_time: string
  topics: { id: string; slug: string; name: string; parent_id: string | null; cluster?: string }[]
  posts: PostDto[]
  comments: CommentDto[]
  interests: InterestDto[]
  post_states: SyncStateDto[]
}

/** One reader-editable prompt chunk; ``default_text`` is the shipped wording. */
export interface PromptChunkDto {
  key: 'style_rules' | 'persona_notes' | 'angles'
  title: string
  hint: string
  text: string
  default_text: string
  /** Server-computed: the stored value is non-empty and differs from default. */
  modified: boolean
}

export interface LockedSectionDto {
  key: string
  title: string
  /** Why this part cannot be edited, in the server's own words. */
  why: string
  text: string
}

export interface PersonaBriefDto {
  id: string
  name: string
  position: string
}

export interface PromptSettingsDto {
  chunks: PromptChunkDto[]
  locked: LockedSectionDto[]
  /** Where 恢复默认 lands: the reader's local file, or the built-in floor. */
  defaults_source: string
  personas: PersonaBriefDto[]
}

export interface PromptSettingsInput {
  style_rules?: string
  persona_notes?: string
  angles?: string
}

export interface PromptPreviewDto {
  /** The exact text the next subbatch call would send. */
  prompt: string
  slots: number
  authors: string[]
  provider: string
  model: string
  /** 0 means no author reaches the prompt — the batch reads in one voice. */
  personas_wired: number
  kind_counts?: Record<string, number>
  warnings?: string[]
}
