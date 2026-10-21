/**
 * Icon-only overflow menu for card actions (docs/08 §4).
 *
 * B09 put 收藏/隐藏/评论 behind this icon to shrink the card; B12 moved 收藏 and
 * 评论 back out because the reader could not tell what a post was without opening
 * it, so on the Feed this now carries only 隐藏. The trigger keeps the 44px touch
 * target even though its label is gone — shrinking that is what makes a
 * fat-fingered tap on a phone destroy a like.
 */

import { useEffect, useRef, useState } from 'react'
import { MoreVertical } from 'lucide-react'

export interface OverflowItem {
  label: string
  icon: React.ReactNode
  active?: boolean
  onSelect: () => void
}

export function OverflowMenu({ items }: { items: OverflowItem[] }) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        className="paf-icon-btn"
        aria-label="更多操作"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <MoreVertical className="size-4" />
      </button>
      {open ? (
        <div
          role="menu"
          className="absolute right-0 z-30 mt-1 w-36 rounded-lg border border-[var(--paf-border)] bg-[var(--paf-surface)] p-1 shadow-lg"
        >
          {items.map((item) => (
            <button
              key={item.label}
              type="button"
              role="menuitem"
              aria-pressed={item.active}
              className={`flex min-h-11 w-full items-center gap-2 rounded-md px-2 text-sm ${
                item.active
                  ? 'text-[var(--paf-accent)]'
                  : 'text-[var(--paf-muted)] hover:bg-black/5 dark:hover:bg-white/10'
              }`}
              onClick={() => {
                item.onSelect()
                setOpen(false)
              }}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
