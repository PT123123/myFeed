import { beforeEach, describe, expect, it, vi } from 'vitest'

import { buildDemoPosts } from '../db/demoFactory'
import { db, getDeviceId, nextClientSeq } from '../db'
import type { PostRow } from '../db/types'
import { queueInterestOp } from '../store/interactions'
import { curationFields, pullFeedToDexie } from './api'
import {
  applyChanges,
  applySnapshot,
  backoffDelayMs,
  FEED_PULL_LIMIT,
  readSyncStatus,
  refreshFeed,
  retryFailedOutbox,
  runSync,
} from './sync'
import type {
  PostDto,
  SyncChangeDto,
  SyncSnapshotDto,
  SyncStateDto,
} from './types'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

it('applies imported topic names and clusters on an existing sync cursor', async () => {
  const id = 'imported-topic-only-test'
  await applyChanges([{
    seq: 9001, object_type: 'topic', object_id: id, operation: 'upsert', revision: 0,
    payload: { id, slug: id, name: 'Imported display name', cluster: 'music-language', parent_id: null },
    changed_at: '2026-10-13T00:00:00Z',
  }])
  expect(await db.topics.get(id)).toMatchObject({ name: 'Imported display name', cluster: 'music-language' })
  await db.topics.delete(id)
})

type Handler = (url: string, init?: RequestInit) => Response | undefined

