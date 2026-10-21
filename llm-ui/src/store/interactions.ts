import { db, getDeviceId, nextClientSeq } from '../db'
import type { InteractionKind, PostRow } from '../db/types'
import type { FeedbackReason } from '../lib/curation'

export type LocalAction = 'view' | 'like' | 'dislike' | 'unlike' | 'save' | 'unsave' | 'hide' | 'unhide'

function uuid(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `op_${Date.now()}_${Math.random().toString(36).slice(2)}`
}

function applyAction(post: PostRow, action: LocalAction, reason?: FeedbackReason): PostRow {
  const now = new Date().toISOString()
  switch (action) {
    case 'view':
      return { ...post, lastReadAt: now, readProgress: 1 }
    case 'like':
      // A structured reason is feedback about the content, not a toggle: it
      // always lands on the matching thumbs state so repeated reason chips
      // stay idempotent (the server scores the transition, not the event).
      if (reason) return { ...post, likeState: 1, lastReadAt: post.lastReadAt ?? now }
      return { ...post, likeState: post.likeState === 1 ? 0 : 1, lastReadAt: post.lastReadAt ?? now }
    case 'dislike':
      // A structured reason is feedback about the content, not a toggle: it
      // always lands on "disliked" so repeated reason chips stay idempotent.
      if (reason) return { ...post, likeState: -1 }
      return { ...post, likeState: post.likeState === -1 ? 0 : -1 }
    case 'unlike':
      return { ...post, likeState: 0 }
    case 'save':
      return { ...post, saved: true }
    case 'unsave':
      return { ...post, saved: false }
    case 'hide':
      return { ...post, hidden: true }
    case 'unhide':
      return { ...post, hidden: false }
  }
}

const KIND_MAP: Record<LocalAction, InteractionKind> = {
  view: 'view',
  like: 'like',
  dislike: 'dislike',
  unlike: 'like',
  save: 'save',
  unsave: 'unsave',
  hide: 'hide',
  unhide: 'hide',
}

/**
 * Apply a user action locally in one Dexie transaction:
 * post flags + interaction log + outbox row (queued for B06 sync).
 * Reading/interacting never triggers an LLM call or network request.
 *
 * A structured ``reason`` (B09) rides in the op's ``payload.reason`` — the
 * server scores it from the state transition, so repeated ops stay idempotent.
 */
export async function applyLocalAction(
  postId: string,
  action: LocalAction,
  payload: Record<string, unknown> | null = null,
  reason?: FeedbackReason,
): Promise<void> {
  const deviceId = await getDeviceId()
  const effectivePayload: Record<string, unknown> | null = reason ? { reason } : payload
  await db.transaction('rw', db.posts, db.interactions, db.outbox, db.sync_meta, async () => {
    const post = await db.posts.get(postId)
    if (!post) return
    const updated = applyAction(post, action, reason)
    const changed =
      updated.likeState !== post.likeState ||
      updated.saved !== post.saved ||
      updated.hidden !== post.hidden ||
      updated.lastReadAt !== post.lastReadAt
    // No state change and no structured feedback → nothing worth recording.
    if (!changed && !reason) return
    const clientSeq = await nextClientSeq(deviceId)
    const occurredAt = new Date().toISOString()
    const opId = uuid()
    if (changed) await db.posts.put(updated)
    await db.interactions.add({
      id: uuid(),
      postId,
      kind: KIND_MAP[action],
      payload: effectivePayload,
      deviceId,
      clientSeq,
      occurredAt,
    })
    await db.outbox.add({
      id: uuid(),
      opId,
      deviceId,
      clientSeq,
      objectType: 'post',
      objectId: postId,
      postId,
      action: KIND_MAP[action],
      payload: effectivePayload,
      baseRevision: post.revision,
      occurredAt,
      status: 'pending',
      createdAt: occurredAt,
      error: null,
    })
  })
}

/**
 * Queue an interest edit (B06). The op carries base_revision so the server
 * can answer REVISION_CONFLICT when another device edited first.
 */
export async function queueInterestOp(
  topicId: string,
  explicitWeight: number,
  blocked: boolean,
): Promise<void> {
  const deviceId = await getDeviceId()
  await db.transaction('rw', db.interests, db.outbox, db.sync_meta, async () => {
    const row = await db.interests.get(topicId)
    const clientSeq = await nextClientSeq(deviceId)
    const occurredAt = new Date().toISOString()
    await db.outbox.add({
      id: uuid(),
      opId: uuid(),
      deviceId,
      clientSeq,
      objectType: 'interest',
      objectId: topicId,
      postId: '',
      action: 'set',
      payload: { explicit_weight: explicitWeight, blocked },
      baseRevision: row?.revision ?? 0,
      occurredAt,
      status: 'pending',
      createdAt: occurredAt,
      error: null,
    })
  })
}

/**
 * Create a reader comment locally and queue it for upload in one transaction
 * (optimistic UI, docs/05 §2). Returns the new comment id.
 */
export async function queueReaderComment(
  postId: string,
  body: string,
  parentId: string | null = null,
): Promise<string> {
  const deviceId = await getDeviceId()
  const commentId = uuid()
  await db.transaction('rw', db.comments, db.outbox, db.sync_meta, async () => {
    await db.comments.put({
      id: commentId,
      postId,
      parentId,
      personaId: null,
      authorType: 'user',
      body,
      createdAt: new Date().toISOString(),
      revision: 1,
    })
    const clientSeq = await nextClientSeq(deviceId)
    const occurredAt = new Date().toISOString()
    await db.outbox.add({
      id: uuid(),
      opId: uuid(),
      deviceId,
      clientSeq,
      objectType: 'comment',
      objectId: postId,
      postId,
      action: 'comment',
      payload: { id: commentId, parent_id: parentId, body },
      baseRevision: 0,
      occurredAt,
      status: 'pending',
      createdAt: occurredAt,
      error: null,
    })
  })
  return commentId
}

export async function pendingOutboxCount(): Promise<number> {
  return db.outbox.where('status').equals('pending').count()
}
