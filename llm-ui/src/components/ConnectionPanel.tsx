import { useState } from 'react'
import type { AppSettings } from '../db/types'
import { normalizeBase, runDiagnostics, type DiagReport } from '../services/diagnostics'
import { runSync } from '../services/sync'
import { injectedToken, isManageMode, isMobileHost, nativeRequest } from '../mobile/transport'

type UpdateSetting = <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => Promise<void>

interface LanEndpoint { name: string; baseUrl: string; role: string }
interface LanScanValue { endpoints?: LanEndpoint[]; unresolved?: number; note?: string }

export function ConnectionPanel({ settings, update }: { settings: AppSettings; update: UpdateSetting }) {
  const [draft, setDraft] = useState<string | null>(null)
  const [tokenDraft, setTokenDraft] = useState<string | null>(null)
  const [report, setReport] = useState<DiagReport | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  const [lan, setLan] = useState<LanEndpoint[]>([])
  const [scanning, setScanning] = useState(false)
  const address = draft ?? settings.apiBaseUrl
  // 手机上这把钥匙归原生存（设置页填、原生同步也用它），壳把它递进来当回显
  const token = tokenDraft ?? (settings.authToken || injectedToken())
  const searchLan = async () => {
    setScanning(true)
    setLan([])
    setNote('')
    try {
      // 只有原生壳能发 mDNS 组播，网页这一侧拿不到 5353
      const reply = await nativeRequest('discovery', { role: 'desktop' })
      const value = (reply.value ?? {}) as LanScanValue
      const found = value.endpoints ?? []
      setLan(found)
      if (found.length === 0) {
        setNote(value.note ?? '一台都没搜到。电脑端要把服务开到局域网（just server-lan），并第一次放行 UDP 5353 入站；急的话照旧手填地址。')
      } else if (value.unresolved) {
        setNote(`另有 ${value.unresolved} 台应了答却没报上地址。`)
      }
    } catch (error) { setNote(error instanceof Error ? error.message : String(error)) }
    finally { setScanning(false) }
  }
  const save = async () => {
    setBusy(true)
    setNote('')
    try {
      const base = normalizeBase(address)
      if (!/^https?:\/\//i.test(base)) throw new Error('请填写完整电脑地址，例如 http://192.168.0.2:8000')
      // 一次动作把地址和令牌都交给原生：手机上「连哪台电脑、用哪把钥匙」由原生设置页那份
      // 持久化，网页只是另一个入口。
      if (isMobileHost()) await nativeRequest('connection', { baseUrl: base, token })
      await update('apiBaseUrl', base)
      await update('authToken', token)
      setDraft(null)
      setTokenDraft(null)
      const result = await runDiagnostics(base, token)
      setReport(result)
      if (result.steps.every((step) => step.state === 'pass')) {
        const sync = await runSync({ force: true })
        const where = isManageMode() ? '回手机首页看电脑生成的内容' : '返回 Feed 查看电脑生成的内容'
        setNote(sync.status === 'ok' ? `已连接并同步。${where}。` : `地址已保存；${sync.error?.message ?? '稍后自动同步'}`)
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
    <label className="block space-y-1 text-sm">访问令牌
      <input className="paf-input" value={token} onChange={(event) => setTokenDraft(event.target.value)}
        placeholder="服务端 PAF_AUTH_TOKEN 的同一个值（没开鉴权就留空）" autoComplete="off" spellCheck={false}
        type="password" />
    </label>
    {isMobileHost() && <p className="text-xs text-[var(--paf-muted)]">这把钥匙存在手机这边（设置页 → AI 内容），手机上那条流的同步用的也是它。</p>}
    {isMobileHost() && <div className="space-y-2">
      <button className="paf-btn" disabled={scanning || busy} onClick={() => void searchLan()}>
        {scanning ? '正在局域网里找…（约 5 秒）' : '搜索局域网里的电脑'}
      </button>
      {lan.map((endpoint) => <button key={endpoint.baseUrl}
        className="block w-full rounded-lg border border-[var(--paf-border)] px-3 py-2 text-left text-sm"
        onClick={() => { setDraft(endpoint.baseUrl); setNote('') }}>
        <span className="font-medium">{endpoint.name}</span>
        <span className="ml-2 text-xs text-[var(--paf-muted)]">{endpoint.baseUrl}</span>
      </button>)}
      {lan.length > 0 && <p className="text-xs text-[var(--paf-muted)]">点一行填进上面那个框，仍要按「保存地址并同步」。</p>}
    </div>}
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