function mockFetch(handler: Handler) {
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const resp = handler(url, init)
    if (!resp) throw new Error(`unhandled fetch: ${url}`)
    return resp
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function postDto(id: string): PostDto {
  return {
    id,
    kind: 'fiction',
    title: `Server post ${id}`,
    body: 'body',
    summary: 'summary',
    topic_id: 'rust-gui',
    persona_id: null,
    source_id: null,
    source_url: null,
    source_title: null,
    published_at: null,
    fetched_at: null,
    series_id: null,
    quality_score: 0.5,
    recommend_reason: '',
    evidence_grade: 'ungraded',
    content_type: '',
    suggested_action: 'read',
    priority: 'medium',
    tags: [],
    demo: false,
    created_at: '2026-10-10T00:00:00+00:00',
    revision: 1,
  }
}

function stateDto(postId: string, over: Partial<SyncStateDto> = {}): SyncStateDto {
  return {
    post_id: postId,
    saved: true,
    like_state: 0,
    hidden: false,
    read_progress: 0.7,
    last_read_at: '2026-10-11T00:00:00+00:00',
    revision: 2,
    updated_at: '2026-10-11T00:00:00+00:00',
    ...over,
  }
}

function snapshotFixture(over: Partial<SyncSnapshotDto> = {}): SyncSnapshotDto {
  return {
    latest_seq: 9,
    server_time: '2026-10-11T02:00:00+00:00',
    topics: [{ id: 'rust-gui', slug: 'rust-gui', name: 'Rust GUI', parent_id: null }],
    posts: [postDto('srv-1')],
    comments: [],
    interests: [
      {
        topic_id: 'rust-gui',
        explicit_weight: 0.6,
        inferred_weight: 0.1,
        depth_bias: 0,
        blocked: false,
        revision: 2,
        updated_at: '2026-10-10T00:00:00+00:00',
      },
    ],
    post_states: [],
    ...over,
  }
}

function change(
  over: Partial<Omit<SyncChangeDto, 'payload'>> & { seq: number; payload?: unknown },
): SyncChangeDto {
  return {
    object_type: 'post',
    object_id: 'demo_0001',
    operation: 'upsert',
    revision: 1,
    payload: {},
    changed_at: '2026-10-11T00:00:00+00:00',
    ...over,
  } as SyncChangeDto
}

async function seedOutbox(n: number, status: 'pending' | 'failed' = 'pending') {
  const deviceId = await getDeviceId()
  const rows = []
  for (let i = 0; i < n; i++) {
    rows.push({
      id: `ob_${i}`,
      opId: `op_${i}`,
      deviceId,
      clientSeq: await nextClientSeq(deviceId),
      objectType: 'post' as const,
      objectId: 'demo_0001',
      postId: 'demo_0001',
      action: 'like' as const,
      payload: null,
      baseRevision: 1,
      occurredAt: new Date().toISOString(),
      status,
      createdAt: new Date().toISOString(),
      error: status === 'failed' ? 'POST_NOT_FOUND: 帖子不存在' : null,
    })
  }
  await db.outbox.bulkAdd(rows)
}

describe('B06 sync engine', () => {
  it('manual pull applies a corrected demo flag and score while preserving local interactions', async () => {
    const post = (await db.posts.get('demo_0001'))!
    await db.posts.put({ ...post, demo: false, saved: true, likeState: 1, readProgress: .8 })
    mockFetch((url) => url.includes('/feed?') ? json({ items: [{ ...postDto(post.id),
      demo: true, quality_score: .25, revision: 2 }], next_cursor: null }) : undefined)
    await pullFeedToDexie(1)
    expect(await db.posts.get(post.id)).toMatchObject({ demo: true, qualityScore: .25,
      revision: 2, saved: true, likeState: 1, readProgress: .8 })
  })
  it('manual feed pull preserves a synced topic display name and cluster', async () => {
    await db.topics.put({ id: 'rust-gui', slug: 'rust-gui', name: 'Synced name', parentId: null, cluster: 'ai-software' })
    mockFetch((url) => url.includes('/feed?') ? json({ items: [postDto('manual-pull')], next_cursor: null }) : undefined)
    await pullFeedToDexie(1)
    expect(await db.topics.get('rust-gui')).toMatchObject({ name: 'Synced name', cluster: 'ai-software' })
  })

  beforeEach(async () => {
    vi.unstubAllGlobals()
    await db.delete()
    await db.open()
    await db.posts.bulkAdd(buildDemoPosts(5))
    await db.topics.put({ id: 'rust-gui', slug: 'rust-gui', name: 'Rust GUI', parentId: null })
  })

  it('applyChanges merges post_state, writes comments and interests', async () => {
    const local = (await db.posts.get('demo_0001')) as PostRow
    await db.posts.put({ ...local, readProgress: 0.4, saved: false })

    await applyChanges([
      change({ seq: 1, object_type: 'post_state', object_id: 'demo_0001', payload: stateDto('demo_0001') }),
      change({
        seq: 2,
        object_type: 'comment',
        object_id: 'c-1',
        payload: {
          id: 'c-1',
          post_id: 'demo_0001',
          parent_id: null,
          persona_id: null,
          persona_name: null,
          author_type: 'user',
          is_ai: false,
          body: '跨端评论',
          created_at: '2026-10-11T00:00:00+00:00',
          revision: 1,
        },
      }),
      change({
        seq: 3,
        object_type: 'interest',
        object_id: 'rust-gui',
        payload: {
          topic_id: 'rust-gui',
          explicit_weight: 0.8,
          inferred_weight: 0.1,
          blocked: true,
          revision: 3,
          updated_at: '2026-10-11T00:00:00+00:00',
        },
      }),
      change({ seq: 4, object_type: 'post', object_id: 'demo_0002', operation: 'delete' }),
    ])

    const merged = await db.posts.get('demo_0001')
    expect(merged?.saved).toBe(true)
    expect(merged?.readProgress).toBe(0.7) // max(local 0.4, server 0.7)
    expect(await db.comments.get('c-1')).toBeTruthy()
    const interest = await db.interests.get('rust-gui')
    expect(interest?.revision).toBe(3)
    expect(interest?.blocked).toBe(true)
    expect(await db.posts.get('demo_0002')).toBeUndefined() // tombstone
  })

  it('keeps curation badges when a post change arrives with blank fields', async () => {
    const local = (await db.posts.get('demo_0001')) as PostRow
    await db.posts.put({
      ...local,
      recommendReason: '你最近连着收藏了三篇同类长文',
      evidenceGrade: 'human_study',
      contentType: 'research',
      suggestedAction: 'collect',
      priority: 'high',
    })
    // A change recorded before the curation columns existed (or one that leaves
    // a field blank) must not wipe the badges; only carried values overwrite.
    await applyChanges([
      change({
        seq: 1,
        object_type: 'post',
        object_id: 'demo_0001',
        payload: {
          ...postDto('demo_0001'),
          title: '服务端改写后的标题',
          recommend_reason: '',
          evidence_grade: '',
          content_type: '',
          suggested_action: '',
          priority: '',
        },
      }),
    ])
    const row = (await db.posts.get('demo_0001')) as PostRow
    expect(row.title).toBe('服务端改写后的标题') // content still follows the server
    expect(row.recommendReason).toBe('你最近连着收藏了三篇同类长文')
    expect(row.evidenceGrade).toBe('human_study')
    expect(row.contentType).toBe('research')
    expect(row.suggestedAction).toBe('collect')
    expect(row.priority).toBe('high')
  })

  it('coerces blank curation values to undefined rather than inventing a grade', () => {
    expect(curationFields(postDto('x'))).toMatchObject({
      evidenceGrade: 'ungraded',
      contentType: undefined,
      suggestedAction: 'read',
      priority: 'medium',
    })
    expect(curationFields(postDto('x')).recommendReason).toBeUndefined()
    expect(curationFields({ ...postDto('x'), content_type: 'tool' }).contentType).toBe('tool')
  })

  it('carries depth_bias from an interest change onto the local row', async () => {
    await applyChanges([
      change({
        seq: 1,
        object_type: 'interest',
        object_id: 'rust-gui',
        payload: {
          topic_id: 'rust-gui',
          explicit_weight: 0.6,
          inferred_weight: 0.05,
          depth_bias: 0.25,
          blocked: false,
          revision: 4,
          updated_at: '2026-10-11T00:00:00+00:00',
        },
      }),
    ])
    const row = await db.interests.get('rust-gui')
    expect(row?.depthBias).toBe(0.25)
    expect(row?.explicitWeight).toBe(0.6)
    expect(row?.inferredWeight).toBe(0.05)
  })

  it('applySnapshot bootstraps server data but keeps demo posts and outbox', async () => {
    // stale server-owned post + a local demo post + one pending op
    const stale: PostRow = {
      ...(await db.posts.get('demo_0001') as PostRow),
      id: 'srv-0',
      demo: false,
    }
    await db.posts.put(stale)
    await queueInterestOp('rust-gui', 0.9, false) // pending op must survive
    expect(await db.outbox.where('status').equals('pending').count()).toBe(1)

    await applySnapshot(snapshotFixture({ post_states: [stateDto('demo_0001')] }))

    expect(await db.posts.get('srv-0')).toBeUndefined() // stale removed
    expect(await db.posts.get('srv-1')).toBeTruthy() // bootstrap added
    const demo = await db.posts.get('demo_0001')
    expect(demo?.demo).toBe(true) // local demo kept
    expect(demo?.saved).toBe(true) // state applied
    expect(await db.topics.get('rust-gui')).toBeTruthy()
    expect((await db.interests.get('rust-gui'))?.revision).toBe(2)
    expect(await db.outbox.where('status').equals('pending').count()).toBe(1) // preserved
  })

  it('runSync bootstraps on first run, pushes in batches, pulls changes', async () => {
    await seedOutbox(120)
    const opsBodies: number[] = []
    const spy = mockFetch((url, init) => {
      if (url.includes('/sync/snapshot')) return json(snapshotFixture())
      if (url.includes('/sync/operations')) {
        const body = JSON.parse(String(init?.body)) as { operations: unknown[] }
        opsBodies.push(body.operations.length)
        return json({
          results: body.operations.map((op) => ({
            op_id: (op as { op_id: string }).op_id,
            status: 'applied',
            revision: 1,
          })),
          latest_seq: 9,
        })
      }
      if (url.includes('/sync/changes')) {
        const since = Number(new URL(url).searchParams.get('since_seq'))
        return json({ changes: [], next_seq: since, has_more: false })
      }
      return undefined
    })

    const result = await runSync({ force: true })
    expect(result.status).toBe('ok')
    expect(result.bootstrapped).toBe(true)
    expect(result.pushed).toBe(120)
    expect(opsBodies).toEqual([50, 50, 20]) // 分批上传
    expect(await db.outbox.count()).toBe(0)

    const status = await readSyncStatus()
    expect(status.pending).toBe(0)
    expect(status.lastSyncAt).toBeTruthy()
    expect(status.lastError).toBeNull()
    expect(await db.sync_meta.get('last_seq')).toMatchObject({ value: 9 })
    expect(spy).toHaveBeenCalled()
  })

  it('network failure keeps ops pending and schedules exponential backoff', async () => {
    await db.sync_meta.put({ key: 'last_seq', value: 5 })
    await seedOutbox(3)
    mockFetch(() => {
      throw new TypeError('fetch failed')
    })

    const result = await runSync({ force: true })
    expect(result.status).toBe('error')
    expect(await db.outbox.where('status').equals('pending').count()).toBe(3)

    const status = await readSyncStatus()
    expect(status.nextRetryAt).not.toBeNull()
    expect(status.lastError?.code).toBeTruthy()

    // Respecting the backoff window (unless forced):
    const skipped = await runSync()
    expect(skipped).toEqual({ status: 'skipped', reason: 'backoff' })

    expect(backoffDelayMs(1)).toBe(5000)
    expect(backoffDelayMs(2)).toBe(10000)
    expect(backoffDelayMs(20)).toBe(300_000) // capped
  })

  it('expired cursor falls back to snapshot without losing queued ops', async () => {
    await db.sync_meta.put({ key: 'last_seq', value: 1 })
    await seedOutbox(1, 'failed') // a failed op must survive the bootstrap

    const spy = mockFetch((url) => {
      if (url.includes('/sync/changes')) {
        return json(
          { detail: { code: 'CURSOR_EXPIRED', message: '游标过期', retryable: true } },
          410,
        )
      }
      if (url.includes('/sync/snapshot')) return json(snapshotFixture())
      return undefined
    })

    const result = await runSync({ force: true })
    expect(result.status).toBe('ok')
    expect(result.bootstrapped).toBe(true)
    expect(await db.sync_meta.get('last_seq')).toMatchObject({ value: 9 })
    expect(await db.outbox.where('status').equals('failed').count()).toBe(1)
    expect(spy).toHaveBeenCalled()
    expect(await db.posts.get('srv-1')).toBeTruthy()
  })

  it('interest conflict re-pulls server truth and leaves a note', async () => {
    await db.sync_meta.put({ key: 'last_seq', value: 5 })
    await db.interests.put({
      topicId: 'rust-gui',
      explicitWeight: 0.9,
      inferredWeight: 0,
      blocked: false,
      revision: 4,
      updatedAt: '2026-10-11T00:00:00+00:00',
    })
    await queueInterestOp('rust-gui', 0.9, false)
    expect(await db.outbox.where('status').equals('pending').count()).toBe(1)

    mockFetch((url, init) => {
      if (url.includes('/sync/operations')) {
        const body = JSON.parse(String(init?.body)) as { operations: { op_id: string }[] }
        return json({
          results: body.operations.map((op) => ({
            op_id: op.op_id,
            status: 'conflict',
            revision: 3,
            error: {
              code: 'REVISION_CONFLICT',
              message: '兴趣设置已被其它设备修改',
              retryable: false,
            },
          })),
          latest_seq: 6,
        })
      }
      if (url.includes('/interests')) {
        return json([
          {
            topic_id: 'rust-gui',
            explicit_weight: 0.2,
            inferred_weight: 0.1,
            blocked: false,
            revision: 3,
            updated_at: '2026-10-11T01:00:00+00:00',
          },
        ])
      }
      if (url.includes('/sync/changes')) {
        const since = Number(new URL(url).searchParams.get('since_seq'))
        return json({ changes: [], next_seq: since, has_more: false })
      }
      return undefined
    })

    const result = await runSync({ force: true })
    expect(result.status).toBe('ok')
    expect(result.conflicts).toBe(1)

    const row = await db.interests.get('rust-gui')
    expect(row?.explicitWeight).toBe(0.2) // server truth wins
    expect(row?.revision).toBe(3)
    expect(await db.outbox.where('status').equals('pending').count()).toBe(0)
    const status = await readSyncStatus()
    expect(status.note).toContain('冲突')
  })

  it('retryFailedOutbox re-enqueues failed ops with a fresh op id', async () => {
    await seedOutbox(2, 'failed')
    const before = await db.outbox.toArray()

    const n = await retryFailedOutbox()
    expect(n).toBe(2)
    const rows = await db.outbox.toArray()
    expect(rows.every((r) => r.status === 'pending')).toBe(true)
    expect(rows.every((r) => r.error === null)).toBe(true)
    expect(new Set(rows.map((r) => r.opId)).size).toBe(2)
    expect(Math.min(...rows.map((r) => r.clientSeq))).toBeGreaterThan(
      Math.max(...before.map((r) => r.clientSeq)),
    )
  })

  it('刷新这一轮拉 50 条：feed 请求带 limit=50，新帖写进本地库', async () => {
    vi.stubGlobal('navigator', { onLine: true })
    await db.sync_meta.put({ key: 'last_seq', value: 9 })
    const known = { ...postDto('demo_0001'), title: '服务端改写过的标题' }
    const fresh = postDto('fresh-post')
    const urls: string[] = []
    mockFetch((url) => {
      urls.push(url)
      if (url.includes('/sync/changes')) {
        const since = Number(new URL(url).searchParams.get('since_seq'))
        return json({ changes: [], next_seq: since, has_more: false })
      }
      if (url.includes('/feed?')) {
        return json({ items: [known, fresh], next_cursor: 'c2' })
      }
      return undefined
    })

    const result = await refreshFeed('manual')
    expect(FEED_PULL_LIMIT).toBe(50)
    expect(result.status).toBe('ok')
    expect(result.limit).toBe(50)
    expect(result.added).toBe(1)
    expect(result.updated).toBe(1)
    // 只有 feed 那一页 50 条：游标存在也不翻第二页
    expect(urls.filter((u) => u.includes('/feed?'))).toHaveLength(1)
    expect(new URL(urls.find((u) => u.includes('/feed?'))!).searchParams.get('limit')).toBe('50')
    expect(await db.posts.get('fresh-post')).toBeTruthy()
    expect((await db.posts.get('demo_0001'))?.title).toBe('服务端改写过的标题')
  })

  it('后台同步仍按 500 一页拉，刷新把游标改成 50', async () => {
    vi.stubGlobal('navigator', { onLine: true })
    await db.sync_meta.put({ key: 'last_seq', value: 9 })
    const changesUrls: string[] = []
    mockFetch((url) => {
      if (url.includes('/sync/changes')) {
        changesUrls.push(new URL(url).searchParams.get('limit') ?? '')
        const since = Number(new URL(url).searchParams.get('since_seq'))
        return json({ changes: [], next_seq: since, has_more: false })
      }
      if (url.includes('/feed?')) return json({ items: [], next_cursor: null })
      return undefined
    })

    await runSync({ force: true })
    expect(changesUrls).toEqual(['500'])
    await refreshFeed('manual')
    expect(changesUrls).toEqual(['500', '50'])
  })

  it('自动那一轮只发 feed 请求，不碰 outbox 也不打乱后台退避', async () => {
    vi.stubGlobal('navigator', { onLine: true })
    await db.sync_meta.put({ key: 'last_seq', value: 9 })
    await seedOutbox(3)
    const urls: string[] = []
    mockFetch((url) => {
      urls.push(url)
      if (url.includes('/sync/changes')) {
        const since = Number(new URL(url).searchParams.get('since_seq'))
        return json({ changes: [], next_seq: since, has_more: false })
      }
      if (url.includes('/feed?')) return json({ items: [postDto('auto-post')], next_cursor: null })
      return undefined
    })

    const result = await refreshFeed('auto')
    expect(result.status).toBe('ok')
    expect(result.sync).toBeNull()
    expect(urls.every((u) => u.includes('/feed?'))).toBe(true)
    expect(urls).toHaveLength(1)
    expect(await db.outbox.where('status').equals('pending').count()).toBe(3) // 自动轮不推
    expect(await db.posts.get('auto-post')).toBeTruthy()
  })
})
