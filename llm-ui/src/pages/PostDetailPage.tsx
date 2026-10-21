import { useLiveQuery } from 'dexie-react-hooks'
import {
  ArrowLeft,
  Bookmark,
  BookmarkCheck,
  ExternalLink,
  EyeOff,
  HelpCircle,
  MessageSquare,
  Send,
  Sparkles,
  ThumbsDown,
  ThumbsUp,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { EmptyState } from '../components/EmptyState'
import { CurationBadges } from '../components/CurationBadges'
import { KindBadge } from '../components/KindBadge'
import { SkeletonList } from '../components/Skeleton'
import { db } from '../db'
import { buildCommentTree, personaAvatar, type CommentNode } from '../lib/comments'
import { DISLIKE_REASONS, LIKE_REASONS, reasonAction } from '../lib/curation'
import { explainRecommendation } from '../lib/explain'
import { buildRecentCounts } from '../lib/score'
import { fullTime, relativeTime } from '../lib/time'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { usePersonaNames } from '../hooks/usePersonaNames'
import {
  ApiError,
  generatePostComments,
  getPostComments,
  syncCommentsToDexie,
} from '../services/api'
import { applyLocalAction, queueReaderComment } from '../store/interactions'

function CommentItem({ node, depth }: { node: CommentNode; depth: number }) {
  const name = node.author_type === 'user' ? '你' : (node.persona_name ?? 'AI 角色')
  const avatar = personaAvatar(name)
  return (
    <li className="space-y-2" style={{ marginLeft: depth > 0 ? 16 : 0 }}>
      <div className="flex items-start gap-2">
        <span
          className={`mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${avatar.colorClass}`}
          aria-hidden
        >
          {avatar.initials}
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-1.5 text-sm">
            <span className="font-medium">{name}</span>
            {node.is_ai ? (
              <span className="paf-chip border-sky-400 text-sky-600 dark:text-sky-300">AI 角色</span>
            ) : null}
            {node.author_type === 'user' ? (
              <span className="paf-chip border-amber-400 text-amber-600">本地 · 待同步</span>
            ) : null}
            <span className="text-xs text-[var(--paf-muted)]" title={fullTime(node.created_at)}>
              {relativeTime(node.created_at)}
            </span>
          </p>
          <p className="mt-0.5 text-sm whitespace-pre-wrap">{node.body}</p>
        </div>
      </div>
      {node.children.length > 0 ? (
        <ul className="space-y-2">
          {node.children.map((child) => (
            <CommentItem key={child.id} node={child} depth={depth + 1} />
          ))}
        </ul>
      ) : null}
    </li>
  )
}

export function PostDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const online = useOnlineStatus()
  const authors = usePersonaNames()

  const [genBusy, setGenBusy] = useState(false)
  const [genMessage, setGenMessage] = useState<string | null>(null)
  const [genError, setGenError] = useState<string | null>(null)
  const [draftComment, setDraftComment] = useState('')
  const [feedbackOpen, setFeedbackOpen] = useState(false)

  const data = useLiveQuery(
    async () => {
      const post = await db.posts.get(id)
      const [topic, comments, interests, allPosts, topics] = await Promise.all([
        post ? db.topics.get(post.topicId) : undefined,
        db.comments.where('postId').equals(id).toArray(),
        db.interests.toArray(),
        db.posts.toArray(),
        db.topics.toArray(),
      ])
      return { post, topic, comments, interests, allPosts, topics }
    },
    [id],
    null,
  )

  useEffect(() => {
    if (data?.post) void applyLocalAction(id, 'view')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, data?.post?.id])

  // Best-effort: pull already-generated comments from the server (read-only GET,
  // never an LLM call).
  const hasPersonaComments = data?.comments.some((c) => c.authorType === 'persona') ?? false
  useEffect(() => {
    if (!online || !data?.post || hasPersonaComments) return
    let cancelled = false
    void (async () => {
      try {
        const dto = await getPostComments(id)
        if (!cancelled && dto.items.length > 0) await syncCommentsToDexie(id, dto.items)
      } catch {
        /* offline or server down: stay local */
      }
    })()
    return () => {
      cancelled = true
    }
  }, [online, data?.post, id, hasPersonaComments])

  const explainCtx = useMemo(() => {
    if (!data) return null
    const interests = new Map(data.interests.map((i) => [i.topicId, i]))
    const recentFeedback = new Map<string, 1 | -1>()
    for (const p of data.allPosts) {
      if (p.likeState !== 0) recentFeedback.set(p.topicId, p.likeState as 1 | -1)
    }
    const recency = buildRecentCounts(data.allPosts, data.topics)
    return {
      interests,
      recentTopicCounts: recency.recentTopicCounts,
      recentClusterCounts: recency.recentClusterCounts,
      topicClusters: recency.topicClusters,
      recentFeedback,
    }
  }, [data])

  const tree = useMemo(() => {
    const rows = data?.comments ?? []
    const asDtos = rows.map((c) => ({
      id: c.id,
      post_id: c.postId,
      parent_id: c.parentId,
      persona_id: c.personaId,
      persona_name: c.personaName ?? null,
      author_type: c.authorType,
      is_ai: c.authorType === 'persona',
      body: c.body,
      created_at: c.createdAt,
    }))
    return buildCommentTree([...asDtos].sort((a, b) => (a.created_at < b.created_at ? -1 : 1)))
  }, [data])

  const onGenerateComments = async () => {
    setGenBusy(true)
    setGenMessage(null)
    setGenError(null)
    try {
      const result = await generatePostComments(id)
      await syncCommentsToDexie(id, result.comments)
      if (result.status === 'generated') {
        setGenMessage(`已生成 ${result.comments.length} 条 AI 评论（仅计费 1 次）`)
      } else if (result.status === 'already') {
        setGenMessage('已有评论，未重复扣费')
      } else {
        setGenMessage('正在生成中，请稍候再看')
      }
    } catch (err) {
      setGenError(
        err instanceof ApiError ? `${err.code}: ${err.message}` : '生成失败（服务端不可用）',
      )
    } finally {
      setGenBusy(false)
    }
  }

  const onAddLocalComment = async () => {
    const body = draftComment.trim()
    if (!body) return
    // Local-first: shows immediately; B06 sync uploads it in the background.
    await queueReaderComment(id, body)
    setDraftComment('')
  }

  if (data === null) return <SkeletonList count={2} />
  const post = data.post
  if (!post || post.status === 'deleted') {
    return (
      <EmptyState
        title="帖子不存在或已删除"
        description="它可能在同步时被移除，返回 Feed 继续阅读。"
        action={
          <Link to="/" className="paf-btn">
            返回 Feed
          </Link>
        }
      />
    )
  }

  const computedReasons =
    explainCtx && post ? explainRecommendation(post, explainCtx) : []
  // B09: the model's own reason is the first line; fall back to the computed
  // explanation only when it is blank.
  const modelReason = post.recommendReason?.trim() ?? ''
  const reasonLines = modelReason ? [modelReason, ...computedReasons] : computedReasons

  return (
    <div className="split:grid split:grid-cols-[minmax(0,1fr)_340px] split:items-start split:gap-5">
      <article className="space-y-4">
        <button type="button" className="paf-btn -ml-2" onClick={() => navigate(-1)}>
          <ArrowLeft className="size-4" /> 返回
        </button>

        <header className="space-y-2">
          <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--paf-muted)]">
            <KindBadge kind={post.kind} demo={post.demo} />
            <span className="paf-chip">{data.topic?.name ?? post.topicId}</span>
            {post.personaId && authors.get(post.personaId) ? (
              <span className="paf-chip" data-author={post.personaId}>
                署名 {authors.get(post.personaId)}
              </span>
            ) : null}
            <span title={fullTime(post.createdAt)}>{relativeTime(post.createdAt)}</span>
          </div>
          <CurationBadges post={post} alwaysShowPriority />
          <h1 className="text-2xl leading-9 font-bold">{post.title}</h1>
          {post.seriesId ? (
            <p className="paf-chip border-teal-400 text-teal-600 dark:text-teal-300">
              连载 · {post.seriesId}
            </p>
          ) : null}
          {post.kind === 'fiction' || post.kind === 'serial' ? (
            <p className="paf-chip border-purple-400 text-purple-600 dark:text-purple-300">
              AI 生成的虚构内容，非真实事件
            </p>
          ) : null}
          {post.kind === 'synthetic_explainer' ? (
            <p className="paf-chip border-sky-400 text-sky-600 dark:text-sky-300">
              AI 合成知识内容，不构成专业意见
            </p>
          ) : null}
          {reasonLines.length > 0 ? (
            <details className="paf-card text-sm">
              <summary className="flex cursor-pointer items-center gap-1.5 font-medium">
                <HelpCircle className="size-4" /> 为什么推荐这条
              </summary>
              <ul className="mt-2 list-inside list-disc space-y-1 text-[var(--paf-muted)]">
                {reasonLines.map((r) => (
                  <li key={r}>{r}</li>
                ))}
              </ul>
            </details>
          ) : null}
        </header>

        {post.kind === 'sourced' ? (
          <div className="paf-card space-y-1 text-sm">
            <p className="paf-section-title">事实来源</p>
            {post.sourceUrl ? (
              <p>
                <a
                  href={post.sourceUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-[var(--paf-accent)] hover:underline"
                >
                  {post.sourceTitle ?? post.sourceUrl}
                  <ExternalLink className="size-3.5" />
                </a>
              </p>
            ) : (
              <p className="text-[var(--paf-muted)]">缺失来源链接（不应标记为 sourced）</p>
            )}
            <p className="text-xs text-[var(--paf-muted)]">
              {post.publishedAt
                ? `发布时间：${fullTime(post.publishedAt)}`
                : '来源时间未知'}{' '}
              · 抓取时间：{fullTime(post.fetchedAt)}
            </p>
          </div>
        ) : null}

        <div className="paf-card">
          <p className="paf-prose">{post.body}</p>
        </div>

        <div className="flex flex-wrap gap-1">
          <button
            type="button"
            className={`paf-btn ${post.likeState === 1 ? 'paf-btn-active' : ''}`}
            onClick={() => void applyLocalAction(post.id, 'like')}
          >
            <ThumbsUp className="size-4" /> 喜欢
          </button>
          <button
            type="button"
            className={`paf-btn ${post.likeState === -1 ? 'paf-btn-active' : ''}`}
            onClick={() => void applyLocalAction(post.id, 'dislike')}
          >
            <ThumbsDown className="size-4" /> 不喜欢
          </button>
          <button
            type="button"
            className="paf-btn"
            aria-expanded={feedbackOpen}
            onClick={() => setFeedbackOpen((v) => !v)}
          >
            <HelpCircle className="size-4" /> 说明原因
          </button>
          <button
            type="button"
            className={`paf-btn ${post.saved ? 'paf-btn-active' : ''}`}
            onClick={() => void applyLocalAction(post.id, post.saved ? 'unsave' : 'save')}
          >
            {post.saved ? <BookmarkCheck className="size-4" /> : <Bookmark className="size-4" />}
            {post.saved ? '已收藏' : '收藏'}
          </button>
          <button
            type="button"
            className="paf-btn"
            onClick={() => void applyLocalAction(post.id, 'hide')}
          >
            <EyeOff className="size-4" /> 隐藏
          </button>
          {feedbackOpen ? (
            <div className="mt-1 flex w-full flex-wrap items-center gap-1.5 rounded-md bg-[var(--paf-bg)] p-2">
              <span className="text-xs text-[var(--paf-muted)]">这条内容的反馈？</span>
              {[...DISLIKE_REASONS, ...LIKE_REASONS].map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  data-reason={value}
                  className="paf-chip border-[var(--paf-border)] text-xs"
                  onClick={() => {
                    void applyLocalAction(post.id, reasonAction(value), null, value)
                    setFeedbackOpen(false)
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
          ) : null}
        </div>
      </article>

      <section className="paf-card mt-4 space-y-3 split:sticky split:top-4 split:mt-0">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="paf-section-title flex items-center gap-1.5">
            <MessageSquare className="size-4" /> 评论
          </h2>
          <button
            type="button"
            className="paf-btn"
            disabled={genBusy || !online}
            onClick={() => void onGenerateComments()}
            title={online ? '由 AI 角色生成，仅计费一次' : '需要连接服务端'}
          >
            <Sparkles className="size-4" />
            {genBusy ? '生成中…' : '生成 AI 评论'}
          </button>
        </div>

        {genMessage ? <p className="text-sm text-emerald-600">{genMessage}</p> : null}
        {genError ? <p className="text-sm text-red-500">{genError}</p> : null}

        {tree.length > 0 ? (
          <ul className="space-y-3">
            {tree.map((node) => (
              <CommentItem key={node.id} node={node} depth={0} />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-[var(--paf-muted)]">
            还没有评论。点击「生成 AI 评论」由虚构角色参与讨论（显著标注 AI，不会冒充真人）；
            重复点击不会重复扣费。
          </p>
        )}

        <div className="flex items-end gap-2 border-t border-[var(--paf-border)] pt-3">
          <textarea
            className="paf-input min-h-16 flex-1 resize-y"
            placeholder="写一条你的看法（保存在本地，B06 后同步）"
            value={draftComment}
            onChange={(e) => setDraftComment(e.target.value)}
            aria-label="你的评论"
          />
          <button
            type="button"
            className="paf-btn"
            disabled={!draftComment.trim()}
            onClick={() => void onAddLocalComment()}
          >
            <Send className="size-4" /> 发送
          </button>
        </div>
      </section>
    </div>
  )
}
