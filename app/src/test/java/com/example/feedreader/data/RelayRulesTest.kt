package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * relay 地址规则。
 *
 * 要紧的是那组「重基」用例：harvest 导出的 OPML 里写的是 `http://127.0.0.1:8099/…`，
 * 手机照原样用就是死链 —— 这条链路一旦错，表现是「同步成功但每条源都拉不动」，
 * 界面上看不出任何和 relay 相关的线索，所以必须在这里钉死。
 */
class RelayRulesTest {

    // ---------------------------------------------------------------- normalizeBase

    @Test
    fun `只填 IP 时补上 http 和默认端口`() {
        assertEquals("http://192.168.1.20:8099", RelayRules.normalizeBase("192.168.1.20"))
        assertEquals("http://192.168.1.20:8099", RelayRules.normalizeBase("  192.168.1.20  "))
        assertEquals("http://localhost:8099", RelayRules.normalizeBase("localhost"))
    }

    @Test
    fun `接受从浏览器地址栏直接复制的那串`() {
        val expected = "http://192.168.1.20:8099"
        assertEquals(expected, RelayRules.normalizeBase("192.168.1.20:8099"))
        assertEquals(expected, RelayRules.normalizeBase("http://192.168.1.20:8099"))
        assertEquals(expected, RelayRules.normalizeBase("http://192.168.1.20:8099/"))
        assertEquals(expected, RelayRules.normalizeBase("http://192.168.1.20:8099/feeds.opml"))
        // 主机名同理：mDNS 名是局域网里第二常见的写法
        assertEquals("http://relay-host:8099", RelayRules.normalizeBase("relay-host"))
    }

    @Test
    fun `自己带了协议和端口就照搬`() {
        assertEquals("https://feed.example.com:8443", RelayRules.normalizeBase("https://feed.example.com:8443/x"))
        assertEquals("http://192.168.1.20:9000", RelayRules.normalizeBase("http://192.168.1.20:9000"))
    }

    @Test
    fun `方括号写法的 IPv6 能过`() {
        assertEquals("http://[::1]:8099", RelayRules.normalizeBase("[::1]"))
        assertEquals("http://[::1]:9000", RelayRules.normalizeBase("http://[::1]:9000/feeds.opml"))
    }

    @Test
    fun `不像地址的一律拒绝`() {
        assertNull(RelayRules.normalizeBase(""))
        assertNull(RelayRules.normalizeBase("   "))
        assertNull(RelayRules.normalizeBase("not a host"))
        assertNull(RelayRules.normalizeBase("a"))
        assertNull(RelayRules.normalizeBase("192.168.1.20:99999"))
        assertNull(RelayRules.normalizeBase("192.168.1.20:abc"))
        assertNull(RelayRules.normalizeBase("ftp://192.168.1.20"))
        assertNull(RelayRules.normalizeBase("[::1"))
        // 不带方括号的 IPv6 没法和「host:port」区分，只能拒 —— 界面会告诉用户怎么改
        assertNull(RelayRules.normalizeBase("fe80::1"))
    }

    @Test
    fun `清单地址挂在基地址下面`() {
        assertEquals(
            "http://192.168.1.20:8099/feeds.opml",
            RelayRules.opmlUrl("http://192.168.1.20:8099"),
        )
    }

    // -------------------------------------------------------------------- rebaseUrl

    @Test
    fun `回环地址换成本机可达的基地址`() {
        val base = "http://192.168.1.20:8099"
        assertEquals("$base/zhihu-example.xml", RelayRules.rebaseUrl("http://127.0.0.1:8099/zhihu-example.xml", base))
        assertEquals("$base/zhihu.xml", RelayRules.rebaseUrl("http://localhost:8099/zhihu.xml", base))
        assertEquals("$base/zhihu.xml", RelayRules.rebaseUrl("http://0.0.0.0:8099/zhihu.xml", base))
        assertEquals("$base/x.xml", RelayRules.rebaseUrl("http://[::1]:8099/x.xml", base))
        // 端口写错也要换：地址的身份是主机 + 文件名，不是它自己那套端口
        assertEquals("$base/x.xml", RelayRules.rebaseUrl("http://127.0.0.1:9999/x.xml", base))
    }

