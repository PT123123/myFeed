package com.example.feedreader.recommend

import com.example.feedreader.data.Article
import com.example.feedreader.data.FeedFailure
import com.example.feedreader.data.Interest
import com.example.feedreader.data.SynonymDict
import com.example.feedreader.data.recall.RecallQuery
import com.example.feedreader.data.recall.RecallService
import io.github.pt123123.semantic.EncodePool
import io.github.pt123123.semantic.NoSemanticEncoder
import io.github.pt123123.semantic.QuantizedVector
import io.github.pt123123.semantic.TextEncoder
import io.github.pt123123.semantic.VectorStore
import io.github.pt123123.semantic.Vectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次推荐的产出统计。
 *
 * 全部保留下来是为了让「首页为什么长这样」可查：条数为 0 是召回问题，
 * [newlyEncoded] 很大是冷启动代价，[semantic] 为 false 说明模型没跑起来。
 */
data class RecommendStats(
    /** 订阅源提供的候选条数。 */
    val pooled: Int = 0,
    /** 各召回通道提供的候选条数（去重前）。 */
    val recalled: Int = 0,
    /** 去重后的候选总数。 */
    val candidates: Int = 0,
    /** 本次新编码的条数。首次运行约等于候选总数，之后接近 0。 */
    val newlyEncoded: Int = 0,
    /**
     * 因为**编码预算**而推迟到下一轮的条数。
     *
     * 这个数长期不减说明预算定小了：那些条目会一直只有词法分、拿不到语义分，
     * 在兴趣流里天然吃亏。稳态下它应该趋近 0（新内容被编码后落盘，下轮直接命中缓存）。
     */
    val deferred: Int = 0,
    /** 语义是否真的参与了排序。false = 退化成纯词法 + 时间。 */
    val semantic: Boolean = false,
    val queriesUsed: Int = 0,
    val queriesSkipped: Int = 0,
    val perChannel: Map<String, Int> = emptyMap(),
)

data class RecommendOutcome(
    val articles: List<ScoredArticle> = emptyList(),
    val failures: List<FeedFailure> = emptyList(),
    val stats: RecommendStats = RecommendStats(),
)

/**
 * 推荐链路的总装：**兴趣词 → 多路召回 → 编码（带磁盘缓存）→ 排序**。
 *
 * 它不负责抓订阅源 —— 那件事连带着磁盘缓存、失败横幅、下拉刷新
 * 都已经在 `FeedViewModel` 里跑通了。这里只接收「已经抓好的订阅文章」，
 * 于是订阅那条路和新增的召回路走的是同一套排序。
 *
 * 性能上唯一要紧的一处：**每篇文章的向量只算一次**。编码是全链路最重的活
 * （512 维 4 层模型，手机单条约 5~8ms），500 条冷启动要 3 秒左右；
 * 而落盘后每次刷新只需要点积（500 条 < 1ms）。所以 [VectorStore] 的命中率
 * 直接决定刷新体验，[RecommendStats.newlyEncoded] 就是用来盯这件事的。
 */
