import { beforeEach, describe, expect, it } from 'vitest'

import { db } from '../db'
import type { CommentRow, InterestRow, OutboxRow, PostRow } from '../db/types'
import {
  applyBackup,
  backupFileName,
  collectBackup,
  mergePost,
  parseBackup,
  serializeBackup,
  type BackupFile,
} from './backup'

function post(over: Partial<PostRow> = {}): PostRow {
  return {
    id: 'p1',
    kind: 'fiction',
    title: '标题',
    body: '正文',
    summary: '摘要',
    topicId: 'rust',
    personaId: null,
    sourceId: null,
    sourceUrl: null,
    sourceTitle: null,
    publishedAt: null,
    fetchedAt: null,
    seriesId: null,
    status: 'published',
    qualityScore: 0.6,
    tags: ['rust'],
    demo: false,
    fingerprint: 'fp1',
    createdAt: '2026-10-01T00:00:00+00:00',
    revision: 1,
    saved: false,
    hidden: false,
    likeState: 0,
    lastReadAt: null,
    readProgress: 0,
    ...over,
  }
}

function comment(over: Partial<CommentRow> = {}): CommentRow {
  return {
    id: 'c1',
    postId: 'p1',
    parentId: null,
    personaId: null,
    authorType: 'user',
    body: '读者评论',
    createdAt: '2026-10-01T00:00:00+00:00',
    revision: 1,
    ...over,
  }
}

function interest(over: Partial<InterestRow> = {}): InterestRow {
  return {
    topicId: 'rust',
    explicitWeight: 2,
    inferredWeight: 0.5,
    blocked: false,
    revision: 1,
    updatedAt: '2026-10-01T00:00:00+00:00',
    ...over,
  }
}

function outboxRow(over: Partial<OutboxRow> = {}): OutboxRow {
  return {
    id: 'row1',
    opId: 'op1',
    deviceId: 'dev-local',
    clientSeq: 1,
    objectType: 'post',
    objectId: 'p1',
    postId: 'p1',
    action: 'save',
    payload: null,
    baseRevision: 0,
    occurredAt: '2026-10-01T00:00:00+00:00',
    status: 'pending',
    createdAt: '2026-10-01T00:00:00+00:00',
    error: null,
    ...over,
  }
}

async function seedLocal() {
  await db.posts.put(post({ id: 'p1', revision: 3, saved: true, lastReadAt: '2026-10-05T00:00:00+00:00' }))
  await db.posts.put(post({ id: 'p-local-only', title: '本机独有' }))
  await db.comments.put(comment())
  await db.topics.put({ id: 'rust', slug: 'rust', name: 'Rust', parentId: null })
  await db.interests.put(interest())
  await db.interactions.put({
    id: 'i1',
    postId: 'p1',
    kind: 'save',
    payload: null,
    deviceId: 'dev-local',
    clientSeq: 1,
    occurredAt: '2026-10-01T00:00:00+00:00',
  })
  await db.outbox.put(outboxRow())
  await db.settings.put({ key: 'authToken', value: 'sekret-token-value' })
  await db.settings.put({ key: 'theme', value: 'dark' })
  await db.settings.put({ key: 'seededVersion', value: 1 })
  await db.sync_meta.put({ key: 'device_id', value: 'dev-local' })
  await db.sync_meta.put({ key: 'last_seq', value: 7 })
}

function fileFrom(other: Partial<BackupFile> = {}): BackupFile {
  return {
    app: 'personal-ai-feed',
    formatVersion: 1,
    schemaVersion: 1,
    exportedAt: '2026-10-10T00:00:00+00:00',
    deviceId: 'dev-other',
    counts: {},
    tables: {
      posts: [],
      comments: [],
      topics: [],
      interests: [],
      interactions: [],
      outbox: [],
      generationJobs: [],
      settings: [],
      syncMeta: [],
    },
    ...other,
  }
}

beforeEach(async () => {
  await Promise.all([
    db.posts.clear(),
    db.comments.clear(),
    db.topics.clear(),
    db.interests.clear(),
    db.interactions.clear(),
    db.outbox.clear(),
    db.generation_jobs_cache.clear(),
    db.settings.clear(),
    db.sync_meta.clear(),
  ])
})

