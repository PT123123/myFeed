import { Moon, Sun } from 'lucide-react'

import type { ThemePreference } from '../lib/theme'
import { resolveTheme } from '../lib/theme'

export function ThemeToggle({
  theme,
  onChange,
}: {
  theme: ThemePreference
  onChange: (next: ThemePreference) => void
}) {
  const next: ThemePreference = resolveTheme(theme) === 'dark' ? 'light' : 'dark'
  return (
    <button
      type="button"
      className="paf-btn"
      aria-label={next === 'dark' ? '切换到浅色主题' : '切换到深色主题'}
      onClick={() => onChange(next)}
    >
      {resolveTheme(theme) === 'dark' ? <Sun className="size-5" /> : <Moon className="size-5" />}
    </button>
  )
}
