import type { ReactNode } from 'react'

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="paf-card flex flex-col items-center gap-2 py-10 text-center">
      <p className="text-sm font-medium">{title}</p>
      <p className="max-w-sm text-sm text-[var(--paf-muted)]">{description}</p>
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
