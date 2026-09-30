package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import com.example.feedreader.data.RssParser
import com.example.feedreader.data.SynonymDict
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.net.SocketTimeoutException

/**
 * 六个通道的解析全部用**真实响应**当样本（`app/src/test/resources/recall/`，
 * 2026-09-24 抓下来的原文），不是手写的精简 JSON。
 *
 * 这么做的理由：这类解析的失败模式是「字段名写错 / 嵌套层级记错 / 类型不符」，
 * 全都表现为**静默返回空列表**。手写样本会顺着我的理解去构造，
 * 恰好验证不了「我理解错了」这件事 —— 只有真响应能。
 */
private object Fixtures {
    fun load(name: String): String {
        val stream = Fixtures::class.java.getResourceAsStream("/recall/$name")
            ?: error("缺少 fixture：/recall/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** 造一批里最少量的字段，只为验证契约（不抛异常 / 不返回脏数据）。 */
private val GARBAGE_BODIES = listOf(
    "",
    "   ",
    "not json at all",
    "{",
    "[]",
    "null",
    """{"items":null,"hits":null,"result_vos":null}""",
    "<html><body>403 Forbidden</body></html>",
)

// ------------------------------------------------------------------ HN

class HackerNewsChannelTest {

    private val channel = HackerNewsChannel()

    @Test
    fun `URL 带上查询串与条数上限`() {
        assertEquals(
            "https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=30&query=llm",
            channel.url("llm", 30),
        )
    }

    @Test
    fun `解析真实响应`() {
        val articles = channel.parse(Fixtures.load("hn_algolia.json"))

        assertEquals(5, articles.size)
        val first = articles.first()
        assertTrue("id 要带通道前缀，避免和其他通道撞", first.id.startsWith("hn-search:"))
        assertTrue(first.title.isNotBlank())
        assertTrue(first.link.startsWith("http"))
        assertEquals("海外", first.category)
        assertEquals("hn-search", first.sourceId)
    }

    /**
     * `created_at_i` 是**秒级**的。忘了乘 1000 的话时间会落到 1970 年，
     * 而排序的时间项只看「有多新」—— 这类错误不会报错，只会让所有 HN 内容沉底。
     */
    @Test
    fun `时间戳按秒解析成毫秒`() {
        val articles = channel.parse(Fixtures.load("hn_algolia.json"))
        val published = articles.map { it.publishedAt }

        assertTrue("不应出现 0 时间：$published", published.all { it > 0L })
        // 秒级时间戳约 1.5e9，没乘 1000 就会落在这个量级以下
        assertTrue("秒/毫秒搞混了：$published", published.all { it > 1_000_000_000_000L })
        // 精确钉一条真实值（样本里 created_at_i = 1566228594）
        assertTrue("换算不是简单的 ×1000：$published", published.contains(1_566_228_594_000L))
    }

    /** Ask HN 这类自帖没有 `url`，不能因此丢条目，要退回 HN 的讨论页。 */
    @Test
    fun `没有外链时退回讨论页`() {
        val body = """
            {"hits":[{"objectID":"42","title":"Ask HN: 你们怎么做端侧推理","url":null,
            "author":"demo-user","created_at_i":1700000000,"points":12,"num_comments":3}]}
        """.trimIndent()

        val article = channel.parse(body).single()
        assertEquals("https://news.ycombinator.com/item?id=42", article.link)
        assertEquals(1700000000_000L, article.publishedAt)
    }

    @Test
    fun `空标题条目被丢掉`() {
        val body = """{"hits":[{"objectID":"1","title":"","url":"https://a.test"},{"objectID":"2","title":"ok"}]}"""
        assertEquals(listOf("hn-search:2"), channel.parse(body).map { it.id })
    }

    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        GARBAGE_BODIES.forEach { assertEquals(emptyList<Article>(), channel.parse(it)) }
    }
}

// ------------------------------------------------------------------ GitHub

class GitHubSearchChannelTest {

    private val channel = GitHubSearchChannel()

    @Test
    fun `URL 带上查询串与条数上限`() {
        assertEquals(
            "https://api.github.com/search/repositories?sort=stars&order=desc&per_page=10&q=llm",
            channel.url("llm", 10),
        )
    }

    @Test
    fun `解析真实响应`() {
        val articles = channel.parse(Fixtures.load("github_search.json"))

        assertEquals(5, articles.size)
        val first = articles.first()
        assertEquals("huggingface/transformers", first.title)
        assertEquals("https://github.com/huggingface/transformers", first.link)
        assertEquals("huggingface", first.author)
        assertEquals("代码", first.category)
    }

    /** 摘要把 star 数和语言也带上 —— 判断一个仓库值不值得点开，这两项最有用。 */
    @Test
    fun `摘要包含 star 数与语言`() {
        val first = channel.parse(Fixtures.load("github_search.json")).first()
        assertTrue("摘要缺 star 数：${first.excerpt}", first.excerpt.contains("★"))
        assertFalse(first.excerpt.isBlank())
    }

    /**
     * 用 `pushed_at` 而不是 `created_at`：老仓库刚更新过就是新鲜内容，
     * 按创建时间会被排到最底下。
     */
    @Test
    fun `时间取 pushed_at`() {
        val body = """
            {"items":[{"full_name":"a/b","html_url":"https://github.com/a/b",
            "created_at":"2015-01-01T00:00:00Z","pushed_at":"2026-09-20T00:00:00Z"}]}
        """.trimIndent()

        val pushed = channel.parse(body).single().publishedAt
        assertTrue("应解析出 2026 年的时间，实际 $pushed", pushed > 1_700_000_000_000L)
    }

    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        GARBAGE_BODIES.forEach { assertEquals(emptyList<Article>(), channel.parse(it)) }
    }
}

// ------------------------------------------------------------------ StackExchange

class StackExchangeChannelTest {

