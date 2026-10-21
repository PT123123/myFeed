import { useEffect } from 'react'

/**
 * Publishes the on-screen keyboard height as `--paf-keyboard-inset` so fixed
 * bars can dodge it (Android WebView keeps the layout viewport full height in
 * edge-to-edge mode, so `bottom: 0` ends up behind the IME).
 */
export function useKeyboardInset(): void {
  useEffect(() => {
    const root = document.documentElement
    const vv = window.visualViewport
    const apply = () => {
      if (!vv) {
        root.style.setProperty('--paf-keyboard-inset', '0px')
        return
      }
      const gap = window.innerHeight - (vv.height + vv.offsetTop)
      // URL bars and toolbars shift the viewport by well under 120px; anything
      // larger is an input method.
      root.style.setProperty('--paf-keyboard-inset', gap > 120 ? `${Math.round(gap)}px` : '0px')
    }
    apply()
    if (!vv) return
    vv.addEventListener('resize', apply)
    vv.addEventListener('scroll', apply)
    window.addEventListener('resize', apply)
    return () => {
      vv.removeEventListener('resize', apply)
      vv.removeEventListener('scroll', apply)
      window.removeEventListener('resize', apply)
      root.style.removeProperty('--paf-keyboard-inset')
    }
  }, [])
}
