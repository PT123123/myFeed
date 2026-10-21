import { useState } from 'react'
import type { AppSettings } from '../db/types'
import { normalizeBase, runDiagnostics, type DiagReport } from '../services/diagnostics'
import { runSync } from '../services/sync'
import { isMobileHost, nativeRequest } from '../mobile/transport'

type UpdateSetting = <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => Promise<void>

export function ConnectionPanel({ settings, update }: { settings: AppSettings; update: UpdateSetting }) {
  const [draft, setDraft] = useState<string | null>(null)
  const [report, setReport] = useState<DiagReport | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  const address = draft ?? settings.apiBaseUrl
  const save = async () => {
    setBusy(true)
    setNote('')
    try {
      const base = normalizeBase(address)
      if (!/^https?:\/\//i.test(base)) throw new Error('请填写完整电脑地址，例如 http://192.168.0.2:8000')
      if (isMobileHost()) await nativeRequest('connection', { baseUrl: base })
      await update('apiBaseUrl', base)
      setDraft(null)
      const result = await runDiagnostics(base, settings.authToken)
      setReport(result)
      if (result.steps.every((step) => step.state === 'pass')) {
        const sync = await runSync({ force: true })
        setNote(sync.status === 'ok' ? '已连接并同步。返回 Feed 查看电脑生成的内容。' : `地址已保存；${sync.error?.message ?? '稍后自动同步'}`)
      } else setNote('地址已保存，请查看下面的连接检查。')
    } catch (error) { setNote(error instanceof Error ? error.message : String(error)) }
    finally { setBusy(false) }
  }
  return <section className="paf-card space-y-3">
    <h2 className="paf-section-title">连接阅流电脑端</h2>
    <p className="text-sm text-[var(--paf-muted)]">填写电脑服务地址。手机与电脑连接同一 Wi-Fi；电脑端需开启局域网服务。模型密钥仍由电脑端保管。</p>
    <label className="block space-y-1 text-sm">电脑地址
      <input className="paf-input" value={address} onChange={(event) => setDraft(event.target.value)}
        placeholder="http://192.168.0.2:8000" inputMode="url" autoComplete="off" spellCheck={false} />
    </label>
    <button className="paf-btn bg-[var(--paf-accent)] text-white" disabled={busy} onClick={() => void save()}>
      {busy ? '检查并同步…' : '保存地址并同步'}
    </button>
    {note && <p role="status" className="text-sm text-[var(--paf-muted)]">{note}</p>}
    {report && <ul className="space-y-2 text-sm">{report.steps.map((step) => <li key={step.id}>
      <span className={step.state === 'fail' ? 'text-red-500' : 'text-[var(--paf-muted)]'}>
        {step.state === 'pass' ? '✓' : step.state === 'fail' ? '✗' : '—'} {step.label}：{step.detail}
      </span>
    </li>)}</ul>}
    <p className="text-xs text-[var(--paf-muted)]">电脑离线时可继续阅读已缓存内容、收藏和评论；待联网后同步。生成和配置修改需要电脑服务在线。</p>
  </section>
}