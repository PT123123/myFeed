package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    @Test
    fun `剥掉 HTML 标签并压缩空白`() {
        assertEquals(
            "粗体 正文",
            TextCleaner.plain("<p><b>粗体</b>\n\n   正文</p>"),
        )
    }

    @Test
    fun `script 与 style 整块丢弃`() {
        val html = "<div>正文</div><script>var a = 1 < 2;</script><style>.a{color:red}</style>"
        val cleaned = TextCleaner.plain(html)
        assertEquals("正文", cleaned)
    }

    @Test
    fun `还原命名实体与数字实体`() {
        assertEquals("A & B", TextCleaner.plain("A &amp; B"))
        assertEquals("引号", TextCleaner.plain("&#24341;&#x53F7;"))
        assertEquals("5 > 3", TextCleaner.plain("5 &gt; 3"))
    }

    @Test
    fun `超出 BMP 的数字实体不会崩`() {
        assertTrue(TextCleaner.plain("emoji &#128512; ok").contains("😀"))
    }

    @Test
    fun `先剥标签再解实体 逃逸的尖括号不会被误剥`() {
        // &lt;b&gt; 解出来是字面量 <b>，不该再当标签处理
        assertEquals("<b>", TextCleaner.plain("&lt;b&gt;"))
    }

    @Test
    fun `超长文本按上限截断`() {
        val cleaned = TextCleaner.plain("字".repeat(500), maxLength = 280)
        assertEquals(281, cleaned.length) // 280 个字 + 省略号
        assertTrue(cleaned.endsWith("…"))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", TextCleaner.plain(null))
        assertEquals("", TextCleaner.plain("   "))
        assertEquals("", TextCleaner.plain("<div></div>"))
    }
}

class DateParserTest {

    @Test
    fun `RFC822 带数字偏移`() {
        assertEquals(1790243549000L, DateParser.parse("Thu, 24 Sep 2026 09:52:29 +0000"))
    }

    @Test
    fun `RFC822 带 GMT 时区不能丢`() {
        assertEquals(1790275139000L, DateParser.parse("Thu, 24 Sep 2026 18:38:59 GMT"))
    }

    @Test
    fun `尾部有额外内容时走兜底路径仍要认对时区`() {
        assertEquals(1790243549000L, DateParser.parse("Thu, 24 Sep 2026 09:52:29 +0000 (UTC)"))
    }

    @Test
    fun `ISO8601 带 Z`() {
        assertEquals(1790244900000L, DateParser.parse("2026-09-24T10:15:00Z"))
        assertEquals(1790244900000L, DateParser.parse("2026-09-24T10:15:00.000Z"))
    }

    @Test
    fun `ISO8601 带冒号形式的时区偏移`() {
        // SimpleDateFormat 的 Z 只认 +0800，所以 +08:00 需要先归一化
        assertEquals(1790216100000L, DateParser.parse("2026-09-24T10:15:00+08:00"))
    }

    @Test
    fun `只有日期也能解析`() {
        assertTrue(DateParser.parse("2026-09-24") > 0L)
        assertTrue(DateParser.parse("2026/09/24") > 0L)
    }

    @Test
    fun `认不出来的返回 0`() {
        assertEquals(0L, DateParser.parse("昨天"))
        assertEquals(0L, DateParser.parse(""))
        assertEquals(0L, DateParser.parse(null))
    }

    @Test
    fun `相对时间分档`() {
        val now = 1_800_000_000_000L
        assertEquals("刚刚", DateParser.relative(now - 30_000L, now))
        assertEquals("5 分钟前", DateParser.relative(now - 5 * 60_000L, now))
        assertEquals("3 小时前", DateParser.relative(now - 3 * 3_600_000L, now))
        assertEquals("2 天前", DateParser.relative(now - 2 * 86_400_000L, now))
        assertTrue(DateParser.relative(now - 30 * 86_400_000L, now).matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertEquals("", DateParser.relative(0L, now))
    }

    @Test
    fun `展示用日期在解析失败时回退到原始字符串`() {
        val base = Article(
            id = "a", title = "t", excerpt = "", link = "", author = "x",
            sourceId = "s", sourceName = "s", category = "c",
            publishedAt = 0L, publishedRaw = "昨天 15:00",
        )
        assertEquals("昨天 15:00", DateParser.display(base, 1_800_000_000_000L))

        val unknown = base.copy(publishedRaw = "")
        assertEquals("时间未知", DateParser.display(unknown, 1_800_000_000_000L))
    }
}
