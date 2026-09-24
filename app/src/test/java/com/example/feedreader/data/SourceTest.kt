package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自建源的校验与 id 生成。
 *
 * 这些断言盯的都是「用户粘进来的东西千奇百怪」那一类边界：地址带空格、漏了协议头、
 * 同一个 feed 末尾多个斜杠、名称里混进制表符。这类问题在界面上只会表现为
 * 「点了添加没反应」或者「加进去了但一直失败」，不打桩根本追不到。
 */
class SourceRulesTest {

    @Test
    fun `同一个地址永远得到同一个 id`() {
        assertEquals(
            "id 必须由地址稳定派生，否则重复导入会攒出一堆副本",
            SourceRules.customId("https://example.com/feed"),
            SourceRules.customId("https://example.com/feed"),
        )
    }

    @Test
    fun `首尾空白不影响 id`() {
        assertEquals(
            SourceRules.customId("https://example.com/feed"),
            SourceRules.customId("  https://example.com/feed  "),
        )
    }

    @Test
    fun `不同地址得到不同 id`() {
        assertNotEquals(
            SourceRules.customId("https://example.com/feed"),
            SourceRules.customId("https://example.com/feed2"),
        )
    }

    /**
     * id 会拼进 `Article.id`（`"${source.id}:$identity"`），也会作为元素进 SharedPreferences
     * 的字符串集合。`hashCode()` 直接 `toString(16)` 会得到带减号的串 —— 能用，但很容易在
     * 别处被当成「非法值 / 缺失」处理，所以实现里做了无符号化，这里钉住。
     */
    @Test
    fun `id 里不出现减号`() {
        val urls = listOf(
            "https://a.example/feed",
            "https://b.example/rss",
            "https://c.example/atom.xml",
            "https://d.example/feed?x=1",
            "https://e.example/feed",
        )
        urls.forEach { url ->
            val id = SourceRules.customId(url)
            assertFalse("$url 派生出的 id 带了减号：$id", id.contains('-'))
            assertTrue("id 应当带前缀免得和内置源撞：$id", id.startsWith("c"))
        }
    }

    @Test
    fun `只认带协议头的地址`() {
        assertTrue(SourceRules.looksLikeUrl("https://example.com/feed"))
        // 自建的老 feed 有不少只有 http，一刀切会把它们挡在外面
        assertTrue(SourceRules.looksLikeUrl("http://example.com/feed"))
        assertFalse(SourceRules.looksLikeUrl("example.com/feed"))
        assertFalse(SourceRules.looksLikeUrl("ftp://example.com/feed"))
        assertFalse(SourceRules.looksLikeUrl("file:///d:/subs.opml"))
        assertFalse(SourceRules.looksLikeUrl(""))
        assertFalse(SourceRules.looksLikeUrl("   "))
    }

    @Test
    fun `名称为空时拿域名兜底`() {
        assertEquals("example.com", SourceRules.fallbackName("https://example.com/feed"))
        assertEquals("example.com", SourceRules.fallbackName("https://example.com"))
        assertEquals("a.b.c", SourceRules.fallbackName("http://a.b.c/rss?x=1"))
        assertEquals("自建源", SourceRules.fallbackName("https://"))
    }

    /**
     * 制表符是持久化格式的分隔符（见 [SourceCodec]），名称里混进来就能让整列错位、
     * 读回来时地址变成名称的一部分。这不是理论风险 —— 从网页上复制标题时很容易带上。
     */
    @Test
    fun `名称里的制表符与换行被清掉`() {
        assertEquals("我的 源", SourceRules.sanitizeName("我的\t源"))
        assertEquals("我的 源", SourceRules.sanitizeName("我的\n源"))
        assertEquals("我的 源", SourceRules.sanitizeName("  我的\r\n源  "))
        // 全角空格和不断行空格也得算空白，中文输入法下这两种很常见
        assertEquals("我的 源", SourceRules.sanitizeName("我的\u3000源"))
        assertEquals("我的 源", SourceRules.sanitizeName("我的\u00a0源"))
    }

    @Test
    fun `过长的名称被截断`() {
        val out = SourceRules.sanitizeName("源".repeat(80))
        assertEquals(SourceRules.MAX_NAME_LENGTH, out.length)
    }

    @Test
    fun `地址为空时提示输入`() {
        assertEquals("请输入订阅源地址", SourceRules.addError("", emptyList()))
        assertEquals("请输入订阅源地址", SourceRules.addError("   ", emptyList()))
    }

    @Test
    fun `少了协议头时给出可操作的提示`() {
        val message = SourceRules.addError("example.com/feed", emptyList())
        assertTrue("提示里要说清该怎么改，实际是：$message", message!!.contains("http"))
    }

    /** 已经内置的源不该再被手动加一遍 —— 那就是同一份内容在首页出现两次。 */
    @Test
    fun `已经内置的地址不能再加`() {
        assertEquals(
            "这个地址已经在列表里了",
            SourceRules.addError("https://www.ruanyifeng.com/blog/atom.xml", FeedSources.DEFAULT),
        )
    }

