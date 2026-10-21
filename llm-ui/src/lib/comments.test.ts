import { describe, expect, it } from 'vitest'

import type { CommentDto } from '../services/types'
import { TEASER_CHARS, buildCommentTree, commentDigest, personaAvatar } from './comments'
import type { DigestibleComment } from './comments'

function comment(overrides: Partial<CommentDto> = {}): CommentDto {
  return {
    id: 'c1',
    post_id: 'p1',
    parent_id: null,
    persona_id: 'pa',
    persona_name: '阿岚',
    author_type: 'persona',
    is_ai: true,
    body: '内容',
    created_at: '2026-10-10T00:00:00Z',
    ...overrides,
  }
}

function row(overrides: Partial<DigestibleComment> = {}): DigestibleComment {
  return {
    body: '内容',
    createdAt: '2026-10-10T00:00:00Z',
    parentId: null,
    authorType: 'persona',
    personaName: '阿岚',
    ...overrides,
  }
}

describe('buildCommentTree', () => {
  it('nests replies under their parents in chronological order', () => {
    const roots = buildCommentTree([
      comment({ id: 'c1', created_at: '2026-10-10T00:00:02Z' }),
      comment({ id: 'c2', parent_id: 'c1', created_at: '2026-10-10T00:00:03Z' }),
      comment({ id: 'c0', created_at: '2026-10-10T00:00:01Z' }),
      comment({ id: 'c3', parent_id: 'c2', created_at: '2026-10-10T00:00:04Z' }),
    ])
    expect(roots.map((r) => r.id)).toEqual(['c0', 'c1'])
    expect(roots[1].children.map((c) => c.id)).toEqual(['c2'])
    expect(roots[1].children[0].children.map((c) => c.id)).toEqual(['c3'])
  })

  it('falls back to root when parent is missing', () => {
    const roots = buildCommentTree([comment({ id: 'orphan', parent_id: 'gone' })])
    expect(roots.map((r) => r.id)).toEqual(['orphan'])
  })

  it('handles an empty list', () => {
    expect(buildCommentTree([])).toEqual([])
  })
})

describe('personaAvatar', () => {
  it('uses first character and a stable color', () => {
    const a = personaAvatar('观察者阿岚')
    expect(a.initials).toBe('观')
    expect(a.colorClass).toMatch(/^bg-/)
    expect(personaAvatar('观察者阿岚')).toEqual(a)
    expect(personaAvatar('工程师小砚').initials).toBe('工')
  })
})

describe('卡上的评论热度（只读本地表，不发起生成）', () => {
  it('没有评论就是 0，不给一条编造的剧透', () => {
    expect(commentDigest([])).toEqual({ count: 0 })
  })

  it('剧透取最早的那条主评论，回复不抢位', () => {
    const digest = commentDigest([
      row({ body: '后来的主评论', createdAt: '2026-10-10T00:00:05Z' }),
      row({ body: '这条是回复', parentId: 'x', createdAt: '2026-10-10T00:00:01Z' }),
      row({ body: '最早的主评论', createdAt: '2026-10-10T00:00:02Z' }),
    ])
    expect(digest.count).toBe(3)
    expect(digest.teaser?.body).toBe('最早的主评论')
  })

  it('全是被回复的楼层时也要有一条可看，不空着', () => {
    const digest = commentDigest([row({ body: '挂不上父级的评论', parentId: 'gone' })])
    expect(digest.teaser?.body).toBe('挂不上父级的评论')
  })

  it('署名：本地读者写「你」，人格名缺失写「AI 角色」', () => {
    expect(commentDigest([row({ authorType: 'user', personaName: null })]).teaser?.author).toBe('你')
    expect(
      commentDigest([row({ personaName: '  ' })]).teaser?.author,
    ).toBe('AI 角色')
  })

  it('多行长评压成一行并按字数收口', () => {
    const digest = commentDigest([row({ body: `第一段\n\n${'长'.repeat(200)}` })])
    expect(digest.teaser?.body).not.toContain('\n')
    expect((digest.teaser?.body ?? '').length).toBeLessThanOrEqual(TEASER_CHARS + 1)
    expect(digest.teaser?.body.endsWith('…')).toBe(true)
  })
})
