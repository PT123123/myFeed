/**
 * Card layout decisions (docs/08 §4).
 *
 * A feed of identical white boxes reads as one grey wall: the reader cannot see
 * which post is worth opening, which one is a marketing claim, or which one is
 * AI fiction without reading the chips. So the card itself carries three
 * orthogonal signals, all computed here (the components only map them to
 * classes — this is where the tests live, since vitest runs in node).
 */

import {
  EVIDENCE_LABELS,
  type EvidenceGrade,
  type GradeStrength,
  type Priority,
} from './curation'
import type { PostRow } from '../db/types'

/** Visual family: what the content is made of, not how good it is. */
export type LayoutFamily = 'sourced' | 'knowledge' | 'narrative'

/** Visual weight: how much the reader should stop for this card. */
export type WeightClass = 'emphasised' | 'normal' | 'muted'

export function layoutFamily(post: Pick<PostRow, 'kind'>): LayoutFamily {
  switch (post.kind) {
    case 'sourced':
      return 'sourced'
    case 'synthetic_explainer':
      return 'knowledge'
    default:
      return 'narrative'
  }
}

/**
 * Priority is server-computed (B09). A pre-B09 row without it stays ``normal``
 * rather than being guessed at from the quality score — inventing urgency for a
 * post the server never ranked would be exactly the fabrication this batch is
 * built to prevent.
 */
export function weightClass(priority?: Priority): WeightClass {
  if (priority === 'high') return 'emphasised'
  if (priority === 'low') return 'muted'
  return 'normal'
}

/** Classes per family: border style, background, and the accent rail colour. */
export const FAMILY_STYLE: Record<LayoutFamily, { card: string; rail: string; caption: string }> =
  {
    sourced: {
      card: 'bg-[var(--paf-surface)]',
      rail: 'bg-emerald-500/70',
      caption: 'text-emerald-700 dark:text-emerald-300',
    },
    knowledge: {
      card: 'bg-[var(--paf-surface)]',
      rail: 'bg-sky-500/70',
      caption: 'text-sky-700 dark:text-sky-300',
    },
    narrative: {
      // Fiction is the one case that must not look like an article: it gets a
      // tinted card on top of the rail.
      card: 'bg-violet-500/5',
      rail: 'bg-violet-500/70',
      caption: 'text-violet-700 dark:text-violet-300',
    },
  }

export const WEIGHT_STYLE: Record<WeightClass, { card: string; title: string }> = {
  emphasised: {
    card: 'border-[var(--paf-border)] shadow-sm',
    title: 'text-[var(--paf-text)] font-medium',
  },
  normal: {
    card: 'border-[var(--paf-border)]',
    title: 'text-[var(--paf-text)]',
  },
  muted: {
    card: 'border-[var(--paf-border)]/60',
    title: 'text-[var(--paf-muted)]',
  },
}

/**
 * Evidence meter: how many of three ticks to fill.
 *
 * ``not_applicable`` gets 0 rather than a low score: a novel has no evidence to
 * grade, and drawing one tick would make fiction look like a weakly-sourced
 * claim (``gradeStrength`` alone cannot tell those apart).
 */
export function meterLevel(grade?: EvidenceGrade): 0 | 1 | 2 | 3 {
  if (!grade || grade === 'not_applicable') return 0
  if (grade === 'systematic_review' || grade === 'human_study') return 3
  if (grade === 'anecdote' || grade === 'marketing' || grade === 'ungraded') return 1
  return 2
}

/**
 * Hover/accessible text for the meter. Chinese only — the internal enum value
 * stays in `data-curation` (project copy rule).
 */
export function meterCaption(grade?: EvidenceGrade): string {
  if (!grade) return '未分级：这条比 B09 早，服务端没给证据档'
  if (grade === 'not_applicable') return '不涉及证据：创作内容，没有可核验的来源'
  return `证据档：${EVIDENCE_LABELS[grade]}`
}

/** The number the reader is allowed to see, never a vague word (docs/08 §4). */
export function scoreReadout(post: Pick<PostRow, 'qualityScore'>): string {
  return post.qualityScore.toFixed(2)
}

/** Bar colours per strength; ``weak`` is amber, never the verified green. */
export const METER_FILL: Record<GradeStrength, string> = {
  trustworthy: 'bg-emerald-500',
  mixed: 'bg-teal-500',
  weak: 'bg-amber-500',
}

/**
 * Chip colours per field family. Four identical grey pills read as one blob of
 * decoration, so only the content type stays neutral: the suggested action is
 * something the reader should act on, and priority is a rank.
 */
export const CHIP_TONE = {
  contentType: 'border-[var(--paf-border)] text-[var(--paf-muted)]',
  action: 'border-transparent bg-[var(--paf-accent)]/10 text-[var(--paf-accent)]',
} as const

export const PRIORITY_TONE: Record<Priority, string> = {
  high: 'border-[var(--paf-accent)] text-[var(--paf-text)]',
  medium: 'border-[var(--paf-border)] text-[var(--paf-muted)]',
  low: 'border-[var(--paf-border)]/70 text-[var(--paf-muted)]/70',
}

/** 探索 overlay: stamped by the server from the plan's slot family. */
export function isExploration(post: Pick<PostRow, 'tags'>): boolean {
  return post.tags.includes('explore')
}
