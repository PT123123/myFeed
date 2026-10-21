import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useState } from 'react'

import { db } from '../db'
import { formatBytes, readStorageStatus, requestPersistence, type StorageStatus } from '../lib/storage'

/**
 * Local database footprint + persistence request (docs/09 §5). Deliberately
 * offers no "free up space" button: the only safe cleanup is the user's own
 * export in 「备份与迁移」, and bookmarks must never be dropped for space.
 */
export function StoragePanel() {
  const [status, setStatus] = useState<StorageStatus | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const counts = useLiveQuery(async () => {
    const [posts, comments, outbox] = await Promise.all([
      db.posts.count(),
      db.comments.count(),
      db.outbox.count(),
    ])
    return { posts, comments, outbox }
  }, [])

  const refresh = useCallback(async () => {
    setStatus(await readStorageStatus())
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const onPersist = useCallback(async () => {
    const ok = await requestPersistence()
    setMessage(
      ok
        ? '已获准持久保存：空间紧张时浏览器不会自动清掉本应用数据。'
        : '浏览器没有批准持久保存。空间吃紧时它仍可能回收数据，重要内容请定期用「备份与迁移」导出。',
    )
    await refresh()
  }, [refresh])

  const nearFull =
    status?.quotaBytes != null && status.usageBytes / status.quotaBytes > 0.8

  return (
    <section className="paf-card space-y-3" data-panel="storage">
      <h2 className="paf-section-title">本机存储占用</h2>
      <ul className="space-y-1 text-sm text-[var(--paf-muted)]">
        {status?.supported ? (
          <>
            <li data-field="usage">
              已用 {formatBytes(status.usageBytes)}
              {status.quotaBytes != null ? ` ／ 上限约 ${formatBytes(status.quotaBytes)}` : '（浏览器未告知上限）'}
            </li>
            <li data-field="persisted">
              持久保存：{status.persisted === null ? '浏览器不支持' : status.persisted ? '已获准' : '未获准'}
            </li>
          </>
        ) : (
          <li>当前环境不报告存储配额（如原生壳内未开放的 WebView）。</li>
        )}
        <li>
          本地内容 {counts?.posts ?? '—'} 条 · 评论 {counts?.comments ?? '—'} 条 · 待同步操作{' '}
          {counts?.outbox ?? '—'} 条
        </li>
      </ul>
      {nearFull ? (
        <p className="text-sm text-amber-600" data-state="near-full">
          已用接近上限：请先在「备份与迁移」导出，再自行清理浏览器存储。空间写满时同步会停在存储不足的提示上，不会自动删除收藏或已发布内容。
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        {status?.supported && status.persisted !== true ? (
          <button
            type="button"
            className="paf-btn"
            onClick={() => void onPersist()}
          >
            请求持久保存
          </button>
        ) : null}
        <button
          type="button"
          className="paf-btn border border-[var(--paf-border)]"
          onClick={() => void refresh()}
        >
          重新统计
        </button>
      </div>
      {message ? <p className="text-sm text-[var(--paf-muted)]">{message}</p> : null}
    </section>
  )
}
