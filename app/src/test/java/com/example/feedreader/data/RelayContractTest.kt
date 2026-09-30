package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * 和 harvest 的跨仓库契约。
 *
 * 夹具 `/relay/feeds.opml` 是 2026-09-27 真机联调时 `harvest serve` 实际吐出来的字节
 * （21 条知乎源，`xmlUrl` 全是相对写法），不是手写样本。
 *
 * 这颗钉子本来就该有：harvest 把 `xmlUrl` 从绝对改成相对时，只有 PC 侧的测试在跟，
 * App 侧一路静默降级 —— 解析器的准入校验只认绝对地址，于是 21 条全被当成坏数据丢掉，
 * 用户扫完整包码看到的是「这份 OPML 里没有可用的订阅源」。跨仓库的格式变更必须两边
 * 各钉一颗，而且要钉在**对方的真实产物**上，自己造的样本只会重复自己的误解。
 */
class RelayContractTest {

    private val base = "http://192.168.1.20:8099"

    private val realOpml: String by lazy {
        RelayContractTest::class.java.getResourceAsStream("/relay/feeds.opml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("缺夹具 /relay/feeds.opml")
    }

    private fun parse(baseUrl: String? = base) = OpmlParser { KXmlParser() }.parse(realOpml, baseUrl = baseUrl)

    @Test
    fun `真实清单一条不落地解析出来`() {
        val sources = parse()

        assertEquals(21, sources.size)
        assertTrue(sources.all { it.url.startsWith("$base/") && it.url.endsWith(".xml") })
        assertEquals("id 由地址派生，21 条地址不该撞车", 21, sources.map { it.id }.distinct().size)
    }

    /**
     * 不传 baseUrl 时这份真清单**应该**剩多少 —— 0。
     *
     * 这条就是当时的故障现场，留着是为了挡住「顺手把 baseUrl 参数删掉」的重构：
     * 相对 `xmlUrl` 在没有基地址可补时确实进不了准入校验，那是文件导入的严格行为，
     * 不是 relay 同步的行为。
     */
    @Test
    fun `没有基地址时相对清单不落地`() {
        assertTrue("相对 xmlUrl 只能在有基地址时才成立", parse(baseUrl = null).isEmpty())
    }

    @Test
    fun `名字里的全角分隔符和大小写都不被动`() {
        val sources = parse()

        assertEquals("知乎 · 示例甲", sources.first().name)
        // 清单没有目录层，分类走兜底而不是空串（空串会让源在界面上无处安放）
        assertEquals(SourceRules.FALLBACK_CATEGORY, sources.first().category)
        // 有一条带大写的 id：路径的大小写动了，feed 就 404，而 Windows 服务不会报错
        assertTrue(sources.any { it.url.endsWith("/zhihu-Upper_Case_Gains.xml") })
    }

    /**
     * 换 IP 之后仍然认得出是同 21 条 feed。
     *
     * 同步按地址里的文件名对齐（[RelayRules.endpointOf]），所以 DHCP 重分配该表现为
     * 「同一条订阅换了地址」，而不是「21 条新订阅 + 21 条永远失败的老订阅」。
     */
    @Test
    fun `换了 IP 的清单和老清单是同一批 feed`() {
        val before = parse(base).map { RelayRules.endpointOf(it.url) ?: error("没有文件名：${it.url}") }
        val after = parse("http://192.168.1.21:8099")
            .map { RelayRules.endpointOf(it.url) ?: error("没有文件名：${it.url}") }

        assertEquals(21, before.size)
        assertEquals("文件名要能唯一认出 feed", 21, before.distinct().size)
        assertEquals(before.sorted(), after.sorted())
        assertTrue(after.all { it.endsWith(".xml") })
    }

    /** 清单里的地址直接就能拉到 feed —— 契约的另一半在 PC 侧，这里只保证不写歪。 */
    @Test
    fun `每条源都落在 base 名下的 xml 文件`() {
        parse().forEach { source ->
            val path = source.url.removePrefix("$base/")
            assertTrue("不该多出子路径或查询串：$source", path.none { it == '/' || it == '?' || it == '#' })
        }
    }

    /**
     * 命令口那边回的真 feed id，和这边从地址反推出来的对得上。
     *
     * 2026-09-28 从 `serve --allow-commands` 的 `POST /command/plan` 抓下来的原始字节。
     * 钉的是「点一条只采这一条」那条路：手机传给电脑的 id 一旦对不上，电脑回 400，
     * 用户看到的是「电脑拒绝了这条命令」，而真实原因只是一个扩展名或一段路径。
     */
    @Test
    fun `手机反推的 feed id 就是电脑那侧的 id`() {
        val planBody = """
            {"protocol": "harvest/1", "ok": true, "action": "plan",
             "data": {"plan": [
                {"feed": "zhihu-example", "platform": "zhihu", "enabled": false,
                 "would_run": false, "reason": "disabled", "last_status": "ok", "known_items": 0},
                {"feed": "zhihu-exampleone", "platform": "zhihu", "enabled": true,
                 "would_run": true, "reason": "due", "last_status": "ok", "known_items": 39},
                {"feed": "zhihu-Upper_Case_Gains", "platform": "zhihu", "enabled": true,
                 "would_run": false, "reason": "interval: 距上次成功不足，还差约 12 分钟",
                 "last_status": "ok", "known_items": 37},
                {"feed": "zhihu-sample-zhi-75-54", "platform": "zhihu", "enabled": true,
                 "would_run": true, "reason": "due", "last_status": "ok", "known_items": 30}]},
             "meta": {"version": "0.1.0"}}
        """.trimIndent()

        val rows = RelayCommands.parseReply(200, planBody).let {
            it as RelayCommands.Reply.Plan
        }.rows
        // 真清单里的地址先取文件名（`RelayRules.endpointOf`，就是界面上那一行用的同一步），
        // 再去掉 `.xml` —— 大小写、下划线都算 id 的一部分
        val fromUrls = parse().mapNotNull { RelayRules.endpointOf(it.url) }.map { RelayCommands.feedIdOf(it) }

        assertEquals(4, rows.size)
        assertEquals(listOf("zhihu-example", "zhihu-exampleone", "zhihu-Upper_Case_Gains", "zhihu-sample-zhi-75-54"),
            rows.map { it.feed })
        // 启用的那几条在清单里；`zhihu-example` 是关着的样例源，harvest 不给它出 feed 文件，
        // 所以它出现在 plan 里却不出现在 OPML 里 —— 这一半也是契约，界面上「21 条 vs 24 条」
        // 的差值就是它
        assertTrue(
            "手机反推出来的 id 必须能在真清单里找到",
            rows.drop(1).all { it.feed in fromUrls },
        )
        assertTrue("关着的样例源不该出现在清单里", "zhihu-example" !in fromUrls)
        assertEquals(21, fromUrls.size)
    }
}