class RecommendEngine(
    private val recall: RecallService,
    private val vectors: VectorStore,
    private val synonyms: SynonymDict = SynonymDict.DEFAULT,
    private val weights: RankWeights = RankWeights(),
) {

    /**
     * @param interests 用户的兴趣列表。空表时退化成纯时间序（不返回空首页）。
     * @param subscribed 已抓好的订阅文章。不想让订阅内容进兴趣流就传空表。
     * @param encoder 语义编码器。传 null（模型不可用）会退化成纯词法 + 时间排序。
     * @param pool 并发编码池。同一批缺失向量并发编码，冷启动快 N 倍（见 [EncodePool]）。
     *   传 null 就退化成逐条串行 —— 单测走的是这条路，结果可复现。
     * @param encodeBudget 本轮最多新编码多少条。见 [DEFAULT_ENCODE_BUDGET]。
     */
    suspend fun recommend(
        interests: List<Interest>,
        subscribed: List<Article> = emptyList(),
        encoder: TextEncoder? = null,
        pool: EncodePool? = null,
        now: Long = System.currentTimeMillis(),
        limit: Int = Int.MAX_VALUE,
        encodeBudget: Int = DEFAULT_ENCODE_BUDGET,
    ): RecommendOutcome {
        val queries = interests
            .filter { it.enabled && it.keyword.isNotBlank() }
            // 按权重从高到低发查询：召回额度（RecallService.MAX_QUERIES）是有限的，
            // 应该花在用户最在意的那几个词上，而不是按列表顺序。
            .sortedByDescending { it.weight }
            .map { RecallQuery.of(it, synonyms) }

        val recalled = recall.recall(queries, subscribed)
        val ranked = rank(recalled.articles, interests, encoder, pool, now, limit, encodeBudget)

        return RecommendOutcome(
            articles = ranked.articles,
            failures = recalled.failures,
            // 召回侧才知道的量（发出去几个词、每路拿回几条）在这里补上
            stats = ranked.stats.copy(
                pooled = subscribed.size,
                recalled = recalled.articles.size - subscribed.size,
                queriesUsed = recalled.queriesUsed,
                queriesSkipped = recalled.queriesSkipped,
                perChannel = recalled.perChannel,
            ),
        )
    }

    /**
     * 只重排一批**已经拿到**的候选，不重新召回。
     *
     * 兴趣的权重和开关只影响排序，跟召回面无关 —— 重新打一轮网络
     * （8 个词 × 6 路 = 48 个请求）换不来任何新内容，只会把「调一下权重」
     * 变成「等半分钟」。所以设置页里改兴趣走这条路，改通道走 [recommend]。
     *
     * 重复调用它本身是有收益的：上一轮因为编码预算被推迟的条目，这一轮会接着
     * 排队编码（观察 [RecommendStats.deferred] 是否在收敛）。
     */
    suspend fun rerank(
        candidates: List<Article>,
        interests: List<Interest>,
        encoder: TextEncoder? = null,
        pool: EncodePool? = null,
        now: Long = System.currentTimeMillis(),
        limit: Int = Int.MAX_VALUE,
        encodeBudget: Int = DEFAULT_ENCODE_BUDGET,
    ): RecommendOutcome = rank(candidates, interests, encoder, pool, now, limit, encodeBudget)

    /** [recommend] 和 [rerank] 的共同下半段：取向量（带缓存）+ 排序。 */
    private suspend fun rank(
        candidates: List<Article>,
        interests: List<Interest>,
        encoder: TextEncoder?,
        pool: EncodePool?,
        now: Long,
        limit: Int,
        encodeBudget: Int,
    ): RecommendOutcome {
        // 编码是 CPU 密集的纯计算，扔到 Default 而不是 IO —— 别和网络请求抢同一组线程
        val encoding = withContext(Dispatchers.Default) {
            ensureVectors(candidates, encoder, pool, encodeBudget)
        }
        if (encoding.changed) {
            // 落盘放 IO：这一步会写文件，和上面那堆计算分开
            withContext(Dispatchers.IO) { persist(encoding) }
        }

        val ranker = Ranker(encoder ?: NoSemanticEncoder, synonyms, weights)
        val ranked = ranker.rank(
            // 这里传完整的 interests：Ranker 自己会滤掉关掉的，重复滤一遍没必要
            interests = interests,
            // 传全部候选而不是「有向量的那些」：缺向量的条目照样能靠词法项参与排序，
            // 这正是模型还没就绪或个别条目编码失败时的降级路径。
            articles = candidates,
            articleVectors = encoding.vectors,
            now = now,
            limit = limit,
        )

        return RecommendOutcome(
            articles = ranked,
            stats = RecommendStats(
                candidates = candidates.size,
                newlyEncoded = encoding.encoded,
                deferred = encoding.deferred,
                semantic = encoder != null,
            ),
        )
    }

    // ------------------------------------------------------------------ 内部

    /** 编码结果。`bySource` 是为了最后按源一次性落盘，而不是每篇写一次文件。 */
    private class Encoding(
        val vectors: Map<String, FloatArray>,
        val bySource: Map<String, Map<String, QuantizedVector>>,
        val encoded: Int,
        val deferred: Int,
    ) {
        val changed: Boolean get() = encoded > 0
    }

    /**
     * 取向量：命中磁盘缓存直接用，缺的才编码。
     *
     * 分两趟走（先扫缓存、再批量编码）而不是边扫边编码，是因为并发池要一次拿到整批
     * 才能铺满线程：逐条提交的话每条都在等上一条，等于串行。
     *
     * 「读了又原样写回」看起来多余，其实是必要的 —— 一个源这次只召回 30 条，
     * 而缓存文件里可能存着前几轮的两百条。按当前窗口重写一遍，文件尺寸
     * 就跟着内容走，不会无限涨。
     */
    private fun ensureVectors(
        articles: List<Article>,
        encoder: TextEncoder?,
        pool: EncodePool?,
        budget: Int,
    ): Encoding {
        if (encoder == null) return Encoding(emptyMap(), emptyMap(), 0, 0)

        val byArticle = HashMap<String, FloatArray>(articles.size * 2)
        val rowsBySource = HashMap<String, HashMap<String, QuantizedVector>>()
        val missing = ArrayList<Article>()

        for ((sourceId, group) in articles.groupBy { it.sourceId }) {
            val cached = vectors.read(sourceId)
            val rows = HashMap<String, QuantizedVector>(group.size * 2)

            for (article in group) {
                val hit = cached[article.id]
                if (hit != null) {
                    // 必须走 toUnitFloats()：量化会让模长偏离 1 约 2%，
                    // 而排序用点积代替余弦，不归一化就不是余弦了
                    byArticle[article.id] = hit.toUnitFloats()
                    rows[article.id] = hit
                } else {
                    missing += article
                }
            }
            rowsBySource[sourceId] = rows
        }

        // 预算按「最新的先编码」分配。冷启动时候选可能上千条，全编码要好几秒，
        // 而首屏只会展示最前面那几十条 —— 按时间倒序切预算，保证被截掉的是
        // 最不可能出现在首屏的老内容。
        val ordered = if (missing.size <= budget) missing else missing.sortedByDescending { it.publishedAt }
        val batch = if (ordered.size <= budget) ordered else ordered.subList(0, budget.coerceAtLeast(0))

        val vectorsOfBatch = if (pool != null) {
            pool.encode(batch.map { encodeText(it) })
        } else {
            // 没有并发池就串行。单测走这条路：结果与线程数无关，可复现。
            batch.map { article -> runCatching { encoder.encode(encodeText(article)) }.getOrNull() }
        }

        var encoded = 0
        for (index in batch.indices) {
            val article = batch[index]
            val vector = vectorsOfBatch.getOrNull(index)?.let { Vectors.l2Normalize(it) } ?: continue
            // 维度对不上说明模型换了：跳过这篇，排序侧会按「没有向量」处理
            if (vector.size != encoder.dimension) continue
            byArticle[article.id] = vector
            rowsBySource[article.sourceId]?.put(article.id, QuantizedVector.quantize(vector))
            encoded++
        }

        return Encoding(byArticle, rowsBySource, encoded, missing.size - batch.size)
    }

    private fun persist(encoding: Encoding) {
        for ((sourceId, rows) in encoding.bySource) {
            // write 自己会跳过空表，不会把「这轮这个源 0 条」写成空文件
            vectors.write(sourceId, rows)
        }
    }

    /**
     * 送去编码的文本。
     *
     * 标题是编辑挑出来的核心信息，主信号给它；摘要补一点上下文，
     * 让标题很短的条目（GitHub 仓库名、SO 的短标题）也有东西可编码。
     * 故意不拼作者和分类 —— 那是元数据，会稀释语义。
     *
     * token 数超上限的部分由 tokenizer 截断（128 token），不用在这里预截。
     */
    private fun encodeText(article: Article): String =
        if (article.excerpt.isEmpty()) article.title else "${article.title} ${article.excerpt}"

    companion object {
        /**
         * 单轮刷新最多新编码多少条。**这是刷新延迟的上界。**
         *
         * 冷启动时候选可能上千条，全编码在手机上要好几秒 —— 而且这发生在
         * 网络请求刚回来、用户正等着看内容的那一刻。给个预算就把最坏情况钉死了：
         * 300 条 / 4 路并发 × 单条约 10ms ≈ 0.8s，可以接受。
         *
         * 被推迟的条目不是丢弃：下一轮它们还是「缺向量」，会继续排队编码，
         * 而已经编码过的落盘缓存，稳态下每轮只需编码新增的那几条。
         * 推迟量可以从 [RecommendStats.deferred] 观察。
         */
        const val DEFAULT_ENCODE_BUDGET = 300
    }
}
