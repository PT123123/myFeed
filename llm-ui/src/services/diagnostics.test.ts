import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from './api'
import { describeApiError, isLoopbackBase, normalizeBase, runDiagnostics } from './diagnostics'

interface FakeResp {
  ok: boolean
  status: number
  json: () => Promise<unknown>
}

type Routes = Record<string, { status?: number; body?: unknown; reject?: Error }>

const HEALTH = {
  status: 'ok',
  version: '0.6.0',
  auth_required: true,
  database: 'sqlite',
  time: '2026-10-11T00:00:00Z',
}

function stubFetch(routes: Routes) {
  const calls: { url: string; init: RequestInit }[] = []
  vi.stubGlobal(
    'fetch',
    async (input: string | URL, init: RequestInit = {}) => {
      const url = String(input)
      calls.push({ url, init })
      const key = Object.keys(routes).find((k) => url.endsWith(k))
      const route = key ? routes[key] : undefined
      if (!route) throw new Error(`unexpected fetch: ${url}`)
      if (route.reject) throw route.reject
      const status = route.status ?? 200
      return {
        ok: status >= 200 && status < 300,
        status,
        json: async () => route.body ?? null,
      } as unknown as FakeResp
    },
  )
  return calls
}

function step(report: Awaited<ReturnType<typeof runDiagnostics>>, id: string) {
  const found = report.steps.find((s) => s.id === id)
  if (!found) throw new Error(`missing step: ${id}`)
  return found
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('地址解析', () => {
  it('去掉首尾空格与尾部斜杠', () => {
    expect(normalizeBase('  http://127.0.0.1:8000///  ')).toBe('http://127.0.0.1:8000')
  })

  it('只对回环地址返回 true，写坏的地址不报错', () => {
    expect(isLoopbackBase('http://localhost:8000')).toBe(true)
    expect(isLoopbackBase('http://127.0.0.1:8000')).toBe(true)
    expect(isLoopbackBase('http://[::1]:8000')).toBe(true)
    expect(isLoopbackBase('http://10.0.2.2:8000')).toBe(false)
    expect(isLoopbackBase('not a url')).toBe(false)
  })
})

describe('三步自检', () => {
  it('全通时逐步给绿灯，并把服务端版本与最新序号写出来', async () => {
    const calls = stubFetch({
      '/health': { body: HEALTH },
      '/api/v1/interests': { body: [] },
      'sync/changes?since_seq=0&limit=1': { body: { changes: [], next_seq: 42, has_more: false } },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000/', 'tok')
    expect(report.steps.map((s) => s.state)).toEqual(['pass', 'pass', 'pass'])
    expect(step(report, 'reachable').detail).toContain('v0.6.0')
    expect(step(report, 'syncable').detail).toBe('变更日志可读，当前最新序号 42')
    expect(report.warnings).toEqual([])
    const authHeader = (calls[1].init.headers as Record<string, string>).Authorization
    expect(authHeader).toBe('Bearer tok')
  })

  it('服务端未开鉴权时说明清楚，而不是假装校验过令牌', async () => {
    stubFetch({
      '/health': { body: { ...HEALTH, auth_required: false } },
      '/api/v1/interests': { body: [] },
      'sync/changes?since_seq=0&limit=1': { body: { changes: [], next_seq: 0, has_more: false } },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', '')
    expect(step(report, 'authorized').detail).toBe('服务端未开鉴权（仅本机回环时安全）')
  })

  it('401 且没填令牌：只修令牌，同步通道先不测', async () => {
    const calls = stubFetch({
      '/health': { body: HEALTH },
      '/api/v1/interests': { status: 401, body: null },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', '   ')
    expect(step(report, 'authorized')).toMatchObject({
      state: 'fail',
      detail: '服务端开了鉴权，这里还没填令牌',
    })
    expect(step(report, 'syncable')).toMatchObject({ state: 'skip', detail: '先修好令牌再测同步' })
    expect(calls).toHaveLength(2)
  })

  it('401 且填了令牌：指向服务端的 PAF_AUTH_TOKEN', async () => {
    stubFetch({
      '/health': { body: HEALTH },
      '/api/v1/interests': { status: 401, body: null },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', 'wrong-token')
    expect(step(report, 'authorized').detail).toContain('PAF_AUTH_TOKEN')
  })

  it('连不上时第一步失败，后两步不做无意义检测', async () => {
    const calls = stubFetch({
      '/health': { reject: new TypeError('Failed to fetch') },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', 'tok')
    expect(step(report, 'reachable')).toMatchObject({
      state: 'fail',
      detail: '无法连接（服务未启动、地址错误或 CORS 被拒）',
    })
    expect(report.steps.filter((s) => s.state === 'skip')).toHaveLength(2)
    expect(calls).toHaveLength(1)
  })

  it('5 秒没有响应时说明是超时', async () => {
    const err = new Error('aborted')
    err.name = 'AbortError'
    stubFetch({ '/health': { reject: err } })
    const report = await runDiagnostics('http://127.0.0.1:8000', '')
    expect(step(report, 'reachable').detail).toBe('5s 内没有响应')
  })

  it('游标过期（410）算通：首次同步会做全量快照', async () => {
    stubFetch({
      '/health': { body: HEALTH },
      '/api/v1/interests': { body: [] },
      'sync/changes?since_seq=0&limit=1': { status: 410, body: null },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', 'tok')
    expect(step(report, 'syncable').state).toBe('pass')
    expect(step(report, 'syncable').detail).toContain('全量快照')
  })

  it('同步通道真的坏掉时保留 HTTP 状态码', async () => {
    stubFetch({
      '/health': { body: HEALTH },
      '/api/v1/interests': { body: [] },
      'sync/changes?since_seq=0&limit=1': { status: 500, body: null },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', 'tok')
    expect(step(report, 'syncable')).toMatchObject({ state: 'fail', detail: 'HTTP 500' })
  })

  it('health 返回 200 但 status 不是 ok 时，第一步判红并继续查后面', async () => {
    stubFetch({
      '/health': { body: { status: 'degraded', database: 'sqlite' } },
      '/api/v1/interests': { status: 401, body: null },
    })
    const report = await runDiagnostics('http://127.0.0.1:8000', '')
    expect(step(report, 'reachable').state).toBe('fail')
    expect(step(report, 'authorized').state).toBe('fail')
  })

  it('地址缺少 http:// 前缀时直接判失败，不发请求', async () => {
    const calls = stubFetch({})
    const report = await runDiagnostics('127.0.0.1:8000', 'tok')
    expect(step(report, 'reachable').detail).toContain('完整形式')
    expect(calls).toHaveLength(0)
  })
})

describe('明文网络提示', () => {
  it('远程 http 地址提示安卓正式包会拦，回环与 https 不提示', async () => {
    const routes = {
      '/health': { body: HEALTH },
      '/api/v1/interests': { body: [] },
      'sync/changes?since_seq=0&limit=1': { body: { changes: [], next_seq: 1, has_more: false } },
    }
    stubFetch(routes)
    const remote = await runDiagnostics('http://feed.example.com', 'tok')
    expect(remote.warnings[0]).toContain('可信 Wi-Fi')

    stubFetch(routes)
    const loopback = await runDiagnostics('http://127.0.0.1:8000', 'tok')
    expect(loopback.warnings).toEqual([])

    stubFetch(routes)
    const tls = await runDiagnostics('https://feed.example.com', 'tok')
    expect(tls.warnings).toEqual([])
  })
})

describe('接口错误文案', () => {
  it('业务错误带错误码，普通错误只说话，其他值兜底', () => {
    expect(describeApiError(new ApiError(409, 'IDEMPOTENCY_CONFLICT', '重复提交', false))).toBe(
      'IDEMPOTENCY_CONFLICT — 重复提交',
    )
    expect(describeApiError(new Error('请求被中断'))).toBe('请求被中断')
    expect(describeApiError('oops')).toBe('未知错误')
  })
})
