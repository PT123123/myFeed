import Dexie, { type EntityTable } from 'dexie'

import type {
  AppSettings,
  CommentRow,
  GenerationJobRow,
  InterestRow,
  InteractionRow,
  OutboxRow,
  PostRow,
  TopicRow,
} from './types'

export class PafDatabase extends Dexie {
  posts!: EntityTable<PostRow, 'id'>
  comments!: EntityTable<CommentRow, 'id'>
  topics!: EntityTable<TopicRow, 'id'>
  interests!: EntityTable<InterestRow, 'topicId'>
  interactions!: EntityTable<InteractionRow, 'id'>
  outbox!: EntityTable<OutboxRow, 'id'>
  sync_meta!: EntityTable<{ key: string; value: unknown }, 'key'>
  generation_jobs_cache!: EntityTable<GenerationJobRow, 'id'>
  settings!: EntityTable<{ key: string; value: unknown }, 'key'>

  constructor(name = 'paf') {
    super(name)
    this.version(1).stores({
      posts:
        'id, kind, topicId, createdAt, status, fingerprint, saved, hidden, [topicId+createdAt]',
      comments: 'id, postId, parentId, createdAt, [postId+createdAt]',
      topics: 'id, slug, name',
      interests: 'topicId, blocked, updatedAt',
      interactions: 'id, postId, kind, deviceId, occurredAt, [deviceId+clientSeq]',
      outbox: 'id, status, createdAt, postId, [status+createdAt]',
      sync_meta: 'key',
      generation_jobs_cache: 'id, status, nextRunAt',
      settings: 'key',
    })
  }
}

export const db = new PafDatabase()

export async function getSetting<T>(key: keyof AppSettings): Promise<T> {
  const row = await db.settings.get(key)
  return row?.value as T
}

export async function setSetting<K extends keyof AppSettings>(
  key: K,
  value: AppSettings[K],
): Promise<void> {
  await db.settings.put({ key, value })
}

export async function getDeviceId(): Promise<string> {
  const existing = await db.sync_meta.get('device_id')
  if (typeof existing?.value === 'string') return existing.value
  const id =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `dev_${Math.random().toString(36).slice(2)}_${Date.now()}`
  await db.sync_meta.put({ key: 'device_id', value: id })
  return id
}

export async function nextClientSeq(deviceId: string): Promise<number> {
  const key = `client_seq:${deviceId}`
  const row = await db.sync_meta.get(key)
  const next = (typeof row?.value === 'number' ? row.value : 0) + 1
  await db.sync_meta.put({ key, value: next })
  return next
}
