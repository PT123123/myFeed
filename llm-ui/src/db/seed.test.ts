import { beforeEach, describe, expect, it } from 'vitest'

import { db } from './index'
import { DEMO_POST_COUNT, reseedDemoData, seedIfNeeded } from './seed'

describe('seedIfNeeded', () => {
  beforeEach(async () => {
    await db.delete()
    await db.open()
  })

  it('seeds demo data once and is idempotent', async () => {
    expect(await seedIfNeeded(300)).toBe(true)
    expect(await db.posts.count()).toBe(300)
    expect(await db.interests.count()).toBeGreaterThan(0)
    expect(await db.settings.get('seededVersion')).toBeTruthy()

    expect(await seedIfNeeded(300)).toBe(false)
    expect(await db.posts.count()).toBe(300)
  })

  it('reseed rebuilds the demo corpus', async () => {
    await seedIfNeeded(100)
    await db.posts.add({ ...(await db.posts.get('demo_0001'))!, id: 'custom' })
    expect(await db.posts.count()).toBe(101)
    const rebuilt = await reseedDemoData(100)
    expect(rebuilt).toBe(100)
    expect(await db.posts.count()).toBe(100)
    expect(await db.posts.get('custom')).toBeUndefined()
  })

  it('default demo size covers the 1000-item scroll acceptance', () => {
    expect(DEMO_POST_COUNT).toBeGreaterThanOrEqual(1000)
  })

  it('does not add demos or overwrite topics when sync populated an uninitialised device', async () => {
    await seedIfNeeded(1)
    const post = (await db.posts.toArray())[0]
    await db.posts.clear()
    await db.posts.add({ ...post, id: 'server-post', demo: false })
    await db.topics.put({ id: post.topicId, slug: post.topicId, name: 'My topic', parentId: null })
    await db.settings.clear()
    expect(await seedIfNeeded()).toBe(false)
    expect(await db.posts.count()).toBe(1)
    expect((await db.topics.get(post.topicId))?.name).toBe('My topic')
    expect((await db.settings.get('seededVersion'))?.value).toBeTruthy()
  })
})
