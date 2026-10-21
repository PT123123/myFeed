/**
 * Thin bridge to the Tauri shell (B07). Everything here degrades to `null` /
 * `'web'` on the browser build, so callers can fall back to downloads and
 * `<input type="file">` without knowing which surface they run on.
 */

import { isMobileHost, nativeRequest } from '../mobile/transport'
export type NativePlatform = 'windows' | 'android' | 'ios' | 'macos' | 'linux' | 'web'

export interface NativeInfo {
  backupDir: string
  appDataDir: string
  cacheDir: string
  appVersion: string
}

export interface NativeBackupEntry {
  name: string
  bytes: number
  modifiedMs: number
}

interface TauriGlobal {
  core: { invoke: (cmd: string, args?: Record<string, unknown>) => Promise<unknown> }
  webview?: {
    getCurrentWebview: () => { setZoom: (zoom: number) => Promise<unknown> } | Promise<{
      setZoom: (zoom: number) => Promise<unknown>
    }>
  }
}

declare global {
  interface Window {
    __TAURI__?: TauriGlobal
    __TAURI_INTERNALS__?: unknown
  }
}

function tauri(): TauriGlobal | null {
  if (typeof window === 'undefined') return null
  if (window.__TAURI__) return window.__TAURI__
  // Dev shells sometimes expose only the internals; without the global API we
  // still cannot call commands safely, so treat it as plain web.
  return null
}

export function isTauri(): boolean {
  return tauri() !== null
}

export function nativePlatform(): NativePlatform {
  if (isMobileHost()) return 'android'
  const api = tauri()
  if (!api) return 'web'
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent
  if (/Android/i.test(ua)) return 'android'
  if (/iPhone|iPad|iPod|CPU OS/i.test(ua)) return 'ios'
  if (/Windows NT/i.test(ua)) return 'windows'
  if (/Mac OS X|Macintosh/i.test(ua)) return 'macos'
  return 'linux'
}

async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T | null> {
  const api = tauri()
  if (!api) return null
  return (await api.core.invoke(cmd, args)) as T
}

export async function getNativeInfo(): Promise<NativeInfo | null> {
  try {
    return await invoke<NativeInfo>('native_info')
  } catch {
    return null
  }
}

/** Absolute path the shell wrote, or null when there is no shell to ask. */
export async function saveNativeBackupFile(name: string, contents: string): Promise<string | null> {
  return invoke<string>('save_backup', { name, contents })
}

export async function listNativeBackupFiles(): Promise<NativeBackupEntry[]> {
  try {
    return (await invoke<NativeBackupEntry[]>('list_backups')) ?? []
  } catch {
    return []
  }
}

export async function readNativeBackupFile(name: string): Promise<string> {
  const text = await invoke<string>('read_backup', { name })
  if (text === null) throw new Error('NO_SHELL: 当前不是桌面/安卓壳，请直接选择文件')
  return text
}

/** Returns false when the shell has no webview zoom API (Android, plain web). */
export async function setPageZoom(zoom: number): Promise<boolean> {
  const api = tauri()
  if (!api?.webview?.getCurrentWebview) return false
  try {
    const view = await api.webview.getCurrentWebview()
    await view.setZoom(zoom)
    return true
  } catch {
    return false
  }
}

/** Downloads through the browser; the native shell path is chosen by the caller. */
export function downloadAsFile(
  name: string,
  contents: string,
  mime = 'application/json',
): void {
  if (isMobileHost()) {
    void nativeRequest('export', { name, contents, mime }).catch((error: unknown) => window.alert(String(error)))
    return
  }
  const blob = new Blob([contents], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}
