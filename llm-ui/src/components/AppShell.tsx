import { useLiveQuery } from 'dexie-react-hooks'
import {
  BarChart3,
  Compass,
  Download,
  Flame,
  Grid2x2,
  Newspaper,
  PenLine,
  Rss,
  Settings as SettingsIcon,
  Users,
  WifiOff,
  X,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'

import { db } from '../db'
import { readingCorpus, unreadCount } from '../lib/feedQuery'
import { useKeyboardInset } from '../hooks/useKeyboardInset'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { GenerationStatusRail } from './GenerationStatusRail'
import { ThemeToggle } from './ThemeToggle'
import type { ThemePreference } from '../lib/theme'

const NAV = [
  { to: '/', label: 'Feed', icon: Flame, end: true },
  { to: '/discover', label: '发现', icon: Compass, end: false },
  { to: '/saved', label: '收藏', icon: Download, end: false },
  { to: '/generation', label: '生成', icon: Rss, end: false },
  { to: '/settings', label: '设置', icon: SettingsIcon, end: false },
]

/** 底部导航放不下的入口收进「更多」弹层：9 个药丸挤在 375px 上按不准。 */
const NAV_MORE = [
  { to: '/usage', label: '用量', icon: BarChart3, end: false },
  { to: '/personas', label: '角色', icon: Users, end: false },
  { to: '/prompts', label: '提示词', icon: PenLine, end: false },
  { to: '/sources', label: '来源', icon: Newspaper, end: false },
]

const TITLES: Record<string, string> = {
  '/': 'Feed',
  '/discover': '发现',
  '/saved': '收藏',
  '/generation': '生成',
  '/usage': '用量',
  '/personas': '角色',
  '/prompts': '提示词',
  '/sources': '来源',
  '/settings': '设置',
}

/** 双列瀑布流和左右分栏需要更宽的主列（docs/08 §1）。 */
const TWO_COL_PATHS = new Set(['/', '/saved', '/discover'])

function mainWidth(path: string): string {
  if (path.startsWith('/post/')) return 'max-w-[1000px]'
  if (TWO_COL_PATHS.has(path)) return 'max-w-[1180px]'
  return 'max-w-[720px]'
}

export function AppShell({
  children,
  theme,
  onThemeChange,
}: {
  children: ReactNode
  theme: ThemePreference
  onThemeChange: (next: ThemePreference) => void
}) {
  const online = useOnlineStatus()
  useKeyboardInset()
  const [moreOpen, setMoreOpen] = useState(false)
  const stats = useLiveQuery(async () => {
    const posts = await db.posts.toArray()
    const interests = await db.interests.toArray()
    const topics = await db.topics.toArray()
    const pending = await db.outbox.where('status').equals('pending').count()
    const corpus = readingCorpus(posts, interests)
    const unreadByTopic = new Map<string, number>()
    for (const p of corpus) {
      if (p.status !== 'published' || p.hidden || p.lastReadAt) continue
      unreadByTopic.set(p.topicId, (unreadByTopic.get(p.topicId) ?? 0) + 1)
    }
    return {
      unread: unreadCount(corpus),
      total: corpus.length,
      pending,
      topTopics: [...interests]
        .filter((i) => !i.blocked)
        .map((i) => ({
          id: i.topicId,
          name: topics.find((t) => t.id === i.topicId)?.name ?? i.topicId,
          weight: i.explicitWeight,
          unread: unreadByTopic.get(i.topicId) ?? 0,
        }))
        .sort((a, b) => b.unread - a.unread || b.weight - a.weight)
        .slice(0, 6),
    }
  }, [])

  const path = useLocation().pathname
  const title = TITLES[path] ?? 'LLM Feed'
  const unreadBadge = stats && stats.unread > 0 ? (stats.unread > 99 ? '99+' : stats.unread) : null

  return (
    <div className="min-h-full">
      {!online ? (
        <div
          role="status"
          className="sticky top-0 z-40 flex items-center justify-center gap-2 bg-amber-500/90 px-3 pb-1.5 pt-[calc(0.375rem+env(safe-area-inset-top))] text-xs font-medium text-amber-950"
        >
          <WifiOff className="size-3.5" />
          离线模式：正在读取本地缓存，互动会排队等待同步
        </div>
      ) : null}

      {/* 1720px 是把主列放宽到能放下第三列的上限：1440 屏仍是两条 442px 的栈（docs/08 §1）。 */}
      <div className="mx-auto flex max-w-[1720px]">
        {/* 左栏：固定导航（推特式），9 个入口全部可见 */}
        <aside className="sticky top-0 hidden h-dvh w-56 shrink-0 flex-col gap-1 border-r border-[var(--paf-border)] p-4 md:flex">
          <div className="mb-4 flex items-center justify-between px-2">
            <span className="text-base font-bold">LLM Feed</span>
            <ThemeToggle theme={theme} onChange={onThemeChange} />
          </div>
          {[...NAV, ...NAV_MORE].map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `paf-nav-item ${isActive ? 'paf-nav-item-active' : ''}`
              }
            >
              <item.icon className="size-4" />
              {item.label}
              {item.to === '/' && unreadBadge ? (
                <span
                  className="ml-auto rounded-full bg-[var(--paf-accent)] px-1.5 text-[11px] font-semibold tabular-nums text-white"
                  data-testid="nav-unread"
                >
                  {unreadBadge}
                </span>
              ) : null}
            </NavLink>
          ))}
          <p className="mt-auto px-3 text-xs leading-5 text-[var(--paf-muted)]">
            Local-First · 阅读与互动零 LLM 调用
          </p>
        </aside>

        {/* 中栏 */}
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="paf-safe-top sticky top-0 z-30 flex items-center justify-between border-b border-[var(--paf-border)] bg-[var(--paf-bg)]/90 px-4 pb-3 backdrop-blur md:hidden">
            <span className="text-base font-bold">LLM Feed</span>
            <div className="flex items-center gap-1">
              <ThemeToggle theme={theme} onChange={onThemeChange} />
            </div>
          </header>

          <main
            className={`mx-auto w-full flex-1 px-3 py-4 pb-24 md:pb-8 md:px-6 ${mainWidth(path)}`}
          >
            <h1 className="sr-only">{title}</h1>
            {children}
          </main>
        </div>

        {/* 右栏：三栏从 1200px 起（docs/08 §1），不是 Tailwind 的 xl */}
        <aside className="sticky top-0 hidden h-dvh w-64 shrink-0 flex-col gap-4 overflow-y-auto border-l border-[var(--paf-border)] p-4 three-col:flex">
          <section className="paf-hero">
            <p className="relative text-xs tracking-wide text-white/80">今日库存</p>
            <p className="relative mt-1 text-3xl font-bold tabular-nums">{stats?.unread ?? '—'}</p>
            <p className="relative mt-1 text-xs leading-5 text-white/80">
              未读 · 信息流共 {stats?.total ?? 0} 条 · 待同步 {stats?.pending ?? 0} 项
            </p>
          </section>
          <GenerationStatusRail />
          <section>
            <h2 className="paf-section-title">在追的主题</h2>
            <ul className="mt-2 space-y-1">
              {stats?.topTopics.map((t) => (
                <li key={t.id}>
                  <NavLink
                    to="/discover"
                    className="flex items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-black/5 dark:hover:bg-white/10"
                  >
                    <span className="min-w-0 truncate">{t.name}</span>
                    <span className="shrink-0 text-xs tabular-nums text-[var(--paf-muted)]">
                      {t.unread > 0 ? `${t.unread} 未读` : `权重 ${t.weight.toFixed(1)}`}
                    </span>
                  </NavLink>
                </li>
              ))}
              {!stats?.topTopics.length ? (
                <li className="text-xs text-[var(--paf-muted)]">暂无</li>
              ) : null}
            </ul>
            <p className="mt-1 px-2 text-xs text-[var(--paf-muted)]">
              未读最多的主题排在最前；点进去调权重。
            </p>
          </section>
        </aside>
      </div>

      {/* 手机端底部导航：5 个主入口 + 更多弹层 */}
      <nav className="paf-bottom-nav fixed inset-x-0 z-40 flex border-t border-[var(--paf-border)] bg-[var(--paf-surface)] pb-[env(safe-area-inset-bottom)] md:hidden">
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              `relative flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-[11px] ${
                isActive ? 'font-semibold text-[var(--paf-accent)]' : 'text-[var(--paf-muted)]'
              }`
            }
          >
            <item.icon className="size-5" />
            {item.label}
            {item.to === '/' && unreadBadge ? (
              <span
                className="absolute top-1.5 right-1/2 mr-[-18px] rounded-full bg-[var(--paf-accent)] px-1 text-[10px] font-semibold tabular-nums text-white"
                aria-hidden
              >
                {unreadBadge}
              </span>
            ) : null}
          </NavLink>
        ))}
        <button
          type="button"
          className="flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-[11px] text-[var(--paf-muted)]"
          aria-haspopup="dialog"
          aria-expanded={moreOpen}
          onClick={() => setMoreOpen(true)}
        >
          <Grid2x2 className="size-5" />
          更多
        </button>
      </nav>

      {moreOpen ? (
        <div className="fixed inset-0 z-50 md:hidden" role="dialog" aria-modal="true" aria-label="更多页面">
          <button
            type="button"
            className="absolute inset-0 bg-black/40"
            aria-label="关闭"
            onClick={() => setMoreOpen(false)}
          />
          <div className="absolute inset-x-0 bottom-0 rounded-t-2xl border-t border-[var(--paf-border)] bg-[var(--paf-surface)] p-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))]">
            <div className="mb-1 flex items-center justify-between px-2">
              <span className="text-sm font-semibold">其余页面</span>
              <button
                type="button"
                className="paf-icon-btn"
                aria-label="关闭更多页面"
                onClick={() => setMoreOpen(false)}
              >
                <X className="size-4" />
              </button>
            </div>
            {NAV_MORE.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className="paf-nav-item"
                onClick={() => setMoreOpen(false)}
              >
                <item.icon className="size-4" />
                {item.label}
              </NavLink>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  )
}
