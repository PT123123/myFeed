package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫一扫结果的分类。
 *
 * 要紧的是那组 `go.html?f=` 用例：这些字符串是 crawlbase 那侧真塞进二维码的内容，
 * 分类一错，表现是「扫了没反应」或者「订阅到一条根本不存在的地址」，而界面上完全
 * 看不出是解析错了。最后那组回环/畸形 id 的用例则是安全边界 —— 二维码来自另一台机器。
 */
class ScanRulesTest {

    private fun link(raw: String?): DeepLink? =
        (ScanRules.classify(raw) as? ScanRules.Outcome.Link)?.link

    private fun rejectReason(raw: String?): String =
        (ScanRules.classify(raw) as? ScanRules.Outcome.Rejected)?.reason.orEmpty()

    // ---------------------------------------------------------------- myfeed 深链

    @Test
    fun `myfeed 整包同步深链直接收下`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("myfeed://relay-sync?base=http%3A%2F%2F192.168.1.20%3A8099"),
        )
    }

    @Test
    fun `myfeed 单源深链带着标题`() {
        assertEquals(
            DeepLink.Subscribe("http://192.168.1.20:8099/zhihu-x.xml", "张三"),
            link("myfeed://subscribe?url=http%3A%2F%2F192.168.1.20%3A8099%2Fzhihu-x.xml&title=%E5%BC%A0%E4%B8%89"),
        )
    }

    // ---------------------------------------------------------------- harvest 二维码

    @Test
    fun `harvest 真实产出的整包二维码地址`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("http://192.168.1.20:8099/go.html?f=__relay__"),
        )
    }

    @Test
    fun `harvest 真实产出的单源二维码地址`() {
        // 这一条是从 serve 出来的 go.html 里抄的原文，不是手写样例
        assertEquals(
            DeepLink.Subscribe("http://192.168.1.20:8099/zhihu-samplehorizon.xml", ""),
            link("http://192.168.1.20:8099/go.html?f=zhihu-samplehorizon"),
        )
    }

    @Test
    fun `桥接页不带 f 时按整包算`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("http://192.168.1.20:8099/go.html"),
        )
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("http://192.168.1.20:8099/go.html?f="),
        )
    }

    @Test
    fun `非默认端口跟着走`() {
        assertEquals(
            DeepLink.Subscribe("http://192.168.1.20:9000/zhihu-x.xml", ""),
            link("http://192.168.1.20:9000/go.html?f=zhihu-x"),
        )
    }

    @Test
    fun `扫到清单本身也算整包`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("http://192.168.1.20:8099/feeds.opml"),
        )
    }

    // ---------------------------------------------------------------- 兜底与拒绝

    @Test
    fun `普通 rss 地址当单源`() {
        assertEquals(
            DeepLink.Subscribe("https://feeds.acast.com/public/show/x", ""),
            link("https://feeds.acast.com/public/show/x"),
        )
    }

    @Test
    fun `回环地址要讲明白为什么连不上`() {
        val reason = rejectReason("http://127.0.0.1:8099/go.html?f=zhihu-x")
        assertTrue("实际：$reason", "回环" in reason)
        assertTrue("实际：$reason", "host auto" in reason)
        // 控制组：同一张码换个主机就必须是正常订阅，否则上面两条断言恒真
        assertNotEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("http://192.168.1.20:8099/go.html?f=zhihu-x"),
        )
    }

    @Test
    fun `畸形 feed id 不拼进地址`() {
        for (bad in listOf("../../etc/passwd", "a/b", "a?x=1", "a#b", "a b", "a\"b")) {
            assertTrue("$bad 应被拒", ScanRules.classify("http://192.168.1.20:8099/go.html?f=$bad")
                is ScanRules.Outcome.Rejected)
        }
        // 控制组：合法 id 里带点和连字符仍然要过，否则上面那串是一概拒绝
        assertEquals(
            DeepLink.Subscribe("http://192.168.1.20:8099/zhihu-53-34.1.xml", ""),
            link("http://192.168.1.20:8099/go.html?f=zhihu-53-34.1"),
        )
    }

    @Test
    fun `过长的 id 拒绝`() {
        val long = "z" + "a".repeat(ScanRules.MAX_FEED_ID)
        assertTrue(rejectReason("http://192.168.1.20:8099/go.html?f=$long").isNotBlank())
    }

    @Test
    fun `不是地址的东西一律拒`() {
        for (raw in listOf("", "   ", null, "hello world", "WIFI:S:x;;", "ftp://192.168.1.20/x")) {
            assertTrue("$raw 应被拒", ScanRules.classify(raw) is ScanRules.Outcome.Rejected)
        }
    }

    @Test
    fun `空白与大小写不影响识别`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            link("  HTTP://192.168.1.20:8099/go.html?f=__relay__ "),
        )
    }
}