    private val channel = StackExchangeChannel()

    @Test
    fun `URL 带上站点与条数上限`() {
        val url = channel.url("rust async", 20)
        assertTrue(url.startsWith("https://api.stackexchange.com/2.3/search/advanced?"))
        assertTrue(url.contains("site=stackoverflow"))
        assertTrue(url.contains("pagesize=20"))
        // 空格编码成 + 或 %20 都合法，两个都认
        assertTrue("查询串没被编码：$url", url.contains("q=rust+async") || url.contains("q=rust%20async"))
    }

    @Test
    fun `解析真实响应`() {
        val articles = channel.parse(Fixtures.load("stackexchange.json"))

        assertEquals(5, articles.size)
        val first = articles.first()
        assertTrue(first.id.startsWith("stackexchange:"))
        assertTrue(first.title.isNotBlank())
        assertTrue(first.link.startsWith("https://stackoverflow.com/"))
        assertEquals("问答", first.category)
    }

    @Test
    fun `摘要带上标签`() {
        val first = channel.parse(Fixtures.load("stackexchange.json")).first()
        assertTrue("摘要没带标签：${first.excerpt}", first.excerpt.contains("android"))
    }

    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        GARBAGE_BODIES.forEach { assertEquals(emptyList<Article>(), channel.parse(it)) }
    }
}

// ------------------------------------------------------------------ CSDN

class CsdnChannelTest {

    private val channel = CsdnChannel()

    @Test
    fun `URL 带上查询串`() {
        assertEquals(
            "https://so.csdn.net/api/v3/search?t=blog&p=1&q=%E5%A4%A7%E6%A8%A1%E5%9E%8B",
            channel.url("大模型", 30),
        )
    }

    @Test
    fun `解析真实响应`() {
        val articles = channel.parse(Fixtures.load("csdn_search.json"))

        // 同一批响应里 26 条结果，比其他通道大一个量级
        assertEquals(26, articles.size)
        assertTrue(articles.all { it.title.isNotBlank() })
        assertTrue(articles.all { it.link.startsWith("https://blog.csdn.net/") })
        assertEquals("中文", articles.first().category)
    }

    /** 标题里带 `<em>大模型</em>` 高亮标记，不剥掉会原样显示成标签文本。 */
    @Test
    fun `高亮标签被剥掉`() {
        val titles = channel.parse(Fixtures.load("csdn_search.json")).map { it.title }
        assertTrue("标题里残留了高亮标记", titles.none { it.contains("<em>") || it.contains("</em>") })
        assertTrue(titles.any { it.contains("大模型") })
    }

    /**
     * `url` 里塞了 `utm_*` / `ops_request_misc` / `request_id`，而 request_id
     * **每次请求都不同** —— 不清理的话同一篇文章每次召回都是新链接，去重全失效。
     */
    @Test
    fun `链接上的跟踪参数被清掉`() {
        val links = channel.parse(Fixtures.load("csdn_search.json")).map { it.link }
        assertTrue("残留跟踪参数", links.none { it.contains("utm_") || it.contains("request_id") })
        // 清完还得是可用的文章地址，不能把正文路径也削掉
        assertTrue(links.all { it.matches(Regex("https://blog\\.csdn\\.net/[^/]+/article/details/\\d+")) })
    }

