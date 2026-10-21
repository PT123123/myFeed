import type { CommentDto } from '../services/types'

export interface CommentNode extends CommentDto {
  children: CommentNode[]
}

/**
 * Build a reply tree from a flat comment list.
 * Roots keep chronological order; orphans (missing parent) fall back to roots.
 */
export function buildCommentTree(items: CommentDto[]): CommentNode[] {
  const nodes = new Map<string, CommentNode>()
  for (const item of items) {
    nodes.set(item.id, { ...item, children: [] })
  }
  const roots: CommentNode[] = []
  for (const item of items) {
    const node = nodes.get(item.id)!
    const parent = item.parent_id ? nodes.get(item.parent_id) : undefined
    if (parent && parent.id !== item.id) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }
  const byTime = (a: CommentNode, b: CommentNode) =>
    a.created_at < b.created_at ? -1 : a.created_at > b.created_at ? 1 : 0
  roots.sort(byTime)
  for (const node of nodes.values()) node.children.sort(byTime)
  return roots
}

const AVATAR_COLORS = [
  'bg-rose-500',
  'bg-sky-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-violet-500',
  'bg-teal-500',
] as const

export interface Avatar {
  initials: string
  colorClass: string
}

/** Initials + stable color block avatar (docs/07 §1: never a real person's photo). */
export function personaAvatar(personaName: string): Avatar {
  const trimmed = personaName.trim()
  const initials = trimmed.slice(0, 1) || '?'
  let hash = 0
  for (let i = 0; i < trimmed.length; i++) {
    hash = (hash * 31 + trimmed.charCodeAt(i)) >>> 0
  }
  return { initials, colorClass: AVATAR_COLORS[hash % AVATAR_COLORS.length] }
}

/** Shape a card needs from a comment; matches ``CommentRow`` without the sync fields. */
export interface DigestibleComment {
  body: string
  createdAt: string
  parentId: string | null
  authorType: 'user' | 'persona'
  personaName?: string | null
}

export interface CommentDigest {
  count: number
  /** 卡上剧透的一条：最早的那条根评论，压成一行。 */
  teaser?: { author: string; body: string }
}

export const TEASER_CHARS = 56

/**
 * Feed-card comment heat (docs/08 §4, 推特式外显): the count and one line of the
 * discussion, computed from the local comment table only — reading a card never
 * triggers a generation call.
 */
export function commentDigest(rows: DigestibleComment[]): CommentDigest {
  if (rows.length === 0) return { count: 0 }
  const byTime = [...rows].sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1))
  const seed = byTime.find((c) => c.parentId === null) ?? byTime[0]
  const author =
    seed.authorType === 'user' ? '你' : (seed.personaName?.trim() || 'AI 角色')
  const flat = seed.body.replace(/\s+/g, ' ').trim()
  const body =
    flat.length > TEASER_CHARS ? `${flat.slice(0, TEASER_CHARS).trimEnd()}…` : flat
  return { count: rows.length, teaser: body ? { author, body } : undefined }
}
