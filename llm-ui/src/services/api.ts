import { db } from '../db'
import { DEFAULT_SETTINGS } from '../db/types'
import { injectedToken } from '../mobile/transport'
import type { PostRow, PostKind } from '../db/types'
import type { ContentType, EvidenceGrade, Priority, SuggestedAction } from '../lib/curation'
import { fingerprintOf } from '../db/demoFactory'
import type {
  CommentDto,
  CommentsDto,
  FeedPageDto,
  FetchLogDto,
  GenerateCommentsDto,
  GenerationSettingsDto,
  InterestDto,
  InteractionInDto,
  InteractionOutDto,
  JobDto,
  PersonaDto,
  PersonaInput,
  PostDto,
  PromptPreviewDto,
  PromptSettingsDto,
  PromptSettingsInput,
  RetentionRunDto,
  RetentionSettingsDto,
  SourceInput,
  SourceItemDto,
  SourceRefreshDto,
  SourceDto,
  SyncChangesOut,
  SyncOpDto,
  SyncOpsOut,
  SyncSnapshotDto,
  TopicDto,
  UsageDto,
  UsageGroupBy,
} from './types'

export class ApiError extends Error {
  code: string
  retryable: boolean
  status: number

  constructor(status: number, code: string, message: string, retryable: boolean) {
    super(message)
    this.status = status
    this.code = code
    this.retryable = retryable
  }
}

