/**
 * 瀑布流在没有 ResizeObserver 的环境里（vitest 的 node、以及首帧）必须退回单列，
 * 而不是把卡片画丢。这是这条降级路径唯一的机器证据。
 */

import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { expect, it } from 'vitest'
import { buildDemoPosts } from '../db/demoFactory'
import type { TopicRow } from '../db/types'
import { PostWaterfall } from './PostWaterfall'

const posts = buildDemoPosts(5)

it('没有量宽能力时画单列，五张卡一张不少', () => {
  const m = renderToStaticMarkup(
    createElement(
      MemoryRouter,
      null,
      createElement(PostWaterfall, { posts, topics: new Map<string, TopicRow>() }),
    ),
  )
  expect(m).toContain('data-columns="1"')
  expect(m.match(/data-testid="post-card"/g)).toHaveLength(posts.length)
  expect(m).toContain('repeat(1, minmax(0, 1fr))')
})
