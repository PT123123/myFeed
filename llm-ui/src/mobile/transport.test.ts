import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { installNativeFetch } from './transport'

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
