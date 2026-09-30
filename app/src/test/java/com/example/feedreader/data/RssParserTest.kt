package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------ 频道标题

    /**
     * 取频道标题时必须**停在第一个条目之前**。条目自己也带 `<title>`，一路扫到底会
     * 拿到第一篇文章的标题 —— 这个错误很难被发现，因为结果看着挺像回事
     * （「添加订阅源」时源的名字就变成了某篇文章的标题）。
     */
    @Test
    fun `取频道标题而不是第一篇文章的标题`() {
        val xml = """
            <rss version="2.0"><channel>
              <title>阮一峰的网络日志</title>
              <link>https://www.ruanyifeng.com/blog/</link>
              <item><title>科技爱好者周刊（第 320 期）</title><link>https://x/1</link></item>
            </channel></rss>
        """.trimIndent()

        assertEquals("阮一峰的网络日志", newParser().feedTitle(xml))
    }

    @Test
    fun `Atom 的 feed 标题也能取到`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Rust Blog</title>
              <entry><title>Announcing Rust 1.82</title><link href="https://x/1"/></entry>
            </feed>
        """.trimIndent()

        assertEquals("Rust Blog", newParser().feedTitle(xml))
    }

    /** CDATA 里的标题（Hacker News 那种写法）不能被漏掉。 */
    @Test
    fun `CDATA 里的频道标题也能取到`() {
        val xml = """
            <rss version="2.0"><channel>
              <title><![CDATA[Hacker News: Front Page]]></title>
              <item><title>某篇文章</title></item>
            </channel></rss>
        """.trimIndent()

        assertEquals("Hacker News: Front Page", newParser().feedTitle(xml))
    }

    /** 有些 feed 就是不写频道标题。这时候该老实返回 null 让调用方用域名兜底，而不是编一个。 */
    @Test
    fun `没有频道标题时返回 null`() {
        val xml = """
            <rss version="2.0"><channel>
              <item><title>只有条目</title></item>
            </channel></rss>
        """.trimIndent()

        assertNull(newParser().feedTitle(xml))
    }

    /** 纯空白的标题也算没有 —— 否则源的「名字」会是一串空格，界面上就是一行空白。 */
    @Test
    fun `频道标题是空白时返回 null`() {
        val xml = "<rss version=\"2.0\"><channel><title>   </title></channel></rss>"
        assertNull(newParser().feedTitle(xml))
    }

    /**
     * crawlbase harvest（PC 上那台 relay）产出的 RSS。
     *
     * 这段是拿 harvest 自己的渲染器 `harvest/formats/rss.py` 真跑出来的输出，不是照着
     * 文档编的 —— 跨仓库契约就该这么钉：它用的是 `dc:creator` + `content:encoded` +
     * `guid isPermaLink=false` 这套组合，pubDate 带 `+0800`。
     *
     * 最阴的一处是 `<atom:link rel="self">`：它长得像 link，指的却是**这份 feed 自己在
     * relay 上的地址**。一旦它被当成条目链接，整个源的每条文章都会指向同一个 xml 文件，
     * 点开全是同一个页面 —— 而这种错在界面上完全看不出来是解析的问题。
     */
    private val harvestRss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>知乎 · 示例亥</title>
            <link>https://www.zhihu.com/people/sample-k</link>
            <description>该用户的知乎回答更新</description>
            <language>zh-cn</language>
            <generator>crawlbase-harvest/1</generator>
            <lastBuildDate>Fri, 25 Sep 2026 16:52:24 +0800</lastBuildDate>
            <atom:link href="http://127.0.0.1:8099/zhihu-sample-k.xml" rel="self" type="application/rss+xml"/>
            <item>
              <title>如何评价 Rust 在嵌入式领域的普及？</title>
              <link>https://www.zhihu.com/question/623456789/answer/1904567890123456789</link>
              <guid isPermaLink="false">zhihu:answer/1904567890123456789</guid>
              <pubDate>Fri, 25 Sep 2026 08:12:31 +0800</pubDate>
              <dc:creator>示例亥</dc:creator>
              <description><![CDATA[<p>先说结论：<b>不要迷信</b>。C 仍然是主力。</p>]]></description>
              <content:encoded><![CDATA[<p>先说结论：<b>不要迷信</b>。C 仍然是主力。</p>]]></content:encoded>
              <enclosure url="https://picx.zhimg.com/v2-abc123_1440w.jpg" type="image/jpeg" length="0"/>
            </item>
            <item>
              <title>2026 年了，为什么我还是在写 C</title>
              <link>https://zhuanlan.zhihu.com/p/1904567890</link>
              <guid isPermaLink="false">zhihu:answer/1904567890123456790</guid>
              <pubDate>Fri, 25 Sep 2026 07:12:31 +0800</pubDate>
              <dc:creator>示例亥</dc:creator>
              <description><![CDATA[<p>上周重构了一个跑了八年的模块…</p>]]></description>
              <content:encoded><![CDATA[<p>上周重构了一个跑了八年的模块…</p>]]></content:encoded>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `吃得下 crawlbase harvest 产出的 RSS`() {
        val articles = newParser().parse(harvestRss, source)

        assertEquals(2, articles.size)
        assertEquals("知乎 · 示例亥", newParser().feedTitle(harvestRss))

        val first = articles.first()
        assertEquals("如何评价 Rust 在嵌入式领域的普及？", first.title)
        assertEquals(
            "https://www.zhihu.com/question/623456789/answer/1904567890123456789",
            first.link,
        )
        assertEquals("示例亥", first.author)
        // 去重身份取 guid，不是 link —— 同一篇回答的链接可能有带 token 的变体
        assertEquals("test:zhihu:answer/1904567890123456789", first.id)
        assertTrue("正文该取到 content:encoded", first.excerpt.contains("不要迷信"))
        assertTrue("带 +0800 的 RFC 822 时间必须解析出来", first.publishedAt > 0L)
        // 只有一段时全文和摘要是同一份文本；分段规则留给下面那条用例验
        assertEquals(first.excerpt, first.body)
        assertTrue("单段不该被拆出空行", !first.body.contains("\n"))

        val second = articles[1]
        assertNotEquals(first.id, second.id)
        assertTrue(second.excerpt.contains("跑了八年"))
    }

    /**
     * 多段回答：全文要留下分段，摘要仍旧压成一行。
     *
     * 知乎回答是 harvest 整篇写进 `content:encoded` 的，而知乎原页在应用内是登录墙 ——
     * 这份全文就是阅读页唯一的正文来源。分段被抹平的话，两千字会糊成一坨，等于没做。
     */
    @Test
    fun `content encoded 的多段全文留在 body 里`() {
        val articles = newParser().parse(
            """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel><title>知乎 · 测试</title>
                <item>
                  <title>护肝片是不是智商税？</title>
                  <link>https://www.zhihu.com/question/1/answer/2</link>
                  <guid isPermaLink="false">zhihu:answer:2</guid>
                  <content:encoded><![CDATA[<p>第一段 有 <b>粗</b> 字</p><p>第二段</p>]]></content:encoded>
                  <description><![CDATA[<p>第一段 有 <b>粗</b> 字</p>]]></description>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
            source,
        )

        val article = articles.single()
        assertEquals("第一段 有 粗 字\n\n第二段", article.body)
        assertEquals("第一段 有 粗 字 第二段", article.excerpt)
    }

    /** `description` 比 `content:encoded` 长时取长的那份 —— 各家源谁长谁短没有规矩。 */
    @Test
    fun `全文取更长的那个正文字段`() {
        val articles = newParser().parse(
            """
            <rss version="2.0"><channel><title>测试</title>
              <item>
                <title>只有 description 带全文</title>
                <link>https://example.com/p</link>
                <guid isPermaLink="false">p</guid>
                <description><![CDATA[<p>这一段很长很长</p><p>还有一段</p>]]></description>
                <content:encoded><![CDATA[<p>短</p>]]></content:encoded>
              </item>
            </channel></rss>
            """.trimIndent(),
            source,
        )

        assertEquals("这一段很长很长\n\n还有一段", articles.single().body)
    }

    /** 全文有上限：候选列表整篇常驻内存，不设上限就是几十 MB。 */
    @Test
    fun `正文超上限时截断`() {
        val articles = newParser().parse(
            """
            <rss version="2.0"><channel><title>测试</title>
              <item>
                <title>超长</title>
                <link>https://example.com/long</link>
                <guid isPermaLink="false">long</guid>
                <description>${"字".repeat(7_000)}</description>
              </item>
            </channel></rss>
            """.trimIndent(),
            source,
        )

        val body = articles.single().body
        assertEquals(6_001, body.length) // 6000 字 + 省略号
        assertTrue(body.endsWith("…"))
        assertEquals(281, articles.single().excerpt.length)
    }
}
