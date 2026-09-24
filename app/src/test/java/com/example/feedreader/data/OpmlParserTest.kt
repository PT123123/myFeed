package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * OPML 解析。
 *
 * 样本对应真实的导出格式（Feedly / Inoreader / 各家阅读器的「导出订阅」）。
 * 用 kxml2 跑解析器，所以这些断言在 JVM 上就能验，不需要设备。
 *
 * 最要紧的一条是「多层目录」那个 —— 目录和源都是 `<outline>`，靠兄弟顺序推
 * 当前目录的做法会直接翻车，而那正是所有导出器都会产出的形态。
 */
class OpmlParserTest {

    private fun newParser() = OpmlParser { KXmlParser() }

    private fun parse(xml: String) = newParser().parse(xml)

    @Test
    fun `解析出源并带上名字`() {
        val sources = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="1.0">
              <head><title>我的订阅</title></head>
              <body>
                <outline text="阮一峰的网络日志" type="rss" xmlUrl="https://www.ruanyifeng.com/blog/atom.xml"/>
                <outline text="Solidot" type="rss" xmlUrl="https://www.solidot.org/index.rss"/>
              </body>
            </opml>
            """.trimIndent(),
        )

        assertEquals(2, sources.size)
        assertEquals("阮一峰的网络日志", sources[0].name)
        assertEquals("https://www.ruanyifeng.com/blog/atom.xml", sources[0].url)
        assertEquals("Solidot", sources[1].name)
        assertTrue("导入进来的都该是自建源", sources.all { it.custom })
    }

    @Test
    fun `目录名成为分类`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="开发">
                <outline text="Rust Blog" xmlUrl="https://blog.rust-lang.org/feed.xml"/>
              </outline>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("开发", sources[0].category)
    }

    @Test
    fun `多层目录里取最近的一层`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="外层">
                <outline text="直接挂在外层" xmlUrl="https://a.example/feed"/>
                <outline text="内层">
                  <outline text="挂在内层" xmlUrl="https://b.example/feed"/>
                </outline>
                <outline text="又回外层" xmlUrl="https://c.example/feed"/>
              </outline>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(listOf("外层", "内层", "外层"), sources.map { it.category })
    }

    @Test
    fun `没有 xmlUrl 的 outline 当目录，不进结果`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="空目录"/>
              <outline text="有源" xmlUrl="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("https://a.example/feed", sources[0].url)
    }

    /**
     * 各家导出器属性名大小写不统一（`xmlUrl` / `xmlurl` / `XMLURL` 都见过）。
     * 按字面量匹配的话，某些导出的文件会解析出 0 个源 —— 界面上只表现为
     * 「导入成功，新增 0 个」，极难追。
     */
    @Test
    fun `属性名大小写不敏感`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline TEXT="大写写法" XMLURL="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("https://a.example/feed", sources[0].url)
    }

    /** 地址里的 `&amp;` 是 XML 转义，得还原成 `&`，否则查询参数整段失效。 */
    @Test
    fun `属性里的实体被还原`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="带参数" xmlUrl="https://a.example/feed?p=1&amp;t=2"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals("https://a.example/feed?p=1&t=2", sources[0].url)
    }

    @Test
    fun `text 优先于 title`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="显示名" title="旧名" xmlUrl="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals("显示名", sources[0].name)
    }

    @Test
    fun `只有 title 时用 title`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline title="只有旧名" xmlUrl="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals("只有旧名", sources[0].name)
    }

    @Test
    fun `没有名字时用域名`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline xmlUrl="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals("a.example", sources[0].name)
    }

    @Test
    fun `同一个地址出现两次只保留一条`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="A" xmlUrl="https://a.example/feed"/>
              <outline text="B" xmlUrl="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("先出现的那个说了算", "A", sources[0].name)
    }

    @Test
    fun `非 http 地址被跳过`() {
        val sources = parse(
            """
            <opml version="2.0"><body>
              <outline text="本地文件" xmlUrl="file:///d:/subs.opml"/>
              <outline text="正常" xmlUrl="https://a.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("https://a.example/feed", sources[0].url)
    }

    @Test
    fun `超过上限时截断`() {
        val body = (0 until OpmlParser.MAX_SOURCES + 20).joinToString("\n") {
            """<outline text="源$it" xmlUrl="https://s$it.example/feed"/>"""
        }
        val sources = parse("""<opml version="2.0"><body>$body</body></opml>""")

        assertEquals(OpmlParser.MAX_SOURCES, sources.size)
    }

    @Test
    fun `兜底分类可以指定`() {
        val sources = newParser().parse(
            """<opml version="2.0"><body><outline text="A" xmlUrl="https://a.example/feed"/></body></opml>""",
            fallbackCategory = "外来",
        )

        assertEquals("外来", sources[0].category)
    }

    /**
     * 纯文本输入必须抛异常而不是安静地返回空表。
     *
     * kxml2 在这里**不抛** —— 它只报 END_DOCUMENT。这条断言就是那次踩坑的记录：
     * 一开始没做根元素校验，用户选错文件时界面报的是「文件里没有找到订阅源」，
     * 他会拿着一个完全正常的 OPML 反复重试。
     */
    @Test
    fun `不是 XML 时抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { parse("这不是 XML，是一段普通文本") }
    }

    /** 选错文件（选成了网页）同样要能和「OPML 是空的」区分开。 */
    @Test
    fun `根元素不是 opml 时抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""<?xml version="1.0"?><html><body>一个网页</body></html>""")
        }
    }

    @Test
    fun `空字符串也抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { parse("") }
    }

    @Test
    fun `body 里一条 outline 都没有时返回空列表`() {
        assertTrue(parse("""<opml version="2.0"><body></body></opml>""").isEmpty())
    }
}
