import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useRef, useState } from 'react'

import { db } from '../db'
import {
  applyBackup,
  backupFileName,
  collectBackup,
  describeImport,
  parseBackup,
  serializeBackup,
  type BackupFile,
  type ImportMode,
} from '../lib/backup'
import {
  copyToClipboard,
  downloadAsFile,
  getNativeInfo,
  isTauri,
  listNativeBackupFiles,
  nativePlatform,
  readNativeBackupFile,
  saveNativeBackupFile,
  type NativeBackupEntry,
  type NativeInfo,
} from '../lib/native'
import { NoticeBar, type NoticeTone } from './NoticeBar'

interface Notice {
  tone: NoticeTone
  message: string
  actionLabel?: string
  onAction?: () => void
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function problem(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

export function BackupPanel() {
  const shell = isTauri()
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  const [mode, setMode] = useState<ImportMode>('merge')
  const [ackReplace, setAckReplace] = useState(false)
  const [info, setInfo] = useState<NativeInfo | null>(null)
  const [shellFiles, setShellFiles] = useState<NativeBackupEntry[]>([])
  const [error, setError] = useState<string | null>(null)

  const counts = useBackupCounts()

  const refreshShellFiles = useCallback(async () => {
    if (!shell) return
    setShellFiles(await listNativeBackupFiles())
  }, [shell])

  useEffect(() => {
    if (!shell) return
    let alive = true
    void (async () => {
      const [native, files] = await Promise.all([getNativeInfo(), listNativeBackupFiles()])
      if (!alive) return
      setInfo(native)
      setShellFiles(files)
    })()
    return () => {
      alive = false
    }
  }, [shell])

  const onExport = useCallback(async () => {
    setBusy(true)
    setError(null)
    try {
      const name = backupFileName()
      const text = await serializeBackup()
      const bytes = new Blob([text]).size
      try {
        const path = await saveNativeBackupFile(name, text)
        if (path) {
          setNotice({
            tone: 'good',
            message: `已导出 ${formatBytes(bytes)} 到 ${path}`,
            actionLabel: '复制路径',
            onAction: () => void copyToClipboard(path).then(() => setNotice(null)),
          })
          await refreshShellFiles()
          return
        }
      } catch (err) {
        // The shell refused to write (storage permission, read-only volume):
        // the browser download path still works on desktop, clipboard elsewhere.
        setError(`写入本机目录失败：${problem(err)}`)
      }
      const platform = nativePlatform()
      if (platform === 'android' || platform === 'ios') {
        const copied = await copyToClipboard(text)
        setNotice({
          tone: copied ? 'warn' : 'bad',
          message: copied
            ? `本机目录写不进去，${formatBytes(bytes)} 的备份已复制到剪贴板：请用文件管理器新建 paf-backup.json 后粘贴保存`
            : '写文件、下载、复制都没成功，导出未完成',
        })
        return
      }
      downloadAsFile(name, text)
      setNotice({
        tone: 'good',
        message: `已导出 ${formatBytes(bytes)}：${name}（浏览器下载）`,
      })
    } catch (err) {
      setError(problem(err))
    } finally {
      setBusy(false)
    }
  }, [refreshShellFiles])

  const importText = useCallback(
    async (text: string, source: string) => {
      setBusy(true)
      setError(null)
      try {
        let parsed: BackupFile
        try {
          parsed = parseBackup(text)
        } catch (err) {
          setNotice({ tone: 'bad', message: `无法导入：${problem(err)}` })
          return
        }
        // Undo lives in the floating bar; a reload drops it, so keep the
        // pre-import snapshot small and in memory only.
        const snapshot = await collectBackup()
        const summary = await applyBackup(parsed, mode)
        const when = parsed.exportedAt ? `${parsed.exportedAt.slice(0, 16).replace('T', ' ')} 导出` : '无时间戳'
        setNotice({
          tone: 'good',
          message: `已导入${source}（${when}）：${describeImport(summary)}。备份里没有访问令牌，需要重新填。`,
          actionLabel: '撤销',
          onAction: () =>
            void (async () => {
              setBusy(true)
              try {
                // 'replace' restores content, but the queue is never wiped by an
                // import, so remove exactly the ops this import added.
                if (summary.queuedOpIds.length > 0) {
                  await db.outbox.where('id').anyOf(summary.queuedOpIds).delete()
                }
                const back = await applyBackup(snapshot, 'replace')
                setNotice({ tone: 'info', message: `已撤销导入：${describeImport(back)}` })
              } catch (err) {
                setNotice({ tone: 'bad', message: `撤销失败：${problem(err)}` })
              } finally {
                setBusy(false)
              }
            })(),
        })
        setAckReplace(false)
      } catch (err) {
        setError(problem(err))
      } finally {
        setBusy(false)
      }
    },
    [mode],
  )

  const onPickFile = useCallback(
    async (file: File | null) => {
      if (!file) return
      await importText(await file.text(), `文件 ${file.name}`)
    },
    [importText],
  )

  const onPickShellFile = useCallback(
    async (name: string) => {
      try {
        await importText(await readNativeBackupFile(name), `本机文件 ${name}`)
      } catch (err) {
        setError(problem(err))
      }
    },
    [importText],
  )

  const canImport = mode === 'merge' || ackReplace

  return (
    <section className="paf-card space-y-3">
      <h2 className="paf-section-title">备份与迁移</h2>
      <ul className="space-y-1 text-sm text-[var(--paf-muted)]">
        <li>本地：帖子 {counts.posts} · 评论 {counts.comments} · 互动 {counts.interactions} · 待同步 {counts.pending}</li>
        <li>
          备份格式 v1（读取时按字段兜底）{info ? ` · 壳版本 v${info.appVersion}` : ''}
        </li>
        {info ? (
          <li className="text-xs">
            导出位置：{info.backupDir || '（不可写）'}
            <br />
            本地库/缓存：{info.appDataDir}
          </li>
        ) : null}
      </ul>

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="paf-btn border border-[var(--paf-border)]"
          onClick={() => void onExport()}
          disabled={busy}
        >
          {busy ? '处理中…' : '导出 JSON 备份'}
        </button>
        <button
          type="button"
          className="paf-btn border border-[var(--paf-border)]"
          onClick={() => fileInput.current?.click()}
          disabled={busy || !canImport}
        >
          选择备份文件导入
        </button>
        <input
          ref={fileInput}
          type="file"
          accept="application/json,.json"
          className="hidden"
          onChange={(e) => void onPickFile(e.target.files?.[0] ?? null)}
        />
      </div>

      <div className="flex flex-wrap gap-3 text-sm">
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="backup-mode"
            checked={mode === 'merge'}
            onChange={() => setMode('merge')}
          />
          合并（保留本机多出来的数据）
        </label>
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="backup-mode"
            checked={mode === 'replace'}
            onChange={() => setMode('replace')}
          />
          覆盖（先清空本机帖子/评论/互动）
        </label>
      </div>

      {mode === 'replace' ? (
        <label className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-800 dark:text-amber-200">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={ackReplace}
            onChange={(e) => setAckReplace(e.target.checked)}
          />
          我明白：覆盖导入会先用备份里的内容替换本机已缓存的帖子、评论、互动和兴趣；导出的备份文件里不含访问令牌，导入后要在「连接」里重新填。浮条上的「撤销」只能撤回这一次导入。
        </label>
      ) : null}

      {shell ? (
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <h3 className="paf-section-title">本机导出记录</h3>
            <button
              type="button"
              className="paf-btn text-xs"
              onClick={() => void refreshShellFiles()}
            >
              刷新
            </button>
          </div>
          {shellFiles.length === 0 ? (
            <p className="text-xs text-[var(--paf-muted)]">还没有导出过备份。</p>
          ) : (
            <ul className="space-y-1 text-sm">
              {shellFiles.map((f) => (
                <li key={f.name} className="flex items-center justify-between gap-2">
                  <span className="min-w-0 flex-1 truncate">{f.name}</span>
                  <span className="shrink-0 text-xs text-[var(--paf-muted)]">
                    {formatBytes(f.bytes)} · {new Date(f.modifiedMs).toLocaleString()}
                  </span>
                  <button
                    type="button"
                    className="paf-btn shrink-0 text-xs"
                    onClick={() => void onPickShellFile(f.name)}
                    disabled={busy || !canImport}
                  >
                    导入
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}

      {error ? <p className="text-sm text-red-500">{error}</p> : null}
      <p className="text-xs text-[var(--paf-muted)]">
        备份只含本机数据：帖子/评论/兴趣/互动/待同步队列/设置；访问令牌永不写入。升级应用或迁移数据格式前请先导出一份。
      </p>

      {notice ? (
        <NoticeBar
          tone={notice.tone}
          message={notice.message}
          actionLabel={notice.actionLabel}
          onAction={notice.onAction}
          onClose={() => setNotice(null)}
        />
      ) : null}
    </section>
  )
}

function useBackupCounts() {
  return useLiveQuery(async () => {
    const [posts, comments, interactions, pending] = await Promise.all([
      db.posts.count(),
      db.comments.count(),
      db.interactions.count(),
      db.outbox.where('status').equals('pending').count(),
    ])
    return { posts, comments, interactions, pending }
  }, [], { posts: 0, comments: 0, interactions: 0, pending: 0 })
}
