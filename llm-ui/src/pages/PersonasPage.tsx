import { useLiveQuery } from 'dexie-react-hooks'
import { Bot, Plus, RefreshCw, RotateCcw, Save } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { db } from '../db'
import { personaAvatar } from '../lib/comments'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import {
  ApiError,
  createPersona,
  listPersonas,
  patchPersona,
  resetPersonaMemory,
} from '../services/api'
import type { PersonaDto } from '../services/types'

interface PersonaDraft {
  name: string
  bio: string
  position: string
  style_prompt: string
  domains: string
  avoid_topics: string
  reply_frequency: number
  enabled: boolean
  memory_summary: string
}

function toDraft(p: PersonaDto): PersonaDraft {
  return {
    name: p.name,
    bio: p.bio,
    position: p.position,
    style_prompt: p.style_prompt,
    domains: p.domains.join(', '),
    avoid_topics: p.avoid_topics.join(', '),
    reply_frequency: p.reply_frequency,
    enabled: p.enabled,
    memory_summary: p.memory_summary,
  }
}

const EMPTY_DRAFT: PersonaDraft = {
  name: '',
  bio: '',
  position: '',
  style_prompt: '',
  domains: '',
  avoid_topics: '',
  reply_frequency: 1,
  enabled: true,
  memory_summary: '',
}

