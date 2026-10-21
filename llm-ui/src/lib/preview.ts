/**
 * Card body preview (docs/08 §4).
 *
 * The Feed used to clamp ``summary`` to four lines. Measured on the local
 * database (2026-10-11): summaries are 24–81 characters long (mean 63), which
 * is about 2.3 rendered lines — so the clamp never bound and the card looked
 * empty no matter how many lines we allowed. The body is the field with actual
 * length (76–536 characters, the real-model posts sit at 176–536), so the card
 * reads the body and the summary stays on the detail page.
 *
 * B12 keeps that choice and drops the line clamp: 320 characters is roughly one
 * complete argument at card width, and what sits behind it is revealed *in the
 * card* by `cardFullText` (推特式外显) rather than by a route change.
 */

import type { PostRow } from '../db/types'

export const PREVIEW_CHARS = 320

/** Inline markup and block markers that must not reach a card. */
export function stripMarkdown(text: string): string {
  let out = text
  out = out.replace(/```[^\n]*\n?/g, '')
  out = out.replace(/`([^`]+)`/g, '$1')
  out = out.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
  out = out.replace(/^\s{0,3}#{1,6}\s+/gm, '')
  out = out.replace(/^\s*[-*+]\s+/gm, '· ')
  out = out.replace(/^\s*\d+\.\s+/gm, '· ')
  out = out.replace(/(\*\*|__)(.*?)\1/g, '$2')
  out = out.replace(/(\*|_)(.*?)\1/g, '$2')
  out = out.replace(/[ \t]+$/gm, '')
  out = out.replace(/\n{3,}/g, '\n\n')
  return out.trim()
}

/**
 * Cut at ``limit`` characters, then back off to the last sentence or line break
 * so a card never ends mid-word. If backing off would drop below half the
 * budget, the hard cut wins — a two-line card is worse than a dangling clause.
 */
export function cutPreview(text: string, limit = PREVIEW_CHARS): string {
  if (text.length <= limit) return text
  const window = text.slice(0, limit)
  const breakAt = Math.max(
    window.lastIndexOf('\n\n'),
    window.lastIndexOf('。'),
    window.lastIndexOf('.'),
    window.lastIndexOf('！'),
    window.lastIndexOf('?'),
  )
  if (breakAt < limit * 0.5) return window.trimEnd()
  // A newline separator is found at its first character; a full stop is kept.
  const end = window[breakAt] === '\n' ? breakAt : breakAt + 1
  return window.slice(0, end).trimEnd()
}

export interface CardPreview {
  text: string
  /** True when the card is showing less than the post actually holds. */
  truncated: boolean
}

export function cardPreview(
  post: Pick<PostRow, 'body' | 'summary'>,
  limit = PREVIEW_CHARS,
): CardPreview {
  const source = post.body.trim() || post.summary.trim()
  const text = stripMarkdown(source)
  const cut = cutPreview(text, limit)
  return { text: cut, truncated: cut.length < text.length }
}

/** 展开态：整条正文，清洗规则与折叠态一致。 */
export function cardFullText(post: Pick<PostRow, 'body' | 'summary'>): string {
  return stripMarkdown(post.body.trim() || post.summary.trim())
}

/** 只有卡上确实少显示了内容，才值得给一个展开键。 */
export function isExpandable(post: Pick<PostRow, 'body' | 'summary'>): boolean {
  return cardPreview(post).truncated
}
