package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * relay 静默自动同步的决策：该不该跑、删过的条目能不能偷偷回来。
 *
 * 这两条都是纯函数，所以整颗测试不需要 Context、也不需要 mock 网络。
 * 尤其删条目那条：自动同步是**每半小时自己跑**的，判错一次就会在用户眼皮底下
 * 反复加回他刚删掉的东西，而界面上什么提示都没有。
 */
class RelayAutoSyncTest {

    private val base = "http://192.168.1.20:8099"
    private val now = 1_760_000_000_000L
    private val halfHour = RelayAutoSync.MIN_INTERVAL_MS

    private fun relaySource(endpoint: String, base: String = this.base) = FeedSource(
        id = SourceRules.customId("$base/$endpoint"),
        name = endpoint,
        url = "$base/$endpoint",
        category = "知乎",
        custom = true,
    )

    @Test
    fun `间隔是个明确的半小时`() {
        assertEquals(30 * 60 * 1000L, halfHour)
    }

    @Test
    fun `开关关掉就永远不自动跑`() {
        assertFalse(RelayAutoSync.shouldRun(false, base, 0L, now))
        // 关掉之后连「从没同步过」都不该放行：那个默认值是给第一次用的，不是给绕过开关用的
        assertFalse(RelayAutoSync.shouldRun(false, base, now - halfHour * 4, now))
    }

    @Test
    fun `没配过地址时不该去撞网络`() {
        assertFalse(RelayAutoSync.shouldRun(true, "", 0L, now))
        assertFalse(RelayAutoSync.shouldRun(true, "   ", 0L, now))
    }

    @Test
    fun `从没同步过时第一次刷新就同步`() {
        assertTrue(RelayAutoSync.shouldRun(true, base, 0L, now))
    }

    @Test
    fun `半小时之内不重复拉清单`() {
        assertFalse(RelayAutoSync.shouldRun(true, base, now - halfHour + 1, now))
        // 刚好踩到边界要放行：节流是「最多半小时一次」，不是「超过半小时」
        assertTrue(RelayAutoSync.shouldRun(true, base, now - halfHour, now))
        assertTrue(RelayAutoSync.shouldRun(true, base, now - halfHour - 1, now))
    }

    /**
     * 上次尝试的时刻比现在还晚（改系统时间、NTP 回拨）时不放行。
     *
     * 这里宁可少跑一次：`now - last` 为负数说明时钟被动过，此时没有任何可靠依据
     * 判断该不该跑，而连点刷新撞一堆超时是真实的手感损失。
     */
    @Test
    fun `时钟往回拨时不趁机连跑`() {
        assertFalse(RelayAutoSync.shouldRun(true, base, now + halfHour, now))
    }

    @Test
    fun `没有删除记录时原样返回同一份清单`() {
        val incoming = listOf(relaySource("zhihu-example.xml"), relaySource("zhihu-sample-oss.xml"))
        assertSame(incoming, RelayAutoSync.skipRemoved(incoming, emptySet()))
    }

    @Test
    fun `删过的那条不会被自动加回来`() {
        val incoming = listOf(relaySource("zhihu-example.xml"), relaySource("zhihu-sample-oss.xml"))
        val kept = RelayAutoSync.skipRemoved(incoming, setOf("zhihu-example.xml"))
        assertEquals(listOf("zhihu-sample-oss.xml"), kept.map { it.name })
    }

    /**
     * PC 换 IP 之后，删除记录依然认得出是同一条。
     *
     * 这是整套身份判定的要害：harvest 那边地址跟着 DHCP 变，App 这边重基后 id 也跟着变。
     * 如果 tombstone 存整条地址，用户删一次、PC 重启一次，那条就又回来了。
     */
    @Test
    fun `换了 IP 仍然认得出是删过的那条`() {
        val moved = "http://192.168.1.21:8099"
        val incoming = listOf(relaySource("zhihu-example.xml", moved), relaySource("zhihu-sample-oss.xml", moved))
        val kept = RelayAutoSync.skipRemoved(incoming, setOf("zhihu-example.xml"))
        assertEquals(1, kept.size)
        assertTrue("留下的该是新地址那条", kept.single().url.startsWith("$moved/"))
    }

    @Test
    fun `认不出文件名的地址不误伤`() {
        // 根地址没有文件名，endpointOf 给 null —— 它不在删除名单的语义范围里，得留下
        val hostOnly = FeedSource("s", "裸主机", base, "自建", custom = true)
        assertTrue(RelayAutoSync.skipRemoved(listOf(hostOnly), setOf("zhihu-example.xml")).contains(hostOnly))
    }

    /** 端口、查询串变了也算同一条：识别只看最后那个文件名。 */
    @Test
    fun `换端口或带查询串都按文件名识别`() {
        val incoming = listOf(
            relaySource("zhihu-example.xml", "http://192.168.1.20:9000"),
            FeedSource("q", "查询串", "$base/zhihu-example.xml?ts=2", "知乎", custom = true),
            relaySource("zhihu-sample-oss.xml"),
        )
        assertEquals(
            listOf("zhihu-sample-oss.xml"),
            RelayAutoSync.skipRemoved(incoming, setOf("zhihu-example.xml")).map { it.name },
        )
    }

    /**
     * 拿 harvest 真实产出的 21 条清单走一遍：删掉一条，下一次自动同步只回来 20 条。
     *
     * 和 [RelayContractTest] 共用同一份夹具。手写两条样本只会重复自己对格式的想象，
     * 而这条链的输入是**对面机器的产物** —— 相对 xmlUrl 重基之后 id 和 endpoint 的对应关系
     * 必须在真清单上验。
     */
    @Test
    fun `真实清单删一条后自动同步回来二十条`() {
        val xml = RelayAutoSyncTest::class.java.getResourceAsStream("/relay/feeds.opml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("缺夹具 /relay/feeds.opml")
        val parsed = OpmlParser { KXmlParser() }.parse(xml, baseUrl = base)
        assertEquals(21, parsed.size)

        val dropped = parsed.first().url.substringAfterLast('/')
        val kept = RelayAutoSync.skipRemoved(parsed, setOf(dropped))

        assertEquals(20, kept.size)
        assertTrue("被删的那条不该回来", kept.none { it.url.endsWith(dropped) })
        // 其余一条都不能少：过滤只认 tombstone，不该顺手做别的决定
        assertEquals(parsed.drop(1).map { it.url }, kept.map { it.url })
    }
}
