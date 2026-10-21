export function SkeletonCard() {
  return (
    <div className="paf-card animate-pulse space-y-3" aria-hidden>
      <div className="h-3 w-24 rounded bg-black/10 dark:bg-white/10" />
      <div className="h-4 w-3/4 rounded bg-black/10 dark:bg-white/10" />
      <div className="space-y-2">
        <div className="h-3 w-full rounded bg-black/10 dark:bg-white/10" />
        <div className="h-3 w-5/6 rounded bg-black/10 dark:bg-white/10" />
      </div>
      <div className="h-3 w-40 rounded bg-black/10 dark:bg-white/10" />
    </div>
  )
}

export function SkeletonList({ count = 3 }: { count?: number }) {
  return (
    <div className="space-y-3">
      {Array.from({ length: count }, (_, i) => (
        <SkeletonCard key={i} />
      ))}
    </div>
  )
}