    @Test
    fun `带空格的重复地址也能识别出来`() {
        val existing = listOf(custom("https://example.com/feed"))
        assertEquals(
            "这个地址已经在列表里了",
            SourceRules.addError("  https://example.com/feed  ", existing),
        )
    }

    @Test
    fun `自建源到上限后拒绝`() {
        val full = List(SourceRules.MAX_COUNT) { custom("https://s$it.example/feed") }
        val message = SourceRules.addError("https://new.example/feed", full)
        assertTrue(
            "应提示上限 ${SourceRules.MAX_COUNT}，实际是：$message",
            message!!.contains(SourceRules.MAX_COUNT.toString()),
        )
    }

    /** 上限只数自建源：内置那十几个不该把配额先吃掉。 */
    @Test
    fun `内置源不占自建源的配额`() {
        val existing = FeedSources.DEFAULT +
            List(SourceRules.MAX_COUNT - 1) { custom("https://s$it.example/feed") }
        assertNull(SourceRules.addError("https://new.example/feed", existing))
    }

    @Test
    fun `合法的新地址通过校验`() {
        assertNull(SourceRules.addError("https://new.example/feed", FeedSources.DEFAULT))
    }

    private fun custom(url: String) = FeedSource(
        id = SourceRules.customId(url),
        name = "自定义",
        url = url,
        category = SourceRules.FALLBACK_CATEGORY,
        custom = true,
    )
}

/**
 * 自建源的文本编解码。
 *
 * 格式是 `名称 \t 分类 \t 地址`，地址放最后一列。这套断言的存在理由只有一个：
 * **写进去的东西必须原样读得回来**，尤其是名称里带奇怪字符的时候。
 */
class SourceCodecTest {

    @Test
    fun `编解码往返不丢字段`() {
        val source = custom("https://example.com/feed", name = "我的源", category = "开发")
        val decoded = SourceCodec.decode(SourceCodec.encode(listOf(source)))

        assertEquals(1, decoded.size)
        assertEquals("我的源", decoded[0].name)
        assertEquals("开发", decoded[0].category)
        assertEquals("https://example.com/feed", decoded[0].url)
        assertEquals(source.id, decoded[0].id)
        assertTrue(decoded[0].custom)
    }

    /**
     * 这是格式设计的核心约束：名称里混进制表符不能把地址挤到别的列去。
     * 写盘前名称已被清干净，所以读回来永远是「最后一段是地址」。
     */
    @Test
    fun `名称里的制表符不会把地址挤错列`() {
        val text = SourceCodec.encode(listOf(custom("https://example.com/feed", name = "我的\t源")))
        assertEquals("每行应当只有两个分隔符", 2, text.count { it == '\t' })

        val decoded = SourceCodec.decode(text)
        assertEquals("https://example.com/feed", decoded[0].url)
        assertEquals("我的 源", decoded[0].name)
    }

    /** 地址从行尾取：即使前面的列被手改乱了，也总能把它捞回来。 */
    @Test
    fun `地址从行尾取`() {
        val decoded = SourceCodec.decode("乱\t七\t八\t糟\thttps://example.com/feed")
        assertEquals(1, decoded.size)
        assertEquals("https://example.com/feed", decoded[0].url)
    }

    /** 一行坏了不该让用户自加的源全被清空 —— 和 [InterestCodec.decode] 一个态度。 */
    @Test
    fun `坏行被跳过但不影响其它行`() {
        val text = listOf(
            "好源\t开发\thttps://a.example/feed",
            "地址不对\t开发\t这不是个地址",
            "字段不够",
            "",
            "另一个好源\t综合\thttps://b.example/rss",
        ).joinToString("\n")

        assertEquals(
            listOf("https://a.example/feed", "https://b.example/rss"),
            SourceCodec.decode(text).map { it.url },
        )
    }

    @Test
    fun `同一个地址写两遍只读出一条`() {
        val text = "A\t开发\thttps://a.example/feed\nB\t综合\thttps://a.example/feed"
        assertEquals(1, SourceCodec.decode(text).size)
    }

    @Test
    fun `名称为空时用域名补上`() {
        val decoded = SourceCodec.decode("\t\thttps://example.com/feed")
        assertEquals(1, decoded.size)
        assertEquals("example.com", decoded[0].name)
        assertEquals(SourceRules.FALLBACK_CATEGORY, decoded[0].category)
    }

    /** id 不落盘、解码时由地址派生 —— 文件被手改过也不会出现 id 和地址对不上。 */
    @Test
    fun `id 不落盘，解码时由地址派生`() {
        val decoded = SourceCodec.decode("源\t开发\thttps://a.example/feed")
        assertEquals(SourceRules.customId("https://a.example/feed"), decoded[0].id)
    }

    @Test
    fun `空文本读回空列表`() {
        assertTrue(SourceCodec.decode("").isEmpty())
        assertTrue(SourceCodec.decode("   \n  ").isEmpty())
    }

    private fun custom(url: String, name: String = "源", category: String = "自建") = FeedSource(
        id = SourceRules.customId(url),
        name = name,
        url = url,
        category = category,
        custom = true,
    )
}
