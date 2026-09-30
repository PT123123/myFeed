package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `myfeed://` 深链解析。
 *
 * 这些字符串全部来自另一台机器生成的二维码，所以「不认识的就不动」和「认识的必须解析对」
 * 同等重要：解析错的表现是同步到一个错地址、或者把用户已有的订阅搬家，而界面只会显示
 * 「同步失败」，看不出是解析阶段出的问题。
 */
class DeepLinkTest {

    // ---------------------------------------------------------------- relay-sync

    @Test
    fun `relay 深链带完整编码地址`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099"),
            DeepLink.parse("myfeed://relay-sync?base=http%3A%2F%2F192.168.1.20%3A8099"),
        )
    }

    @Test
    fun `relay 深链也接受没编码的地址`() {
        // 编码是 go.html 用 encodeURIComponent 做的，但用户手敲/别的生成器未必编码，
        // 未编码时值里本来就只有一个 ':'，直接原样交出去即可（normalizeBase 自己会判）
        assertEquals(
            DeepLink.RelaySync("192.168.1.20:8099"),
            DeepLink.parse("myfeed://relay-sync?base=192.168.1.20:8099"),
        )
    }

    @Test
    fun `host 与协议的大小写不影响`() {
        assertEquals(
            DeepLink.RelaySync("http://relay-host:8099"),
            DeepLink.parse("MYFEED://Relay-Sync?base=http%3A%2F%2Frelay-host%3A8099"),
        )
    }

    /**
     * 配对码（电脑 `serve --allow-commands` 的 /pair 页）多带一个 `tok`。
     *
     * 左边那串是 2026-09-28 从真页面上抓下来的字节。base 必须保持编码状态：
     * 深链是按 `&` 切查询串的，`base=http://…` 不编码会把这条链腰斩成两段，
     * 表现是「扫了没反应」而不是「扫错」。
     */
    @Test
    fun `配对码带着 token`() {
        assertEquals(
            DeepLink.RelaySync("http://192.168.1.20:8099", "pair-token-example"),
            DeepLink.parse("myfeed://relay-sync?base=http%3A%2F%2F192.168.1.20%3A8099&tok=pair-token-example"),
        )
    }

    /** 参数顺序不该有意义：电脑上换个写法、或中间有人重排查询串，都该扫得出同一件事。 */
    @Test
    fun `tok 写在 base 前面也一样`() {
        assertEquals(
            DeepLink.RelaySync("http://relay-host:8099", "abc-123_XY"),
            DeepLink.parse("myfeed://relay-sync?tok=abc-123_XY&base=http%3A%2F%2Frelay-host%3A8099"),
        )
    }

    /**
     * 认不出的 token 当没配对，但**订阅那部分照收**。
     *
     * 这是一张外部二维码：太长、带 `<`、带 `..`、百分号半截的都可能。把整条链判死等于
     * 因为一把可疑的钥匙而拒掉一整车订阅，用户看到的是「扫不出」，而真正危险的其实没发生。
     */
    @Test
    fun `可疑的 token 只丢掉配对这一半`() {
        val base = "myfeed://relay-sync?base=http%3A%2F%2Frelay-host%3A8099&tok="

        assertEquals(
            "空串就是不配对",
            DeepLink.RelaySync("http://relay-host:8099", ""),
            DeepLink.parse(base),
        )
        assertEquals(
            "字符集不对（secrets.token_urlsafe 不会产出这些）",
            DeepLink.RelaySync("http://relay-host:8099", ""),
            DeepLink.parse(base + "../.."),
        )
        assertEquals(
            "超长就当不是钥匙",
            DeepLink.RelaySync("http://relay-host:8099", ""),
            DeepLink.parse(base + "a".repeat(65)),
        )
        assertEquals(
            "刚好 64 位还是钥匙",
            DeepLink.RelaySync("http://relay-host:8099", "a".repeat(64)),
            DeepLink.parse(base + "a".repeat(64)),
        )
        assertEquals(
            "解码后带控制字符",
            DeepLink.RelaySync("http://relay-host:8099", ""),
            DeepLink.parse(base + "a%00b"),
        )
    }

    // ---------------------------------------------------------------- subscribe

    @Test
    fun `订阅深链拆出地址与中文标题`() {
        val link = DeepLink.parse(
            "myfeed://subscribe?url=http%3A%2F%2F192.168.1.20%3A8099%2Fzhihu-demo-user.xml" +
                "&title=%E7%9F%A5%E4%B9%8E%20%C2%B7%20%E7%A4%BA%E4%BE%8B%E7%94%A8%E6%88%B7",
        )
        assertEquals(
            DeepLink.Subscribe("http://192.168.1.20:8099/zhihu-demo-user.xml", "知乎 · 示例用户"),
            link,
        )
    }

    @Test
    fun `没有 title 时是空串而不是 null`() {
        // addSource 用空串走「退回探测到的频道标题」那条既有逻辑
        assertEquals(
            DeepLink.Subscribe("http://192.168.1.20:8099/a.xml", ""),
            DeepLink.parse("myfeed://subscribe?url=http%3A%2F%2F192.168.1.20%3A8099%2Fa.xml"),
        )
    }

    @Test
    fun `host 后面带斜杠也认`() {
        assertEquals(
            DeepLink.RelaySync("http://relay-host:8099"),
            DeepLink.parse("myfeed://relay-sync/?base=http%3A%2F%2Frelay-host%3A8099"),
        )
    }

    // ---------------------------------------------------------------- 不认识的形状

    @Test
    fun `不是 myfeed 协议的一律忽略`() {
        assertNull(DeepLink.parse("http://192.168.1.20:8099/feeds.opml"))
        assertNull(DeepLink.parse("myfeeds://relay-sync?base=x"))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse(null))
    }

    @Test
    fun `未知 host 不猜`() {
        assertNull(DeepLink.parse("myfeed://unsubscribe?url=http%3A%2F%2Fx"))
        assertNull(DeepLink.parse("myfeed://"))
    }

    @Test
    fun `缺了关键参数不猜`() {
        assertNull(DeepLink.parse("myfeed://relay-sync"))
        assertNull(DeepLink.parse("myfeed://relay-sync?base="))
        assertNull(DeepLink.parse("myfeed://subscribe?title=%E5%85%89%E6%9C%89%E6%A0%87%E9%A2%98"))
    }

    @Test
    fun `半截百分号不会让整条深链炸掉`() {
        // 解码不了就原样交出去，不做二次猜测：后面 normalizeBase 会判它不是地址，
        // 弹窗里显示的仍是二维码里那串，用户看得出差哪个字符。
        assertEquals(
            DeepLink.RelaySync("http%3A%2F%2F192.168.1.%"),
            DeepLink.parse("myfeed://relay-sync?base=http%3A%2F%2F192.168.1.%"),
        )
    }
}
