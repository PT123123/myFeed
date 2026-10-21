import { useMemo } from 'react'

import type { CommentDigest } from '../lib/comments'
import { cardHeightEstimate, splitWaterfall } from '../lib/layout'
import type { PostRow, TopicRow } from '../db/types'
import { PostCard } from './PostCard'
import { useColumnCount } from '../hooks/useColumnCount'

/**
 * 小红书式双列瀑布流（docs/08 §1）。
 *
 * 卡片按估算高度进当前最矮的一列，所以一条长文不会把同列后面的卡推成一片空白；
 * 列内顺序仍与 Feed 排序一致。列数由容器实测宽度决定，窄屏自然回落到单列。
 */
export function PostWaterfall({
  posts,
  topics,
  authors,
  reasonOf,
  commentsOf,
}: {
  posts: PostRow[]
  topics: Map<string, TopicRow>
  authors?: Map<string, string>
  reasonOf?: (post: PostRow) => string | undefined
  commentsOf?: (post: PostRow) => CommentDigest | undefined
}) {
  const { ref, columns } = useColumnCount()

  const buckets = useMemo(
    () => splitWaterfall(posts, columns, cardHeightEstimate),
    [posts, columns],
  )

  return (
    <div
      ref={ref}
      className="grid gap-3"
      style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      data-testid="post-waterfall"
      data-columns={columns}
    >
      {buckets.map((bucket, index) => (
        <div key={index} className="flex min-w-0 flex-col gap-3">
          {bucket.map((post) => (
            <PostCard
              key={post.id}
              post={post}
              topics={topics}
              authors={authors}
              reason={reasonOf?.(post)}
              comments={commentsOf?.(post)}
            />
          ))}
        </div>
      ))}
    </div>
  )
}
