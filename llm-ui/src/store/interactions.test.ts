import { beforeEach, describe, expect, it, vi } from 'vitest'

import { buildDemoPosts } from '../db/demoFactory'
import { db } from '../db'
import { applyLocalAction } from './interactions'

/**
 * B09 feedback chips (docs/07 §7.2): a structured reason is a signal about the
 * content, so it must land on the matching thumbs state without toggling it off,
 * and it must reach the server inside the op payload.
 */
describe('structured feedback reasons', () => {
  beforeEach(async () => {
    await db.delete()
    await db.open()
    await db.posts.bulkAdd(buildDemoPosts(5))
  })

  it('keeps a disliked post disliked when the reader adds a reason', async () => {
    await applyLocalAction('demo_0001', 'dislike')
    await applyLocalAction('demo_0001', 'dislike', null, 'too_shallow')
    expect((await db.posts.get('demo_0001'))?.likeState).toBe(-1)

    const ops = (await db.outbox.toArray()).sort((a, b) => a.clientSeq - b.clientSeq)
    expect(ops.map((o) => o.action)).toEqual(['dislike', 'dislike'])
    expect(ops.map((o) => o.payload)).toEqual([null, { reason: 'too_shallow' }])
    expect(ops.map((o) => o.clientSeq)).toEqual([1, 2])
  })

  it('routes 想继续 onto the like op without unliking an already-liked post', async () => {
    await applyLocalAction('demo_0001', 'like')
    await applyLocalAction('demo_0001', 'like', null, 'want_more')
    expect((await db.posts.get('demo_0001'))?.likeState).toBe(1)

    const logged = (await db.interactions.toArray()).sort((a, b) => a.clientSeq - b.clientSeq)
    expect(logged.map((i) => [i.kind, i.payload])).toEqual([
      ['like', null],
      ['like', { reason: 'want_more' }],
    ])
    expect(logged.map((i) => i.postId)).toEqual(['demo_0001', 'demo_0001'])
  })

  it('repeated reason chips each queue one op, so the server can price the transition', async () => {
    await applyLocalAction('demo_0002', 'dislike', null, 'too_deep')
    await applyLocalAction('demo_0002', 'dislike', null, 'too_deep')
    expect(await db.outbox.where('postId').equals('demo_0002').count()).toBe(2)
    expect((await db.posts.get('demo_0002'))?.likeState).toBe(-1)
  })

  it('a plain thumbs-up still toggles off', async () => {
    await applyLocalAction('demo_0003', 'like')
    await applyLocalAction('demo_0003', 'like')
    expect((await db.posts.get('demo_0003'))?.likeState).toBe(0)
    // A toggle is a real state change, so both ops are recorded.
    expect(await db.outbox.where('postId').equals('demo_0003').count()).toBe(2)
  })

  it('reading and interacting never reach the network', async () => {
    const fetchSpy = vi.fn(() => {
      throw new Error('生成请求不得由阅读/互动触发')
    })
    vi.stubGlobal('fetch', fetchSpy)
    await applyLocalAction('demo_0004', 'view')
    await applyLocalAction('demo_0004', 'save')
    await applyLocalAction('demo_0004', 'dislike', null, 'not_interested')
    expect(fetchSpy).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
