import { useLiveQuery } from 'dexie-react-hooks'
import { Link } from 'react-router-dom'
import { useCallback, useState } from 'react'

import { BackupPanel } from '../components/BackupPanel'
import { ConnectionPanel } from '../components/ConnectionPanel'
import { RetentionPanel } from '../components/RetentionPanel'
import { ShellPanel } from '../components/ShellPanel'
import { StoragePanel } from '../components/StoragePanel'
import { db } from '../db'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { useSettings } from '../hooks/useSettings'
import { isTauri } from '../lib/native'
import { readSyncStatus, retryFailedOutbox, runSync } from '../services/sync'

export function SettingsPage() {
  const { settings, update } = useSettings()
  const [syncMsg, setSyncMsg] = useState<string | null>(null)
  const [syncing, setSyncing] = useState(false)
  const online = useOnlineStatus()
  const shell = isTauri()

  const stats = useLiveQuery(async () => {
    const [posts, interactions, outbox] = await Promise.all([
      db.posts.count(),
      db.interactions.count(),
      db.outbox.where('status').equals('pending').count(),
    ])
    return { posts, interactions, outbox }
  }, [])

  const syncState = useLiveQuery(() => readSyncStatus(), [], null)

  const onSyncNow = useCallback(async () => {
    setSyncing(true)
    setSyncMsg(null)
    try {
      const r = await runSync({ force: true })
      if (r.status === 'ok') {
        const parts = [`上传 ${r.pushed ?? 0}`, `下载 ${r.pulled ?? 0}`]
        if (r.bootstrapped) parts.push('全量快照')
        if (r.failed) parts.push(`失败 ${r.failed}`)
        if (r.conflicts) parts.push(`冲突 ${r.conflicts}`)
        setSyncMsg(`同步完成：${parts.join('、')}`)
      } else if (r.status === 'skipped') {
        setSyncMsg(
          r.reason === 'offline'
            ? '当前离线，联网后自动同步'
            : r.reason === 'backoff'
              ? '失败退避中，稍后自动重试'
              : '已有同步在进行',
        )
      } else {
        setSyncMsg(`同步失败：${r.error?.code ?? ''} ${r.error?.message ?? ''}`)
      }
    } finally {
      setSyncing(false)
    }
  }, [])

  const onRetryFailed = useCallback(async () => {
    const n = await retryFailedOutbox()
    setSyncMsg(n > 0 ? `已重新排队 ${n} 项失败操作` : '没有失败操作')
    if (n > 0) void onSyncNow()
  }, [onSyncNow])

  if (!settings) {
    return <p className="paf-card text-sm text-[var(--paf-muted)]">加载设置…</p>
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-lg font-bold">LLM Feed 设置</h1>
        <p className="text-sm text-[var(--paf-muted)]">Local-First：设置与数据都保存在本机。</p>
      </div>

      <section className="paf-card space-y-3">
        <h2 className="paf-section-title">生成与内容管理</h2>
        <div className="grid grid-cols-2 gap-2">
          {[
            ['/generation', '生成任务与模型'], ['/prompts', '提示词与写作风格'],
            ['/personas', '角色与人格'], ['/sources', '真实来源'],
            ['/discover', '兴趣主题'], ['/usage', 'Token 与费用'],
          ].map(([to, label]) => <Link key={to} to={to} className="paf-btn border border-[var(--paf-border)]">{label}</Link>)}
        </div>
      </section>
      <p className="text-sm text-[var(--paf-muted)]">已缓存 {stats?.posts ?? 0} 条内容 · 待同步 {stats?.outbox ?? 0} 项</p>
      <ConnectionPanel settings={settings} update={update} />

      <section className="paf-card space-y-3">
        <h2 className="paf-section-title">阅读状态与评论同步</h2>
        <ul className="space-y-1 text-sm text-[var(--paf-muted)]">
          <li>
            上次同步：
            {syncState?.lastSyncAt
              ? new Date(syncState.lastSyncAt).toLocaleString()
              : '尚未同步'}
          </li>
          <li>
            待同步：{syncState?.pending ?? '—'} 项 · 失败：
            {syncState?.failed ?? '—'} 项
          </li>
          {syncState?.nextRetryAt ? (
            <li>失败退避中，下次自动重试：{new Date(syncState.nextRetryAt).toLocaleTimeString()}</li>
          ) : null}
          {syncState?.lastError ? (
            <li className="text-red-500">
              最近错误：{syncState.lastError.code} — {syncState.lastError.message}
              {syncState.lastError.code === 'AUTH_REQUIRED' ? '（令牌在上方「连接」里填）' : ''}
            </li>
          ) : null}
          {syncState?.note ? (
            <li className="text-amber-600">{syncState.note}</li>
          ) : null}
        </ul>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className="paf-btn border border-[var(--paf-border)]"
            onClick={() => void onSyncNow()}
            disabled={syncing}
          >
            {syncing ? '同步中…' : online ? '立即同步' : '离线（稍后自动同步）'}
          </button>
          <button
            type="button"
            className="paf-btn border border-[var(--paf-border)]"
            onClick={() => void onRetryFailed()}
            disabled={(syncState?.failed ?? 0) === 0}
          >
            重试失败项
          </button>
        </div>
        {syncMsg ? <p className="text-sm text-[var(--paf-muted)]">{syncMsg}</p> : null}
      </section>

      <section className="paf-card space-y-3">
        <h2 className="paf-section-title">外观与排序</h2>
        <label className="flex items-center justify-between text-sm">
          主题
          <select
            className="paf-input w-36"
            value={settings.theme}
            onChange={(e) =>
              void update('theme', e.target.value as 'light' | 'dark' | 'system')
            }
          >
            <option value="system">跟随系统</option>
            <option value="light">浅色</option>
            <option value="dark">深色</option>
          </select>
        </label>
        <label className="flex items-center justify-between text-sm">
          Feed 默认排序
          <select
            className="paf-input w-36"
            value={settings.sortMode}
            onChange={(e) => void update('sortMode', e.target.value as 'new' | 'hot')}
          >
            <option value="new">按时间</option>
            <option value="hot">按推荐分</option>
          </select>
        </label>
      </section>

      <BackupPanel />
      <p className="px-1 text-xs text-[var(--paf-muted)]">旧版 Personal AI Feed 安卓应用里的独有本地数据，可先在旧应用导出 JSON 备份，再在这里导入；两个应用的 Android 私有存储不会自动合并。</p>

      <StoragePanel />

      <RetentionPanel />

      {shell ? <ShellPanel /> : null}

      <section className="paf-card space-y-2 text-sm text-[var(--paf-muted)]">
        <h2 className="paf-section-title">关于</h2>
        <p>
          阅流 · LLM Feed：独立信息流、离线阅读、批量生成、提示词、角色、来源、用量与跨端同步。
        </p>
        <p>阅读、滚动、互动全程不会发起 LLM 请求；合成内容均带 AI 标识。</p>
      </section>
    </div>
  )
}
