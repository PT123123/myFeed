import { describe, expect, it } from 'vitest'

import { PREVIEW_CHARS, cardPreview, cutPreview, stripMarkdown } from './preview'

describe('markdown never reaches a feed card', () => {
  it('drops emphasis, heading and link markers but keeps the words', () => {
    expect(stripMarkdown('**现象还原**：')).toBe('现象还原：')
    expect(stripMarkdown('## 排查步骤')).toBe('排查步骤')
    expect(stripMarkdown('见 [原文](https://example.com/a) 说明')).toBe('见 原文 说明')
    expect(stripMarkdown('用 `raycast` 检测')).toBe('用 raycast 检测')
  })

  it('turns bullets and numbered steps into one flat marker', () => {
    expect(stripMarkdown('- 日志埋点\n2. 状态快照')).toBe('· 日志埋点\n· 状态快照')
  })

  it('removes code fences and collapses blank runs', () => {
    expect(stripMarkdown('前\n```python\nx = 1\n```\n\n\n\n后')).toBe('前\nx = 1\n\n后')
  })
})

describe('the card reads the long field, not the short one', () => {
  it('prefers the body', () => {
    const { text } = cardPreview({ body: '正文内容比摘要长得多。', summary: '短摘要' })
    expect(text).toBe('正文内容比摘要长得多。')
  })

  it('falls back to the summary for rows with no body', () => {
    const { text, truncated } = cardPreview({ body: '   ', summary: '只有摘要' })
    expect(text).toBe('只有摘要')
    expect(truncated).toBe(false)
  })

  it('marks a card that is showing less than the post holds', () => {
    const long = '句子。'.repeat(200)
    const { text, truncated } = cardPreview({ body: long, summary: '' })
    expect(truncated).toBe(true)
    expect(text.length).toBeLessThanOrEqual(PREVIEW_CHARS)
  })
})

describe('cutPreview lands on a boundary', () => {
  it('backs off to the last full sentence', () => {
    const text = `${'甲'.repeat(100)}。${'乙'.repeat(400)}。`
    expect(cutPreview(text, 150)).toBe(`${'甲'.repeat(100)}。`)
  })

  it('keeps a paragraph break over a mid-word stop', () => {
    const text = `${'丙'.repeat(120)}\n\n${'丁'.repeat(300)}`
    expect(cutPreview(text, 130)).toBe(`${'丙'.repeat(120)}`)
  })

  it('takes the hard cut rather than returning almost nothing', () => {
    const text = `${'戊'.repeat(300)}`
    expect(cutPreview(text, 100)).toBe('戊'.repeat(100))
  })

  it('leaves short text untouched', () => {
    expect(cutPreview('短文。', 100)).toBe('短文。')
  })
})
