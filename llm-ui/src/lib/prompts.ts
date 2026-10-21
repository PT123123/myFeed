/**
 * Prompt studio judgements, kept out of the page so vitest can reach them
 * (ADR-008: what can be computed is computed, and what is computed is tested).
 *
 * Everything here answers one question the reader has while editing: "did my
 * words actually reach the model, and what did I lose by writing them?"
 */

export type PromptChunkKey = 'style_rules' | 'persona_notes' | 'angles'

// Mirror of the server's ``prompts.MAX_CHUNK_CHARS``.
export const CHUNK_CHAR_LIMIT = 4000

/** One subbatch is 5 slots (``generation.BATCH_SIZE``); the angle pool feeds it. */
export const SUBBATCH_SLOTS = 5

/** Meaningful lines: trimmed, blanks dropped — the server normalises the same way. */
export function chunkLines(text: string): string[] {
  return text
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
}

export function isChunkModified(text: string, defaultText: string): boolean {
  const mine = chunkLines(text)
  // An empty box is not a change: the server silently reuses the shipped wording.
  return mine.length > 0 && mine.join('\n') !== chunkLines(defaultText).join('\n')
}

export interface ChunkDiff {
  added: string[]
  removed: string[]
  kept: number
}

/**
 * Line-level diff against the shipped wording.
 *
 * Order-insensitive on purpose: moving a rule to the top of the box is not the
 * same as deleting it, and the reader needs to see that.
 */
export function diffChunk(text: string, defaultText: string): ChunkDiff {
  const mine = chunkLines(text)
  const base = chunkLines(defaultText)
  const mineSet = new Set(mine)
  const baseSet = new Set(base)
  return {
    added: mine.filter((line) => !baseSet.has(line)),
    removed: base.filter((line) => !mineSet.has(line)),
    kept: mine.filter((line) => baseSet.has(line)).length,
  }
}

/**
 * What the reader should know before saving, phrased as a decision they can act
 * on. Empty means the chunk is fine as written.
 */
export function chunkWarnings(key: PromptChunkKey, text: string): string[] {
  const lines = chunkLines(text)
  const out: string[] = []
  if (lines.length === 0) {
    out.push('留空不会取消这块规则——服务端会用回默认写法。要弱化一条规则，请把它改短，不要整块删掉。')
  }
  if (text.length > CHUNK_CHAR_LIMIT) {
    out.push(`已超出 ${CHUNK_CHAR_LIMIT} 字上限，多出来的部分不会进入提示词。`)
  }
  if (key === 'angles' && lines.length > 0 && lines.length < SUBBATCH_SLOTS) {
    out.push(
      `角度池只有 ${lines.length} 条，而一次调用要 ${SUBBATCH_SLOTS} 个槽位：用完后会复用角度。增加不同角度可减少同批重复。`,
    )
  }
  if (key === 'style_rules' && lines.length > 0 && !lines.some((l) => /^写法[：:]/.test(l))) {
    out.push('这一段没有以「写法：」开头。提示词里它紧跟在硬约束之后，模型会把第一行当成规则标题，建议保留「写法：」起头。')
  }
  return out
}

/** How many slots in a preview prompt actually carry an author marker. */
export function countAuthorMarkers(prompt: string): number {
  return (prompt.match(/；作者=/g) ?? []).length
}

/** Slots carrying each B13 editorial marker (brief / concrete seed / form).
 *  The planner stamps these; counting them here proves the local profile's
 *  brief/seeds/formats actually reached the prompt instead of silently no-op. */
export function countBriefMarkers(prompt: string): number {
  return (prompt.match(/；主题范围=/g) ?? []).length
}
export function countFocusMarkers(prompt: string): number {
  return (prompt.match(/；具体切口=/g) ?? []).length
}
export function countFormatMarkers(prompt: string): number {
  return (prompt.match(/；本条写法=/g) ?? []).length
}

/** Non-sourced slots in a preview — the ones that should have been signed. */
export function countUnsignedSlots(prompt: string): number {
  const itemLines = prompt.split('\n').filter((line) => /^\s*\d+\.\s+topic_id=/.test(line))
  return itemLines.filter((line) => !line.includes('kind=sourced') && !line.includes('；作者=')).length
}

export interface PreviewStats {
  chars: number
  lines: number
  signedSlots: number
  unsignedSlots: number
  briefedSlots: number
  focusedSlots: number
  formattedSlots: number
}

export function previewStats(prompt: string): PreviewStats {
  const signed = countAuthorMarkers(prompt)
  return {
    chars: prompt.length,
    lines: prompt.split('\n').length,
    signedSlots: signed,
    unsignedSlots: countUnsignedSlots(prompt),
    briefedSlots: countBriefMarkers(prompt),
    focusedSlots: countFocusMarkers(prompt),
    formattedSlots: countFormatMarkers(prompt),
  }
}
