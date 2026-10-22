import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { injectedToken, installNativeFetch, isManageMode } from './transport'

const originalFetch = vi.fn()
let send: ReturnType<typeof vi.fn>
beforeEach(() => {
  originalFetch.mockReset().mockResolvedValue(new Response('asset'))
  send = vi.fn((raw: string) => {
    const message = JSON.parse(raw) as { id: string }
    queueMicrotask(() => window.llmTransport?.onmessage?.({ data: JSON.stringify({
      id: message.id, status: 200, body: '{"items":[]}',
    }) } as MessageEvent<string>))
  })
  vi.stubGlobal('window', {
    __YUELIU_HOST__: { baseUrl: 'http://reader.example:8000' },
    location: { href: 'https://appassets.androidplatform.net/llm/index.html' },
    fetch: originalFetch,
    setTimeout, clearTimeout,
    llmTransport: { postMessage: send, onmessage: null },
  })
  installNativeFetch()
})
afterEach(() => vi.unstubAllGlobals())

it('uses Android HTTP for API calls and preserves body and idempotency metadata', async () => {
  const response = await window.fetch('http://reader.example:8000/api/v1/sync/operations', {
    method: 'POST', body: '{"operations":[]}', headers: { 'Idempotency-Key': 'fixture-operation' },
  })
  expect(await response.json()).toEqual({ items: [] })
  const request = JSON.parse(send.mock.calls[0][0])
  expect(request.body).toBe('{"operations":[]}')
  expect(request.headers['idempotency-key']).toBe('fixture-operation')
  expect(originalFetch).not.toHaveBeenCalled()
})

it('leaves assets and external non-API pages outside the Android API bridge', async () => {
  await window.fetch('https://example.com/article')
  expect(originalFetch).toHaveBeenCalledOnce()
  expect(send).not.toHaveBeenCalled()
})

it('does not start a native request when the caller already cancelled it', async () => {
  const abort = new AbortController()
  abort.abort()
  await expect(window.fetch('http://reader.example:8000/health', { signal: abort.signal })).rejects.toMatchObject({ name: 'AbortError' })
  expect(send).not.toHaveBeenCalled()
})

/**
 * 原生注入的那三个字段。
 *
 * `window.__YUELIU_HOST__` 是原生在 `</head>` 前塞进 index.html 的，所以读取只能发生在
 * 脚本执行之前 —— App.tsx / AppShell.tsx 都在模块顶层调用这两个函数一次并缓存结果。
 * 少钉一颗的代价是「阅读入口又回到手机壳里」这种没人会当 bug 报的现象。
 */
it('reads manage mode and token from the injected host object', () => {
  vi.unstubAllGlobals()
  vi.stubGlobal('window', { __YUELIU_HOST__: { baseUrl: 'http://reader.example:8000', token: 'desk-token', manage: true } })
  expect(isManageMode()).toBe(true)
  expect(injectedToken()).toBe('desk-token')

  vi.unstubAllGlobals()
  vi.stubGlobal('window', { __YUELIU_HOST__: { baseUrl: 'http://reader.example:8000' } })
  expect(isManageMode()).toBe(false)
  expect(injectedToken()).toBe('')

  vi.unstubAllGlobals()
  vi.stubGlobal('window', { location: { href: 'https://example.com/llm/index.html' } })
  expect(isManageMode()).toBe(false)
  expect(injectedToken()).toBe('')
})
