/**
 * Column layout decisions (docs/08 §1).
 *
 * The reader asked for a feed that reads left-to-right in two stacks, Xiaohongshu
 * style, without a tall card in one column pushing everything below it out of
 * order. All width maths lives here so the component only consumes numbers and
 * vitest (node environment) can check them.
 */

import type { PostRow } from '../db/types'
import { cardPreview } from './preview'

/** Narrowest a card may get before we drop a column. */
export const MIN_CARD_WIDTH = 360
export const COLUMN_GAP = 12
export const MAX_COLUMNS = 3

/**
 * How many stacks fit in ``width``. Measured from the real container (a media
 * query would count the side rails that are already subtracted by flexbox), so
 * a narrow window simply returns 1 instead of a squashed pair.
 */
export function columnsFor(width: number): number {
  if (!Number.isFinite(width) || width <= 0) return 1
  const fit = Math.floor((width + COLUMN_GAP) / (MIN_CARD_WIDTH + COLUMN_GAP))
  return Math.max(1, Math.min(MAX_COLUMNS, fit))
}

/** Rough rendered height of a collapsed card, in px — enough to balance stacks. */
export function cardHeightEstimate(post: Pick<PostRow, 'title' | 'body' | 'summary'>): number {
  const lines = (text: string, charsPerLine: number) => {
    const stripped = text.replace(/\s+$/g, '')
    if (!stripped) return 0
    return stripped
      .split('\n')
      .reduce((sum, line) => sum + Math.max(1, Math.ceil(line.length / charsPerLine)), 0)
  }
  const preview = cardPreview(post)
  // 卡头 44 + 标题行 + 正文行 + 徽章/理由/动作区约 120。
  return 44 + lines(post.title, 26) * 26 + lines(preview.text, 30) * 24 + 120
}

/**
 * Greedy shortest-column split: each card goes to whichever stack is currently
 * shortest, so a wall of long posts does not turn one column into a scroll
 * graveyard. Feed order is preserved *within* a column.
 */
export function splitWaterfall<T>(
  items: readonly T[],
  columns: number,
  heightOf: (item: T) => number,
): T[][] {
  const cols = Math.max(1, Math.min(MAX_COLUMNS, Math.floor(columns) || 1))
  const buckets: T[][] = Array.from({ length: cols }, () => [])
  const heights = new Array<number>(cols).fill(0)
  for (const item of items) {
    let target = 0
    for (let i = 1; i < cols; i++) if (heights[i] < heights[target]) target = i
    buckets[target].push(item)
    heights[target] += heightOf(item)
  }
  return buckets
}