describe('backup file shape', () => {
  it('names files with only characters the shell accepts', () => {
    const name = backupFileName(new Date('2026-10-11T03:04:05'))
    expect(name).toMatch(/^paf-backup-\d{4}-\d{2}-\d{2}T\d{6}\.json$/)
  })

  it('never writes the access token or the device identity into a backup', async () => {
    await seedLocal()
    const file = await collectBackup()
    expect(file.tables.settings.map((s) => s.key)).not.toContain('authToken')
    expect(file.tables.syncMeta.map((m) => m.key)).not.toContain('device_id')
    expect(file.deviceId).toBe('dev-local')
    expect(file.counts.posts).toBe(2)
    const text = await serializeBackup()
    expect(text).not.toContain('sekret-token-value')
  })

  it('refuses a truncated or non-JSON file', () => {
    expect(() => parseBackup('{"app":"personal-ai-feed",')).toThrow(/不是有效的 JSON/)
    expect(() => parseBackup('[1,2,3]')).toThrow(/不是一个对象/)
  })

  it('refuses a foreign file and files from an unknown format version', () => {
    expect(() => parseBackup(JSON.stringify({ app: 'other', formatVersion: 1, tables: {} }))).toThrow(
      /别的程序导出/,
    )
    const tooNew = JSON.stringify(fileFrom({ formatVersion: 99 }))
    expect(() => parseBackup(tooNew)).toThrow(/请先升级应用/)
    const tooOld = JSON.stringify(fileFrom({ formatVersion: 0 }))
    expect(() => parseBackup(tooOld)).toThrow(/迁移一次/)
  })

  it('reports which block is broken instead of guessing', () => {
    const broken = JSON.stringify({
      app: 'personal-ai-feed',
      formatVersion: 1,
      tables: { posts: [{ title: '缺少 id' }], comments: [], topics: [], interests: [], interactions: [], outbox: [] },
    })
    expect(() => parseBackup(broken)).toThrow(/posts 有 1 条记录缺少 id/)
    const missing = JSON.stringify({
      app: 'personal-ai-feed',
      formatVersion: 1,
      tables: { comments: [], topics: [], interests: [], interactions: [], outbox: [] },
    })
    expect(() => parseBackup(missing)).toThrow(/posts 区块缺失/)
  })
})

