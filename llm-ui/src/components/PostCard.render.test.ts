/**
 * Component-level smoke for the B09 card (docs/08 §4).
 *
 * `cardStyle.test.ts`/`preview.test.ts` only prove the pure functions decide the
 * right thing; they cannot catch a component that computes a family and then
 * forgets to render it. This renders the real card to static markup — it is
 * still not a human looking at pixels, but it is the strongest evidence this
 * repo's vitest (node environment, no Testing Library) can produce.
 */

import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { buildDemoPosts } from '../db/demoFactory'
import type { CommentDigest } from '../lib/comments'
import type { PostRow, TopicRow } from '../db/types'
import { PostCard } from './PostCard'

const demo = buildDemoPosts(4)
const sourced = demo.find((p) => p.kind === 'sourced') as PostRow

function markup(
  post: PostRow,
  reason?: string,
  authors?: Map<string, string>,
  comments?: CommentDigest,
) {
  return renderToStaticMarkup(
    createElement(
      MemoryRouter,
      null,
      createElement(PostCard, { post, topics: new Map<string, TopicRow>(), reason, authors, comments }),
    ),
  )
}

const filled = (m: string) =>
  (m.match(/rounded-\[2px\] bg-(?:emerald|teal|amber)-500/g) ?? []).length
const emptyTicks = (m: string) =>
  (m.match(/rounded-\[2px\] bg-\[var\(--paf-border\)\]/g) ?? []).length

