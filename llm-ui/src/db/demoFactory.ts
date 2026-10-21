import type { PostKind, PostRow, TopicRow } from './types'

/** Demo topics. Same ids as the server-side factory. */
export const DEMO_TOPICS: TopicRow[] = [
  { id: 'rust-gui', slug: 'rust-gui', name: 'Rust GUI', parentId: null },
  { id: 'game-dev', slug: 'game-dev', name: '游戏开发', parentId: null },
  { id: 'llm-eng', slug: 'llm-eng', name: 'LLM 工程', parentId: null },
  { id: 'productivity', slug: 'productivity', name: '效率方法', parentId: null },
  { id: 'urban-life', slug: 'urban-life', name: '城市生活', parentId: null },
  { id: 'scifi-fiction', slug: 'scifi-fiction', name: '科幻故事', parentId: null },
  { id: 'retro-tech', slug: 'retro-tech', name: '复古科技', parentId: null },
  { id: 'tea-culture', slug: 'tea-culture', name: '茶文化', parentId: null },
]

const FICTION_ANGLES = [
  '深夜编辑器里的幽灵',
  '一场关于 GUI 的辩论',
  '最后一次版本发布',
  '写给未来维护者的信',
  '当编译器开始做梦',
]

const EXPLAINER_ANGLES = [
  '为什么虚拟列表能救长列表',
  'SQLite WAL 模式到底做了什么',
  '结构化输出的三种实现路径',
  '离线优先架构的取舍',
  'Token 预算是怎么被花掉的',
]

const SERIES_ANGLES = [
  '独立开发者周记：第一周',
  '独立开发者周记：第二周',
  '独立开发者周记：第三周',
]

const SOURCE_ANGLES = [
  '演示来源：某公开技术博客的构建日志摘要',
  '演示来源：某开源项目周报要点整理',
  '演示来源：某公开论坛讨论串的结构化摘要',
]

const EXTRA_SENTENCES = [
  '点赞、收藏、隐藏都会先写入本地 IndexedDB。',
  '刷新页面后内容仍然存在。',
  '该内容由 mock 工厂生成，不涉及任何真实人物言论。',
  '如果服务端不可用，本地缓存依旧可读。',
]

/** Deterministic PRNG so demo data is reproducible across devices. */
function mulberry32(seed: number): () => number {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function fingerprintOf(title: string, body: string): string {
  const norm = title.toLowerCase().replace(/\s+/g, '') + '|' + body.slice(0, 200)
  let h = 2166136261
  for (let i = 0; i < norm.length; i++) {
    h ^= norm.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(16).padStart(8, '0') + norm.length.toString(16)
}

function pick<T>(rng: () => number, arr: readonly T[]): T {
  return arr[Math.floor(rng() * arr.length)]
}

function makeBody(rng: () => number, extra: string, tail = ''): string {
  const long =
    '长条目开始。\n\n' +
    Array.from(
      { length: 6 },
      (_, i) =>
        `第 ${i + 1} 段：演示段落，检查滚动性能与阅读舒适度，包含一些术语 like idempotency、cursor、outbox。`,
    ).join('\n\n') +
    '\n\n—— 长条目结束，仍是 Demo 数据。'
  const templates = [
    `这一条是演示内容，用于验证本地持久化与滚动加载。\n\n${extra}${tail}`,
    `短条目：用于测试列表密度。\n${extra}${tail}`,
    `中等长度的演示正文。它包含多段文字，用来检查卡片的排版、展开折叠以及中英混排（mixed 中文 English with \`code\` inline）。\n\n${extra}${tail}\n\n结尾再次强调：这是 Demo 数据。`,
    `${long}\n\n${extra}${tail}`,
  ]
  return pick(rng, templates)
}

/**
 * Build demo posts. Mirrors apps/server/app/services/demo_factory.py.
 * Every 5th post is tagged `explore` so the exploration filter has data.
 * `sourced` posts use https://example.com/demo/... URLs and are demo-flagged,
 * never fake real journalism.
 */
export function buildDemoPosts(count: number, now = Date.now()): PostRow[] {
  const rng = mulberry32(20261010)
  const base = Date.UTC(2026, 9, 1, 8, 0, 0)
  const kinds: PostKind[] = ['fiction', 'synthetic_explainer', 'sourced', 'serial']
  const posts: PostRow[] = []
  let seriesCounter = 0

  for (let i = 0; i < count; i++) {
    const kind = kinds[i % kinds.length]
    const topic = DEMO_TOPICS[i % DEMO_TOPICS.length]
    const created = new Date(base + 7 * 60_000 * i)
    const extra = pick(rng, EXTRA_SENTENCES)
    let title: string
    let body: string
    let sourceUrl: string | null = null
    let sourceTitle: string | null = null
    let publishedAt: string | null = null
    let fetchedAt: string | null = null
    let seriesId: string | null = null

    if (kind === 'fiction') {
      title = `${pick(rng, FICTION_ANGLES)}（#${i + 1}）`
      body = makeBody(rng, extra)
    } else if (kind === 'synthetic_explainer') {
      title = `${pick(rng, EXPLAINER_ANGLES)} #${i + 1}`
      body = makeBody(rng, extra, '\n\nAI 生成的知识性说明，不构成专业意见。')
    } else if (kind === 'sourced') {
      title = `${pick(rng, SOURCE_ANGLES)} #${i + 1}`
      body = makeBody(rng, extra, '\n\n本条为演示来源内容的改写，事实请以上方来源链接为准。')
      const idx = (i % 3) + 1
      sourceUrl = `https://example.com/demo/feed-${idx}#item-${i + 1}`
      sourceTitle = `Demo Feed ${idx}（演示来源，非真实站点）`
      publishedAt = new Date(created.getTime() - (2 + Math.floor(rng() * 46)) * 3_600_000).toISOString()
      fetchedAt = created.toISOString()
    } else {
      if (i % 4 === 3) seriesCounter += 1
      title = `${SERIES_ANGLES[seriesCounter % SERIES_ANGLES.length]}（${topic.name}） #${i + 1}`
      body = makeBody(rng, extra, '\n\n连载条目，后续会继续更新。')
      seriesId = `series-${seriesCounter}`
    }

    const tags = [topic.id, kind, 'demo']
    if (i % 5 === 0) tags.push('explore')

    posts.push({
      id: `demo_${String(i + 1).padStart(4, '0')}`,
      kind,
      title,
      body,
      summary: body.length > 80 ? `${body.slice(0, 80)}…` : body,
      topicId: topic.id,
      personaId: null,
      sourceId: null,
      sourceUrl,
      sourceTitle,
      publishedAt,
      fetchedAt,
      seriesId,
      status: 'published',
      qualityScore: Math.round((0.55 + rng() * 0.4) * 1000) / 1000,
      tags,
      demo: true,
      fingerprint: fingerprintOf(title, body),
      createdAt: created.toISOString(),
      revision: 1,
      saved: false,
      hidden: false,
      likeState: 0,
      lastReadAt: null,
      readProgress: 0,
    })
    void now
  }

  posts.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
  return posts
}
