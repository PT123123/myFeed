import { beforeEach, describe, expect, it } from 'vitest'

import { db, getDeviceId } from './index'
import { buildDemoPosts } from './demoFactory'
import { applyLocalAction, pendingOutboxCount } from '../store/interactions'

describe('local-first persistence (fake IndexedDB)', () => {
  beforeEach(async () => {
    await db.delete()
    await db.open()
    const posts = buildDemoPosts(20)
    await db.posts.bulkAdd(posts)
    await db.topics.bulkPut([
      { id: 'rust-gui', slug: 'rust-gui', name: 'Rust GUI', parentId: null },
    ])
  })

  it('keeps posts after reopening the database (refresh simulation)', async () => {
    expect(await db.posts.count()).toBe(20)
    db.close()
    await db.open()
    expect(await db.posts.count()).toBe(20)
  })

  it('applies like + save atomically and queues outbox ops', async () => {
    await applyLocalAction('demo_0001', 'like')
    await applyLocalAction('demo_0001', 'save')
    const post = await db.posts.get('demo_0001')
    expect(post?.likeState).toBe(1)
    expect(post?.saved).toBe(true)
    expect(post?.lastReadAt).toBeTruthy()

    expect(await db.interactions.where('postId').equals('demo_0001').count()).toBe(2)
    expect(await pendingOutboxCount()).toBe(2)

    const outbox = await db.outbox.where('postId').equals('demo_0001').toArray()
    expect(outbox.every((o) => o.status === 'pending')).toBe(true)
    expect(new Set(outbox.map((o) => o.clientSeq)).size).toBe(2)
  })

  it('toggles like off and hides posts', async () => {
    await applyLocalAction('demo_0002', 'like')
    await applyLocalAction('demo_0002', 'like')
    expect((await db.posts.get('demo_0002'))?.likeState).toBe(0)

    await applyLocalAction('demo_0002', 'hide')
    expect((await db.posts.get('demo_0002'))?.hidden).toBe(true)
  })

  it('assigns a stable device id', async () => {
    const a = await getDeviceId()
    const b = await getDeviceId()
    expect(a).toBe(b)
  })

  it('ignores actions on missing posts', async () => {
    await applyLocalAction('nope', 'like')
    expect(await db.outbox.count()).toBe(0)
  })
})
