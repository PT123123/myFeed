import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  getNativeInfo,
  isTauri,
  listNativeBackupFiles,
  nativePlatform,
  readNativeBackupFile,
  saveNativeBackupFile,
  setPageZoom,
} from './native'

type Handlers = Record<string, (args?: Record<string, unknown>) => unknown>

function stubShell(handlers: Handlers, webview = true) {
  const invoke = vi.fn(async (cmd: string, args?: Record<string, unknown>) => {
    const handler = handlers[cmd]
    if (!handler) throw new Error(`unexpected command: ${cmd}`)
    return handler(args)
  })
  const setZoom = vi.fn(async () => {})
  vi.stubGlobal('window', {
    __TAURI__: {
      core: { invoke },
      ...(webview ? { webview: { getCurrentWebview: () => ({ setZoom }) } } : {}),
    },
  })
  return { invoke, setZoom }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('without a shell', () => {
  it('reports the plain web surface and never calls commands', async () => {
    expect(isTauri()).toBe(false)
    expect(nativePlatform()).toBe('web')
    expect(await getNativeInfo()).toBeNull()
    expect(await saveNativeBackupFile('paf-backup-x.json', '{}')).toBeNull()
    expect(await listNativeBackupFiles()).toEqual([])
    expect(await setPageZoom(1.25)).toBe(false)
  })
})

describe('with a stubbed Tauri shell', () => {
  it('calls the commands the Rust side registers, with matching args', async () => {
    const { invoke } = stubShell({
      native_info: () => ({
        backupDir: '/downloads',
        appDataDir: '/data',
        cacheDir: '/cache',
        appVersion: '0.6.0',
      }),
      save_backup: (args) => `/downloads/${String(args?.name)}`,
    })
    const info = await getNativeInfo()
    expect(info?.backupDir).toBe('/downloads')
    expect(invoke).toHaveBeenCalledWith('native_info', undefined)

    const path = await saveNativeBackupFile('paf-backup-a.json', '{"a":1}')
    expect(path).toBe('/downloads/paf-backup-a.json')
    expect(invoke).toHaveBeenCalledWith('save_backup', {
      name: 'paf-backup-a.json',
      contents: '{"a":1}',
    })
  })

  it('reads the platform from the shell webview user agent', async () => {
    stubShell({})
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Linux; Android 15) wry/0.57' })
    expect(nativePlatform()).toBe('android')
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) wry' })
    expect(nativePlatform()).toBe('windows')
  })

  it('degrades to an empty list when the shell cannot read the directory', async () => {
    stubShell({
      list_backups: () => {
        throw new Error('BACKUP_DIR_UNAVAILABLE')
      },
    })
    expect(await listNativeBackupFiles()).toEqual([])
  })

  it('keeps the shell error text so the UI can show what to fix', async () => {
    stubShell({
      read_backup: () => {
        throw new Error('READ_FAILED: 系统找不到指定的文件。 (os error 2)')
      },
    })
    await expect(readNativeBackupFile('paf-backup-gone.json')).rejects.toThrow(/READ_FAILED/)
  })

  it('drives webview zoom when the shell exposes it', async () => {
    const { setZoom } = stubShell({})
    expect(await setPageZoom(1.25)).toBe(true)
    expect(setZoom).toHaveBeenCalledWith(1.25)
  })

  it('reports no zoom support when the shell has no webview API', async () => {
    stubShell({}, false)
    expect(await setPageZoom(1)).toBe(false)
  })

  it('swallows native info failures instead of blocking the settings page', async () => {
    stubShell({
      native_info: () => {
        throw new Error('boom')
      },
    })
    expect(await getNativeInfo()).toBeNull()
  })
})
