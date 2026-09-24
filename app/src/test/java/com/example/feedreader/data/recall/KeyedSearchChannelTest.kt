package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecallChannel.recall] 的第一个参数类型是 [HttpGet]，但 keyed 通道**忽略**它、
 * 改用构造时注入的 [KeyedHttp]。[DUMMY_HTTP_GET] 就是填这个位置用的占位，
 * 它本身永远不该被调用（若被调用会抛，帮我们抓住「recall 误用了 HttpGet 参数」）。
 */
private val DUMMY_HTTP_GET: HttpGet = object : HttpGet {
    override suspend fun text(url: String, accept: String): String =
        error("keyed 通道不应调用 HttpGet 参数")
}

/**
 * 四个「填 key 才用」的搜索通道（Perplexity / 秘塔 / Tavily / Brave）。
 *
 * 和别的通道一样，**解析用真实响应当样本**（fixtures 在 `resources/recall/`），
 * 不手写精简 JSON —— 字段名 / 嵌套层级 / 类型错了只会静默返回空表，手写样本顺着
 * 我的理解构造恰好验证不了「我理解错了」。
 *
 * 区别是这类通道走 [KeyedHttp]（POST + Bearer / 特鉴权头），所以测试分两块：
 *  - `parseResults` 直接喂 fixture 正文（纯函数，不碰网络）；
 *  - `recall` 用 [FakeKeyedHttp] / [KeyedHttpNoop] 验证「空 key 不出网」「有 key 也能在
 *    不发起真实请求的前提下跑通整条链路」（空响应收成空表、不抛）。
 */