    /** `create_time` 是**毫秒时间戳的字符串**，`created_at` 才是给人看的日期。 */
    @Test
    fun `时间优先取毫秒时间戳字符串`() {
        val published = channel.parse(Fixtures.load("csdn_search.json")).map { it.publishedAt }
        assertTrue("不应出现 0 时间", published.all { it > 0L })
        assertTrue("毫秒时间戳解析错了", published.all { it > 1_600_000_000_000L })
    }

    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        GARBAGE_BODIES.forEach { assertEquals(emptyList<Article>(), channel.parse(it)) }
    }
}

// ------------------------------------------------------------------ V2EX

class V2exChannelTest {

    private val channel = V2exChannel()

    @Test
    fun `URL 带上查询串与条数上限`() {
        assertEquals(
            "https://www.sov2ex.com/api/search?sort=sumup&size=20&q=%E5%A4%A7%E6%A8%A1%E5%9E%8B",
            channel.url("大模型", 20),
        )
    }

    /**
     * sov2ex 把 ES 的 `hits.hits[]` 在**顶层展平**了：`hits` 直接就是数组，
     * 内容套在 `_source` 里。按常规的 `hits.hits` 取会得到空表且不报错。
     */
    @Test
    fun `解析真实响应 - hits 在顶层是数组`() {
        val articles = channel.parse(Fixtures.load("v2ex_sov2ex.json"))

        assertEquals(10, articles.size)
        val first = articles.first()
        assertTrue(first.id.startsWith("v2ex:"))
        assertTrue(first.title.isNotBlank())
        assertTrue(first.link.startsWith("https://www.v2ex.com/t/"))
        assertEquals("中文", first.category)
        assertTrue("created 是本地时间字符串，应能解析", first.publishedAt > 0L)
    }

    /** 按 `hits.hits` 取的话这里会是空表 —— 把「错的取法」固定成断言。 */
    @Test
    fun `不能按 hints 嵌套取`() {
        val nested = """{"hits":{"hits":[{"_source":{"id":1,"title":"x"}}]}}"""
        assertEquals(emptyList<Article>(), channel.parse(nested))
    }

    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        GARBAGE_BODIES.forEach { assertEquals(emptyList<Article>(), channel.parse(it)) }
    }
}

// ------------------------------------------------------------------ RSSHub

class RssHubChannelTest {

    // RssParser 默认用 android.util.Xml，在 JVM 单测里是 stub，所以注入 kxml2
    private fun channel(baseUrl: String = "") =
        RssHubChannel(baseUrl = baseUrl, parser = RssParser { KXmlParser() })

    @Test
    fun `默认走内置公共镜像`() {
        assertEquals(RssHubChannel.DEFAULT_MIRROR + "/weibo/keyword/x", channel().url("x", 20))
        // 填了自建实例就用自建的，且结尾斜杠不会造成双斜杠
        assertEquals("https://my.host/r/weibo/keyword/x", channel("https://my.host/r/").url("x", 20))
    }

    @Test
    fun `关键词做 URL 编码`() {
        assertEquals(
            RssHubChannel.DEFAULT_MIRROR + "/weibo/keyword/%E5%A4%A7%E6%A8%A1%E5%9E%8B",
            channel().url("大模型", 20),
        )
    }

    /**
     * RSSHub 返回的是标准 RSS，直接复用 RssParser。
     * 样本是真响应：`/weibo/keyword/大模型` 在公共镜像上抓到的 10 条。
     */
    @Test
    fun `解析真实 RSS 响应`() {
        val articles = channel().parse(Fixtures.load("rsshub_weibo_keyword.xml"))

        assertEquals(10, articles.size)
        assertTrue(articles.all { it.title.isNotBlank() })
        assertTrue(articles.all { it.id.startsWith("rsshub:") })
    }

    /** XML 解析失败会抛异常，但通道契约要求收成空表 —— 否则一路炸掉整次刷新。 */
    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        GARBAGE_BODIES.forEach { assertEquals(emptyList<Article>(), channel().parse(it)) }
    }
}

// ------------------------------------------------------------------ URL 清理

class CleanLinkTest {

