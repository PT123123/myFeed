/**
 * Shared curation readout for the Feed card and the post detail page (docs/08 §4).
 *
 * Both pages used to build their own grey pills, so a change in one drifted from
 * the other. The card and the detail page now render the same component: an
 * evidence meter (graphic, not a fourth word), a content-type chip, a suggested
 * action chip, and the computed quality score always on screen — a verdict the
 * reader cannot see is a verdict they cannot disagree with.
 */

import type { PostRow } from '../db/types'
import {
  contentTypeLabel,
  evidenceLabel,
  gradeStrength,
  priorityLabel,
  suggestedActionLabel,
} from '../lib/curation'
import {
  CHIP_TONE,
  METER_FILL,
  PRIORITY_TONE,
  meterCaption,
  meterLevel,
  scoreReadout,
} from '../lib/cardStyle'

export function EvidenceMeter({ grade }: { grade?: PostRow['evidenceGrade'] }) {
  const level = meterLevel(grade)
  const fill = METER_FILL[gradeStrength(grade)]
  return (
    <span
      data-curation={grade ?? 'ungraded'}
      title={meterCaption(grade)}
      className="inline-flex items-center gap-1"
    >
      <span aria-hidden className="flex items-center gap-0.5">
        {[3, 2, 1].map((tick) => (
          <span
            key={tick}
            className={`h-2 w-2 rounded-[2px] ${level >= tick ? fill : 'bg-[var(--paf-border)]]'}`}
          />
        ))}
      </span>
      {/* 不涉及证据 fills no bars; the label carries it. */}
      <span className="text-[11px] text-[var(--paf-muted)]">{evidenceLabel(grade) ?? '未分级'}</span>
    </span>
  )
}

export function CurationBadges({
  post,
  alwaysShowPriority = false,
}: {
  post: PostRow
  /** The detail page reports the full verdict; the card hides the common 中档. */
  alwaysShowPriority?: boolean
}) {
  // 中优先级 is what most cards are and says nothing; the card's own weight
  // already shows high and low.
  const priority = post.priority
  const showPriority = alwaysShowPriority || priority === 'high' || priority === 'low'
  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-x-2.5 gap-y-1">
      <EvidenceMeter grade={post.evidenceGrade} />
      {post.contentType ? (
        <span
          data-curation={post.contentType}
          className={`paf-chip-tight border ${CHIP_TONE.contentType}`}
        >
          {contentTypeLabel(post.contentType)}
        </span>
      ) : null}
      {post.suggestedAction ? (
        <span
          data-curation={post.suggestedAction}
          className={`paf-chip-tight border ${CHIP_TONE.action}`}
        >
          {suggestedActionLabel(post.suggestedAction)}
        </span>
      ) : null}
      {showPriority && priority ? (
        <span data-curation={priority} className={`paf-chip-tight border ${PRIORITY_TONE[priority]}`}>
          优先级{priorityLabel(priority)}
        </span>
      ) : null}
      <span className="ml-auto text-[11px] tabular-nums text-[var(--paf-muted)]">
        <span aria-hidden>质量 </span>
        <span data-score={post.qualityScore}>{scoreReadout(post)}</span>
      </span>
    </div>
  )
}
