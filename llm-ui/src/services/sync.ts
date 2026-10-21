/**
 * B06 client sync engine (docs/05): push outbox → pull changes → bootstrap.
 *
 * - Outbox rows are written with the UI action in one Dexie transaction
 *   (store/interactions.ts) — reading/interacting never triggers network I/O;
 *   this module only runs in the background loop / manual retry.
 * - Push is idempotent (op_id + client_seq); pull is cursor based; an expired
 *   cursor falls back to a full snapshot without touching the local outbox.
 * - Failures schedule exponential backoff so a flapping network never hammers
 *   the server.
 */

import { db, getDeviceId, nextClientSeq, type PafDatabase } from '../db'
import type { CommentRow, InterestRow, OutboxRow, TopicRow } from '../db/types'
import type { TopicCluster } from '../lib/curation'
import { parseTime } from '../lib/time'
import {
  ApiError,
  curationFields,
  getInterests,
  getSyncChanges,
  getSyncSnapshot,
  postSyncOperations,
  pullFeedToDexie,
  toPostRow,
} from './api'
import type {
  CommentDto,
  InterestDto,
  PostDto,
  SyncChangeDto,
  SyncChangesOut,
  SyncOpDto,
  SyncOpResultDto,
  SyncSnapshotDto,
  SyncStateDto,
  TopicDto,
} from './types'

export const SYNC_BATCH = 50
export const SYNC_PAGE = 500
/** 主界面刷新每轮从服务端拉回的条数（手动刷新与 30s 自动刷新共用）。 */
export const FEED_PULL_LIMIT = 50
export const BACKOFF_BASE_MS = 5000
export const BACKOFF_MAX_MS = 5 * 60_000
export const SYNC_INTERVAL_MS = 30_000

export interface SyncStatus {
  pending: number
  failed: number
  lastSyncAt: string | null
  lastError: { code: string; message: string; at: string } | null
  note: string | null
  nextRetryAt: number | null
}

export interface SyncRunResult {
  status: 'ok' | 'skipped' | 'error'
  reason?: 'running' | 'backoff' | 'offline'
  pushed?: number
  failed?: number
  conflicts?: number
  pulled?: number
  bootstrapped?: boolean
  error?: { code: string; message: string }
}

interface BackoffState {
  failures: number
  nextAt: number
}

let running = false

function uuid(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `op_${Date.now()}_${Math.random().toString(36).slice(2)}`
}

async function metaGet<T>(key: string): Promise<T | undefined> {
  const row = await db.sync_meta.get(key)
  return row?.value as T | undefined
}

async function metaSet(key: string, value: unknown): Promise<void> {
  await db.sync_meta.put({ key, value })
}

export function backoffDelayMs(failures: number): number {
  return Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** Math.max(0, failures - 1))
}

// --- apply helpers ---------------------------------------------------------

function toCommentRow(dto: CommentDto): CommentRow {
  return {
    id: dto.id,
    postId: dto.post_id,
    parentId: dto.parent_id,
    personaId: dto.persona_id,
    authorType: dto.author_type,
    body: dto.body,
    personaName: dto.persona_name,
    createdAt: dto.created_at,
    revision: dto.revision ?? 1,
  }
}

function toInterestRow(dto: InterestDto): InterestRow {
  return {
    topicId: dto.topic_id,
    explicitWeight: dto.explicit_weight,
    inferredWeight: dto.inferred_weight,
    depthBias: dto.depth_bias ?? 0,
    blocked: dto.blocked,
    revision: dto.revision ?? 1,
    updatedAt: dto.updated_at,
  }
}

function ts(iso: string | null | undefined): number {
  if (!iso) return 0
  const t = parseTime(iso)
  return Number.isNaN(t) ? 0 : t
}

function newerIso(a: string | null, b: string | null): string | null {
  return ts(b) > ts(a) ? b : a
}

