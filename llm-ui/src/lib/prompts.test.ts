/**
 * Prompt studio judgements (B10, docs/08 §5).
 *
 * The page's whole job is to tell the reader whether their words reached the
 * model and what they lost. That decision lives here so it can be tested
 * without a browser — each guard below carries the control case that proves it
 * is not merely always-false.
 */

import { describe, expect, it } from 'vitest'

import {
  CHUNK_CHAR_LIMIT,
  chunkLines,
  chunkWarnings,
  countAuthorMarkers,
  countBriefMarkers,
  countFocusMarkers,
  countFormatMarkers,
  countUnsignedSlots,
  diffChunk,
  isChunkModified,
  previewStats,
} from './prompts'

const SHIPPED_STYLE = ['写法：', '- 先说清这条写给谁。', '- 一篇只讲一件事。'].join('\n')

describe('chunkLines 与服务端同构', () => {
  it('丢掉空行与首尾空白，CRLF 与 LF 同解', () => {
    expect(chunkLines(' 一条\r\n\r\n\r\n二条  \n\n')).toEqual(['一条', '二条'])
  })

  it('纯空白读成零条', () => {
    expect(chunkLines('   \n\t\n')).toEqual([])
  })
})

describe('「我改过吗」', () => {
  it('留空不算改：服务端会静默用回默认写法', () => {
    expect(isChunkModified('', SHIPPED_STYLE)).toBe(false)
    expect(isChunkModified('  \n  ', SHIPPED_STYLE)).toBe(false)
  })

  it('只差空白与换行风格不算改', () => {
    expect(isChunkModified(SHIPPED_STYLE.replace('\n', '\r\n') + '\n', SHIPPED_STYLE)).toBe(false)
  })

  it('换掉一条规则才算改', () => {
    const mine = SHIPPED_STYLE.replace('- 一篇只讲一件事。', '- 一篇可以讲两件事。')
    expect(isChunkModified(mine, SHIPPED_STYLE)).toBe(true)
    // Control: the untouched copy is still not modified.
    expect(isChunkModified(SHIPPED_STYLE, SHIPPED_STYLE)).toBe(false)
  })

  it('默认值为空时，写了字才算改', () => {
    expect(isChunkModified('', '')).toBe(false)
    expect(isChunkModified('一条', '')).toBe(true)
  })
})

describe('改动读数', () => {
  it('调整顺序不是删改', () => {
    const reordered = ['写法：', '- 一篇只讲一件事。', '- 先说清这条写给谁。'].join('\n')
    expect(diffChunk(reordered, SHIPPED_STYLE)).toEqual({ added: [], removed: [], kept: 3 })
  })

  it('逐行列出新增与删掉的默认规则', () => {
    const mine = ['写法：', '- 只用三句话写完。', '- 一篇只讲一件事。'].join('\n')
    const d = diffChunk(mine, SHIPPED_STYLE)
    expect(d.added).toEqual(['- 只用三句话写完。'])
    expect(d.removed).toEqual(['- 先说清这条写给谁。'])
    expect(d.kept).toBe(2)
  })
})

describe('保存前的自检', () => {
  it('留空提醒的是「不会取消规则」，不是让用户删干净', () => {
    const w = chunkWarnings('style_rules', '')
    expect(w.join('\n')).toContain('留空不会取消这块规则')
  })

  it('超出上限只截断多出来的部分', () => {
    const over = '写'.repeat(CHUNK_CHAR_LIMIT + 1)
    expect(chunkWarnings('style_rules', over).join('\n')).toContain(`${CHUNK_CHAR_LIMIT} 字上限`)
  })

  it('角度池少于一次调用的槽位会解释角度复用', () => {
    expect(chunkWarnings('angles', '我踩过的坑\n一处反例\n一次复盘')).toHaveLength(1)
    // Control: 五条以上（一次调用刚好五个槽位）不再提示。
    expect(chunkWarnings('angles', ['一', '二', '三', '四', '五'].join('\n'))).toEqual([])
  })

  it('写法块丢了「写法：」起头才提示', () => {
    expect(chunkWarnings('style_rules', '- 直接开写。')).toHaveLength(1)
    // Control: the shipped opener satisfies the guard.
    expect(chunkWarnings('style_rules', SHIPPED_STYLE)).toEqual([])
  })

  it('人格接法块没有额外规则，留空之外一路绿灯', () => {
    expect(chunkWarnings('persona_notes', '- 用第一人称写。')).toEqual([])
  })
})

describe('预览里到底有几个槽位被署了名', () => {
  const prompt = [
    '今天是 2026-10-11（UTC）。按下面列出的 3 个槽位各写 1 条帖子，只输出 JSON。',
    '1. topic_id=t1（Rust GUI，簇 rust）, kind=synthetic_explainer, length=short, angle=一次复盘；作者=工程师小砚',
    '2. topic_id=t2（城市生活，簇 urban）, kind=sourced, length=short, angle=原文标题, source_item_id=si1',
    '3. topic_id=t3（茶，簇 urban）, kind=fiction, length=medium, angle=一处反例',
    '   [来源 2] 标题：原文标题',
  ].join('\n')

  it('只数真正的署名标记', () => {
    expect(countAuthorMarkers(prompt)).toBe(1)
  })

  it('未署名只算非 sourced 的槽位', () => {
    expect(countUnsignedSlots(prompt)).toBe(1)
  })

  it('三个数一起报，供底栏读数用', () => {
    const stats = previewStats(prompt)
    expect(stats.signedSlots).toBe(1)
    expect(stats.unsignedSlots).toBe(1)
    expect(stats.lines).toBe(5)
    expect(stats.chars).toBe(prompt.length)
  })

  it('全签名的预览报 0 个缺口（对照）', () => {
    const allSigned = prompt
      .replace('angle=一处反例', 'angle=一处反例；作者=生活家老周')
      .split('\n')
      .filter((line) => !line.includes('kind=sourced'))
      .join('\n')
    expect(countUnsignedSlots(allSigned)).toBe(0)
    expect(countAuthorMarkers(allSigned)).toBe(2)
  })
})

describe('B13 选题细化计数', () => {
  const briefed = [
    '1. topic_id=t1（Rust GUI，簇 rust）, kind=synthetic_explainer, length=short, '
      + 'angle=一次复盘；作者=小砚；主题范围=只讲桌面 GUI 生态；具体切口=为什么重写而不是绑 C；本条写法=围绕一个例子讲概念',
    '2. topic_id=t2（城市生活，簇 urban）, kind=sourced, length=short, angle=原文标题, source_item_id=si1；主题范围=通勤与街区',
    '3. topic_id=t3（茶，簇 urban）, kind=fiction, length=medium, angle=一处反例；本条写法=第一人称短场景',
  ].join('\n')

  it('范围按主题落，sourced 也带', () => {
    expect(countBriefMarkers(briefed)).toBe(2)
  })

  it('切口只给非 sourced 槽位', () => {
    expect(countFocusMarkers(briefed)).toBe(1)
  })

  it('写法按 kind 兼容分发', () => {
    expect(countFormatMarkers(briefed)).toBe(2)
  })

  it('画像没配时三个计数一起为 0（界面据此报“没落进提示词”）', () => {
    const stats = previewStats('1. topic_id=t1（x，簇 y）, kind=fiction, length=short, angle=z；作者=人')
    expect(stats.briefedSlots).toBe(0)
    expect(stats.focusedSlots).toBe(0)
    expect(stats.formattedSlots).toBe(0)
  })
})