function splitList(raw: string): string[] {
  return raw
    .split(/[,，、\n]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

export function PersonasPage() {
  const online = useOnlineStatus()
  const [personas, setPersonas] = useState<PersonaDto[]>([])
  const [loading, setLoading] = useState(true)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draft, setDraft] = useState<PersonaDraft>(EMPTY_DRAFT)
  const [creating, setCreating] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const localCommentCount = useLiveQuery(
    () => db.comments.where('authorType').equals('persona').count(),
    [],
    0,
  )

  const refresh = useCallback(async () => {
    try {
      setPersonas(await listPersonas())
      setError(null)
    } catch (err) {
      setError(
        err instanceof ApiError
          ? `服务端不可用：${err.message}`
          : '服务端不可用，无法加载角色',
      )
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (online) void refresh()
  }, [online, refresh])

  const startEdit = (p: PersonaDto) => {
    setCreating(false)
    setEditingId(p.id)
    setDraft(toDraft(p))
    setMessage(null)
    setError(null)
  }

  const saveEdit = async () => {
    if (!editingId) return
    try {
      const updated = await patchPersona(editingId, {
        name: draft.name,
        bio: draft.bio,
        position: draft.position,
        style_prompt: draft.style_prompt,
        domains: splitList(draft.domains),
        avoid_topics: splitList(draft.avoid_topics),
        reply_frequency: draft.reply_frequency,
        enabled: draft.enabled,
        memory_summary: draft.memory_summary,
      })
      setPersonas((prev) => prev.map((p) => (p.id === updated.id ? updated : p)))
      setEditingId(null)
      setMessage(`角色「${updated.name}」已保存`)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '保存失败')
    }
  }

  const saveNew = async () => {
    if (!draft.name.trim()) {
      setError('角色名不能为空')
      return
    }
    try {
      const created = await createPersona({
        name: draft.name,
        bio: draft.bio,
        position: draft.position,
        style_prompt: draft.style_prompt,
        domains: splitList(draft.domains),
        avoid_topics: splitList(draft.avoid_topics),
        reply_frequency: draft.reply_frequency,
        enabled: draft.enabled,
      })
      setPersonas((prev) => [...prev, created])
      setCreating(false)
      setDraft(EMPTY_DRAFT)
      setMessage(`角色「${created.name}」已创建`)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '创建失败')
    }
  }

  const resetMemory = async (p: PersonaDto) => {
    try {
      const updated = await resetPersonaMemory(p.id)
      setPersonas((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
      if (editingId === p.id) setDraft((d) => ({ ...d, memory_summary: '' }))
      setMessage(`「${updated.name}」的记忆已重置`)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '重置失败')
    }
  }

  if (!online) {
    return (
      <EmptyState
        title="离线中"
        description="角色管理需要连接服务端。已有评论仍可在帖子详情查看。"
        action={
          <Link to="/" className="paf-btn">
            去读 Feed
          </Link>
        }
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-bold">AI 角色</h1>
          <p className="text-sm text-[var(--paf-muted)]">
            虚构角色参与评论，全部标识 AI，不冒充真人 · 已缓存角色评论 {localCommentCount ?? 0} 条
          </p>
        </div>
        <div className="flex gap-1">
          <button
            type="button"
            className="paf-btn"
            onClick={() => {
              setLoading(true)
              void refresh()
            }}
          >
            <RefreshCw className="size-4" /> 刷新
          </button>
          <button
            type="button"
            className="paf-btn"
            onClick={() => {
              setCreating(true)
              setEditingId(null)
              setDraft(EMPTY_DRAFT)
              setError(null)
              setMessage(null)
            }}
          >
            <Plus className="size-4" /> 新建角色
          </button>
        </div>
      </div>

      {message ? <p className="text-sm text-emerald-600">{message}</p> : null}
      {error ? <p className="text-sm text-red-500">{error}</p> : null}

      {creating ? (
        <section className="paf-card space-y-2">
          <h2 className="paf-section-title">新建角色</h2>
          <label className="block space-y-1 text-sm">
            名字（必填）
            <input
              className="paf-input"
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
          </label>
          <label className="block space-y-1 text-sm">
            立场 / 观点
            <input
              className="paf-input"
              placeholder="如：批判性怀疑"
              value={draft.position}
              onChange={(e) => setDraft({ ...draft, position: e.target.value })}
            />
          </label>
          <label className="block space-y-1 text-sm">
            简介
            <input
              className="paf-input"
              value={draft.bio}
              onChange={(e) => setDraft({ ...draft, bio: e.target.value })}
            />
          </label>
          <div className="flex gap-2">
            <button type="button" className="paf-btn" onClick={() => void saveNew()}>
              <Save className="size-4" /> 创建
            </button>
            <button type="button" className="paf-btn" onClick={() => setCreating(false)}>
              取消
            </button>
          </div>
        </section>
      ) : null}

      {loading ? (
        <p className="paf-card text-sm text-[var(--paf-muted)]">加载角色…</p>
      ) : personas.length === 0 && !creating ? (
        <EmptyState
          title="还没有角色"
          description="创建 2–3 个立场不同的角色，让评论区有真正的讨论。"
        />
      ) : null}

      <ul className="space-y-3">
        {personas.map((p) => {
          const avatar = personaAvatar(p.name)
          const isEditing = editingId === p.id
          return (
            <li key={p.id} className="paf-card space-y-3">
              <div className="flex items-start gap-3">
                <span
                  className={`flex size-9 shrink-0 items-center justify-center rounded-full text-sm font-bold text-white ${avatar.colorClass}`}
                  aria-hidden
                >
                  {avatar.initials}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {p.name}
                    <span className="paf-chip border-sky-400 text-sky-600 dark:text-sky-300">
                      <Bot className="size-3" /> AI 角色
                    </span>
                    <span className={`paf-chip ${p.enabled ? '' : 'opacity-60'}`}>
                      {p.enabled ? '启用中' : '已停用'}
                    </span>
                    {p.position ? <span className="paf-chip">{p.position}</span> : null}
                  </p>
                  <p className="mt-0.5 text-sm text-[var(--paf-muted)]">{p.bio}</p>
                  {p.domains.length > 0 ? (
                    <p className="mt-1 flex flex-wrap gap-1 text-xs text-[var(--paf-muted)]">
                      {p.domains.map((d) => (
                        <span key={d} className="paf-chip">
                          {d}
                        </span>
                      ))}
                    </p>
                  ) : null}
                </div>
                <div className="flex shrink-0 gap-1">
                  <button
                    type="button"
                    className="paf-btn"
                    onClick={() => (isEditing ? setEditingId(null) : startEdit(p))}
                  >
                    {isEditing ? '收起' : '编辑'}
                  </button>
                  <button
                    type="button"
                    className="paf-btn"
                    title="清空记忆摘要"
                    onClick={() => void resetMemory(p)}
                  >
                    <RotateCcw className="size-4" /> 重置记忆
                  </button>
                </div>
              </div>

              {isEditing ? (
                <div className="space-y-2 border-t border-[var(--paf-border)] pt-3">
                  <label className="block space-y-1 text-sm">
                    名字
                    <input
                      className="paf-input"
                      value={draft.name}
                      onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    立场 / 观点
                    <input
                      className="paf-input"
                      value={draft.position}
                      onChange={(e) => setDraft({ ...draft, position: e.target.value })}
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    简介
                    <input
                      className="paf-input"
                      value={draft.bio}
                      onChange={(e) => setDraft({ ...draft, bio: e.target.value })}
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    风格提示（style_prompt）
                    <textarea
                      className="paf-input min-h-16"
                      value={draft.style_prompt}
                      onChange={(e) => setDraft({ ...draft, style_prompt: e.target.value })}
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    擅长领域（逗号分隔，如 rust-gui, tea-culture）
                    <input
                      className="paf-input"
                      value={draft.domains}
                      onChange={(e) => setDraft({ ...draft, domains: e.target.value })}
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    回避主题（逗号分隔）
                    <input
                      className="paf-input"
                      value={draft.avoid_topics}
                      onChange={(e) => setDraft({ ...draft, avoid_topics: e.target.value })}
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    回复频率（1–10）
                    <input
                      type="number"
                      min={1}
                      max={10}
                      className="paf-input"
                      value={draft.reply_frequency}
                      onChange={(e) =>
                        setDraft({ ...draft, reply_frequency: Number(e.target.value) })
                      }
                    />
                  </label>
                  <label className="block space-y-1 text-sm">
                    记忆摘要（自动生成，长度受限；可手动编辑）
                    <textarea
                      className="paf-input min-h-20"
                      value={draft.memory_summary}
                      onChange={(e) => setDraft({ ...draft, memory_summary: e.target.value })}
                    />
                  </label>
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={draft.enabled}
                      onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
                    />
                    启用（参与评论生成）
                  </label>
                  <div className="flex gap-2">
                    <button type="button" className="paf-btn" onClick={() => void saveEdit()}>
                      <Save className="size-4" /> 保存
                    </button>
                    <button type="button" className="paf-btn" onClick={() => setEditingId(null)}>
                      取消
                    </button>
                  </div>
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
