import { X } from 'lucide-react'

export type NoticeTone = 'info' | 'good' | 'warn' | 'bad'

const TONES: Record<NoticeTone, string> = {
  info: 'border-[var(--paf-border)] bg-[var(--paf-surface)] text-[var(--paf-text)]',
  good: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  warn: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-200',
  bad: 'border-red-500/40 bg-red-500/10 text-red-700 dark:text-red-300',
}

/**
 * Bottom floating bar: the place this app puts results of actions that changed
 * data, including the way back (撤销) next to the message.
 */
export function NoticeBar({
  tone = 'info',
  message,
  actionLabel,
  onAction,
  onClose,
}: {
  tone?: NoticeTone
  message: string
  actionLabel?: string
  onAction?: () => void
  onClose: () => void
}) {
  return (
    <div
      role="status"
      aria-live="polite"
      data-tone={tone}
      className={`paf-notice mx-auto flex max-w-[720px] items-start gap-3 rounded-xl border px-4 py-3 text-sm shadow-lg ${TONES[tone]}`}
    >
      <span className="flex-1 leading-6 break-words">{message}</span>
      {actionLabel && onAction ? (
        <button type="button" className="paf-btn shrink-0" onClick={onAction}>
          {actionLabel}
        </button>
      ) : null}
      <button
        type="button"
        aria-label="关闭提示"
        className="shrink-0 rounded-md p-1.5 text-[var(--paf-muted)] hover:bg-black/5 dark:hover:bg-white/10"
        onClick={onClose}
      >
        <X className="size-4" />
      </button>
    </div>
  )
}
