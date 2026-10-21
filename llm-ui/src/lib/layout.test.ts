import { describe, expect, it } from 'vitest'

import { buildDemoPosts } from '../db/demoFactory'
import type { PostRow } from '../db/types'
import { MAX_COLUMNS, cardHeightEstimate, columnsFor, splitWaterfall } from './layout'

describe('列数只看容器实测宽度', () => {
  it('窄到一条卡都放不下就回到单列', () => {
    expect(columnsFor(0)).toBe(1)
    expect(columnsFor(375)).toBe(1)
    expect(columnsFor(Number.NaN)).toBe(1)
  })

  it('每多出一张卡的宽度才加一列，最多加到三列', () => {
    expect(columnsFor(748)).toBe(2)
    expect(columnsFor(1120)).toBe(3)
    expect(columnsFor(4000)).toBe(MAX_COLUMNS)
  })
})

describe('瀑布流按最矮的一列发卡', () => {
  const posts = buildDemoPosts(6)

  it('单列时顺序原样，不玩列内倒置', () => {
    expect(splitWaterfall(posts, 1, () => 100).flat().map((p) => p.id)).toEqual(
      posts.map((p) => p.id),
    )
  })

  it('等高卡片在两列间轮转，列内顺序与 Feed 排序一致', () => {
    const [left, right] = splitWaterfall(posts, 2, () => 100)
    expect(left.map((p) => p.id)).toEqual([posts[0].id, posts[2].id, posts[4].id])
    expect(right.map((p) => p.id)).toEqual([posts[1].id, posts[3].id, posts[5].id])
  })

  it('一条长文不会把同一列压成空白：后面的矮卡改投另一列', () => {
    const heights = [1000, 100, 100, 100]
    const items = heights.map((_, i) => ({ id: String(i) }))
    const buckets = splitWaterfall(items, 2, (x) => heights[Number(x.id)])
    expect(buckets[0].map((x) => x.id)).toEqual(['0'])
    expect(buckets[1].map((x) => x.id)).toEqual(['1', '2', '3'])
  })

  it('列数非法时退回单列而不是崩溃', () => {
    expect(splitWaterfall(posts, 0, () => 1)).toHaveLength(1)
    expect(splitWaterfall(posts, Number.NaN, () => 1)).toHaveLength(1)
    expect(splitWaterfall(posts, 9, () => 1)).toHaveLength(MAX_COLUMNS)
  })
})

describe('卡高估算', () => {
  const base = buildDemoPosts(1)[0] as PostRow

  it('正文越长，估算越高；展开前的可见字数封顶', () => {
    const short = { ...base, body: '一句话。' }
    const long = { ...base, body: '很长的一段正文。'.repeat(80) }
    expect(cardHeightEstimate(long)).toBeGreaterThan(cardHeightEstimate(short))
    // 超出 320 字的部分走「展开全文」，不再撑高折叠卡。
    expect(cardHeightEstimate({ ...base, body: '字'.repeat(400) })).toBe(
      cardHeightEstimate({ ...base, body: '字'.repeat(500) }),
    )
  })

  it('没有正文时用摘要兜底，不留零高', () => {
    const onlySummary = { ...base, body: '', summary: '只有摘要的一条' }
    expect(cardHeightEstimate(onlySummary)).toBeGreaterThan(cardHeightEstimate({ ...base, body: '', summary: '' }))
  })
})