async function baseUrl(): Promise<string> {
  const row = await db.settings.get('apiBaseUrl')
  const url = (row?.value as string | undefined) ?? DEFAULT_SETTINGS.apiBaseUrl
  return url.replace(/\/$/, '')
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const base = await baseUrl()
  const tokenRow = await db.settings.get('authToken')
  // 手机上的令牌归原生那一侧存（设置页填、同步用它）。这里优先用本会话刚填进库里的值，
  // 没有就用壳递进来的那份 —— 两边读同一把钥匙，否则「网页同步通了、原生的还是 401」
  // 这种症状会长期查不清。
  const stored = typeof tokenRow?.value === 'string' ? tokenRow.value : ''
  const token = stored || injectedToken()
  let resp: Response
  try {
    resp = await fetch(`${base}/api/v1${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init.headers ?? {}),
      },
    })
  } catch (err) {
    throw new ApiError(
      0,
      'NETWORK',
      err instanceof Error ? err.message : '网络错误',
      true,
    )
  }
  if (!resp.ok) {
    let code = 'HTTP_ERROR'
    let message = `HTTP ${resp.status}`
    let retryable = resp.status >= 500
    try {
      const body = (await resp.json()) as {
        detail?: { code?: string; message?: string; retryable?: boolean }
        error?: { code?: string; message?: string; retryable?: boolean }
      }
      const d = body.detail ?? body.error
      if (d) {
        code = d.code ?? code
        message = d.message ?? message
        retryable = d.retryable ?? retryable
      }
    } catch {
      /* keep defaults */
    }
    throw new ApiError(resp.status, code, message, retryable)
  }
  return (await resp.json()) as T
}

export async function createGenerationJob(body: {
  requested_count: number
  mode?: string
  client_key?: string
}): Promise<JobDto> {
  return request<JobDto>('/generation/jobs', {
    method: 'POST',
    body: JSON.stringify({ mode: 'manual', ...body }),
  })
}

export async function listGenerationJobs(limit = 20): Promise<JobDto[]> {
  const page = await request<{ items: JobDto[] }>(`/generation/jobs?limit=${limit}`)
  return page.items
}

export async function cancelGenerationJob(id: string): Promise<JobDto> {
  return request<JobDto>(`/generation/jobs/${id}/cancel`, { method: 'POST' })
}

export async function retryGenerationJob(id: string): Promise<JobDto> {
  return request<JobDto>(`/generation/jobs/${id}/retry`, { method: 'POST' })
}

export async function getGenerationUsage(
  groupBy: UsageGroupBy = 'day',
  range: { from?: string; to?: string } = {},
): Promise<UsageDto> {
  const qs = new URLSearchParams({ group_by: groupBy })
  if (range.from) qs.set('from', range.from)
  if (range.to) qs.set('to', range.to)
  return request<UsageDto>(`/generation/usage?${qs.toString()}`)
}

export async function getGenerationSettings(): Promise<GenerationSettingsDto> {
  return request<GenerationSettingsDto>('/generation/settings')
}

export async function putGenerationSettings(
  body: Partial<GenerationSettingsDto>,
): Promise<GenerationSettingsDto> {
  return request<GenerationSettingsDto>('/generation/settings', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export async function checkHealth(): Promise<{ version: string; time: string }> {
  const base = await baseUrl()
  const resp = await fetch(`${base}/health`)
  if (!resp.ok) throw new ApiError(resp.status, 'HEALTH', `HTTP ${resp.status}`, true)
  return (await resp.json()) as { version: string; time: string }
}

/**
 * Map the B09 curation fields from a DTO onto a local row fragment.
 *
 * The server sends these strings with meaningful blanks (``""`` for
 * ``content_type``/``recommend_reason``). An empty value is left ``undefined``
 * so a change that carries nothing cannot wipe a badge the device already has —
 * we never invent a grade.
 */
export function curationFields(
  dto: PostDto,
): Pick<PostRow, 'recommendReason' | 'evidenceGrade' | 'contentType' | 'suggestedAction' | 'priority'> {
  return {
    recommendReason: dto.recommend_reason || undefined,
    evidenceGrade: dto.evidence_grade ? (dto.evidence_grade as EvidenceGrade) : undefined,
    contentType: dto.content_type ? (dto.content_type as ContentType) : undefined,
    suggestedAction: dto.suggested_action
      ? (dto.suggested_action as SuggestedAction)
      : undefined,
    priority: dto.priority ? (dto.priority as Priority) : undefined,
  }
}

export function toPostRow(dto: PostDto): PostRow {
  return {
    id: dto.id,
    kind: dto.kind as PostKind,
    title: dto.title,
    body: dto.body,
    summary: dto.summary ?? dto.body.slice(0, 80),
    topicId: dto.topic_id,
    personaId: dto.persona_id,
    sourceId: dto.source_id,
    sourceUrl: dto.source_url,
    sourceTitle: dto.source_title,
    publishedAt: dto.published_at,
    fetchedAt: dto.fetched_at,
    seriesId: dto.series_id,
    status: 'published',
    qualityScore: dto.quality_score,
    tags: dto.tags,
    demo: dto.demo,
    fingerprint: dto.id.startsWith('demo_') ? fingerprintOf(dto.title, dto.body) : dto.id,
    createdAt: dto.created_at,
    revision: dto.revision,
    saved: false,
    hidden: false,
    likeState: 0,
    lastReadAt: null,
    readProgress: 0,
    ...curationFields(dto),
  }
}

export interface PullResult {
  fetched: number
  added: number
  updated: number
}

/**
 * Pull server feed pages into Dexie (B03 bridge; full sync arrives in B06).
 * Existing local interaction state is preserved on update.
 */
export async function pullFeedToDexie(maxPages = 20, limit = 100): Promise<PullResult> {
  let cursor: string | null = null
  let fetched = 0
  let added = 0
  let updated = 0

  for (let page = 0; page < maxPages; page++) {
    const qs = new URLSearchParams({ limit: String(limit) })
    if (cursor) qs.set('cursor', cursor)
    const data = await request<FeedPageDto>(`/feed?${qs.toString()}`)
    if (data.items.length === 0) break
    fetched += data.items.length

    await db.transaction('rw', db.posts, db.topics, async () => {
      for (const dto of data.items) {
        // Feed items have no topic metadata. Do not erase a synced display
        // name or cluster with an internal-id placeholder on every manual pull.
        if (!(await db.topics.get(dto.topic_id))) await db.topics.put({
          id: dto.topic_id,
          slug: dto.topic_id,
          name: dto.topic_id,
          parentId: null,
        })
        const existing = await db.posts.get(dto.id)
        if (existing) {
          const cur = curationFields(dto)
          await db.posts.put({
            ...existing,
            kind: dto.kind,
            topicId: dto.topic_id,
            personaId: dto.persona_id,
            sourceId: dto.source_id,
            sourceUrl: dto.source_url,
            sourceTitle: dto.source_title,
            publishedAt: dto.published_at,
            fetchedAt: dto.fetched_at,
            seriesId: dto.series_id,
            qualityScore: dto.quality_score,
            demo: dto.demo,
            title: dto.title,
            body: dto.body,
            summary: dto.summary ?? existing.summary,
            tags: dto.tags,
            revision: dto.revision,
            recommendReason: cur.recommendReason ?? existing.recommendReason,
            evidenceGrade: cur.evidenceGrade ?? existing.evidenceGrade,
            contentType: cur.contentType ?? existing.contentType,
            suggestedAction: cur.suggestedAction ?? existing.suggestedAction,
            priority: cur.priority ?? existing.priority,
          })
          updated += 1
        } else {
          await db.posts.put(toPostRow(dto))
          added += 1
        }
      }
    })

    cursor = data.next_cursor
    if (!cursor) break
  }

  return { fetched, added, updated }
}

// --- B04: personas / comments / interests ---

export async function listPersonas(): Promise<PersonaDto[]> {
  return request<PersonaDto[]>('/personas')
}

export async function createPersona(body: PersonaInput): Promise<PersonaDto> {
  return request<PersonaDto>('/personas', { method: 'POST', body: JSON.stringify(body) })
}

export async function patchPersona(
  id: string,
  body: Partial<PersonaInput>,
): Promise<PersonaDto> {
  return request<PersonaDto>(`/personas/${id}`, { method: 'PATCH', body: JSON.stringify(body) })
}

export async function resetPersonaMemory(id: string): Promise<PersonaDto> {
  return request<PersonaDto>(`/personas/${id}/memory/reset`, { method: 'POST' })
}

export async function getPostComments(postId: string): Promise<CommentsDto> {
  return request<CommentsDto>(`/posts/${postId}/comments`)
}

/**
 * On-demand AI comment generation. Idempotent server-side: repeat calls return
 * ``status:'already'`` and never bill again (docs/06 §9).
 */
export async function generatePostComments(postId: string): Promise<GenerateCommentsDto> {
  return request<GenerateCommentsDto>(`/posts/${postId}/generate-comments`, { method: 'POST' })
}

export async function putInterest(
  topicId: string,
  body: { explicit_weight: number; blocked: boolean },
): Promise<InterestDto> {
  return request<InterestDto>(`/interests/${encodeURIComponent(topicId)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

/**
 * Replace locally cached persona comments for a post with server truth.
 * Reader (local) comments are preserved — they belong to the device until B06.
 */
export async function syncCommentsToDexie(
  postId: string,
  comments: CommentDto[],
): Promise<number> {
  await db.transaction('rw', db.comments, async () => {
    await db.comments.where('postId').equals(postId).and((c) => c.authorType === 'persona').delete()
    for (const c of comments) {
      await db.comments.put({
        id: c.id,
        postId: c.post_id,
        parentId: c.parent_id,
        personaId: c.persona_id,
        authorType: c.author_type,
        body: c.body,
        personaName: c.persona_name,
        createdAt: c.created_at,
        revision: 1,
      })
    }
  })
  return comments.length
}

// --- B05 sources ---

export async function listSources(): Promise<SourceDto[]> {
  return request<SourceDto[]>('/sources')
}

export async function createSource(body: SourceInput): Promise<SourceDto> {
  return request<SourceDto>('/sources', { method: 'POST', body: JSON.stringify(body) })
}

export async function patchSource(
  id: string,
  body: Partial<SourceInput>,
): Promise<SourceDto> {
  return request<SourceDto>(`/sources/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

export async function deleteSource(id: string): Promise<void> {
  await request<void>(`/sources/${id}`, { method: 'DELETE' })
}

export async function refreshSource(id: string): Promise<SourceRefreshDto> {
  return request<SourceRefreshDto>(`/sources/${id}/refresh`, { method: 'POST' })
}

export async function getSourceItems(id: string): Promise<SourceItemDto[]> {
  return request<SourceItemDto[]>(`/sources/${id}/items`)
}

export async function getSourceLogs(id: string): Promise<FetchLogDto[]> {
  return request<FetchLogDto[]>(`/sources/${id}/logs`)
}

// --- B06: sync protocol ---

export async function getInterests(): Promise<InterestDto[]> {
  return request<InterestDto[]>('/interests')
}

export async function getSyncChanges(
  sinceSeq: number,
  limit = 500,
): Promise<SyncChangesOut> {
  return request<SyncChangesOut>(
    `/sync/changes?since_seq=${sinceSeq}&limit=${limit}`,
  )
}

export async function postSyncOperations(
  deviceId: string,
  operations: SyncOpDto[],
): Promise<SyncOpsOut> {
  return request<SyncOpsOut>('/sync/operations', {
    method: 'POST',
    body: JSON.stringify({ device_id: deviceId, operations }),
  })
}

export async function getSyncSnapshot(): Promise<SyncSnapshotDto> {
  return request<SyncSnapshotDto>('/sync/snapshot')
}

// --- B08: docs/04 §1 contract endpoints, retention windows ---

export async function listTopics(): Promise<TopicDto[]> {
  return request<TopicDto[]>('/topics')
}

/**
 * Single-post interaction over REST. ``op_id`` is the idempotency key, so a
 * retry after a dropped response cannot double-count (docs/04 §1).
 */
export async function postInteraction(
  postId: string,
  body: InteractionInDto,
): Promise<InteractionOutDto> {
  return request<InteractionOutDto>(`/posts/${postId}/interactions`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function getRetentionSettings(): Promise<RetentionSettingsDto> {
  return request<RetentionSettingsDto>('/settings/retention')
}

export async function putRetentionSettings(
  body: Partial<RetentionSettingsDto>,
): Promise<RetentionSettingsDto> {
  return request<RetentionSettingsDto>('/settings/retention', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export async function runRetentionNow(): Promise<RetentionRunDto> {
  return request<RetentionRunDto>('/maintenance/retention', { method: 'POST' })
}

// --- B10: prompt studio ---

export async function getPromptSettings(): Promise<PromptSettingsDto> {
  return request<PromptSettingsDto>('/settings/prompts')
}

export async function putPromptSettings(body: PromptSettingsInput): Promise<PromptSettingsDto> {
  return request<PromptSettingsDto>('/settings/prompts', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

/**
 * The real prompt for a throwaway plan. No provider call, so no cost and no
 * published post — this is the only way a mis-edit is visible before the next
 * batch (docs/06 §5).
 */
export async function previewPrompt(count = 5): Promise<PromptPreviewDto> {
  return request<PromptPreviewDto>(`/settings/prompts/preview?count=${count}`, { method: 'POST' })
}
