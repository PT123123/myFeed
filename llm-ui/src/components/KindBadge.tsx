import { KIND_LABELS, type PostKind } from '../db/types'

const STYLE: Record<PostKind, string> = {
  fiction: 'border-purple-300 text-purple-700 dark:border-purple-500/50 dark:text-purple-300',
  synthetic_explainer:
    'border-sky-300 text-sky-700 dark:border-sky-500/50 dark:text-sky-300',
  sourced: 'border-emerald-300 text-emerald-700 dark:border-emerald-500/50 dark:text-emerald-300',
  serial: 'border-amber-300 text-amber-700 dark:border-amber-500/50 dark:text-amber-300',
}

export function KindBadge({ kind, demo }: { kind: PostKind; demo?: boolean }) {
  return (
    <span className={`paf-chip-tight border ${STYLE[kind]}`}>
      {KIND_LABELS[kind]}
      {kind === 'fiction' ? ' · AI' : ''}
      {demo ? ' · Demo' : ''}
    </span>
  )
}
