import { useState } from 'react'
import {
  Bookmark,
  BookmarkCheck,
  EyeOff,
  MessageSquare,
  ThumbsDown,
  ThumbsUp,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import type { PostRow, TopicRow } from '../db/types'
import type { CommentDigest } from '../lib/comments'
import { personaAvatar } from '../lib/comments'
import {
  DISLIKE_REASONS,
  LIKE_REASONS,
  reasonAction,
} from '../lib/curation'
import {
  FAMILY_STYLE,
  WEIGHT_STYLE,
  isExploration,
  layoutFamily,
  weightClass,
} from '../lib/cardStyle'
import { cardFullText, cardPreview } from '../lib/preview'
import { relativeTime } from '../lib/time'
import { applyLocalAction } from '../store/interactions'
import { CurationBadges } from './CurationBadges'
import { KindBadge } from './KindBadge'
import { OverflowMenu } from './OverflowMenu'

function ActionButton({
  active,
  label,
  onClick,
  children,
}: {
  active?: boolean
  label: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={`paf-btn ${active ? 'paf-btn-active' : ''}`}
    >
      {children}
    </button>
  )
}

export function PostCard({
  post,
  topics,
  authors,
  hideActions = false,
  reason,
  comments,
}: {
  post: PostRow
  topics: Map<string, TopicRow>
  /** persona id -> display name; absent means the card falls back to the topic name. */
  authors?: Map<string, string>
  hideActions?: boolean
  /** computed "why recommended" one-liner (B04 explainable ranking) */
  reason?: string
  /** 本地评论计数与一条剧透（docs/08 §4：卡上不发起任何生成调用） */
  comments?: CommentDigest
}) {
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const topicName = topics.get(post.topicId)?.name ?? post.topicId
  const authorName = post.personaId ? authors?.get(post.personaId) : undefined
  // 推特式卡头：署名行必须有主语，查得到人格名就署人，查不到就署主题，
  // 内部 id（p_engineer_yan 这种）任何时候都不上界面。
  const accountName = authorName ?? topicName
  const avatar = personaAvatar(accountName)
  const unread = !post.lastReadAt
  const family = layoutFamily(post)
  const weight = weightClass(post.priority)
  const preview = cardPreview(post)
  const shown = expanded ? cardFullText(post) : preview.text
  // The model's own reason is the first line; fall back to the computed one.
  const modelReason = post.recommendReason?.trim() ? post.recommendReason.trim() : ''
  const shownReason = modelReason || reason || ''

  return (
    <article
      className={`paf-card relative overflow-hidden transition-opacity ${FAMILY_STYLE[family].card} ${WEIGHT_STYLE[weight].card} ${
        unread ? '' : 'opacity-70'
      }`}
      data-testid="post-card"
      data-family={family}
      data-weight={weight}
      data-expanded={expanded}
    >
      <span
        aria-hidden
        className={`absolute inset-y-0 left-0 w-1 ${FAMILY_STYLE[family].rail}`}
        data-rail={family}
      />

      <header className="flex items-center gap-2">
        <span
          className={`flex size-8 shrink-0 items-center justify-center rounded-full text-[13px] font-bold text-white ${avatar.colorClass}`}
          aria-hidden
        >
          {avatar.initials}
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-baseline gap-x-1.5 text-[13px] leading-5">
            <span
              className="truncate font-semibold"
              data-author={authorName ? post.personaId : undefined}
              data-account={authorName ? 'persona' : 'topic'}
            >
              {accountName}
            </span>
            <span className="text-[var(--paf-muted)]" title={post.createdAt}>
              {relativeTime(post.createdAt)}
            </span>
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {unread ? (
            <span className="paf-chip-tight border-[var(--paf-accent)] text-[var(--paf-accent)]">
              未读
            </span>
          ) : null}
          {post.saved ? (
            <span className="paf-chip-tight">
              <BookmarkCheck className="mr-0.5 inline-block size-3 align-[-2px]" />
              已收藏
            </span>
          ) : null}
          {!hideActions ? (
            <OverflowMenu
              items={[
                {
                  label: '隐藏',
                  icon: <EyeOff className="size-4" />,
                  active: post.hidden,
                  onSelect: () => void applyLocalAction(post.id, 'hide'),
                },
              ]}
            />
          ) : null}
        </div>
      </header>

      <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-[var(--paf-muted)]">
        <KindBadge kind={post.kind} demo={post.demo} />
        {/* 没有署名时卡头已经写了主题名，不再重复一枚 chip。 */}
        {authorName ? <span className="paf-chip-tight">{topicName}</span> : null}
        {isExploration(post) ? (
          <span className="paf-chip-tight border-[var(--paf-accent)]/50 text-[var(--paf-accent)]">
            探索
          </span>
        ) : null}
      </div>

      <CurationBadges post={post} />

      <h2 className={`mt-2 text-[15px] leading-6 ${WEIGHT_STYLE[weight].title}`}>
        <Link to={`/post/${post.id}`} className="hover:underline">
          {post.title}
        </Link>
      </h2>

      <p className="paf-prose mt-1.5 text-[var(--paf-muted)]">
        {shown}
        {preview.truncated && !expanded ? <span aria-hidden> …</span> : null}
      </p>

      {preview.truncated ? (
        <button
          type="button"
          data-testid="card-expand"
          aria-expanded={expanded}
          onClick={() => setExpanded((v) => !v)}
          className="mt-1 min-h-11 text-sm text-[var(--paf-accent)] hover:underline"
        >
          {expanded ? '收起' : '展开全文'}
        </button>
      ) : null}

      {shownReason ? (
        <p className="mt-1 text-xs text-[var(--paf-muted)]">
          {modelReason ? '推荐理由：' : '为什么推荐：'}
          {shownReason}
        </p>
      ) : null}

      {post.kind === 'sourced' && post.sourceUrl ? (
        <p className="mt-2 text-xs text-[var(--paf-muted)]">
          来源：
          <a
            href={post.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="text-[var(--paf-accent)] hover:underline"
          >
            {post.sourceTitle ?? post.sourceUrl}
          </a>
        </p>
      ) : null}

      {comments && comments.count > 0 ? (
        <Link
          to={`/post/${post.id}`}
          className="mt-2 block rounded-lg bg-[var(--paf-bg)] p-2 text-xs hover:underline"
          data-comment-count={comments.count}
        >
          <span className="text-[var(--paf-muted)]">
            {comments.count} 条评论 · {comments.teaser?.author ?? '读者'}：
          </span>
          <span className="line-clamp-2">{comments.teaser?.body ?? '点进去看讨论'}</span>
        </Link>
      ) : null}

      {!hideActions ? (
        <div className="mt-2 flex flex-wrap items-center gap-1 border-t border-[var(--paf-border)] pt-1">
          <ActionButton
            label="喜欢"
            active={post.likeState === 1}
            onClick={() => void applyLocalAction(post.id, 'like')}
          >
            <ThumbsUp className="size-4" />
            <span className="text-xs">喜欢</span>
          </ActionButton>
          <ActionButton
            label="不喜欢"
            active={post.likeState === -1}
            onClick={() => {
              // The verdict applies immediately; the panel only refines why.
              void applyLocalAction(post.id, 'dislike')
              setFeedbackOpen((v) => !v)
            }}
          >
            <ThumbsDown className="size-4" />
            <span className="text-xs">不喜欢</span>
          </ActionButton>
          <Link to={`/post/${post.id}`} className="paf-btn" aria-label="评论">
            <MessageSquare className="size-4" />
            <span className="text-xs tabular-nums">{comments?.count ?? 0}</span>
          </Link>
          <ActionButton
            label={post.saved ? '取消收藏' : '收藏'}
            active={post.saved}
            onClick={() => void applyLocalAction(post.id, post.saved ? 'unsave' : 'save')}
          >
            {post.saved ? (
              <BookmarkCheck className="size-4" />
            ) : (
              <Bookmark className="size-4" />
            )}
            <span className="text-xs">{post.saved ? '已收藏' : '收藏'}</span>
          </ActionButton>

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
      ) : null}
    </article>
  )
}
