import { useCallback, useEffect, useState } from 'react'

import { db, getSetting, setSetting } from '../db'
import { DEFAULT_SETTINGS, type AppSettings } from '../db/types'
import { applyThemeClass, type ThemePreference } from '../lib/theme'

export function useSettings(): {
  settings: AppSettings | null
  update: <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => Promise<void>
} {
  const [settings, setSettings] = useState<AppSettings | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const rows = await db.settings.toArray()
      const map = new Map(rows.map((r) => [r.key, r.value]))
      const merged = { ...defaultsFromEnv(), ...Object.fromEntries(map) } as AppSettings
      if (!cancelled) setSettings(merged)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const update = useCallback(
    async <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => {
      await setSetting(key, value)
      if (key === 'theme') applyThemeClass(value as ThemePreference)
      setSettings((prev) => (prev ? { ...prev, [key]: value } : prev))
    },
    [],
  )

  return { settings, update }
}

function defaultsFromEnv(): AppSettings {
  return {
    apiBaseUrl: DEFAULT_SETTINGS.apiBaseUrl,
    authToken: '',
    theme: 'system',
    sortMode: 'new',
    seededVersion: 0,
    explorationRatio: 0.2,
    mixSourced: 0.5,
    mixOriginal: 0.3,
    unreadTarget: 200,
  }
}

export { getSetting, setSetting }