async function upsertPostFromPayload(
  payload: PostDto,
  database: PafDatabase,
): Promise<void> {
  const existing = await database.posts.get(payload.id)
  if (existing) {
    // Content is server-owned; local interaction state is preserved here and
    // only overwritten by explicit post_state changes / snapshot states.
    const cur = curationFields(payload)
    await database.posts.put({
      ...existing,
      kind: payload.kind,
      title: payload.title,
      body: payload.body,
      summary: payload.summary ?? existing.summary,
      topicId: payload.topic_id,
      personaId: payload.persona_id,
      sourceId: payload.source_id,
      sourceUrl: payload.source_url,
      sourceTitle: payload.source_title,
      publishedAt: payload.published_at,
      fetchedAt: payload.fetched_at,
      seriesId: payload.series_id,
      qualityScore: payload.quality_score,
      tags: payload.tags,
      demo: payload.demo,
      status: 'published',
      revision: payload.revision,
      recommendReason: cur.recommendReason ?? existing.recommendReason,
      evidenceGrade: cur.evidenceGrade ?? existing.evidenceGrade,
      contentType: cur.contentType ?? existing.contentType,
      suggestedAction: cur.suggestedAction ?? existing.suggestedAction,
      priority: cur.priority ?? existing.priority,
    })
  } else {
    await database.posts.put(toPostRow(payload))
  }
}

async function applyState(state: SyncStateDto, database: PafDatabase): Promise<void> {
  const post = await database.posts.get(state.post_id)
  if (!post) return // content not cached on this device; nothing to attach to
  await database.posts.put({
    ...post,
    saved: state.saved,
    hidden: state.hidden,
    likeState: state.like_state === 1 || state.like_state === -1 ? state.like_state : 0,
    readProgress: Math.max(post.readProgress, state.read_progress),
    lastReadAt: newerIso(post.lastReadAt, state.last_read_at),
  })
}

/** Apply a page of changes in one transaction (docs/05 §2 step 4). */
export async function applyChanges(
  changes: SyncChangeDto[],
  database: PafDatabase = db,
): Promise<number> {
  if (changes.length === 0) return 0
  await database.transaction(
    'rw',
    database.posts,
    database.comments,
    database.interests,
    database.topics,
    async () => {
      for (const ch of changes) {
        switch (ch.object_type) {
          case 'topic': {
            if (ch.operation === 'delete') {
              await database.topics.delete(ch.object_id)
            } else {
              const topic = ch.payload as unknown as TopicDto
              await database.topics.put({
                id: topic.id, slug: topic.slug, name: topic.name,
                parentId: topic.parent_id,
                cluster: (topic.cluster ?? 'general') as TopicCluster,
              })
            }
            break
          }
          case 'post':
            if (ch.operation === 'delete') {
              await database.posts.delete(ch.object_id)
            } else {
              await upsertPostFromPayload(ch.payload as unknown as PostDto, database)
            }
            break
          case 'comment':
            await database.comments.put(toCommentRow(ch.payload as unknown as CommentDto))
            break
          case 'interest':
            await database.interests.put(toInterestRow(ch.payload as unknown as InterestDto))
            break
          case 'post_state':
            await applyState(ch.payload as unknown as SyncStateDto, database)
            break
        }
      }
    },
  )
  return changes.length
}

/**
 * Full bootstrap (first sync or expired cursor). Replaces server-owned data
 * and **never touches the outbox**, so pending operations survive (docs/05 §4).
 */
export async function applySnapshot(
  snap: SyncSnapshotDto,
  database: PafDatabase = db,
): Promise<void> {
  await database.transaction(
    'rw',
    database.posts,
    database.topics,
    database.comments,
    database.interests,
    async () => {
      for (const t of snap.topics) {
        const row: TopicRow = {
          id: t.id,
          slug: t.slug,
          name: t.name,
          parentId: t.parent_id,
          cluster: t.cluster as TopicCluster | undefined,
        }
        await database.topics.put(row)
      }
      const remoteIds = new Set(snap.posts.map((p) => p.id))
      for (const p of snap.posts) await upsertPostFromPayload(p, database)
      // Remove stale server-owned posts; local demo posts are never server data.
      const stale = await database.posts
        .filter((p) => !p.demo && !remoteIds.has(p.id))
        .primaryKeys()
      if (stale.length > 0) await database.posts.bulkDelete(stale)
      for (const c of snap.comments) await database.comments.put(toCommentRow(c))
      for (const i of snap.interests) await database.interests.put(toInterestRow(i))
      for (const s of snap.post_states) await applyState(s, database)
    },
  )
}

// --- push ------------------------------------------------------------------

function toOpDto(op: OutboxRow): SyncOpDto {
  return {
    op_id: op.opId,
    client_seq: op.clientSeq,
    object_type: op.objectType ?? 'post',
    object_id: op.objectId ?? op.postId,
    action: op.action,
    payload: op.payload ?? {},
    base_revision: op.baseRevision,
    occurred_at: op.occurredAt,
  }
}