    @Test
    fun `相对写法补上基地址`() {
        val base = "http://192.168.1.20:8099"
        assertEquals("$base/xhs.xml", RelayRules.rebaseUrl("xhs.xml", base))
        assertEquals("$base/xhs.xml", RelayRules.rebaseUrl("/xhs.xml", base))
        assertEquals("$base/xhs.xml", RelayRules.rebaseUrl("xhs.xml", "$base/"))
        assertEquals("", RelayRules.rebaseUrl("", base))
    }

    @Test
    fun `非回环的绝对地址不动`() {
        val base = "http://192.168.1.20:8099"
        // 用户自己改了 harvest 的 base，或者 OPML 是从别处导来的 —— 那是对的，不该动
        assertEquals(
            "https://feeds.example.com/zhihu.xml",
            RelayRules.rebaseUrl("https://feeds.example.com/zhihu.xml", base),
        )
    }

    @Test
    fun `重基之后 id 跟着地址走`() {
        val base = "http://192.168.1.20:8099"
        val incoming = RelayRules.rebaseSources(
            listOf(
                FeedSource(id = "stale", name = "知乎 · 示例亥", url = "http://127.0.0.1:8099/zhihu.xml", category = "热点"),
            ),
            base,
        )

        assertEquals(1, incoming.size)
        val source = incoming.first()
        assertEquals("$base/zhihu.xml", source.url)
        assertEquals(SourceRules.customId(source.url), source.id)
        assertEquals("知乎 · 示例亥", source.name)
        assertEquals("热点", source.category)
    }

    @Test
    fun `丢掉重基后不成其为地址的项`() {
        val incoming = listOf(
            FeedSource(id = "a", name = "坏", url = "", category = ""),
            FeedSource(id = "b", name = "好", url = "http://127.0.0.1:8099/ok.xml", category = ""),
        )
        val rebased = RelayRules.rebaseSources(incoming, "http://192.168.1.20:8099")

        assertEquals(1, rebased.size)
        assertEquals("http://192.168.1.20:8099/ok.xml", rebased.first().url)
        assertEquals("好", rebased.first().name)
    }

    // -------------------------------------------------------------------- endpoint

    @Test
    fun `文件名是跨地址的身份`() {
        assertEquals("zhihu.xml", RelayRules.endpointOf("http://127.0.0.1:8099/zhihu.xml"))
        assertEquals("zhihu.xml", RelayRules.endpointOf("http://192.168.1.20:8099/zhihu.xml"))
        assertEquals("zhihu.xml", RelayRules.endpointOf("http://h:8099/zhihu.xml?t=1"))
        assertNull(RelayRules.endpointOf("http://192.168.1.20:8099"))
        assertNull(RelayRules.endpointOf("http://192.168.1.20:8099/"))
    }

    // ------------------------------------------------- 真实导出样本（端到端一条）

    /** harvest 的 `out/feeds.opml` 就是这个形状（连标题文案都一样）。 */
    private val harvestOpml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>crawlbase harvest</title></head>
          <body>
            <outline type="rss" text="知乎 · 示例亥" title="知乎 · 示例亥" xmlUrl="http://127.0.0.1:8099/zhihu-sample-k.xml" htmlUrl="https://www.zhihu.com/people/sample-k"/>
            <outline type="rss" text="知乎 · 示例A" title="知乎 · 示例A" xmlUrl="http://127.0.0.1:8099/zhihu-sample-h.xml" htmlUrl="https://www.zhihu.com/people/sample-h"/>
          </body>
        </opml>
    """.trimIndent()

    @Test
    fun `解析 harvest 的清单并整份落到 PC 地址上`() {
        val base = RelayRules.normalizeBase("192.168.1.20")!!
        val parsed = OpmlParser { KXmlParser() }.parse(harvestOpml)
        val sources = RelayRules.rebaseSources(parsed, base)

        assertEquals(
            listOf(
                "http://192.168.1.20:8099/zhihu-sample-k.xml",
                "http://192.168.1.20:8099/zhihu-sample-h.xml",
            ),
            sources.map { it.url },
        )
        assertEquals(listOf("知乎 · 示例亥", "知乎 · 示例A"), sources.map { it.name })
        // 没有目录层，所以落到兜底分类；这条不是 relay 的规则，只是记下真实行为
        assertEquals(listOf(SourceRules.FALLBACK_CATEGORY, SourceRules.FALLBACK_CATEGORY), sources.map { it.category })
    }
}
