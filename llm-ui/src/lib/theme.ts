const STORAGE_KEY = 'paf-theme'

export type ThemePreference = 'light' | 'dark' | 'system'

export function resolveTheme(pref: ThemePreference): 'light' | 'dark' {
  if (pref === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return pref
}

export function applyThemeClass(pref: ThemePreference): void {
  const root = document.documentElement
  root.classList.toggle('dark', resolveTheme(pref) === 'dark')
  try {
    localStorage.setItem(STORAGE_KEY, pref)
  } catch {
    /* private mode */
  }
}

export function readStoredTheme(): ThemePreference {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch {
    /* ignore */
  }
  return 'system'
}