interface PushOutcome {
  pushed: number
  failed: number
  conflicts: SyncOpResultDto[]
}

async function pushOutbox(): Promise<PushOutcome> {
  const deviceId = await getDeviceId()
  const outcome: PushOutcome = { pushed: 0, failed: 0, conflicts: [] }
  const pending = (await db.outbox.where('status').equals('pending').toArray()).sort(
    (a, b) => a.clientSeq - b.clientSeq,
  )
  for (let i = 0; i < pending.length; i += SYNC_BATCH) {
    const slice = pending.slice(i, i + SYNC_BATCH)
    const out = await postSyncOperations(
      deviceId,
      slice.map(toOpDto),
    )
    const byId = new Map<string, SyncOpResultDto>(out.results.map((r) => [r.op_id, r]))
    for (const op of slice) {
      const res = byId.get(op.opId)
      if (!res) continue
      if (res.status === 'applied') {
        await db.outbox.delete(op.id)
        outcome.pushed += 1
      } else if (res.status === 'conflict') {
        await db.outbox.delete(op.id)
        outcome.conflicts.push(res)
      } else {
        // Deterministic server verdict (e.g. POST_NOT_FOUND) — keep visible
        // as failed instead of retrying forever.
        const code = res.error?.code ?? 'ERROR'
        const message = res.error?.message ?? ''
        await db.outbox.update(op.id, { status: 'failed', error: `${code}: ${message}` })
        outcome.failed += 1
      }
    }
  }
  return outcome
}

/** Re-enqueue failed ops with a fresh op_id/client_seq so a real retry is possible. */
export async function retryFailedOutbox(): Promise<number> {
  const failed = await db.outbox.where('status').equals('failed').toArray()
  if (failed.length === 0) return 0
  const deviceId = await getDeviceId()
  await db.transaction('rw', db.outbox, db.sync_meta, async () => {
    for (const row of failed) {
      await db.outbox.delete(row.id)
      const clientSeq = await nextClientSeq(deviceId)
      await db.outbox.add({
        ...row,
        id: uuid(),
        opId: uuid(),
        clientSeq,
        status: 'pending',
        error: null,
      })
    }
  })
  return failed.length
}

async function refreshInterestsAfterConflict(): Promise<void> {
  const list = await getInterests()
  await db.interests.bulkPut(list.map(toInterestRow))
  await metaSet('sync_note', '兴趣设置与其它设备冲突：已拉取服务端最新值，请重新调整')
}

// --- pull ------------------------------------------------------------------

interface PullOutcome {
  pulled: number
  bootstrapped: boolean
}

async function bootstrapSnapshot(): Promise<number> {
  const snap = await getSyncSnapshot()
  await applySnapshot(snap)
  await metaSet('last_seq', snap.latest_seq)
  return snap.latest_seq
}

async function pullChanges(pullLimit = SYNC_PAGE): Promise<PullOutcome> {
  let pulled = 0
  let bootstrapped = false
  let cursor = await metaGet<number>('last_seq')
  if (cursor === undefined) {
    await bootstrapSnapshot()
    cursor = (await metaGet<number>('last_seq')) ?? 0
    bootstrapped = true
  }
  for (;;) {
    let page: SyncChangesOut
    try {
      page = await getSyncChanges(cursor, pullLimit)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'CURSOR_EXPIRED') {
        // docs/05 §4: expired cursor ⇒ snapshot; outbox was already pushed.
        await bootstrapSnapshot()
        return { pulled, bootstrapped: true }
      }
      throw err
    }
    await applyChanges(page.changes)
    pulled += page.changes.length
    cursor = page.next_seq
    await metaSet('last_seq', cursor)
    if (!page.has_more) break
  }
  return { pulled, bootstrapped }
}

// --- status / failures -----------------------------------------------------

export async function readSyncStatus(): Promise<SyncStatus> {
  const [pending, failed, lastSyncAt, lastError, note, backoff] = await Promise.all([
    db.outbox.where('status').equals('pending').count(),
    db.outbox.where('status').equals('failed').count(),
    metaGet<string>('last_sync_at'),
    metaGet<SyncStatus['lastError']>('sync_error'),
    metaGet<string>('sync_note'),
    metaGet<BackoffState>('backoff'),
  ])
  return {
    pending,
    failed,
    lastSyncAt: lastSyncAt ?? null,
    lastError: lastError ?? null,
    note: note ?? null,
    nextRetryAt: backoff && backoff.nextAt > Date.now() ? backoff.nextAt : null,
  }
}