describe('applyBackup', () => {
  it('round-trips a whole library through replace', async () => {
    await seedLocal()
    const text = await serializeBackup()
    const file = parseBackup(text)

    await db.posts.clear()
    await db.comments.clear()
    await db.interests.clear()

    const summary = await applyBackup(file, 'replace')
    expect(summary.postsAdded).toBe(2)
    // topics + interactions survived the partial clear, so replace discards 2 rows
    expect(summary.clearedRows).toBe(2)
    expect(await db.posts.count()).toBe(2)
    expect(await db.comments.count()).toBe(1)
    expect((await db.interests.get('rust'))?.explicitWeight).toBe(2)
    expect(await db.settings.get('theme')).toEqual({ key: 'theme', value: 'dark' })
  })

  it('keeps newer local content and unions reading state on merge', async () => {
    await seedLocal()
    const file = fileFrom({
      tables: {
        ...fileFrom().tables,
        posts: [
          post({
            id: 'p1',
            title: '旧标题',
            revision: 2,
            saved: false,
            lastReadAt: '2026-10-02T00:00:00+00:00',
            readProgress: 0.4,
          }),
          post({ id: 'p-other', title: '对端独有' }),
        ],
      },
    })
    const summary = await applyBackup(file, 'merge')
    expect(summary.postsUpdated).toBe(1)
    expect(summary.postsAdded).toBe(1)
    const p1 = await db.posts.get('p1')
    expect(p1?.title).toBe('标题') // revision 3 beats revision 2
    expect(p1?.saved).toBe(true)
    expect(p1?.lastReadAt).toBe('2026-10-05T00:00:00+00:00')
    expect(p1?.readProgress).toBe(0.4)
    expect(await db.posts.get('p-local-only')).toBeTruthy()
    expect(await db.posts.get('p-other')).toBeTruthy()
  })

  it('adopts the higher-revision interest but never a foreign device identity', async () => {
    await seedLocal()
    const file = fileFrom({
      tables: {
        ...fileFrom().tables,
        interests: [interest({ revision: 5, explicitWeight: 4 })],
        syncMeta: [
          { key: 'device_id', value: 'dev-from-backup' },
          { key: 'last_seq', value: 42 },
          { key: 'client_seq:dev-from-backup', value: 9 },
        ],
      },
    })
    await applyBackup(file, 'merge')
    expect((await db.interests.get('rust'))?.revision).toBe(5)
    // Another device's id and its seq counters must not land in this library.
    expect((await db.sync_meta.get('device_id'))?.value).toBe('dev-local')
    expect(await db.sync_meta.get('client_seq:dev-from-backup')).toBeUndefined()
    expect((await db.sync_meta.get('last_seq'))?.value).toBe(7)
  })

  it('resets the change-log cursor for a replace so a stale cursor cannot skip changes', async () => {
    await seedLocal()
    const file = fileFrom({
      tables: {
        ...fileFrom().tables,
        posts: [post({ id: 'p-file' })],
        syncMeta: [{ key: 'last_seq', value: 42 }],
      },
    })
    await applyBackup(file, 'replace')
    expect((await db.sync_meta.get('last_seq'))?.value).toBe(42)
    expect((await db.sync_meta.get('device_id'))?.value).toBe('dev-local')
  })

  it('skips ops the device already knows and queues foreign pending ops for undo', async () => {
    await seedLocal()
    const file = fileFrom({
      tables: {
        ...fileFrom().tables,
        outbox: [
          outboxRow({ id: 'row1', opId: 'op1' }),
          outboxRow({ id: 'row2', opId: 'op2', deviceId: 'dev-other', clientSeq: 3 }),
          outboxRow({ id: 'row3', opId: 'op3', deviceId: 'dev-local', status: 'synced' }),
        ],
      },
    })
    const summary = await applyBackup(file, 'merge')
    expect(summary.outboxQueued).toBe(1)
    expect(summary.outboxSkipped).toBe(2)
    expect(summary.queuedOpIds).toEqual(['row2'])
    // Undo path: dropping exactly the queued ids leaves the local op alone.
    await db.outbox.where('id').anyOf(summary.queuedOpIds).delete()
    expect(await db.outbox.count()).toBe(1)
    expect((await db.outbox.get('row1'))?.opId).toBe('op1')
  })

  it('takes the max seed version instead of an older one', async () => {
    await seedLocal()
    await applyBackup(
      fileFrom({ tables: { ...fileFrom().tables, settings: [{ key: 'seededVersion', value: 0 }] } }),
      'merge',
    )
    expect((await db.settings.get('seededVersion'))?.value).toBe(1)
  })

  it('rolls the whole import back when a write fails halfway', async () => {
    await seedLocal()
    const file = fileFrom({
      tables: {
        ...fileFrom().tables,
        posts: [
          post({ id: 'ok1' }),
          // A function value cannot be stored: Dexie fails inside the transaction.
          post({ id: 'ok2', tags: [(() => 1) as unknown as string] }),
        ],
      },
    })
    await expect(applyBackup(file, 'merge')).rejects.toThrow()
    expect(await db.posts.get('ok1')).toBeUndefined()
    expect(await db.posts.count()).toBe(2)
  })
})

describe('mergePost', () => {
  it('prefers the incoming revision and keeps the stronger like signal', () => {
    const local = post({ revision: 1, likeState: 1, readProgress: 0.2 })
    const incoming = post({ revision: 4, title: '服务端新标题', likeState: 0, readProgress: 0.9 })
    const merged = mergePost(local, incoming)
    expect(merged.title).toBe('服务端新标题')
    // 取消点赞（0）不该盖掉更强的信号，与 B06 服务端 |value| 取大一致
    expect(merged.likeState).toBe(1)
    expect(merged.readProgress).toBe(0.9)
  })

  it('adopts an equal-strength incoming reaction and keeps the newer content', () => {
    const local = post({ revision: 4, likeState: 1, title: '本机标题' })
    const opposite = post({ revision: 2, likeState: -1 })
    expect(mergePost(local, opposite).likeState).toBe(-1)
    expect(mergePost(local, opposite).title).toBe('本机标题')
    expect(mergePost(local, post({ revision: 9, likeState: 1 })).title).toBe('标题')
  })
})
