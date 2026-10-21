import { useEffect, useState } from 'react'
import { HashRouter, Route, Routes } from 'react-router-dom'

import { AppShell } from './components/AppShell'
import { seedIfNeeded } from './db/seed'
import { useSettings } from './hooks/useSettings'
import { applyThemeClass, readStoredTheme, type ThemePreference } from './lib/theme'
import { DiscoverPage } from './pages/DiscoverPage'
import { FeedPage } from './pages/FeedPage'
import { GenerationPage } from './pages/GenerationPage'
import { PersonasPage } from './pages/PersonasPage'
import { PostDetailPage } from './pages/PostDetailPage'
import { PromptsPage } from './pages/PromptsPage'
import { SavedPage } from './pages/SavedPage'
import { SettingsPage } from './pages/SettingsPage'
import { SourcesPage } from './pages/SourcesPage'
import { UsagePage } from './pages/UsagePage'
import { runSync, SYNC_INTERVAL_MS } from './services/sync'

export default function App() {
  const { settings, update } = useSettings()
  const [theme, setTheme] = useState<ThemePreference>(() => readStoredTheme())

  useEffect(() => {
    applyThemeClass(theme)
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if (theme === 'system') applyThemeClass('system')
    }
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [theme])

  useEffect(() => {
    if (settings?.theme && settings.theme !== theme) {
      setTheme(settings.theme)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings?.theme])

  useEffect(() => {
    void seedIfNeeded()
  }, [])

  // B06 background sync: initial round, then on a timer / when back online.
  // Interactions themselves never hit the network — they only enqueue.
  useEffect(() => {
    const kick = () => {
      void runSync()
    }
    const initial = setTimeout(kick, 1500)
    const interval = setInterval(kick, SYNC_INTERVAL_MS)
    window.addEventListener('online', kick)
    return () => {
      clearTimeout(initial)
      clearInterval(interval)
      window.removeEventListener('online', kick)
    }
  }, [])

  const onThemeChange = (next: ThemePreference) => {
    setTheme(next)
    applyThemeClass(next)
    void update('theme', next)
  }

  return (
    <HashRouter>
      <AppShell theme={theme} onThemeChange={onThemeChange}>
        <Routes>
          <Route path="/" element={<FeedPage />} />
          <Route path="/post/:id" element={<PostDetailPage />} />
          <Route path="/discover" element={<DiscoverPage />} />
          <Route path="/saved" element={<SavedPage />} />
          <Route path="/generation" element={<GenerationPage />} />
          <Route path="/personas" element={<PersonasPage />} />
          <Route path="/prompts" element={<PromptsPage />} />
          <Route path="/sources" element={<SourcesPage />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<FeedPage />} />
        </Routes>
      </AppShell>
    </HashRouter>
  )
}
