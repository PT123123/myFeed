/**
 * B07 data backup: a single JSON file that round-trips the whole local store.
 *
 * - Exports never contain the access token (`authToken`) or the device identity
 *   keys, so a backup file is safe to move between machines.
 * - Imports are validated before anything is written and run in one Dexie
 *   transaction, so a damaged file can never leave half a library behind.
 * - `formatVersion` gates files written by a future schema: we refuse instead of
 *   guessing at fields we do not know yet.
 */

import { db, type PafDatabase } from '../db'
import type {
  CommentRow,
  GenerationJobRow,
  InterestRow,
  InteractionRow,
  OutboxRow,
  PostRow,
  TopicRow,
} from '../db/types'

export const BACKUP_APP = 'personal-ai-feed'
export const BACKUP_FORMAT_VERSION = 1
/** Bump together with a Dexie `version(n)` when stored shapes change. */
export const BACKUP_SCHEMA_VERSION = 1

const SETTING_SECRET_KEYS = ['authToken']
const SYNC_IDENTITY_PREFIXES = ['device_id', 'client_seq:']
const SYNC_STATE_KEYS = ['last_seq', 'last_sync_at', 'sync_error', 'sync_note', 'backoff']

type Row = Record<string, unknown>

export interface BackupTables {
  posts: PostRow[]
  comments: CommentRow[]
  topics: TopicRow[]
  interests: InterestRow[]
  interactions: InteractionRow[]
  outbox: OutboxRow[]
  generationJobs: GenerationJobRow[]
  settings: { key: string; value: unknown }[]
  syncMeta: { key: string; value: unknown }[]
}

export interface BackupFile {
  app: typeof BACKUP_APP
  formatVersion: number
  schemaVersion: number
  exportedAt: string
  deviceId: string
  counts: Partial<Record<keyof BackupTables, number>>
  tables: BackupTables
}

export type ImportMode = 'merge' | 'replace'

export interface ImportSummary {
  mode: ImportMode
  postsAdded: number
  postsUpdated: number
  comments: number
  topics: number
  interests: number
  interactions: number
  jobs: number
  outboxQueued: number
  outboxSkipped: number
  settings: number
  clearedRows: number
  /** Local primary keys this import queued, so the caller can undo them. */
  queuedOpIds: string[]
}

export class BackupError extends Error {
  code: string

  constructor(code: string, message: string) {
    super(message)
    this.code = code
  }
}

export function backupFileName(at = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp =
    `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}` +
    `T${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`
  return `paf-backup-${stamp}.json`
}

// --- collect ---------------------------------------------------------------

function stripSecretSettings(rows: { key: string; value: unknown }[]) {
  return rows.filter((r) => !SETTING_SECRET_KEYS.includes(r.key))
}

/** Identity keys belong to the device that owns the sync cursors. */
function stripIdentityMeta(rows: { key: string; value: unknown }[]) {
  return rows.filter((r) => !SYNC_IDENTITY_PREFIXES.some((p) => r.key.startsWith(p)))
}

export async function collectBackup(database: PafDatabase = db): Promise<BackupFile> {
  const [
    posts,
    comments,
    topics,
    interests,
    interactions,
    outbox,
    generationJobs,
    settings,
    syncMeta,
  ] = await Promise.all([
    database.posts.toArray(),
    database.comments.toArray(),
    database.topics.toArray(),
    database.interests.toArray(),
    database.interactions.toArray(),
    database.outbox.toArray(),
    database.generation_jobs_cache.toArray(),
    database.settings.toArray(),
    database.sync_meta.toArray(),
  ])
  const deviceIdRow = syncMeta.find((r) => r.key === 'device_id')
  const tables: BackupTables = {
    posts,
    comments,
    topics,
    interests,
    interactions,
    outbox,
    generationJobs,
    settings: stripSecretSettings(settings),
    syncMeta: stripIdentityMeta(syncMeta),
  }
  const counts = Object.fromEntries(
    Object.entries(tables).map(([name, rows]) => [name, (rows as Row[]).length]),
  ) as BackupFile['counts']
  return {
    app: BACKUP_APP,
    formatVersion: BACKUP_FORMAT_VERSION,
    schemaVersion: BACKUP_SCHEMA_VERSION,
    exportedAt: new Date().toISOString(),
    deviceId: typeof deviceIdRow?.value === 'string' ? deviceIdRow.value : '',
    counts,
    tables,
  }
}

export async function serializeBackup(database: PafDatabase = db): Promise<string> {
  return JSON.stringify(await collectBackup(database), null, 1)
}

// --- parse -----------------------------------------------------------------

const TABLE_KEYS: (keyof BackupTables)[] = [
  'posts',
  'comments',
  'topics',
  'interests',
  'interactions',
  'outbox',
  'generationJobs',
  'settings',
  'syncMeta',
]

