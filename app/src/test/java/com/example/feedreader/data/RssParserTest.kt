package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * 样本取自真实订阅源（抓下来的原文片段），不是编的。
 * 用 kxml2 的解析器跑，所以这些断言在 JVM 上就能验，不需要设备。
 */
class RssParserTest {

    private val source = FeedSource(
        id = "test",
        name = "测试源",
        url = "https://example.com/feed",
        category = "科技",
    )

    private fun newParser() = RssParser { KXmlParser() }

    /** Hacker News：标题和正文都裹在 CDATA 里，正文是 HTML。 */
    private val rssWithCdata = """
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>Hacker News: Front Page</title>
            <link>https://news.ycombinator.com/</link>
            <item>
              <title><![CDATA[Starlink ground station in Poland hit by fire in suspected arson attack]]></title>
              <description><![CDATA[
        <p>Article URL: <a href="https://notesfrompoland.com/2026/09/24/starlink/">https://notesfrompoland.com/2026/09/24/starlink/</a></p>
        <p>Points: 31</p>
        ]]></description>
              <pubDate>Thu, 24 Sep 2026 09:52:29 +0000</pubDate>
              <link>https://notesfrompoland.com/2026/09/24/starlink-ground-station-in-poland/</link>
              <dc:creator>giuliomagnifico</dc:creator>
              <guid isPermaLink="false">https://news.ycombinator.com/item?id=49828409</guid>
            </item>
            <item>
              <title><![CDATA[Nokia Design Archive (2025)]]></title>
              <description><![CDATA[<p>Points: 7</p>]]></description>
              <pubDate>Thu, 24 Sep 2026 09:40:00 +0000</pubDate>
              <link>https://nokia.example/archive</link>
              <dc:creator>someone-else</dc:creator>
              <guid>https://news.ycombinator.com/item?id=49828000</guid>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `解析 CDATA 包裹的 RSS 2_0`() {
        val articles = newParser().parse(rssWithCdata, source)

        assertEquals(2, articles.size)

        val first = articles[0]
        assertEquals("Starlink ground station in Poland hit by fire in suspected arson attack", first.title)
        assertEquals("https://notesfrompoland.com/2026/09/24/starlink-ground-station-in-poland/", first.link)
        assertEquals("giuliomagnifico", first.author)
        assertEquals("测试源", first.sourceName)
        assertEquals("科技", first.category)
        assertEquals(1790243549000L, first.publishedAt)

        // HTML 标签要被剥掉，不能把 <p> 带进列表
        assertTrue("摘要里不该残留标签: ${first.excerpt}", !first.excerpt.contains('<'))
        assertTrue("摘要应保留正文文字", first.excerpt.contains("Points: 31"))

        // 两条 id 不能撞
        assertNotEquals(first.id, articles[1].id)
    }

    /** InfoQ：正文是实体转义的 HTML，全角标点，日期是 GMT。 */
    private val rssWithEscapedHtml = """
        <rss version="2.0">
          <channel>
            <item>
              <title>云栖之后，10+阿里AI实战派将亮相QCon上海站</title>
              <link>https://www.infoq.cn/article/lh6Z5E9Zkr33bOeHQGky?utm_source=rss&amp;utm_medium=article</link>
              <description>&lt;div align=&#39;right&#39;&gt;&lt;a href=&#39;https://x&#39;&gt;点击查看原文&gt;&lt;/a&gt;&lt;/div&gt;</description>
              <author>QCon全球软件开发大会</author>
              <guid>https://www.infoq.cn/article/lh6Z5E9Zkr33bOeHQGky</guid>
              <pubDate>Thu, 24 Sep 2026 18:38:59 GMT</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `解析实体转义的描述并还原实体`() {
        val articles = newParser().parse(rssWithEscapedHtml, source)

        assertEquals(1, articles.size)
        val article = articles[0]

        assertEquals("云栖之后，10+阿里AI实战派将亮相QCon上海站", article.title)
        // &amp; 是 XML 实体，解析器该还原成 &
        assertEquals(
            "https://www.infoq.cn/article/lh6Z5E9Zkr33bOeHQGky?utm_source=rss&utm_medium=article",
            article.link,
        )
        assertEquals("点击查看原文>", article.excerpt)
        assertEquals("QCon全球软件开发大会", article.author)
        // GMT 时区必须被正确识别，否则会整体偏移
        assertEquals(1790275139000L, article.publishedAt)
    }

    /** Atom：链接在 href 属性上，且要挑 rel="alternate" 那条。 */
    private val atomFeed = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Example Atom</title>
          <entry>
            <title>Atom 文章标题</title>
            <link rel="alternate" type="text/html" href="https://example.com/atom-post"/>
            <link rel="self" href="https://example.com/self-should-be-ignored"/>
            <id>tag:example.com,2026:1</id>
            <updated>2026-09-24T10:15:00Z</updated>
            <summary>Atom 摘要内容</summary>
            <author><name>作者名</name></author>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `解析 Atom 的 link href 与嵌套 author`() {
        val articles = newParser().parse(atomFeed, source)

        assertEquals(1, articles.size)
        val article = articles[0]

        assertEquals("Atom 文章标题", article.title)
        assertEquals("https://example.com/atom-post", article.link)
        assertEquals("Atom 摘要内容", article.excerpt)
        assertEquals("作者名", article.author)
        assertEquals(1790244900000L, article.publishedAt)
    }

    @Test
    fun `超过 limit 时提前截断`() {
        val items = (1..8).joinToString("") { index ->
            "<item><title>第 $index 条</title><link>https://example.com/$index</link></item>"
        }
        val xml = "<rss version=\"2.0\"><channel>$items</channel></rss>"

        assertEquals(3, newParser().parse(xml, source, limit = 3).size)
    }

    @Test
    fun `没有标题的条目被丢弃`() {
        val xml = """
            <rss version="2.0"><channel>
              <item><link>https://example.com/no-title</link></item>
              <item><title>正常条目</title><link>https://example.com/ok</link></item>
            </channel></rss>
        """.trimIndent()

        val articles = newParser().parse(xml, source)
        assertEquals(1, articles.size)
        assertEquals("正常条目", articles[0].title)
    }

    @Test
    fun `完全没有条目时返回空列表而不是抛异常`() {
        val xml = "<rss version=\"2.0\"><channel><title>空源</title></channel></rss>"
        assertTrue(newParser().parse(xml, source).isEmpty())
    }
}
