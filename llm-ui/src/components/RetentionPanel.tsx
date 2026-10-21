import { useCallback, useEffect, useState } from 'react'

import { ApiError, getRetentionSettings, putRetentionSettings, runRetentionNow } from '../services/api'
import type { RetentionSettingsDto, RetentionRunDto } from '../services/types'
import { NoticeBar } from './NoticeBar'

interface WindowField {
  key: keyof Omit<RetentionSettingsDto, 'prune_read_unsaved'>
  /** The question the number answers, phrased so the user can decide. */
  label: string
  /** What shrinking this window costs, in plain words. */
  cost: string
  min: number
}

const FIELDS: WindowField[] = [
  {
    key: 'source_items_days',
    label: '抓回来、还没做成帖子的原文保留多少天',
    cost: '已发布帖子的出处链接与标题存在帖子自己身上，清理后仍能看到来源。',
    min: 1,
  },
  {
    key: 'client_ops_days',
    label: '各设备已提交操作的记账保留多少天',
    cost: '要比你最久不上线的那台设备更长，否则它补交的操作可能被算两次。',
    min: 1,
  },
  {
    key: 'tombstone_days',
    label: '已删除内容的删除标记保留多少天',
    cost: '落后超过这个天数的设备会改为整包重下，收藏与阅读记录不会丢。',
    min: 1,
  },
  {
    key: 'demo_posts_days',
    label: '已被设备取走的演示内容保留多少天',
    cost: '只清理演示（Demo）帖子，不影响真实生成或已收藏的内容。',
    min: 1,
  },
  {
    key: 'read_unsaved_days',
    label: '读完多少天后清理「读过且没收藏」的帖子',
    cost: '只在下面那个开关打开时生效；收藏过、点过赞或踩过的内容永不删。',
    min: 7,
  },
]

const DROPPED_LABELS: Record<string, string> = {
  source_items: '过期原文缓存',
  client_ops: '过期操作记账',
  tombstones: '过期删除标记',
  demo_posts: '过期演示帖子',
  read_unsaved: '读过且没收藏的帖子',
}

