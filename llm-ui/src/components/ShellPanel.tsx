import { useEffect, useState } from 'react'

import { copyToClipboard, getNativeInfo, nativePlatform, setPageZoom, type NativeInfo } from '../lib/native'
import { NoticeBar } from './NoticeBar'

const ZOOMS = [0.8, 1, 1.25, 1.5]

/** Desktop/Android shell specifics: window zoom and where the local store lives. */
export function ShellPanel() {
  const platform = nativePlatform()
  const [info, setInfo] = useState<NativeInfo | null>(null)
  const [zoom, setZoom] = useState(1)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    void getNativeInfo().then((v) => {
      if (alive) setInfo(v)
    })
    return () => {
      alive = false
    }
  }, [])

  const canZoom = platform === 'windows' || platform === 'macos' || platform === 'linux'

  return (
    <section className="paf-card space-y-3">
      <h2 className="paf-section-title">应用壳（{platform}）</h2>
      <ul className="space-y-1 text-sm text-[var(--paf-muted)]">
        <li>壳版本：v{info?.appVersion ?? '—'}</li>
        {info ? <li className="break-all">数据目录：{info.appDataDir}</li> : null}
        {info ? <li className="break-all">缓存目录：{info.cacheDir}</li> : null}
      </ul>
      <div className="flex flex-wrap gap-2">
        {canZoom
          ? ZOOMS.map((z) => (
              <button
                key={z}
                type="button"
                className={`paf-btn border border-[var(--paf-border)] ${z === zoom ? 'paf-btn-active' : ''}`}
                onClick={() => {
                  setZoom(z)
                  void setPageZoom(z).then((ok) =>
                    setNotice(ok ? `页面缩放 ${Math.round(z * 100)}%` : '这个壳不支持页面缩放'),
                  )
                }}
              >
                {Math.round(z * 100)}%
              </button>
            ))
          : null}
        {info ? (
          <button
            type="button"
            className="paf-btn border border-[var(--paf-border)] text-xs"
            onClick={() =>
              void copyToClipboard(`${info.appDataDir}\n${info.cacheDir}`).then((ok) =>
                setNotice(ok ? '目录已复制' : '复制失败，请手动选中文字'),
              )
            }
          >
            复制目录
          </button>
        ) : null}
      </div>
      {canZoom ? (
        <p className="text-xs text-[var(--paf-muted)]">
          快捷键：Ctrl 与 + / − 缩放、Ctrl+0 复位（由壳直接处理）。
        </p>
      ) : (
        <p className="text-xs text-[var(--paf-muted)]">
          安卓用系统字体缩放；物理返回键先退上一页，Feed 首页再按一次退出应用。
        </p>
      )}
      <p className="text-xs text-[var(--paf-muted)]">
        阅读缓存存在这个目录下的 IndexedDB 里：在系统里「清除数据」或卸载应用会一起删掉，导出备份是唯一的取回方式。
      </p>
      {notice ? (
        <NoticeBar tone="info" message={notice} onClose={() => setNotice(null)} />
      ) : null}
    </section>
  )
}
