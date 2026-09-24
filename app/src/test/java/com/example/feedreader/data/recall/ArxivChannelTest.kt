package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * arXiv 通道解析用**真实响应**当样本（`/recall/arxiv_pharmacology.xml`，
 * 2026-09-25 抓的原文），不是手写的精简 Atom。
 *
 * 理由同其它通道：这类解析的失败模式是「字段名写错 / 嵌套层级记错」，全都表现为
 * **静默返回空列表**，手写样本会顺着我的理解去构造，恰好验证不了「我理解错了」。
 */
class ArxivChannelTest {

    /** 注入 JVM 版 kxml2，解析逻辑才能脱离设备验证。 */
    private val channel = ArxivChannel(createParser = { KXmlParser() })

    private fun load(name: String): String {
        val stream = ArxivChannelTest::class.java.getResourceAsStream("/recall/$name")
            ?: error("缺少 fixture：/recall/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `URL 带上查询串与条数上限`() {
        val url = channel.url("pharmacology", 15)
        assertEquals(
            "https://export.arxiv.org/api/query?search_query=all:pharmacology" +
                "&max_results=15&sortBy=submittedDate&sortOrder=descending",
            url,
        )
    }

    @Test
    fun `URL 对关键词做编码`() {
        val url = channel.url("drug discovery", 15)
        // URLEncoder 把空格编成 +（query string 里等价于空格，arXiv 认），
        // 也可能被编成 %20 —— 两个都合法，但空格绝不能原样出现。
        assertTrue(
            "查询串没被编码",
            url.contains("search_query=all:drug+discovery") ||
                url.contains("search_query=all:drug%20discovery"),
        )
        assertFalse("空格不应原样出现", url.contains("search_query=all:drug discovery"))
    }

    @Test
    fun `解析真实响应`() {
        val articles = channel.parse(load("arxiv_pharmacology.xml"))

        assertEquals(10, articles.size)
        val first = articles.first()
        assertTrue("id 要带通道前缀", first.id.startsWith("arxiv:"))
        assertTrue(first.title.isNotBlank())
        assertTrue("链接应指向论文页", first.link.startsWith("https://arxiv.org/abs/"))
        assertEquals("学术", first.category)
        assertEquals("arxiv", first.sourceId)
    }

    /**
     * arXiv 的 `<author><name>` 嵌套是这条路单独写解析器的唯一理由：
     * 若回落成「arXiv 文献」就说明作者没摘出来。
     */
    @Test
    fun `作者名从嵌套 name 里摘出来`() {
        val articles = channel.parse(load("arxiv_pharmacology.xml"))

        assertTrue("作者不应回落成通道名", articles.none { it.author == "arXiv 文献" })
        assertTrue("应有非空作者", articles.all { it.author.isNotBlank() })
        // 第一篇是 4 个作者的论文，应能 join 出来
        assertTrue("多作者应能拼接：${articles.first().author}", articles.first().author.contains(","))
    }

    /** `published` 是 ISO8601（2021-02-15T15:52:41Z），漏解析会落到 0，排序时沉底。 */
    @Test
    fun `时间戳按 ISO8601 解析`() {
        val articles = channel.parse(load("arxiv_pharmacology.xml"))
        assertTrue("不应出现 0 时间", articles.all { it.publishedAt > 0L })
        assertTrue("应解析出 2021 年的时间", articles.any { it.publishedAt > 1_600_000_000_000L })
    }

    /** 摘要取的是 `<summary>`（论文摘要），比标题长得多，是这条通道的核心价值。 */
    @Test
    fun `摘要是论文摘要而非空`() {
        val first = channel.parse(load("arxiv_pharmacology.xml")).first()
        assertTrue("摘要不应空白：${first.excerpt}", first.excerpt.length > 50)
    }

    /** 契约：畸形 / 空 / 限流返回的 HTML 都收成空表，不抛异常、不拖垮整次刷新。 */
    @Test
    fun `畸形输入返回空表而不是抛异常`() {
        val garbage = listOf(
            "",
            "   ",
            "not xml at all",
            "<html><body>403 Forbidden</body></html>",
            "<feed xmlns='http://www.w3.org/2005/Atom'><entry></entry></feed>",
        )
        garbage.forEach { assertEquals(emptyList<Article>(), channel.parse(it)) }
    }
}
