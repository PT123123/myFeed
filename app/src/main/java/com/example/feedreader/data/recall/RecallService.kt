package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import com.example.feedreader.data.FeedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 一次多路召回的结果。
 *
 * [perChannel] 保留每路实际拿回几条，是为了让「为什么首页就这几篇」这个问题能查：
 * 是某路挂了、还是关键词在英文语料上天然空手而归，看数字就分得清。
 */
data class RecallOutcome(
    val articles: List<Article>,
    val failures: List<FeedFailure>,
    val perChannel: Map<String, Int> = emptyMap(),
    /** 实际发出去的关键词数量。 */
    val queriesUsed: Int = 0,
    /** 因为 [RecallService.MAX_QUERIES] 被砍掉的兴趣词数量。 */
    val queriesSkipped: Int = 0,
)

/**
 * 多路并行召回。
 *
 * 三件事在这里收口，都是刻意做的：
 *
 * 1. **每路独立失败。** 单路超时 / 解析错 / 限流都只记一条失败，不影响其他路。
 *    这是沿用订阅源那套 `FetchOutcome(articles, failures)` 的思路。
 * 2. **去重。** 同一篇文章很可能既在订阅里、又被微博/CSDN 搜到。
 *    **按链接去重而不是按 id** —— 各通道的 id 前缀不同，按 id 去重等于没去重。
 * 3. **请求数封顶。** 请求数 = 兴趣词 × 通道数，会线性爆。见 [MAX_QUERIES]。
 */
class RecallService(
    private val channels: List<RecallChannel>,
    private val http: HttpGet,
    /** 单路超时。某一路挂住不该让整个刷新一直转圈。 */
    private val perChannelTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /**
     * @param queries 需要召回的兴趣词（已含词表展开）。
     * @param subscribed 已订阅源抓到的文章，会原样混进候选集（也参与去重）。
     */
    suspend fun recall(
        queries: List<RecallQuery>,
        subscribed: List<Article> = emptyList(),
    ): RecallOutcome = coroutineScope {
        val usable = queries.filter { it.keyword.isNotBlank() }
        val active = usable.take(MAX_QUERIES)

        val tasks = active.flatMap { query ->
            channels.map { channel -> async { runOne(channel, query) } }
        }

        val collected = ArrayList<Article>()
        val failuresByChannel = LinkedHashMap<String, MutableList<String>>()
        val counts = LinkedHashMap<String, Int>()

        for (task in tasks) {
            val run = task.await()
            val articles = run.articles
            if (articles == null) {
                // 同一个通道在多个关键词上失败时聚合成一条，否则失败横幅会刷满一屏
                failuresByChannel.getOrPut(run.channel.name) { ArrayList() } += run.error.orEmpty()
                continue
            }
            collected += articles
            counts[run.channel.id] = (counts[run.channel.id] ?: 0) + articles.size
        }

        if (subscribed.isNotEmpty()) counts[RecallChannels.ID_SUBSCRIPTION] = subscribed.size

        RecallOutcome(
            articles = dedupe(subscribed + collected),
            failures = failuresByChannel.map { (name, messages) -> FeedFailure(name, summarize(messages)) },
            perChannel = counts,
            queriesUsed = active.size,
            queriesSkipped = usable.size - active.size,
        )
    }

    // ------------------------------------------------------------------ 内部

    private class Run(
        val channel: RecallChannel,
        /** null 表示这一路失败，原因在 [error]。 */
        val articles: List<Article>?,
        val error: String?,
    )

    private suspend fun runOne(channel: RecallChannel, query: RecallQuery): Run = try {
        // withTimeoutOrNull 返回 null 说明超时；okhttp 自己的 callTimeout 也会抛，两条都兜
        val articles = withTimeoutOrNull(perChannelTimeoutMs) { channel.recall(http, query) }
            ?: throw IOException("超时")
        Run(channel, articles, null)
    } catch (cancel: CancellationException) {
        // 协程取消不是「这一路失败」，必须原样抛出去，否则整个刷新都停不下来
        throw cancel
    } catch (error: Exception) {
        Run(channel, null, describe(error))
    }

    private fun describe(error: Throwable): String = when (error) {
        // 子类必须排在 IOException 前面，不然永远显示成泛泛的「网络错误」
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException, is InterruptedIOException -> "连接超时"
        is IOException -> error.message ?: "网络错误"
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun summarize(messages: List<String>): String {
        val unique = messages.distinct()
        val detail = if (unique.size == 1) unique.first() else unique.take(2).joinToString("；")
        return if (messages.size == 1) detail else "$detail（${messages.size} 个关键词）"
    }

    /**
     * 按链接去重，保序保留第一次出现的那条。
     *
     * 去重键取 `link`：同一篇文章在不同通道里的 id 前缀不同（`csdn:` / `v2ex:`），
     * 按 id 去重等于没去重。链接为空的退化成 id —— 那种情况本来就只可能来自同一路。
     */
    private fun dedupe(articles: List<Article>): List<Article> {
        val seen = HashSet<String>(articles.size * 2)
        return articles.filter { seen.add(identityOf(it)) }
    }

    private fun identityOf(article: Article): String =
        article.link.trim().trimEnd('/').ifEmpty { article.id }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 12_000L

        /**
         * 一次刷新最多用多少个兴趣词。
         *
         * 请求数是「兴趣词数 × 通道数」，线性增长：8 个词 × 6 路 = 48 个请求。
         * 继续加大召回面不如把权重调准 —— 召回的活儿是**保下限**，
         * 排序才是决定首页长什么样的那一步。
         */
        const val MAX_QUERIES = 8
    }
}
