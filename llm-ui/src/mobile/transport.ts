/** Origin-restricted Android message transport. No API keys are bundled in the UI. */
export interface MobileHost {
  baseUrl: string
  /** Bearer token held by the native side (the single source of truth on a phone). */
  token?: string
  /** True = the app reads content natively, so this WebView only runs the production side. */
  manage?: boolean
}
interface Reply { id: string; error?: string; status?: number; body?: string; value?: unknown }
declare global {
  interface Window {
    __YUELIU_HOST__?: MobileHost
    llmTransport?: { postMessage: (message: string) => void; onmessage: ((event: MessageEvent<string>) => void) | null }
  }
}
const pending = new Map<string, { resolve: (value: Reply) => void; reject: (error: Error) => void }>()
let serial = 0
export const isMobileHost = () => typeof window !== 'undefined' && !!window.__YUELIU_HOST__
export const initialBaseUrl = () => typeof window === 'undefined' ? '' : window.__YUELIU_HOST__?.baseUrl ?? ''
export const injectedToken = () => typeof window === 'undefined' ? '' : window.__YUELIU_HOST__?.token ?? ''
/** 「只管生产」形态：阅读那一半在原生那条流里，这里不再并列一套 Feed/收藏/发现。 */
export const isManageMode = () => typeof window !== 'undefined' && !!window.__YUELIU_HOST__?.manage

export function nativeRequest(action: string, payload: Record<string, unknown> = {}): Promise<Reply> {
  const transport = window.llmTransport
  if (!transport) return Promise.reject(new Error('当前 WebView 不支持应用通信，请更新 Android System WebView'))
  const id = `llm-${++serial}`
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      pending.delete(id)
      reject(new Error(action === 'export' ? '保存文件超时' : '电脑服务未响应，请检查地址和网络'))
    }, action === 'export' ? 300_000 : 35_000)
    pending.set(id, {
      resolve: (reply) => { clearTimeout(timer); resolve(reply) },
      reject: (error) => { clearTimeout(timer); reject(error) },
    })
    transport.onmessage = (event) => {
      const reply = JSON.parse(event.data) as Reply
      const request = pending.get(reply.id)
      if (!request) return
      pending.delete(reply.id)
      if (reply.error) request.reject(new Error(reply.error))
      else request.resolve(reply)
    }
    transport.postMessage(JSON.stringify({ id, action, ...payload }))
  })
}

/** Only API calls use native HTTP, so assets and external websites never receive this bridge. */
export function installNativeFetch(): void {
  if (!isMobileHost()) return
  const original = window.fetch.bind(window)
  window.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const target = new URL(url, window.location.href)
    if (!/^https?:$/.test(target.protocol) ||
        !(target.pathname.startsWith('/api/v1/') || target.pathname === '/health')) {
      return original(input, init)
    }
    const request = new Request(input, init)
    if (request.signal.aborted) throw new DOMException('Aborted', 'AbortError')
    const result = nativeRequest('http', {
      url: request.url, method: request.method,
      headers: Object.fromEntries(request.headers.entries()),
      body: request.method === 'GET' || request.method === 'HEAD' ? null : await request.text(),
    })
    const reply = await new Promise<Reply>((resolve, reject) => {
      const abort = () => reject(new DOMException('Aborted', 'AbortError'))
      request.signal.addEventListener('abort', abort, { once: true })
      void result.then(resolve, reject).finally(() => request.signal.removeEventListener('abort', abort))
    })
    if (request.signal.aborted) throw new DOMException('Aborted', 'AbortError')
    return new Response(reply.status === 204 ? null : reply.body ?? '', {
      status: reply.status ?? 502, headers: { 'Content-Type': 'application/json' },
    })
  }
}
