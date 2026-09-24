package com.example.feedreader.recommend

import com.example.feedreader.data.Article
import com.example.feedreader.data.Interest
import com.example.feedreader.data.SynonymDict
import com.example.feedreader.data.recall.HttpGet
import com.example.feedreader.data.recall.RecallChannel
import com.example.feedreader.data.recall.RecallQuery
import com.example.feedreader.data.recall.RecallService
import io.github.pt123123.semantic.NoSemanticEncoder
import io.github.pt123123.semantic.QuantizedVector
import io.github.pt123123.semantic.TextEncoder
import io.github.pt123123.semantic.VectorStore
import io.github.pt123123.semantic.Vectors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketTimeoutException

/**
 * 一个「按主题分维」的假编码器。
 *
 * 够验证「语义项真的参与了排序」这件事：相似的文本落在同一维上，
 * 点积就等于主题重合度。[calls] 用来钉住「每篇只编码一次」这条性质。
 */
private class TopicEncoder : TextEncoder {
    override val dimension = VectorStore.EXPECTED_DIMENSION

    var calls = 0
        private set

    override fun encode(text: String): FloatArray {
        calls++
        val lowered = text.lowercase()
        val vector = FloatArray(dimension)
        TOPICS.forEachIndexed { index, words ->
            if (words.any { lowered.contains(it) }) vector[index] = 1f
        }
        return Vectors.l2Normalize(vector)
    }

    private companion object {
        val TOPICS = listOf(
            listOf("大模型", "推理", "模型"),
            listOf("rust", "异步", "所有权"),
            listOf("隐私", "加密", "端到端"),
        )
    }
}

/** 不做网络请求：这套测试只验证编排，网络那层在 RecallServiceTest 里单独验。 */
private object NoHttp : HttpGet {
    override suspend fun text(url: String, accept: String) = "{}"
}

private class StubChannel(
    override val id: String,
    private val produce: (String) -> List<Article>,
) : RecallChannel {
    override val name = id
    override val category = "测试"
    override val limit = 20
    override fun url(query: String, limit: Int) = "https://example.test/$id?q=$query"
    override fun parse(body: String) = emptyList<Article>()
    override suspend fun recall(http: HttpGet, query: RecallQuery) = produce(query.keyword)
}

/** 记录每个关键词被发了几次，用来验证「禁用的不召回」「按权重排序」。 */
private class SpyChannel : RecallChannel {
    val keywords = mutableListOf<String>()
    override val id = "spy"
    override val name = "spy"
    override val category = "测试"
    override val limit = 5
    override fun url(query: String, limit: Int) = "https://example.test/spy?q=$query"
    override fun parse(body: String) = emptyList<Article>()
    override suspend fun recall(http: HttpGet, query: RecallQuery): List<Article> {
        keywords += query.keyword
        return emptyList()
    }
}

private val NOW = 1_760_000_000_000L
private const val DAY = 24L * 3600 * 1000

private fun article(
    id: String,
    title: String,
    source: String = "stub",
    ageDays: Long = 0,
    link: String = "https://example.test/$id",
    excerpt: String = "",
) = Article(
    id = id,
    title = title,
    excerpt = excerpt,
    link = link,
    author = "作者",
    sourceId = source,
    sourceName = source,
    category = "测试",
    publishedAt = NOW - ageDays * DAY,
)

class RecommendEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun engineWith(
        channels: List<RecallChannel>,
        store: VectorStore = VectorStore(temp.root),
    ) = RecommendEngine(
        recall = RecallService(channels, NoHttp),
        vectors = store,
        synonyms = SynonymDict.DEFAULT,
    )

    @Test
    fun `语义相近的排前面`() = runBlocking {
        val engine = engineWith(
            listOf(
                StubChannel("stub") {
                    listOf(
                        article("rust", "Rust 异步运行时的调度实现"),
                        article("ai", "大模型推理成本再降一半"),
                        article("privacy", "端到端加密的五种实现错误"),
                    )
                },
            ),
        )

        val outcome = engine.recommend(
            interests = listOf(Interest("大模型")),
            encoder = TopicEncoder(),
            now = NOW,
        )

        assertEquals("ai", outcome.articles.first().article.id)
        assertEquals("大模型", outcome.articles.first().topInterest)
        assertTrue(outcome.stats.semantic)
        assertEquals(3, outcome.stats.newlyEncoded)
    }

    /** 模型不可用必须降级到纯词法，而不是抛异常或返回空首页。 */
    @Test
    fun `没有编码器时退化成纯词法排序`() = runBlocking {
        val engine = engineWith(
            listOf(
                StubChannel("stub") {
                    listOf(article("hit", "大模型的推理优化实践"), article("miss", "周末去爬山"))
                },
            ),
        )

        val outcome = engine.recommend(interests = listOf(Interest("大模型")), encoder = null, now = NOW)

        assertFalse(outcome.stats.semantic)
        assertEquals(0, outcome.stats.newlyEncoded)
        // 语义没了，词法项仍然能把命中的那条捞上来
        assertEquals("hit", outcome.articles.first().article.id)
    }

    /**
     * 这是整条链路性能上最关键的性质：**每篇文章只编码一次**。
     * 第二次刷新必须全从磁盘缓存读 —— 否则「冷启动 3 秒」会变成「每次刷新 3 秒」。
     */
    @Test
    fun `第二次刷新全部命中缓存不再编码`() = runBlocking {
        val encoder = TopicEncoder()
        val store = VectorStore(temp.root)
        val engine = engineWith(
            listOf(StubChannel("stub") { (1..6).map { article("a$it", "大模型相关第 $it 篇") } }),
            store,
        )

        val first = engine.recommend(listOf(Interest("大模型")), encoder = encoder, now = NOW)
        assertEquals(6, first.stats.newlyEncoded)
        val callsAfterFirst = encoder.calls

        val second = engine.recommend(listOf(Interest("大模型")), encoder = encoder, now = NOW)

        assertEquals("第二次刷新不该再编码文章", 0, second.stats.newlyEncoded)
        assertTrue(
            "除兴趣词外不该再有编码调用：$callsAfterFirst -> ${encoder.calls}",
            encoder.calls <= callsAfterFirst + 1,
        )
        assertEquals(6, store.read("stub").size)
    }

    /** 增量：只有新出现的条目会被编码。 */
    @Test
    fun `只编码新增条目`() = runBlocking {
        val encoder = TopicEncoder()
        var extra = false
        val engine = engineWith(
            listOf(
                StubChannel("stub") {
                    val base = listOf(article("a1", "大模型 A"), article("a2", "大模型 B"))
                    if (extra) base + article("a3", "大模型 C") else base
                },
            ),
        )

        engine.recommend(listOf(Interest("大模型")), encoder = encoder, now = NOW)
        extra = true
        val outcome = engine.recommend(listOf(Interest("大模型")), encoder = encoder, now = NOW)

        assertEquals(1, outcome.stats.newlyEncoded)
    }

    /** 订阅文章和召回文章进同一个候选池，且按链接去重。 */
    @Test
    fun `订阅文章与召回结果合并去重`() = runBlocking {
        val shared = "https://example.test/shared"
        val engine = engineWith(
            listOf(
                StubChannel("stub") {
                    listOf(article("fromSearch", "大模型搜索来的", link = shared))
                },
            ),
        )

        val outcome = engine.recommend(
            interests = listOf(Interest("大模型")),
            subscribed = listOf(article("fromFeed", "大模型订阅来的", source = "hn", link = shared)),
            encoder = TopicEncoder(),
            now = NOW,
        )

        assertEquals(1, outcome.stats.candidates)
        assertEquals(1, outcome.stats.pooled)
        assertEquals(0, outcome.stats.recalled)
        assertEquals("fromFeed", outcome.articles.single().article.id)
    }

    /** 召回失败的通道要能浮到 UI，不能被静默吞掉。 */
    @Test
    fun `召回失败向上传递`() = runBlocking {
        val failing = object : RecallChannel {
            override val id = "bad"
            override val name = "坏的通道"
            override val category = "测试"
            override val limit = 5
            override fun url(query: String, limit: Int) = "https://example.test/bad"
            override fun parse(body: String) = emptyList<Article>()
            override suspend fun recall(http: HttpGet, query: RecallQuery): List<Article> =
                throw SocketTimeoutException()
        }

        val outcome = engineWith(listOf(failing))
            .recommend(listOf(Interest("大模型")), encoder = TopicEncoder(), now = NOW)

        assertEquals(1, outcome.failures.size)
        assertEquals("坏的通道", outcome.failures.single().source)
    }

    /**
     * 没有兴趣词时退化成时间序，而不是给一个空首页。
     *
     * 注意候选这时**只能来自订阅池** —— 没有兴趣词就没有查询串，
     * 召回通道根本没有东西可发（下面的断言把这一点也钉住了）。
     * 库里没有兴趣链接、又关掉了订阅，就真的没内容可推，那是空列表，不是 bug。
     */
    @Test
    fun `没有兴趣词时按时间倒序`() = runBlocking {
        val spy = SpyChannel()
        val engine = engineWith(listOf(spy))

        val outcome = engine.recommend(
            interests = emptyList(),
            subscribed = listOf(article("old", "旧闻", source = "hn", ageDays = 10), article("new", "新闻", source = "hn")),
            encoder = TopicEncoder(),
            now = NOW,
        )

        assertEquals(listOf("new", "old"), outcome.articles.map { it.article.id })
        assertTrue("没有兴趣词就不该发查询", spy.keywords.isEmpty())
        assertEquals(2, outcome.stats.pooled)
    }

    /** 关掉的兴趣词不参与召回 —— 也不能占掉有限的召回额度。 */
    @Test
    fun `禁用的兴趣词不参与召回`() = runBlocking {
        val spy = SpyChannel()

        engineWith(listOf(spy)).recommend(
            interests = listOf(
                Interest("大模型", 1f, enabled = true),
                Interest("Rust", 2f, enabled = false),
            ),
            encoder = TopicEncoder(),
            now = NOW,
        )

        assertEquals(listOf("大模型"), spy.keywords)
    }

    /** 兴趣词按权重降序发查询：召回额度有限，先花在最在意的词上。 */
    @Test
    fun `召回顺序按兴趣权重降序`() = runBlocking {
        val spy = SpyChannel()

        engineWith(listOf(spy)).recommend(
            interests = listOf(Interest("低权重", 0.5f), Interest("高权重", 2f)),
            encoder = TopicEncoder(),
            now = NOW,
        )

        assertEquals(listOf("高权重", "低权重"), spy.keywords)
    }

    /** 候选为空是合法结果，不该报错。 */
    @Test
    fun `没有任何候选时返回空列表`() = runBlocking {
        val outcome = engineWith(listOf(StubChannel("stub") { emptyList() }))
            .recommend(listOf(Interest("大模型")), encoder = TopicEncoder(), now = NOW)

        assertTrue(outcome.articles.isEmpty())
        assertEquals(0, outcome.failures.size)
    }

    /** 换模型后旧缓存维度不符，必须丢弃重算，而不是让排序越界。 */
    @Test
    fun `维度不符的旧缓存被丢弃`() = runBlocking {
        val dir = temp.newFolder("dim-change")
        // 造一份「上一版模型是 64 维」写下的缓存
        VectorStore(dir, expectedDimension = 64)
            .write("stub", mapOf("a1" to QuantizedVector.quantize(FloatArray(64) { 0.1f })))

        val engine = engineWith(
            listOf(StubChannel("stub") { listOf(article("a1", "大模型")) }),
            VectorStore(dir),
        )
        val outcome = engine.recommend(listOf(Interest("大模型")), encoder = TopicEncoder(), now = NOW)

        assertEquals("旧缓存应作废重编码", 1, outcome.stats.newlyEncoded)
        assertEquals(1, outcome.articles.size)
    }

    /** [NoSemanticEncoder] 必须是零向量，且每次返回新数组 —— 向量会被原地归一化。 */
    @Test
    fun `无模型编码器返回独立的零向量`() {
        val a = NoSemanticEncoder.encode("任意文本")
        val b = NoSemanticEncoder.encode("另一段文本")

        assertFalse("不能共享同一个数组", a === b)
        assertEquals(VectorStore.EXPECTED_DIMENSION, a.size)
        assertTrue(a.all { it == 0f })
        // 归一化零向量不能产生 NaN，也不能影响另一份
        Vectors.l2Normalize(a)
        assertTrue(b.all { it == 0f })
    }

    // ------------------------------------------------------------------ rerank

    /**
     * 这是 [RecommendEngine.rerank] 存在的全部理由：**改兴趣不能触发网络请求**。
     * 设置页里拖一下权重就重打 48 个请求，是这一版最容易被做错的地方。
     */
    @Test
    fun `rerank 不发出任何召回请求`() = runBlocking {
        val spy = SpyChannel()
        val engine = engineWith(listOf(spy))
        val candidates = listOf(article("a1", "大模型推理"), article("a2", "Rust 所有权"))

        // 先跑一次完整推荐，把候选池建出来
        engine.recommend(listOf(Interest("大模型")), encoder = TopicEncoder(), now = NOW)
        val keywordCountAfterRecommend = spy.keywords.size

        val outcome = engine.rerank(
            candidates = candidates,
            interests = listOf(Interest("大模型")),
            encoder = TopicEncoder(),
            now = NOW,
        )

        assertEquals(
            "rerank 不该再发查询",
            keywordCountAfterRecommend,
            spy.keywords.size,
        )
        assertEquals(candidates.size, outcome.stats.candidates)
        assertTrue("没有召回就不该有失败项", outcome.failures.isEmpty())
    }

    /** 候选不变、只换兴趣，排序结果必须跟着变 —— 否则「权重调整」是个假的开关。 */
    @Test
    fun `rerank 按新的兴趣重排`() = runBlocking {
        val engine = engineWith(listOf(SpyChannel()))
        val candidates = listOf(
            article("rust", "Rust 异步运行时的调度实现"),
            article("ai", "大模型推理成本再降一半"),
        )

        val byRust = engine.rerank(candidates, listOf(Interest("Rust")), TopicEncoder(), now = NOW)
        val byAi = engine.rerank(candidates, listOf(Interest("大模型")), TopicEncoder(), now = NOW)

        assertEquals("rust", byRust.articles.first().article.id)
        assertEquals("Rust", byRust.articles.first().topInterest)
        assertEquals("ai", byAi.articles.first().article.id)
    }

    /**
     * 被编码预算推迟的条目，下一轮 rerank 要接着排队编码。
     * 否则那些条目会永远只有词法分，在兴趣流里天然吃亏。
     */
    @Test
    fun `rerank 接着编码上一轮被预算推迟的条目`() = runBlocking {
        val encoder = TopicEncoder()
        val store = VectorStore(temp.root)
        val engine = engineWith(listOf(SpyChannel()), store)
        val candidates = (1..5).map { article("a$it", "大模型相关第 $it 篇") }

        val first = engine.rerank(
            candidates,
            listOf(Interest("大模型")),
            encoder,
            now = NOW,
            encodeBudget = 2,
        )
        assertEquals("预算卡住只编码 2 条", 2, first.stats.newlyEncoded)
        assertEquals("其余 3 条被推迟", 3, first.stats.deferred)

        val second = engine.rerank(
            candidates,
            listOf(Interest("大模型")),
            encoder,
            now = NOW,
            encodeBudget = 5,
        )
        assertEquals("这一轮把剩下的补上", 3, second.stats.newlyEncoded)
        assertEquals(0, second.stats.deferred)
        // 向量按文章所在的 sourceId 落盘；article() 的默认来源是 "stub"
        assertEquals(5, store.read("stub").size)
    }

    /** 关掉全部兴趣时，rerank 照样要给一份按时间排的结果，不能返回空首页。 */
    @Test
    fun `rerank 在没有兴趣时退化成时间序`() = runBlocking {
        val engine = engineWith(listOf(SpyChannel()))
        val candidates = listOf(
            article("old", "旧闻", ageDays = 30),
            article("new", "新闻", ageDays = 0),
        )

        val outcome = engine.rerank(candidates, emptyList(), TopicEncoder(), now = NOW)

        assertEquals(listOf("new", "old"), outcome.articles.map { it.article.id })
        assertTrue(outcome.articles.all { it.topInterest.isEmpty() })
    }
}
