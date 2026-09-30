package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败横幅的聚合。只有纯函数，不碰网络。
 *
 * 这块之所以值得单独钉，是因为它的失败模式是「刷屏」而不是「报错」：relay 那台 PC
 * 一关机，21 行一样的「连接超时」把真正该看到的信号埋掉，用户反而以为是一堆源坏了。
 */
class FeedRepositoryTest {

    private fun row(name: String, url: String, message: String) = Triple(name, url, message)

    @Test
    fun `同一台主机的同一种失败收成一行`() {
        val rows = (1..21).map { row("知乎 · 源$it", "http://192.168.1.20:8099/zhihu-$it.xml", "连接超时") }

        val failures = FeedRepository.collapseFailures(rows)

        assertEquals(1, failures.size)
        assertEquals("知乎 · 源1 等 21 个源（192.168.1.20:8099）", failures.single().source)
        assertEquals("连接超时", failures.single().message)
    }

    /** 对照：原因一样但主机不同，绝不能合并 —— 那会把「PC 没开机」和「没开 VPN」混成一条。 */
    @Test
    fun `不同主机的同类失败各留一行`() {
        val failures = FeedRepository.collapseFailures(
            listOf(
                row("知乎 · a", "http://192.168.1.20:8099/a.xml", "连接超时"),
                row("OpenAI Blog", "https://openai.com/blog/rss.xml", "连接超时"),
            ),
        )

        assertEquals(2, failures.size)
        assertEquals(listOf("知乎 · a", "OpenAI Blog"), failures.map { it.source })
    }

    /** 同一台主机上的两种原因也不合并：一个是连不上，一个是解析坏了，处置完全不同。 */
    @Test
    fun `同一主机的不同原因各留一行`() {
        val failures = FeedRepository.collapseFailures(
            listOf(
                row("a", "http://192.168.1.20:8099/a.xml", "连接超时"),
                row("b", "http://192.168.1.20:8099/b.xml", "不是有效的 XML"),
            ),
        )

        assertEquals(2, failures.size)
    }

    @Test
    fun `单条失败保留源名，空列表不造出幽灵行`() {
        val one = FeedRepository.collapseFailures(listOf(row("Solidot", "https://www.solidot.org/index.rss", "域名解析失败")))
        assertEquals("Solidot", one.single().source)
        assertTrue(FeedRepository.collapseFailures(emptyList()).isEmpty())
    }

    /**
     * 每条失败都要带出主机。
     *
     * 横幅上「这批失败是不是 relay 那台 PC」只认这个字段 —— 源名里的括号是给人看的，
     * 拿去比对就会有一天改文案时悄悄失配（那时电脑端提示整个消失，还不报错）。
     */
    @Test
    fun `每条失败都带上聚合用的主机`() {
        val failures = FeedRepository.collapseFailures(
            listOf(
                row("知乎 · a", "http://192.168.1.20:8099/a.xml", "连接超时"),
                row("OpenAI Blog", "https://openai.com/blog/rss.xml", "连接超时"),
            ),
        )

        assertEquals(listOf("192.168.1.20:8099", "openai.com"), failures.map { it.host })
    }

    /** 端口和大小写参与主机判定：`:8099` 和 `:80` 是两台机器。 */
    @Test
    fun `端口不同视为不同主机`() {
        val failures = FeedRepository.collapseFailures(
            listOf(
                row("a", "http://PC.local:8099/a.xml", "连接超时"),
                row("b", "http://PC.local:9000/b.xml", "连接超时"),
            ),
        )

        assertEquals(2, failures.size)
    }
}