private object KeyedFixtures {
    fun load(name: String): String {
        val stream = KeyedFixtures::class.java.getResourceAsStream("/recall/$name")
            ?: error("缺少 fixture：/recall/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** 记录有没有真的发请求；返回空串，让 parseResults 收成空表，整条链路不抛。 */
private class FakeKeyedHttp : KeyedHttp {
    val requested = mutableListOf<KeyedRequest>()
    override suspend fun request(req: KeyedRequest): String {
        requested += req
        return ""
    }
}

class KeyedSearchChannelTest {

    // ------------------------------------------------------------ Perplexity

    private fun pplx(key: String = "KEY") = PerplexityChannel(key, KeyedHttpNoop)

    @Test
    fun `perplexity 解析真实响应 - search_results 优先`() {
        val articles = pplx().parseResults(KeyedFixtures.load("perplexity.json"))

        assertEquals(2, articles.size)
        val first = articles.first()
        assertTrue("id 要带通道前缀", first.id.startsWith("pplx:"))
        assertEquals("Example pharmacology result", first.title)
        assertEquals("https://example.com/a", first.link)
        assertEquals("AI搜索", first.category)
        assertEquals("pplx", first.sourceId)
        assertEquals("Perplexity", first.sourceName)
        // 有 date 的那条要能解析出时间；另一条 date="" 落 0 也合法
        assertTrue("带日期的条目应解析出时间", articles.first().publishedAt > 0L)
    }

    @Test
    fun `perplexity 有 search_results 时不回退到 citations`() {
        val articles = pplx().parseResults(KeyedFixtures.load("perplexity.json"))
        // citations 里那条裸 URL 不应出现
        assertEquals(emptyList<String>(), articles.map { it.link }
            .filter { it.contains("skip-me-citations") })
    }

    @Test
    fun `perplexity 只有 citations 时也兜底出内容`() {
        val body = """{"citations":["https://a.test/1","https://a.test/2"]}"""
        val articles = pplx().parseResults(body)
        assertEquals(2, articles.size)
        assertEquals("https://a.test/1", articles.first().link)
        assertEquals("https://a.test/1", articles.first().title) // 没标题时标题退回链接
    }

    // ------------------------------------------------------------ 秘塔 Metaso

    private fun metaso(key: String = "KEY") = MetasoChannel(key, KeyedHttpNoop)

    @Test
    fun `秘塔 解析真实响应 - webpages 取 link 字段`() {
        val articles = metaso().parseResults(KeyedFixtures.load("metaso.json"))

        assertEquals(2, articles.size)
        val first = articles.first()
        assertTrue("id 要带通道前缀", first.id.startsWith("metaso:"))
        assertEquals("中文搜索结果一", first.title)
        assertEquals("https://example.com/cn", first.link)
        assertEquals("AI搜索(中文)", first.category)
        assertEquals("metaso", first.sourceId)
        assertEquals("秘塔 Metaso", first.sourceName)
        assertTrue(first.excerpt.contains("这是第一条摘要"))
        // 第二条没 date，publishedAt 落 0 合法；第一条有 2024-01-01 应解析出来
        assertTrue(articles.any { it.publishedAt > 0L })
    }

    @Test
    fun `秘塔 宽容认 results 与 data 嵌套和 url 字段`() {
        val viaResults = metaso().parseResults(
            """{"results":[{"title":"r","url":"https://r.test/x","snippet":"s"}]}""",
        )
        assertEquals("https://r.test/x", viaResults.single().link)

        val viaData = metaso().parseResults(
            """{"data":{"results":[{"title":"d","link":"https://d.test/y","description":"s"}]}}""",
        )
        assertEquals("https://d.test/y", viaData.single().link)
    }

    // ------------------------------------------------------------ Tavily

    private fun tavily(key: String = "KEY") = TavilyChannel(key, KeyedHttpNoop)

    @Test
    fun `tavily 解析真实响应 - results 取 content 当摘要`() {
        val articles = tavily().parseResults(KeyedFixtures.load("tavily.json"))

        assertEquals(2, articles.size)
        val first = articles.first()
        assertTrue("id 要带通道前缀", first.id.startsWith("tavily:"))
        assertEquals("Tavily result one", first.title)
        assertEquals("https://example.com/t1", first.link)
        assertEquals("全网搜索", first.category)
        assertEquals("tavily", first.sourceId)
        assertEquals("Tavily", first.sourceName)
        assertTrue(first.excerpt.contains("Extracted content"))
        // Tavily 没有日期字段，publishedAt 应落 0（展示时回退「时间未知」）
        assertEquals(0L, articles.first().publishedAt)
    }

    // ------------------------------------------------------------ Brave

    private fun brave(key: String = "KEY") = BraveChannel(key, KeyedHttpNoop)

    @Test
    fun `brave 解析真实响应 - web results 取 description`() {
        val articles = brave().parseResults(KeyedFixtures.load("brave.json"))

        assertEquals(2, articles.size)
        val first = articles.first()
        assertTrue("id 要带通道前缀", first.id.startsWith("brave:"))
        assertEquals("Brave result one", first.title)
        assertEquals("https://example.com/b1", first.link)
        assertEquals("全网搜索", first.category)
        assertEquals("brave", first.sourceId)
        assertEquals("Brave Search", first.sourceName)
        assertTrue(first.excerpt.contains("Description of the first"))
    }

    @Test
    fun `brave 缺 web results 时返回空表`() {
        assertEquals(emptyList<Article>(), brave().parseResults("""{"type":"search"}"""))
    }

    // ------------------------------------------------------------ 畸形输入契约

    @Test
    fun `四路对畸形 JSON 都收成空表而不是抛`() {
        val garbage = listOf("", "   ", "not json", "{", "[]", "null", "<html>403</html>", "{}")
        garbage.forEach {
            assertEquals(emptyList<Article>(), pplx().parseResults(it))
            assertEquals(emptyList<Article>(), metaso().parseResults(it))
            assertEquals(emptyList<Article>(), tavily().parseResults(it))
            assertEquals(emptyList<Article>(), brave().parseResults(it))
        }
    }

    // ------------------------------------------------------------ buildRequest 形状

    @Test
    fun `perplexity 请求是 POST + Bearer + sonar 模型`() {
        val req = pplx("SEC").buildRequest("pharmacology", 10)
        assertEquals("POST", req.method)
        assertTrue(req.url.contains("api.perplexity.ai/chat/completions"))
        assertEquals("Bearer SEC", req.headers["Authorization"])
        assertTrue("body 要带模型名", req.body!!.contains("\"sonar\""))
        assertTrue(req.body!!.contains("pharmacology"))
    }

    @Test
    fun `秘塔 请求是 POST + Bearer + q 字段`() {
        val req = metaso("SEC").buildRequest("大模型", 10)
        assertEquals("POST", req.method)
        assertTrue(req.url.contains("metaso.cn/api/v1/search"))
        assertEquals("Bearer SEC", req.headers["Authorization"])
        assertTrue(req.body!!.contains("\"q\":\"大模型\""))
        assertTrue(req.body!!.contains("\"size\":10"))
    }

    @Test
    fun `tavily 请求是 POST + Bearer + query 字段`() {
        val req = tavily("SEC").buildRequest("llm", 10)
        assertEquals("POST", req.method)
        assertTrue(req.url.contains("api.tavily.com/search"))
        assertEquals("Bearer SEC", req.headers["Authorization"])
        assertTrue(req.body!!.contains("\"query\":\"llm\""))
        assertTrue(req.body!!.contains("\"max_results\":10"))
    }

    @Test
    fun `brave 请求是 GET + X-Subscription-Token（不是 Bearer）`() {
        val req = brave("SEC").buildRequest("drug discovery", 10)
        assertEquals("GET", req.method)
        assertTrue(req.url.contains("api.search.brave.com/res/v1/web/search"))
        // 关键词被 URL 编码，且带 count
        assertTrue(req.url.contains("q="))
        assertTrue(req.url.contains("count=10"))
        // 关键 gotcha：Brave 用 X-Subscription-Token 而不是 Bearer
        assertEquals("SEC", req.headers["X-Subscription-Token"])
        assertFalse("Brave 不应出现 Bearer", req.headers.containsKey("Authorization"))
        assertEquals(null, req.body)
    }

    // ------------------------------------------------------------ recall 链路（不联网）

    @Test
    fun `空 key 时 recall 不发起任何网络请求`() = runBlocking {
        val http = FakeKeyedHttp()
        val channel = PerplexityChannel("", http)
        val result = channel.recall(DUMMY_HTTP_GET, RecallQuery("llm", emptyList(), 5))
        assertEquals(emptyList<Article>(), result)
        assertEquals("空 key 不应发请求", emptyList<KeyedRequest>(), http.requested)
    }

    @Test
    fun `空关键词时 recall 直接返回空表`() = runBlocking {
        val http = FakeKeyedHttp()
        val channel = TavilyChannel("KEY", http)
        val result = channel.recall(DUMMY_HTTP_GET, RecallQuery("   ", emptyList(), 5))
        assertEquals(emptyList<Article>(), result)
        assertEquals(emptyList<KeyedRequest>(), http.requested)
    }

    @Test
    fun `有 key 但响应为空时 recall 收成空表不抛`() = runBlocking {
        // KeyedHttpNoop 返回 ""，parseResults("") 应得到空表，整条链路不抛异常
        val channel = BraveChannel("KEY", KeyedHttpNoop)
        val result = channel.recall(DUMMY_HTTP_GET, RecallQuery("llm", emptyList(), 5))
        assertEquals(emptyList<Article>(), result)
    }

    @Test
    fun `recall 走 keyedHttp 而非 HttpGet 参数`() = runBlocking {
        // buildRequest 把关键词送进请求；用 FakeKeyedHttp 验证真的走了 keyedHttp
        val http = FakeKeyedHttp()
        val channel = MetasoChannel("KEY", http)
        channel.recall(DUMMY_HTTP_GET, RecallQuery("大模型", emptyList(), 5))
        assertEquals(1, http.requested.size)
        assertTrue(http.requested.single().url.contains("metaso.cn"))
    }
}