export function RetentionPanel() {
  const [values, setValues] = useState<RetentionSettingsDto | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'info' | 'good' | 'warn'; message: string } | null>(
    null,
  )
  const [confirmPrune, setConfirmPrune] = useState(false)
  const [armedRun, setArmedRun] = useState(false)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setLoadError(null)
    try {
      setValues(await getRetentionSettings())
    } catch (err) {
      setLoadError(err instanceof ApiError ? `${err.code}：${err.message}` : '读取失败')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  if (loadError) {
    return (
      <section className="paf-card space-y-2" data-panel="retention" data-state="unreachable">
        <h2 className="paf-section-title">服务端数据保留</h2>
        <p className="text-sm text-[var(--paf-muted)]">
          连不上服务端，看不到保留期设置：{loadError}
        </p>
        <button
          type="button"
          className="paf-btn border border-[var(--paf-border)]"
          onClick={() => void load()}
        >
          重新读取
        </button>
      </section>
    )
  }

  if (!values) {
    return (
      <section className="paf-card text-sm text-[var(--paf-muted)]" data-panel="retention">
        读取数据保留设置…
      </section>
    )
  }

  const setValue = (key: WindowField['key'], raw: string) => {
    const n = Number.parseInt(raw, 10)
    if (Number.isNaN(n)) return
    setValues({ ...values, [key]: n } as RetentionSettingsDto)
    setArmedRun(false)
  }

  const onSave = async () => {
    setBusy(true)
    try {
      const saved = await putRetentionSettings(values)
      setValues(saved)
      setNotice({ tone: 'good', message: '已保存服务端保留期设置。' })
    } catch (err) {
      setNotice({
        tone: 'warn',
        message: err instanceof ApiError ? `保存失败：${err.code} ${err.message}` : '保存失败',
      })
    } finally {
      setBusy(false)
      setConfirmPrune(false)
    }
  }

  const onRunNow = async () => {
    setBusy(true)
    try {
      const result: RetentionRunDto = await runRetentionNow()
      setValues(result.settings)
      const parts = Object.entries(result.dropped).filter(([, n]) => n > 0)
      setNotice({
        tone: 'good',
        message:
          parts.length === 0
            ? '已检查一遍：没有到期需要清理的数据。'
            : `已清理：${parts
                .map(([k, n]) => `${DROPPED_LABELS[k] ?? k} ${n} 条`)
                .join('、')}。此操作不可撤销，如需找回请先用「备份与迁移」导出。`,
      })
    } catch (err) {
      setNotice({
        tone: 'warn',
        message: err instanceof ApiError ? `清理失败：${err.code} ${err.message}` : '清理失败',
      })
    } finally {
      setBusy(false)
      setArmedRun(false)
    }
  }

  return (
    <section className="paf-card space-y-3" data-panel="retention">
      <h2 className="paf-section-title">服务端数据保留</h2>
      <p className="text-sm text-[var(--paf-muted)]">
        每天自动执行一次；也可手动立即清理。收藏过的内容永不被清理。
      </p>

      <div className="space-y-2">
        {FIELDS.map((f) => {
          const value = values[f.key] as number
          return (
            <label key={f.key} className="block space-y-1">
              <span className="flex items-center justify-between gap-3 text-sm">
                <span>{f.label}</span>
                <span className="flex shrink-0 items-center gap-1">
                  <input
                    type="number"
                    className="paf-input w-20 text-right"
                    min={f.min}
                    max={3650}
                    value={value}
                    data-window={f.key}
                    onChange={(e) => setValue(f.key, e.target.value)}
                  />
                  <span className="text-sm text-[var(--paf-muted)]">天</span>
                </span>
              </span>
              <span className="block text-xs text-[var(--paf-muted)]">{f.cost}</span>
            </label>
          )
        })}
      </div>

      <div className="space-y-2 border-t border-[var(--paf-border)] pt-3">
        <label className="flex items-start gap-3 text-sm">
          <input
            type="checkbox"
            className="mt-1 size-4"
            checked={values.prune_read_unsaved}
            data-window="prune_read_unsaved"
            onChange={(e) => {
              if (e.target.checked) {
                setConfirmPrune(true)
              } else {
                setValues({ ...values, prune_read_unsaved: false })
                setConfirmPrune(false)
              }
            }}
          />
          <span>
            允许清理「读过且没收藏」的帖子
            <span className="mt-0.5 block text-xs text-[var(--paf-muted)]">
              默认关闭。开启后这些帖子会从所有设备消失，且不可撤销。
            </span>
          </span>
        </label>

        {confirmPrune ? (
          <div
            className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm"
            data-state="confirm-enable-prune"
          >
            <p>
              确认开启？读完后 {values.read_unsaved_days} 天仍没收藏的帖子会被标记删除，并同步到每台设备。收藏、点赞、点过踩的一律保留。
            </p>
            <div className="mt-2 flex gap-2">
              <button
                type="button"
                className="paf-btn"
                onClick={() => {
                  setValues({ ...values, prune_read_unsaved: true })
                  setConfirmPrune(false)
                }}
              >
                确认开启
              </button>
              <button type="button" className="paf-btn border border-[var(--paf-border)]" onClick={() => setConfirmPrune(false)}>
                先不开
              </button>
            </div>
          </div>
        ) : null}
      </div>

      <div className="flex flex-wrap gap-2">
        <button type="button" className="paf-btn" onClick={() => void onSave()} disabled={busy}>
          {busy ? '处理中…' : '保存设置'}
        </button>
        {armedRun ? (
          <button
            type="button"
            className="paf-btn border border-red-500/50 text-red-600 dark:text-red-400"
            data-state="confirm-run"
            onClick={() => void onRunNow()}
            disabled={busy}
          >
            再点一次：立即清理（不可撤销）
          </button>
        ) : (
          <button
            type="button"
            className="paf-btn border border-[var(--paf-border)]"
            data-state="arm-run"
            onClick={() => setArmedRun(true)}
          >
            立即清理一次
          </button>
        )}
      </div>
      <p className="text-xs text-[var(--paf-muted)]">
        「立即清理一次」会按上面的天数删数据，删掉的内容无法在这里恢复。
      </p>

      {notice ? (
        <NoticeBar
          tone={notice.tone}
          message={notice.message}
          onClose={() => setNotice(null)}
        />
      ) : null}
    </section>
  )
}
