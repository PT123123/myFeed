package com.example.feedreader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页的路由：什么时候给全文，什么时候仍然去加载原页。
 *
 * 关键是那条**对照**：全文为空时不许离线渲染。宁让用户看到知乎自己的登录墙（他还能
 * 登录一次把评论看完），也别给他一段空白 —— 空白在界面上和「这条源坏了」分不清。
 */
class ReaderRoutingTest {

    private fun article(link: String, body: String = "整段回答") = Article(
        id = "relay:1",
        title = "护肝片是不是智商税？",
        excerpt = "摘要",
        body = body,
        link = link,
        author = "作者",
        sourceId = "relay",
        sourceName = "知乎 · 测试",
        category = "自建",
        publishedAt = 0L,
    )

    @Test
    fun `知乎和小红书和 X 认作登录墙域`() {
        listOf(
            "https://www.zhihu.com/question/1/answer/2",
            "https://zhuanlan.zhihu.com/p/1904567890",
            "https://m.xiaohongshu.com/explore/abc",
            "https://x.com/foo/status/1",
            "https://twitter.com/foo/status/1",
        ).forEach { assertTrue("该认出来：$it", ReaderRouting.isLoginWalled(it)) }
    }

    /** 子域匹配必须按标签边界，否则 `notzhihu.com` 这类域会被顺手圈进去。 */
    @Test
    fun `同后缀的别人家域不算登录墙`() {
        listOf(
            "https://notzhihu.com/p/1",
            "https://zhihu.com.evil.test/answer",
            "https://myx.com/a",
            "https://m.weibo.cn/status/1", // 微博单条能匿名读，专门写过暗色配色
            "https://www.solidot.org/index.rss",
        ).forEach { assertFalse("不该误伤：$it", ReaderRouting.isLoginWalled(it)) }
    }

    @Test
    fun `登录墙域带全文时先给全文`() {
        assertTrue(ReaderRouting.readOffline(article("https://www.zhihu.com/question/1/answer/2")))
    }

    @Test
    fun `没有全文时不许离线渲染`() {
        assertFalse(
            "没有全文就别离线渲染，界面会变一段空白",
            ReaderRouting.readOffline(article("https://www.zhihu.com/question/1/answer/2", body = "")),
        )
    }

    @Test
    fun `不需要登录的站点不去抢原页`() {
        assertFalse(ReaderRouting.readOffline(article("https://www.solidot.org/story?sid=1")))
    }

    /** 源没给链接时原来就是本地兜底页，这条行为不能因为新逻辑改掉。 */
    @Test
    fun `没有链接时一律本地渲染`() {
        assertTrue(ReaderRouting.readOffline(article("", body = "")))
        assertTrue(ReaderRouting.readOffline(article("")))
    }

    /** 大小写和端口不参与判断：host 先小写再比。 */
    @Test
    fun `大写主机名照样认得`() {
        assertTrue(ReaderRouting.isLoginWalled("HTTPS://WWW.ZHIHU.COM/question/1"))
        assertTrue(ReaderRouting.isLoginWalled("https://www.zhihu.com:443/question/1"))
    }

    /** 解析不出主机名（脏数据）时按「不是登录墙」处理，交给 WebView 自己报错。 */
    @Test
    fun `畸形地址不误判`() {
        listOf("zhihu.com/question/1", "/relative/path", "data:text/html,x").forEach {
            assertFalse("没有协议头不该当登录墙：$it", ReaderRouting.isLoginWalled(it))
        }
    }

    // ------------------------------------------------------------ 摘要 vs 全文

    /**
     * relay 实测形状：200 字上下的摘要 + 要登录的原页 → 不许管它叫全文。
     *
     * 268 是 2026-09-27 从平板 `files/feed_cache` 里量出来的知乎 relay 单篇最长正文。
     */
    @Test
    fun `知乎 relay 那种短正文认作摘要`() {
        val zhihu = "https://www.zhihu.com/question/1/answer/2"
        assertTrue(ReaderRouting.isExcerptOnly(article(zhihu, body = "摘".repeat(268))))
        assertFalse(ReaderRouting.isExcerptOnly(article(zhihu, body = "全".repeat(600))))
        // 599/600 是那条线的两侧
        assertTrue(ReaderRouting.isExcerptOnly(article(zhihu, body = "全".repeat(599))))
    }

    /** 正文为空时离线页根本不会出现（readOffline 就是 false），也就无所谓摘要。 */
    @Test
    fun `没有正文时不谈摘要`() {
        assertFalse(ReaderRouting.isExcerptOnly(article("https://www.zhihu.com/a/1", body = "")))
    }

    /** 没有原页就不许提示「整篇在原页」。 */
    @Test
    fun `无链接的本地兜底页不算摘要`() {
        assertFalse(ReaderRouting.isExcerptOnly(article("", body = "一小段")))
    }

    /** 非登录墙域走 WebView 加载原页，这条判断根本不介入。 */
    @Test
    fun `普通站点不贴摘要标签`() {
        assertFalse(ReaderRouting.isExcerptOnly(article("https://sspai.com/post/1", body = "短")))
    }
}