async function noteFailure(code: string, message: string): Promise<void> {
  const prev = (await metaGet<BackoffState>('backoff')) ?? { failures: 0, nextAt: 0 }
  const failures = prev.failures + 1
  await metaSet('backoff', {
    failures,
    nextAt: Date.now() + backoffDelayMs(failures),
  })
  await metaSet('sync_error', { code, message, at: new Date().toISOString() })
}

async function noteSuccess(hadConflict: boolean): Promise<void> {
  await metaSet('backoff', { failures: 0, nextAt: 0 })
  await metaSet('sync_error', null)
  await metaSet('last_sync_at', new Date().toISOString())
  if (!hadConflict) await metaSet('sync_note', null)
}

// --- runner ----------------------------------------------------------------

/**
 * One full sync round: bootstrap if first run → push outbox → pull changes.
 * Safe to call from a timer; concurrent calls are no-ops.
 */
export async function runSync(
  opts: { force?: boolean; pullLimit?: number } = {},
): Promise<SyncRunResult> {
  if (running) return { status: 'skipped', reason: 'running' }
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return { status: 'skipped', reason: 'offline' }
  }
  const backoff = await metaGet<BackoffState>('backoff')
  if (!opts.force && backoff && backoff.nextAt > Date.now()) {
    return { status: 'skipped', reason: 'backoff' }
  }

  running = true
  try {
    let bootstrapped = false
    // First run: snapshot before push so interest ops carry current revisions.
    if ((await metaGet<number>('last_seq')) === undefined) {
      await bootstrapSnapshot()
      bootstrapped = true
    }

    const push = await pushOutbox()
    if (push.conflicts.length > 0) {
      await refreshInterestsAfterConflict()
    }

    const pull = await pullChanges(opts.pullLimit)

    await noteSuccess(push.conflicts.length > 0)
    return {
      status: 'ok',
      pushed: push.pushed,
      failed: push.failed,
      conflicts: push.conflicts.length,
      pulled: pull.pulled,
      bootstrapped: bootstrapped || pull.bootstrapped,
    }
  } catch (err) {
    const apiErr =
      err instanceof ApiError
        ? err
        : new ApiError(0, 'UNKNOWN', err instanceof Error ? err.message : '同步失败', true)
    await noteFailure(apiErr.code, apiErr.message)
    return {
      status: 'error',
      error: { code: apiErr.code, message: apiErr.message },
    }
  } finally {
    running = false
  }
}

export interface FeedRefreshResult {
  status: 'ok' | 'skipped' | 'error'
  reason?: 'offline'
  /** 本轮从服务端 feed 拉回的条数上限。 */
  limit: number
  added?: number
  updated?: number
  /** 互动 outbox 是否随本轮推过；自动轮为 null（不打乱后台退避）。 */
  sync?: SyncRunResult | null
  error?: { code: string; message: string }
}

/**
 * 主界面刷新：拉最新 50 条进本地库。
 * 手动点额外先跑一轮 `runSync({force:true, pullLimit:50})` 把攒下的互动推出去、
 * 顺带增量同步；每 30s 的自动轮**只做一次 feed 请求**，不碰退避计时器，
 * 免得和服务端故障期间的后台退避打同一通电话打两次。
 */
export async function refreshFeed(source: 'manual' | 'auto'): Promise<FeedRefreshResult> {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return { status: 'skipped', reason: 'offline', limit: FEED_PULL_LIMIT }
  }
  const sync =
    source === 'manual' ? await runSync({ force: true, pullLimit: FEED_PULL_LIMIT }) : null
  try {
    const pulled = await pullFeedToDexie(1, FEED_PULL_LIMIT)
    return {
      status: 'ok',
      limit: FEED_PULL_LIMIT,
      added: pulled.added,
      updated: pulled.updated,
      sync,
    }
  } catch (err) {
    const apiErr =
      err instanceof ApiError
        ? err
        : new ApiError(0, 'UNKNOWN', err instanceof Error ? err.message : '刷新失败', true)
    const message =
      apiErr.code === 'NETWORK' ? '连不上服务端：后端没起来或地址不对' : apiErr.message
    return { status: 'error', error: { code: apiErr.code, message }, sync, limit: FEED_PULL_LIMIT }
  }
}
