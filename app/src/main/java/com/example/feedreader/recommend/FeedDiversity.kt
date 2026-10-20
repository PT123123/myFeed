package com.example.feedreader.recommend

/** Local, bounded reranking. A small source/interest penalty breaks floods
 * among similarly relevant candidates; it cannot replace relevance outright.
 * Raw scores stay intact for diagnostics. No candidates or read state are lost.
 */
object FeedDiversity {
    fun arrange(scored: List<ScoredArticle>, limit: Int = Int.MAX_VALUE): List<ScoredArticle> {
        val (matched, unmatched) = scored.partition { it.matchedInterests.isNotEmpty() }
        val remaining = matched.toMutableList()
        val result = ArrayList<ScoredArticle>()
        while (remaining.isNotEmpty() && result.size < limit) {
            val window = result.takeLast(8)
            val best = remaining.indices.maxByOrNull { index ->
                val item = remaining[index]
                val sourceCount = window.count { it.article.sourceId == item.article.sourceId }
                val interestCount = if (item.topInterest.isEmpty()) 0 else
                    window.count { it.topInterest == item.topInterest }
                item.score - (0.035f * sourceCount + 0.025f * interestCount).coerceAtMost(0.18f)
            } ?: break
            result += remaining.removeAt(best)
        }
        // A recency-only candidate must not displace a genuine match just
        // because the matching source has already appeared in the window.
        return result + unmatched.take((limit - result.size).coerceAtLeast(0))
    }
}
