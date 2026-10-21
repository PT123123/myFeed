/**
 * Client mirror of the server slot sizing (`app/services/planner.py`
 * `_slot_families`, ADR-007) so the Discover page can promise the same numbers
 * the planner will produce. Keep in sync with the server.
 */

export interface MixRatios {
  /** Absolute share of the batch built from stored source articles. */
  sourced: number
  original: number
  exploration: number
}

/** Python's `round()` rounds half to even; the preview must match it. */
function roundHalfEven(n: number): number {
  const floor = Math.floor(n)
  const rest = n - floor
  if (rest > 0.5) return floor + 1
  if (rest < 0.5) return floor
  return floor % 2 === 0 ? floor : floor + 1
}

export function mixSlots(count: number, r: MixRatios): Required<MixRatios> {
  const sourced = Math.min(count, roundHalfEven(count * Math.max(0, r.sourced)))
  const rest = count - sourced
  const o = Math.max(0, r.original)
  const e = Math.max(0, r.exploration)
  if (o + e <= 0) return { sourced, original: rest, exploration: 0 }
  const original = Math.min(rest, roundHalfEven((rest * o) / (o + e)))
  return { sourced, original, exploration: rest - original }
}

/** Batch size the preview phrases its answers about. */
export const PREVIEW_BATCH = 100

export interface MixNote {
  tone: 'info' | 'warn'
  text: string
}

/**
 * Consequences the sliders cannot show on their own, in the order the reader
 * would act on them. All counts are per 100 generated posts.
 */
export function mixNotes(r: MixRatios): MixNote[] {
  const slots = mixSlots(PREVIEW_BATCH, r)
  const notes: MixNote[] = []
  const sum = r.sourced + r.original + r.exploration
  if (sum > 1.0001) {
    notes.push({
      tone: 'warn',
      text: `三项合计 ${(sum * 100).toFixed(0)}%，超过 100%：每 100 条里真实来源先占 ${
        slots.sourced
      } 条，剩下 ${PREVIEW_BATCH - slots.sourced} 条再按 AI 原创与探索的相对比例分，两者都会被压缩。`,
    })
  }
  if (r.exploration === 0) {
    notes.push({
      tone: 'info',
      text: '探索为 0：不再生成虚构与连载内容，Feed 里只剩真实来源扩展与 AI 原创。',
    })
  }
  if (r.original === 0 && r.exploration === 0) {
    notes.push({
      tone: 'warn',
      text: 'AI 原创与探索都为 0：真实来源之外的名额会全部记给 AI 原创。',
    })
  }
  if (r.sourced > 0) {
    notes.push({
      tone: 'info',
      text: `每 100 条里约 ${slots.sourced} 条来自真实来源：可用原文不足时，缺的名额由 AI 原创补上，不会留空。`,
    })
  }
  return notes
}