/** Each Dexie table has its own primary key path; interests keys on topicId. */
const KEY_FIELD: Record<keyof BackupTables, string> = {
  posts: 'id',
  comments: 'id',
  topics: 'id',
  interests: 'topicId',
  interactions: 'id',
  outbox: 'id',
  generationJobs: 'id',
  settings: 'key',
  syncMeta: 'key',
}

function assertArray(name: keyof BackupTables, rows: unknown, problems: string[]): Row[] {
  if (!Array.isArray(rows)) {
    problems.push(`${name} 不是列表`)
    return []
  }
  const idField = KEY_FIELD[name]
  const bad = (rows as Row[]).filter(
    (r) => !r || typeof r !== 'object' || typeof r[idField] !== 'string',
  )
  if (bad.length > 0) {
    problems.push(`${name} 有 ${bad.length} 条记录缺少 ${idField}`)
  }
  return rows as Row[]
}

export function parseBackup(text: string): BackupFile {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    throw new BackupError(
      'NOT_JSON',
      '文件不是有效的 JSON：可能被截断、被改写过，或不是本应用导出的备份',
    )
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new BackupError('NOT_BACKUP', '这不像一份备份：文件顶层不是一个对象')
  }
  const file = raw as Partial<BackupFile>
  if (file.app !== BACKUP_APP) {
    throw new BackupError(
      'WRONG_APP',
      `这是别的程序导出的文件（app=${String(file.app ?? '缺失')}），不是 Personal AI Feed 备份`,
    )
  }
  if (typeof file.formatVersion !== 'number') {
    throw new BackupError('NO_VERSION', '备份缺少格式版本号，无法判断能不能安全导入')
  }
  if (file.formatVersion > BACKUP_FORMAT_VERSION) {
    throw new BackupError(
      'TOO_NEW',
      `这份备份来自更高的格式版本 v${file.formatVersion}（当前应用只认到 v${BACKUP_FORMAT_VERSION}）：请先升级应用，再导入`,
    )
  }
  if (file.formatVersion < BACKUP_FORMAT_VERSION) {
    throw new BackupError(
      'TOO_OLD',
      `这份备份的格式版本是 v${file.formatVersion}，当前应用要求 v${BACKUP_FORMAT_VERSION}：请先升级到中间版本迁移一次`,
    )
  }
  if (!file.tables || typeof file.tables !== 'object') {
    throw new BackupError('NO_TABLES', '备份里没有任何数据区块（tables 字段缺失）')
  }
  const problems: string[] = []
  const tables = {} as BackupTables
  const rawTables = file.tables as unknown as Record<string, unknown>
  for (const name of TABLE_KEYS) {
    // A missing block is fine only for generationJobs (pure cache) and syncMeta.
    const value = rawTables[name]
    if (value === undefined) {
      if (name === 'generationJobs' || name === 'syncMeta' || name === 'settings') {
        tables[name] = [] as never
        continue
      }
      problems.push(`${name} 区块缺失`)
      continue
    }
    tables[name] = assertArray(name, value, problems) as never
  }
  if (problems.length > 0) {
    throw new BackupError('BROKEN_FILE', `备份文件不完整：${problems.slice(0, 3).join('；')}`)
  }
  return {
    app: BACKUP_APP,
    formatVersion: file.formatVersion,
    schemaVersion: typeof file.schemaVersion === 'number' ? file.schemaVersion : 0,
    exportedAt: typeof file.exportedAt === 'string' ? file.exportedAt : '',
    deviceId: typeof file.deviceId === 'string' ? file.deviceId : '',
    counts: file.counts ?? {},
    tables,
  }
}

// --- merge rules -----------------------------------------------------------

function ts(iso: unknown): number {
  if (typeof iso !== 'string') return 0
  const t = Date.parse(iso)
  return Number.isNaN(t) ? 0 : t
}

