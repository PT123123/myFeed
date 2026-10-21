import { db, getSetting, setSetting } from './index'
import { isMobileHost } from '../mobile/transport'
import { buildDemoPosts, DEMO_TOPICS } from './demoFactory'
import { CURRENT_SEED_VERSION, DEFAULT_SETTINGS, type AppSettings } from './types'

export const DEMO_POST_COUNT = 1200

/**
 * Seed demo topics/posts/interests/settings on first load.
 * Idempotent: guarded by the seededVersion setting and fingerprint uniqueness.
 */
export async function seedIfNeeded(count = DEMO_POST_COUNT): Promise<boolean> {
  if (isMobileHost()) return false
  const version = await getSetting<number>('seededVersion')
  const existing = await db.posts.count()
  if (version === CURRENT_SEED_VERSION && existing > 0) return false
  // A synced device may have content before it ever opened the feed. Never
  // overwrite its topic names or inject 1200 demos after a seed-version change.
  if (await db.posts.filter((post) => !post.demo).count()) {
    await db.transaction('rw', db.settings, async () => {
      for (const [key, value] of Object.entries(DEFAULT_SETTINGS)) {
        if (!(await db.settings.get(key))) await db.settings.put({ key, value })
      }
      await db.settings.put({ key: 'seededVersion', value: CURRENT_SEED_VERSION })
    })
    return false
  }

  const posts = buildDemoPosts(count)
  const now = new Date().toISOString()

  await db.transaction(
    'rw',
    db.posts,
    db.topics,
    db.interests,
    db.settings,
    async () => {
      await db.topics.bulkPut(DEMO_TOPICS)
      const fps = new Set((await db.posts.orderBy('fingerprint').keys()) as string[])
      const fresh = posts.filter((p) => !fps.has(p.fingerprint))
      if (fresh.length) await db.posts.bulkAdd(fresh)

      const interestCount = await db.interests.count()
      if (interestCount === 0) {
        await db.interests.bulkAdd(
          DEMO_TOPICS.map((t, i) => ({
            topicId: t.id,
            explicitWeight: i < 3 ? 0.8 : 0.5,
            inferredWeight: 0,
            blocked: false,
            updatedAt: now,
          })),
        )
      }
      for (const [key, value] of Object.entries(DEFAULT_SETTINGS)) {
        const existingSetting = await db.settings.get(key)
        if (!existingSetting) await db.settings.put({ key, value })
      }
      await db.settings.put({ key: 'seededVersion', value: CURRENT_SEED_VERSION })
    },
  )
  return true
}

export async function reseedDemoData(count = DEMO_POST_COUNT): Promise<number> {
  await db.transaction('rw', db.posts, db.settings, async () => {
    await db.posts.clear()
    await setSetting('seededVersion', 0)
  })
  const ok = await seedIfNeeded(count)
  return ok ? count : 0
}

export type { AppSettings }
