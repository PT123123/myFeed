import { db } from '../db'
import { CURRENT_SEED_VERSION, DEFAULT_SETTINGS, type PostKind, type PostRow } from '../db/types'
import { initialBaseUrl, nativeRequest } from './transport'

/** Fresh mobile installs start empty; the old native AI snapshot stays readable during migration. */
export async function bootstrapMobile(): Promise<void> {
  await db.transaction('rw', db.settings, async () => {
    for (const [key, value] of Object.entries(DEFAULT_SETTINGS)) {
      if (!(await db.settings.get(key))) await db.settings.put({ key, value })
    }
    const inherited = initialBaseUrl()
    if (inherited) await db.settings.put({ key: 'apiBaseUrl', value: inherited })
    await db.settings.put({ key: 'seededVersion', value: CURRENT_SEED_VERSION })
  })
  if (!(await db.sync_meta.get('native_cache_imported'))) {
    const reply = await nativeRequest('legacy')
    const rows = (reply.value ?? []) as Array<{
      id: string; title: string; body: string; summary: string; kind: PostKind;
      sourceUrl: string; sourceTitle: string; createdAt: string; lastReadAt: string | null;
    }>
    await db.transaction('rw', db.posts, db.sync_meta, async () => {
      for (const row of rows) {
        if (await db.posts.get(row.id)) continue
        const post: PostRow = {
          ...row, topicId: 'desktop-import', personaId: null, sourceId: null,
          publishedAt: row.createdAt, fetchedAt: null, seriesId: null, status: 'published',
          qualityScore: 0, tags: [], demo: false, fingerprint: `native:${row.id}`,
          revision: 0, saved: false, hidden: false, likeState: 0, readProgress: 0,
        }
        await db.posts.put(post)
      }
      await db.sync_meta.put({ key: 'native_cache_imported', value: true })
    })
  }
}