    @Test
    fun `清掉 utm 一族与 CSDN 的跟踪参数`() {
        assertEquals(
            "https://blog.csdn.net/a/article/details/1",
            cleanLink(
                "https://blog.csdn.net/a/article/details/1?ops_request_misc=x&request_id=y" +
                    "&biz_id=0&utm_medium=distribute.pc_search_result&utm_term=z"
            ),
        )
    }

    /**
     * 功能性查询参数必须留着：删了链接就废了，而且不同视频会撞成同一个地址。
     * 同一串里的跟踪参数仍然要清掉。
     */
    @Test
    fun `保留功能性查询参数`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            cleanLink("https://www.youtube.com/watch?v=abc123"),
        )
        assertEquals(
            "https://a.test/p?id=7",
            cleanLink("https://a.test/p?utm_source=x&id=7"),
        )
        assertEquals(
            "https://a.test/p?id=7&page=2",
            cleanLink("https://a.test/p?utm_medium=y&id=7&page=2"),
        )
    }

    @Test
    fun `去掉锚点与尾随空白`() {
        assertEquals("https://a.test/p", cleanLink("  https://a.test/p#section  "))
        assertEquals("https://a.test/p", cleanLink("https://a.test/p?utm_source=x#s"))
    }

    @Test
    fun `没有查询串的地址原样返回`() {
        assertEquals("https://a.test/p", cleanLink("https://a.test/p"))
        assertEquals("", cleanLink(null))
        assertEquals("", cleanLink("   "))
    }
}

// ------------------------------------------------------------------ 查询对象

class RecallQueryTest {

    private val synonyms = SynonymDict.DEFAULT

    @Test
    fun `原词永远排第一且不重复出现`() {
        val query = RecallQuery.of("大模型", synonyms)
        assertEquals("大模型", query.keyword)
        assertFalse("相关词里不该有原词", query.alternates.contains("大模型"))
        assertTrue(query.alternates.isNotEmpty())
    }

    /**
     * 这是召回阶段最要紧的一处：「大模型」发给 GitHub 搜索是空手而归，
     * 而词表里的 `llm` 能召回一堆。替换必须发生在发请求之前。
     */
    @Test
    fun `英文语料改用拉丁相关词`() {
        assertEquals("llm", RecallQuery.of("大模型", synonyms).latin)
        assertEquals("llm", RecallQuery.of("AI 大模型", synonyms).latin)
        // 「人工智能」的词表第一位是更贴切的 ai，不是 llm
        assertEquals("ai", RecallQuery.of("人工智能", synonyms).latin)
    }

    @Test
    fun `原词本身是拉丁词就用原词`() {
        assertEquals("rust", RecallQuery.of("rust", synonyms).latin)
        assertEquals("CSS", RecallQuery.of("CSS", synonyms).latin)
    }

    /** 词表里只有中文相关词时只能退回原词，不能硬挑一个中英混排的串发出去。 */
    @Test
    fun `没有拉丁相关词时退回原词`() {
        val query = RecallQuery.of("量化交易", synonyms)
        assertTrue("词表扩出的中文词不该被当拉丁词用", query.alternates.none { it.all { c -> c.code < 0x80 } })
        assertEquals("量化交易", query.latin)
    }

    @Test
    fun `空关键词不炸`() {
        val query = RecallQuery.of("   ", synonyms)
        assertEquals("", query.keyword)
        assertEquals(emptyList<String>(), query.alternates)
        assertEquals("", query.latin)
    }

    @Test
    fun `大小写不同视为同一个词`() {
        val query = RecallQuery.of("  CSS  ", synonyms)
        assertEquals("CSS", query.keyword)
        assertFalse("归一化后与原词相同的不该再出现", query.alternates.contains("css"))
    }
}

// ------------------------------------------------------------------ 多路召回编排

private fun articleOf(id: String, link: String = "https://example.test/$id", category: String = "测试") =
    Article(
        id = id,
        title = "标题 $id",
        excerpt = "",
        link = link,
        author = "作者",
        sourceId = "s",
        sourceName = "S",
        category = category,
        publishedAt = 0L,
    )

private class FakeHttpGet(private val body: String = "{}") : HttpGet {
    val requested = mutableListOf<String>()
    override suspend fun text(url: String, accept: String): String {
        requested += url
        return body
    }
}

/**
 * `produce` 放在最后一个参数位，是为了能用尾随 lambda 写得像 `FakeChannel("a") { listOf(...) }`。
 * 它要是排在 `delayMs` 前面，尾随 lambda 会绑到 `delayMs` 上，报一堆「参数类型不符」。
 */
