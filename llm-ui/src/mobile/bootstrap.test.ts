import { beforeEach, describe, expect, it, vi } from 'vitest'
import { db } from '../db'
import { DEFAULT_SETTINGS } from '../db/types'
import { seedIfNeeded } from '../db/seed'
import { bootstrapMobile } from './bootstrap'

const legacy = vi.hoisted(() => vi.fn())
vi.mock('./transport', () => ({
  initialBaseUrl: () => 'http://reader.example:8000',
  isMobileHost: () => true,
  nativeRequest: legacy,
}))

beforeEach(async () => {
  await db.delete()
  await db.open()
  legacy.mockReset().mockResolvedValue({ value: [] })
})

describe('mobile install and upgrade', () => {
  it('starts with the saved computer connection and no fabricated demo posts or interests', async () => {
    await bootstrapMobile()
    await seedIfNeeded()
    expect(await db.posts.count()).toBe(0)
    expect(await db.interests.count()).toBe(0)
    expect((await db.settings.get('apiBaseUrl'))?.value).toBe('http://reader.example:8000')
  })

  it('imports the previous native AI cache once without replacing existing feedback', async () => {
    const row = {
      id: 'cached-post', title: '缓存内容', body: '可离线阅读的正文', summary: '缓存摘要',
      kind: 'synthetic_explainer', sourceUrl: '', sourceTitle: '电脑端',
      createdAt: '2026-10-20T00:00:00Z', lastReadAt: '2026-10-20T01:00:00Z',
    }
    legacy.mockResolvedValue({ value: [row] })
    await bootstrapMobile()
    expect((await db.posts.get(row.id))?.lastReadAt).toBe(row.lastReadAt)
    await db.posts.update(row.id, { saved: true, body: '服务器已更新的正文' })
    await bootstrapMobile()
    expect(legacy).toHaveBeenCalledTimes(1)
    expect((await db.posts.get(row.id))?.saved).toBe(true)
    expect((await db.posts.get(row.id))?.body).toBe('服务器已更新的正文')
    expect(await db.outbox.count()).toBe(0)
    expect((await db.settings.get('unreadTarget'))?.value).toBe(DEFAULT_SETTINGS.unreadTarget)
  })
})
