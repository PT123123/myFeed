package com.example.feedreader.ui.web

import com.example.feedreader.data.Article
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用自己渲染的那页正文。
 *
 * 这片以前没有测试：配色写在 Compose 文件的 private 函数里，测不到，于是「夜里为什么白」
 * 只能靠肉眼。抽成 [OfflinePage] 之后钉三件事 —— 暗色必须由参数决定（不交给 WebView 的
 * 夜间判定）、摘要长度的正文要说清不是整篇、订阅正文里的标记只能当文本。
 */
class OfflinePageTest {

    private fun article(link: String, body: String) = Article(
        id = "relay:1",
        title = "护肝片是不是智商税？",
        excerpt = "摘要",
        body = body,
        link = link,
        author = "作者",
        sourceId = "relay",
        sourceName = "知乎 · 测试",
        category = "电脑端采集",
        publishedAt = 0L,
    )

    @Test
    fun `暗色那份把配色写死在样式里而不是问媒体查询`() {
        val html = OfflinePage.html(article(ZHIHU, "整段回答".repeat(30)), dark = true)
        assertTrue("body 上该直接带暗底：$html", html.contains("background: #121212"))
        // 这一条是本片存在的理由：WebView 的 prefers-color-scheme 跟的是 App 声明的主题资源，
        // 交给它就等于把暗色押在 ROM 的判定上（真机因此出过整页白）。
        assertFalse(
            "不能再依赖媒体查询决定暗色",
            html.contains("prefers-color-scheme"),
        )
    }

    @Test
    fun `亮色那份还是白底`() {
        val html = OfflinePage.html(article(ZHIHU, "整段回答".repeat(30)), dark = false)
        assertTrue("body 上该直接带白底：$html", html.contains("background: #ffffff"))
    }

    @Test
    fun `摘要长度的正文在文末留一句去原页`() {
        val excerptOnly = OfflinePage.html(article(ZHIHU, "只有两三百字"), dark = true)
        assertTrue("该说这是摘要：$excerptOnly", excerptOnly.contains("以上只是订阅带回来的摘要"))

        val full = OfflinePage.html(article(ZHIHU, "长".repeat(601)), dark = true)
        assertFalse("有全文就不该再提醒摘要", full.contains("以上只是订阅带回来的摘要"))
    }

    @Test
    fun `正文里的尖括号只当文本`() {
        val html = OfflinePage.html(
            article(ZHIHU, "他说 <b>加粗</b> & 这样 <script>alert(1)</script>"),
            dark = true,
        )
        assertTrue("尖括号该被转义：$html", html.contains("&lt;script&gt;"))
        assertFalse("不能原样带进文档", html.contains("<script>"))
        assertTrue("& 该转义", html.contains("&amp;"))
    }

    @Test
    fun `没有链接也没有正文也没有摘要时说一句空话而不是空白页`() {
        // 三层内容全空才该出兜底句：helper 里 excerpt 是有字的，所以这里单独造一条。
        val empty = article("", "").copy(excerpt = "")
        val html = OfflinePage.html(empty, dark = false)
        assertTrue("该有兜底文案", html.contains("这条订阅源没有提供正文"))
    }

    @Test
    fun `正文空但有摘要时读摘要`() {
        val html = OfflinePage.html(article(ZHIHU, ""), dark = false)
        assertFalse("有摘要就不该说没内容", html.contains("这条订阅源没有提供正文"))
        assertTrue("该把摘要排成段：$html", html.contains("<p>摘要</p>"))
    }

    private companion object {
        const val ZHIHU = "https://www.zhihu.com/question/1/answer/2"
    }
}