private class FakeChannel(
    override val id: String,
    override val name: String = id,
    private val failWith: Exception? = null,
    private val delayMs: Long = 0L,
    private val produce: (String) -> List<Article> = { emptyList() },
) : RecallChannel {
    override val category = "测试"
    override val limit = 10

    override fun url(query: String, limit: Int) = "https://example.test/$id?q=$query"

    override fun parse(body: String) = emptyList<Article>()

    override suspend fun recall(http: HttpGet, query: RecallQuery): List<Article> {
        if (delayMs > 0L) delay(delayMs)
        failWith?.let { throw it }
        return produce(query.keyword)
    }
}

class RecallServiceTest {

    private fun queries(vararg keywords: String) = keywords.map { RecallQuery(it, emptyList(), 5) }

    @Test
    fun `合并订阅文章与各路召回`() = runBlocking {
        val service = RecallService(
            channels = listOf(
                FakeChannel("a") { listOf(articleOf("a1")) },
                FakeChannel("b") { listOf(articleOf("b1"), articleOf("b2")) },
            ),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(queries("x"), subscribed = listOf(articleOf("sub")))

        assertEquals(4, outcome.articles.size)
        assertEquals(0, outcome.failures.size)
        assertEquals(1, outcome.perChannel["a"])
        assertEquals(2, outcome.perChannel["b"])
        assertEquals(1, outcome.perChannel[RecallChannels.ID_SUBSCRIPTION])
    }

    /**
     * 同一篇文章很可能既在订阅里、又被微博搜到。**按链接去重** ——
     * 各通道的 id 前缀不同（`csdn:` / `v2ex:`），按 id 去重等于没去重。
     */
    @Test
    fun `按链接去重且保留订阅那一份`() = runBlocking {
        val shared = "https://example.test/shared"
        val service = RecallService(
            channels = listOf(
                FakeChannel("a") { listOf(articleOf("a1", shared, category = "中文")) },
                FakeChannel("b") { listOf(articleOf("b1", shared, category = "代码")) },
            ),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(
            queries("x"),
            subscribed = listOf(articleOf("sub", shared, category = "订阅分类")),
        )

        assertEquals(1, outcome.articles.size)
        // 订阅那份先出现，保留它的来源信息
        assertEquals("订阅分类", outcome.articles.single().category)
    }

    /**
     * 链接不同、id 相同的两条也只能留一条。
     *
     * 光按链接去重放不出这一对，而首页拿 `article.id` 当 LazyColumn 的 key —— 列表里
     * 真出现重复 id，界面不是画歪而是整页崩（设置页今天就崩在这上面）。坏 feed 确实会
     * 产出「guid 复用、link 各不同」的条目，所以这条不是假想敌。
     */
    @Test
    fun `同 id 不同链接也只留一条`() = runBlocking {
        val service = RecallService(
            channels = listOf(
                FakeChannel("a") {
                    listOf(
                        articleOf("dup", "https://example.test/one"),
                        articleOf("dup", "https://example.test/two"),
                    )
                },
            ),
            http = FakeHttpGet(),
        )

        val ids = service.recall(queries("x")).articles.map { it.id }

        assertEquals("id 必须唯一，否则 LazyColumn 直接崩", listOf("dup"), ids)
    }

    /** 对照：链接和 id 都不一样时，两条都该活着 —— 上面那条不许变成「只留一条」。 */
    @Test
    fun `不同 id 不同链接都保留`() = runBlocking {
        val service = RecallService(
            channels = listOf(
                FakeChannel("a") {
                    listOf(
                        articleOf("one", "https://example.test/one"),
                        articleOf("two", "https://example.test/two"),
                    )
                },
            ),
            http = FakeHttpGet(),
        )

        assertEquals(2, service.recall(queries("x")).articles.size)
    }

    @Test
    fun `链接尾部斜杠差异视为同一篇`() = runBlocking {
        val service = RecallService(
            channels = listOf(
                FakeChannel("a") { listOf(articleOf("a1", "https://example.test/p")) },
                FakeChannel("b") { listOf(articleOf("b1", "https://example.test/p/")) },
            ),
            http = FakeHttpGet(),
        )

        assertEquals(1, service.recall(queries("x")).articles.size)
    }

    /** 同一通道在多个关键词上失败要聚合成一条，否则失败横幅会被刷满。 */
    @Test
    fun `同一通道的多次失败聚合成一条`() = runBlocking {
        val service = RecallService(
            channels = listOf(FakeChannel("flaky", name = "爱挂的通道", failWith = SocketTimeoutException())),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(queries("a", "b", "c"))

        assertEquals(1, outcome.failures.size)
        val failure = outcome.failures.single()
        assertEquals("爱挂的通道", failure.source)
        assertTrue("要说明影响范围：${failure.message}", failure.message.contains("3 个关键词"))
        assertEquals(emptyList<Article>(), outcome.articles)
    }

    /** 一路挂掉不能影响其他路 —— 这是整套召回的设计前提。 */
    @Test
    fun `单路失败不影响其他路`() = runBlocking {
        val service = RecallService(
            channels = listOf(
                FakeChannel("bad", name = "坏的", failWith = SocketTimeoutException()),
                FakeChannel("good") { listOf(articleOf("g1")) },
            ),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(queries("x"))

        assertEquals(1, outcome.articles.size)
        assertEquals(1, outcome.failures.size)
    }

    @Test
    fun `哪一步都没挂但全空也算正常结果`() = runBlocking {
        val service = RecallService(
            channels = listOf(FakeChannel("a") { emptyList() }),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(queries("x"))

        assertEquals(emptyList<Article>(), outcome.articles)
        // 中文关键词在英文语料上召回为空是常态，不该报成失败
        assertEquals(0, outcome.failures.size)
        assertEquals(0, outcome.perChannel["a"])
    }

    @Test
    fun `单路超时被记成失败而不是一直等`() = runBlocking {
        val service = RecallService(
            channels = listOf(
                FakeChannel("slow", name = "慢的", delayMs = 5_000L) { listOf(articleOf("s1")) },
                FakeChannel("fast") { listOf(articleOf("f1")) },
            ),
            http = FakeHttpGet(),
            perChannelTimeoutMs = 60L,
        )

        val outcome = service.recall(queries("x"))

        assertEquals(listOf("fast"), outcome.perChannel.keys.toList())
        assertEquals(1, outcome.failures.size)
        assertEquals("慢的", outcome.failures.single().source)
    }

    /** 请求数是「兴趣词 × 通道数」，会线性爆，必须有硬上限。 */
    @Test
    fun `兴趣词数量有上限`() = runBlocking {
        val service = RecallService(
            channels = listOf(FakeChannel("a") { listOf(articleOf("x")) }),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(queries(*Array(20) { "k$it" }))

        assertEquals(RecallService.MAX_QUERIES, outcome.queriesUsed)
        assertEquals(20 - RecallService.MAX_QUERIES, outcome.queriesSkipped)
    }

    @Test
    fun `空白关键词不发起请求`() = runBlocking {
        val service = RecallService(
            channels = listOf(FakeChannel("a") { emptyList() }),
            http = FakeHttpGet(),
        )

        val outcome = service.recall(listOf(RecallQuery("  "), RecallQuery("ok")))

        assertEquals(1, outcome.queriesUsed)
        assertEquals(0, outcome.queriesSkipped)
    }

    /** 每路都按自己的 `limit` 和查询词拼地址，查询词要经过 `prefersLatin` 替换。 */
    @Test
    fun `请求地址按通道各自拼装`() = runBlocking {
        val http = FakeHttpGet()
        val service = RecallService(
            channels = listOf(V2exChannel(), HackerNewsChannel()),
            http = http,
        )

        service.recall(listOf(RecallQuery.of("大模型", SynonymDict.DEFAULT)))

        assertEquals(2, http.requested.size)
        // 中文语料通道用原词
        assertTrue(http.requested.any { it.contains("sov2ex.com") && it.contains("%E5%A4%A7%E6%A8%A1%E5%9E%8B") })
        // 英文语料通道换成拉丁相关词，而不是发中文过去收 0 条
        assertTrue(http.requested.any { it.contains("hn.algolia.com") && it.contains("query=llm") })
    }

    @Test
    fun `每个通道的 limit 上限生效`() = runBlocking {
        val http = FakeHttpGet()
        val service = RecallService(
            // GitHub 通道自己的上限是 10，请求要 30 也只给 10
            channels = listOf(GitHubSearchChannel()),
            http = http,
        )

        service.recall(listOf(RecallQuery("llm", emptyList(), 30)))

        assertTrue(http.requested.single().contains("per_page=10"))
    }
}
