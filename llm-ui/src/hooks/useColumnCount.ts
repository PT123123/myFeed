import { useEffect, useRef, useState } from 'react'

import { columnsFor } from '../lib/layout'

/**
 * 列数跟着真实容器宽度走，而不是跟着视口媒体查询走：左右两条侧栏是 flex 兄弟，
 * 主列拿到多少像素只有量出来才知道（docs/08 §1）。
 */
export function useColumnCount() {
  const ref = useRef<HTMLDivElement | null>(null)
  const [columns, setColumns] = useState(1)

  useEffect(() => {
    const node = ref.current
    if (!node || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? 0
      setColumns(columnsFor(width))
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  return { ref, columns }
}
