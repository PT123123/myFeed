/**
 * B07 connection self-check: three visible states instead of one "失败" label,
 * so a wrong address, a missing token and a dead server are distinguishable
 * from the UI alone.
 */

import { ApiError } from './api'

export type DiagState = 'pass' | 'fail' | 'skip'

export interface DiagStep {
  id: 'reachable' | 'authorized' | 'syncable'
  label: string
  state: DiagState
  detail: string
}

export interface DiagReport {
  steps: DiagStep[]
  warnings: string[]
  at: string
}

interface HealthBody {
  status?: string
  version?: string
  auth_required?: boolean
  database?: string
  time?: string
}

const PROBE_TIMEOUT_MS = 5000

export function normalizeBase(raw: string): string {
  return raw.trim().replace(/\/+$/, '')
}

export function isLoopbackBase(raw: string): boolean {
  try {
    const host = new URL(normalizeBase(raw)).hostname
    return host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '0.0.0.0'
  } catch {
    return false
  }
}

async function probe(
  url: string,
  token: string,
): Promise<{ ok: boolean; status: number; body: unknown; reason: string }> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS)
  try {
    const resp = await fetch(url, {
      signal: controller.signal,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    let body: unknown = null
    try {
      body = await resp.json()
    } catch {
      body = null
    }
    return {
      ok: resp.ok,
      status: resp.status,
      body,
      reason: resp.ok ? '' : `HTTP ${resp.status}`,
    }
  } catch (err) {
    const aborted = err instanceof Error && err.name === 'AbortError'
    return {
      ok: false,
      status: 0,
      body: null,
      reason: aborted ? `${PROBE_TIMEOUT_MS / 1000}s 内没有响应` : '无法连接（服务未启动、地址错误或 CORS 被拒）',
    }
  } finally {
    clearTimeout(timer)
  }
}

export async function runDiagnostics(rawBase: string, token: string): Promise<DiagReport> {
  const base = normalizeBase(rawBase)
  const warnings: string[] = []
  const steps: DiagStep[] = []

  if (!base || !/^https?:\/\//i.test(base)) {
    steps.push({
      id: 'reachable',
      label: '服务进程',
      state: 'fail',
      detail: '地址要写成 http://127.0.0.1:8000 这种完整形式',
    })
    steps.push({ id: 'authorized', label: '访问令牌', state: 'skip', detail: '地址不合法，未检测' })
    steps.push({ id: 'syncable', label: '同步通道', state: 'skip', detail: '地址不合法，未检测' })
    return { steps, warnings, at: new Date().toISOString() }
  }

  const isHttps = base.startsWith('https://')
  if (!isHttps && !isLoopbackBase(base)) {
    warnings.push(
      '局域网 HTTP 适用于可信 Wi-Fi；通过公网连接时使用 HTTPS。',
    )
  }

  const health = await probe(`${base}/health`, '')
  if (!health.ok) {
    steps.push({ id: 'reachable', label: '服务进程', state: 'fail', detail: health.reason })
    steps.push({ id: 'authorized', label: '访问令牌', state: 'skip', detail: '服务没响应，未检测' })
    steps.push({ id: 'syncable', label: '同步通道', state: 'skip', detail: '服务没响应，未检测' })
    return { steps, warnings, at: new Date().toISOString() }
  }
  const info = (health.body ?? {}) as HealthBody
  steps.push({
    id: 'reachable',
    label: '服务进程',
    state: info.status === 'ok' ? 'pass' : 'fail',
    detail: `v${info.version ?? '?'} · 数据库 ${info.database ?? '?'}${info.time ? ` · ${info.time}` : ''}`,
  })

  const authRequired = Boolean(info.auth_required)
  const tokenSet = token.trim() !== ''
  const api = await probe(`${base}/api/v1/interests`, tokenSet ? token : '')
  if (api.status === 401) {
    steps.push({
      id: 'authorized',
      label: '访问令牌',
      state: 'fail',
      detail: tokenSet ? '令牌不对：请填服务端 PAF_AUTH_TOKEN 的同一个值' : '服务端开了鉴权，这里还没填令牌',
    })
  } else if (!api.ok) {
    steps.push({ id: 'authorized', label: '访问令牌', state: 'fail', detail: api.reason })
  } else {
    steps.push({
      id: 'authorized',
      label: '访问令牌',
      state: 'pass',
      detail: authRequired ? '令牌有效' : '服务端未开鉴权（仅本机回环时安全）',
    })
  }

  if (api.status === 401) {
    steps.push({ id: 'syncable', label: '同步通道', state: 'skip', detail: '先修好令牌再测同步' })
  } else {
    const changes = await probe(`${base}/api/v1/sync/changes?since_seq=0&limit=1`, token)
    if (changes.status === 410) {
      steps.push({
        id: 'syncable',
        label: '同步通道',
        state: 'pass',
        detail: '游标会过期：首次同步自动做全量快照',
      })
    } else if (changes.ok) {
      const body = (changes.body ?? {}) as { next_seq?: number }
      steps.push({
        id: 'syncable',
        label: '同步通道',
        state: 'pass',
        detail: `变更日志可读，当前最新序号 ${body.next_seq ?? 0}`,
      })
    } else {
      steps.push({
        id: 'syncable',
        label: '同步通道',
        state: 'fail',
        detail: changes.reason || `HTTP ${changes.status}`,
      })
    }
  }

  return { steps, warnings, at: new Date().toISOString() }
}

export function describeApiError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code} — ${err.message}`
  return err instanceof Error ? err.message : '未知错误'
}