describe('卡片真的把这套判定画了出来', () => {
  it('真实来源 + 高优先 + 系统综述：三格全绿，族与分量落在 data-* 上', () => {
    const m = markup({
      ...sourced,
      priority: 'high',
      evidenceGrade: 'systematic_review',
      contentType: 'report',
      suggestedAction: 'read',
      qualityScore: 0.625,
      recommendReason: '因为你在追 Rust GUI 这条线',
    })
    expect(m).toContain('data-family="sourced"')
    expect(m).toContain('data-weight="emphasised"')
    expect(m).toContain('data-rail="sourced"')
    expect(filled(m)).toBe(3)
    expect(m).toContain('系统综述')
    expect(m).toContain('深度报道')
    expect(m).toContain('优先级高')
    expect(m).toContain('质量')
    expect(m).toContain('0.63')
    expect(m).toContain('因为你在追 Rust GUI 这条线')
  })

  it('读的是正文不是摘要', () => {
    const m = markup({ ...sourced, summary: '这一句只应该出现在兜底路径里' })
    expect(m).toContain(sourced.body.slice(0, 8))
    expect(m).not.toContain('这一句只应该出现在兜底路径里')
  })

  it('创作内容画 0 格，pre-B09 旧数据留空而不是编一个可信的默认值', () => {
    const creative = markup({
      ...sourced,
      kind: 'fiction',
      evidenceGrade: 'not_applicable',
      recommendReason: '',
    })
    expect(filled(creative)).toBe(0)
    expect(emptyTicks(creative)).toBe(3)
    expect(creative).toContain('不涉及证据')
    expect(creative).not.toContain('bg-emerald-500"')
    // 没有模型理由也没有传入理由时，宁可空着。
    expect(creative).not.toContain('推荐理由')
    expect(creative).not.toContain('为什么推荐')

    const legacy = markup({
      ...sourced,
      priority: undefined,
      evidenceGrade: undefined,
      contentType: undefined,
      suggestedAction: undefined,
      recommendReason: '',
    })
    expect(legacy).toContain('data-weight="normal"')
    expect(legacy).toContain('data-curation="ungraded"')
    expect(legacy).toContain('未分级')
    // 缺字段（这条比 B09 早）与"服务端明说没评上档"是两种诚实：前者 0 格 + 未分级，后者 1 格琥珀。
    expect(filled(legacy)).toBe(0)
    const ungraded = markup({ ...sourced, evidenceGrade: 'ungraded' })
    expect(filled(ungraded)).toBe(1)
    expect(ungraded).toContain('rounded-[2px] bg-amber-500')
    expect(ungraded).toContain('未分级')
  })

  it('三族各画各的色带，连载与虚构同族', () => {
    const familyOf = (kind: PostRow['kind']) =>
      markup((demo.find((p) => p.kind === kind) ?? { ...sourced, kind }) as PostRow).match(
        /data-family="([a-z]+)"/,
      )?.[1]
    expect(familyOf('sourced')).toBe('sourced')
    expect(familyOf('synthetic_explainer')).toBe('knowledge')
    expect(familyOf('fiction')).toBe('narrative')
    expect(familyOf('serial')).toBe('narrative')
    expect(new Set(demo.map((post) => markup(post).match(/data-family="([a-z]+)"/)?.[1])).size).toBe(
      3,
    )
  })

  it('「探索」chip 只跟随服务端盖上的标签（带对照）', () => {
    expect(markup({ ...sourced, tags: [...sourced.tags, 'explore'] })).toContain('探索')
    expect(markup(sourced)).not.toContain('探索')
  })

  it('署名跟着人格名走：查得到名字署人，查不到就署主题，内部 id 永不上界面', () => {
    const signed = { ...sourced, personaId: 'p_engineer_yan' }
    const names = new Map([['p_engineer_yan', '工程师小砚']])
    const m = markup(signed, undefined, names)
    expect(m).toContain('工程师小砚')
    expect(m).toContain('data-author="p_engineer_yan"')
    expect(m).toContain('data-account="persona"')
    // 名字不在本地表里（离线或人格被删）时改署主题名，而不是把内部 id 糊上去。
    const bare = markup(signed)
    expect(bare).not.toContain('p_engineer_yan')
    expect(bare).toContain('data-account="topic"')
    expect(markup({ ...sourced, personaId: null }, undefined, names)).toContain(
      'data-account="topic"',
    )
  })

  it('正文没读完时给一个原地展开键，读得完就不给', () => {
    const long = markup({ ...sourced, body: '第一段说明。'.repeat(120) })
    expect(long).toContain('data-testid="card-expand"')
    expect(long).toContain('aria-expanded="false"')
    expect(long).toContain('展开全文')
    // 对照：一句到底的内容不配多一个键。
    const short = markup({ ...sourced, body: '一句话就说完。' })
    expect(short).not.toContain('data-testid="card-expand"')
    expect(short).not.toContain('展开全文')
  })

  it('评论热度是本地数字：有剧透才画那一行，没有就整块不出现', () => {
    const withComments = markup(sourced, undefined, undefined, {
      count: 3,
      teaser: { author: '工程师小砚', body: '这段我在平板上复现过。' },
    })
    expect(withComments).toContain('data-comment-count="3"')
    expect(withComments).toContain('3 条评论')
    expect(withComments).toContain('这段我在平板上复现过。')
    expect(withComments).toContain('aria-label="评论"')

    expect(markup(sourced, undefined, undefined, { count: 0 })).not.toContain('data-comment-count')
    expect(markup(sourced)).not.toContain('data-comment-count')
  })

  it('底部动作行把收藏和评论摆在外面，只有隐藏收进溢出菜单', () => {
    const m = markup(sourced, undefined, undefined, { count: 2, teaser: undefined })
    expect(m).toContain('aria-label="喜欢"')
    expect(m).toContain('aria-label="不喜欢"')
    expect(m).toContain('aria-label="收藏"')
    expect(m).toContain('aria-label="评论"')
    expect(m).toContain('aria-label="更多操作"')
    expect(m).toContain('aria-haspopup="menu"')
    expect(m).toContain('aria-expanded="false"')
    // 收起时「隐藏」根本不在文档里，而不是藏在样式后面。
    expect(m).not.toContain('role="menu"')
    expect(m).not.toContain('>隐藏<')
    // 缩的是标签与文字，不是手指落点。
    expect(m.match(/paf-btn/g)?.length).toBe(4)
    expect(m).toContain('paf-icon-btn')
  })
})
