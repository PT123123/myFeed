import { describe, expect, it } from 'vitest'

import { buildDemoPosts, fingerprintOf } from './demoFactory'

describe('demoFactory', () => {
  it('builds at least 1000 posts with unique ids and fingerprints', () => {
    const posts = buildDemoPosts(1200)
    expect(posts.length).toBe(1200)
    expect(new Set(posts.map((p) => p.id)).size).toBe(1200)
    expect(new Set(posts.map((p) => p.fingerprint)).size).toBe(1200)
  })

  it('mixes all four post kinds and marks demo data', () => {
    const posts = buildDemoPosts(100)
    const kinds = new Set(posts.map((p) => p.kind))
    expect(kinds).toEqual(new Set(['fiction', 'synthetic_explainer', 'sourced', 'serial']))
    expect(posts.every((p) => p.demo)).toBe(true)
    expect(posts.every((p) => p.tags.includes('demo'))).toBe(true)
  })

  it('gives sourced posts a demo source URL and fetched_at', () => {
    const sourced = buildDemoPosts(40).filter((p) => p.kind === 'sourced')
    expect(sourced.length).toBeGreaterThan(0)
    for (const p of sourced) {
      expect(p.sourceUrl).toMatch(/^https:\/\/example\.com\/demo\//)
      expect(p.sourceTitle).toBeTruthy()
      expect(p.fetchedAt).toBeTruthy()
    }
  })

  it('tags every 5th post as explore', () => {
    const posts = buildDemoPosts(50)
    expect(posts.filter((p) => p.tags.includes('explore')).length).toBeGreaterThanOrEqual(9)
  })

  it('is deterministic for the same seed', () => {
    const a = buildDemoPosts(30)
    const b = buildDemoPosts(30)
    expect(a).toEqual(b)
  })

  it('fingerprints change when content changes', () => {
    expect(fingerprintOf('a', 'b')).not.toBe(fingerprintOf('a', 'c'))
  })
})