function num(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * Content follows the higher revision; reading/collection state is the union of
 * both sides, matching the B06 server merge rules.
 */
export function mergePost(local: PostRow, incoming: PostRow): PostRow {
  const content = incoming.revision >= local.revision ? incoming : local
  const like =
    Math.abs(num(incoming.likeState)) >= Math.abs(num(local.likeState))
      ? incoming.likeState
      : local.likeState
  return {
    ...local,
    ...content,
    saved: Boolean(local.saved || incoming.saved),
    hidden: Boolean(local.hidden || incoming.hidden),
    likeState: like,
    lastReadAt: ts(incoming.lastReadAt) > ts(local.lastReadAt) ? incoming.lastReadAt : local.lastReadAt,
    readProgress: Math.max(num(local.readProgress), num(incoming.readProgress)),
  }
}

function mergeInterest(local: InterestRow, incoming: InterestRow): InterestRow {
  return num(incoming.revision) >= num(local.revision) ? incoming : local
}

function isPending(row: OutboxRow): boolean {
  return row.status === 'pending'
}

// --- apply -----------------------------------------------------------------

export async function applyBackup(
  file: BackupFile,
  mode: ImportMode,
  database: PafDatabase = db,
): Promise<ImportSummary> {
  const summary: ImportSummary = {
    mode,
    postsAdded: 0,
    postsUpdated: 0,
    comments: 0,
    topics: 0,
    interests: 0,
    interactions: 0,
    jobs: 0,
    outboxQueued: 0,
    outboxSkipped: 0,
    settings: 0,
    clearedRows: 0,
    queuedOpIds: [],
  }

  await database.transaction(
    'rw',
    [
      database.posts,
      database.comments,
      database.topics,
      database.interests,
      database.interactions,
      database.outbox,
      database.generation_jobs_cache,
      database.settings,
      database.sync_meta,
    ],
    async () => {
      if (mode === 'replace') {
        const before =
          (await database.posts.count()) +
          (await database.comments.count()) +
          (await database.topics.count()) +
          (await database.interests.count()) +
          (await database.interactions.count()) +
          (await database.generation_jobs_cache.count())
        await Promise.all([
          database.posts.clear(),
          database.comments.clear(),
          database.topics.clear(),
          database.interests.clear(),
          database.interactions.clear(),
          database.generation_jobs_cache.clear(),
        ])
        summary.clearedRows = before
        // The file describes a library we do not have deltas for: replay the
        // server change log from the start instead of trusting a stale cursor.
        await database.sync_meta.delete('last_seq')
        await database.sync_meta.delete('backoff')
        await database.sync_meta.delete('sync_error')
        await database.sync_meta.delete('sync_note')
      }

      const deviceId = await database.sync_meta.get('device_id')
      const localDevice = typeof deviceId?.value === 'string' ? deviceId.value : ''

      for (const row of file.tables.posts) {
        const existing = await database.posts.get(row.id)
        if (existing) {
          await database.posts.put(mergePost(existing, row))
          summary.postsUpdated += 1
        } else {
          await database.posts.put(row)
          summary.postsAdded += 1
        }
      }

      for (const row of file.tables.comments) {
        await database.comments.put(row)
        summary.comments += 1
      }
      for (const row of file.tables.topics) {
        await database.topics.put(row)
        summary.topics += 1
      }
      for (const row of file.tables.interests) {
        const existing = await database.interests.get(row.topicId)
        await database.interests.put(existing ? mergeInterest(existing, row) : row)
        summary.interests += 1
      }
      for (const row of file.tables.interactions) {
        if (await database.interactions.get(row.id)) continue
        await database.interactions.put(row)
        summary.interactions += 1
      }
      for (const row of file.tables.generationJobs) {
        await database.generation_jobs_cache.put(row)
        summary.jobs += 1
      }

      // Ops already known to this device stay as they are (already synced or
      // already failed); foreign pending ops are re-queued untouched so the
      // server's op_id archive can dedupe a replay. `opId` is not a Dexie
      // index, so the local set is read once instead of queried per row.
      const knownOps = new Set((await database.outbox.toArray()).map((r) => r.opId))
      for (const row of file.tables.outbox) {
        const sameIdentity = localDevice !== '' && row.deviceId === localDevice
        if (knownOps.has(row.opId) || (sameIdentity && !isPending(row))) {
          summary.outboxSkipped += 1
          continue
        }
        await database.outbox.put(row)
        knownOps.add(row.opId)
        summary.outboxQueued += 1
        summary.queuedOpIds.push(row.id)
      }

      for (const row of file.tables.settings) {
        if (SETTING_SECRET_KEYS.includes(row.key)) continue
        if (row.key === 'seededVersion') {
          const local = await database.settings.get('seededVersion')
          const localValue = typeof local?.value === 'number' ? local.value : 0
          const incomingValue = typeof row.value === 'number' ? row.value : 0
          await database.settings.put({ key: row.key, value: Math.max(localValue, incomingValue) })
        } else {
          await database.settings.put(row)
        }
        summary.settings += 1
      }

      // Only transport-neutral state (never identity, never a stale cursor) is
      // taken from the file.
      for (const row of file.tables.syncMeta) {
        if (SYNC_IDENTITY_PREFIXES.some((p) => row.key.startsWith(p))) continue
        if (SYNC_STATE_KEYS.includes(row.key) && mode === 'merge') continue
        await database.sync_meta.put(row)
      }
    },
  )

  return summary
}

export function describeImport(s: ImportSummary): string {
  const parts = [
    `帖子 +${s.postsAdded}/更新 ${s.postsUpdated}`,
    `评论 ${s.comments}`,
    `互动 ${s.interactions}`,
  ]
  if (s.interests) parts.push(`兴趣 ${s.interests}`)
  if (s.outboxQueued) parts.push(`待同步 ${s.outboxQueued}`)
  if (s.outboxSkipped) parts.push(`跳过已存在 ${s.outboxSkipped}`)
  if (s.mode === 'replace') parts.push(`导入前清空 ${s.clearedRows} 行`)
  return parts.join('，')
}
