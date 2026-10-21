/**
 * Storage footprint of the local database (docs/09 §5).
 *
 * The browser decides the quota, so this is a read-only report plus an explicit
 * request for persistence; nothing here deletes data on the user's behalf.
 */

export interface StorageStatus {
  supported: boolean
  usageBytes: number
  quotaBytes: number | null
  persisted: boolean | null
}

export const QUOTA_CODE = 'QUOTA_EXCEEDED'

export async function readStorageStatus(): Promise<StorageStatus> {
  if (typeof navigator === 'undefined' || !navigator.storage?.estimate) {
    return { supported: false, usageBytes: 0, quotaBytes: null, persisted: null }
  }
  const est = await navigator.storage.estimate()
  const persisted = navigator.storage.persisted ? await navigator.storage.persisted() : null
  return {
    supported: true,
    usageBytes: est.usage ?? 0,
    quotaBytes: typeof est.quota === 'number' && est.quota > 0 ? est.quota : null,
    persisted,
  }
}

/** Ask the browser to keep this site's storage when space runs low. */
export async function requestPersistence(): Promise<boolean> {
  if (typeof navigator === 'undefined' || !navigator.storage?.persist) return false
  return navigator.storage.persist()
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let i = 0
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i += 1
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[i]}`
}

/** IndexedDB reports a full disk as QuotaExceededError. */
export function isQuotaError(err: unknown): boolean {
  return err instanceof Error && err.name === 'QuotaExceededError'
}

export function quotaMessage(err: unknown): string {
  return err instanceof Error && err.name === 'QuotaExceededError'
    ? '本机存储空间不足：请先在「备份与迁移」导出，再清理浏览器存储后重试。'
    : err instanceof Error
      ? err.message
      : '存储写入失败'
}
