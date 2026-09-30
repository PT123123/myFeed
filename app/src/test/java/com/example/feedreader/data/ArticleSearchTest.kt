package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页搜索的匹配规则。
 *
 * 重点盯两条容易做错的：**空查询必须不过滤**（写成 `fieldHit != null` 的话搜索框清空
 * 那一刻首页会整个变白），和**单字查询不进正文**（六百篇 × 六千字每敲一个字扫一遍，
 * 而一个字的命中基本等于全命中）。
 */
class ArticleSearchTest {

    private fun article(
        title: String = "一个标题",
        excerpt: String = "开头的 280 字摘要",
        body: String = "正文中段提到了跑了八年的老服务",
        sourceName: String = "知乎",
    ) = Article(
        id = "test:$title",
        title = title,
        excerpt = excerpt,
        body = body,
        link = "https://example.com/a",
        author = "作者",
        sourceId = "test",
        sourceName = sourceName,
        category = "科技",
        publishedAt = 1_700_000_000_000L,
        publishedRaw = "",
    )

    @Test
    fun `空查询不过滤，什么都算命中`() {
        val a = article()
        assertTrue(ArticleSearch.matches(a, ""))
        assertTrue(ArticleSearch.matches(a, "   "))
        assertNull(ArticleSearch.fieldHit(a, ""))
    }

    @Test
    fun `什么都没命中`() {
        val a = article()
        assertFalse(ArticleSearch.matches(a, "根本没有这个词"))
        assertNull(ArticleSearch.fieldHit(a, "根本没有这个词"))
    }

    @Test
    fun `命中在标题`() {
        val a = article(title = "Rust 在嵌入式领域的普及")
        assertEquals(ArticleSearch.HIT_TITLE, ArticleSearch.fieldHit(a, "嵌入式"))
        assertNull("标题命中不用解释为什么", ArticleSearch.label(ArticleSearch.HIT_TITLE))
    }

    @Test
    fun `命中在摘要`() {
        val a = article(excerpt = "别迷信重写，先看这三年")
        assertEquals(ArticleSearch.HIT_EXCERPT, ArticleSearch.fieldHit(a, "迷信"))
    }

    @Test
    fun `命中在来源名`() {
        assertEquals(ArticleSearch.HIT_SOURCE, ArticleSearch.fieldHit(article(), "知乎"))
    }

    @Test
    fun `摘要里没有、只在正文里出现的词要能搜到`() {
        val a = article()
        val hit = ArticleSearch.fieldHit(a, "跑了八年")
        assertEquals(ArticleSearch.HIT_BODY, hit)
        assertEquals("正文里提到", ArticleSearch.label(hit))
    }

    @Test
    fun `单个字不进正文`() {
        val a = article(body = "全文里有无数个「了」字了了了")
        assertNull(ArticleSearch.fieldHit(a, "了"))
        assertFalse(ArticleSearch.matches(a, "了"))
        // 两个字就进正文
        assertEquals(ArticleSearch.HIT_BODY, ArticleSearch.fieldHit(a, "无数个"))
    }

    @Test
    fun `英文大小写不敏感`() {
        val a = article(title = "Hermes 提供 7 款免费模型")
        assertEquals(ArticleSearch.HIT_TITLE, ArticleSearch.fieldHit(a, "hermes"))
    }

    @Test
    fun `查询首尾空格不影响判断`() {
        val a = article()
        assertEquals(ArticleSearch.HIT_BODY, ArticleSearch.fieldHit(a, "  跑了八年  "))
    }

    @Test
    fun `标题优先于正文：两个字段都有同一个词时只报标题`() {
        val a = article(title = "老服务的八年", body = "老服务其实没什么好写的")
        assertEquals(ArticleSearch.HIT_TITLE, ArticleSearch.fieldHit(a, "老服务"))
    }

    @Test
    fun `正文为空时不误命中`() {
        val a = article(body = "")
        assertNull(ArticleSearch.fieldHit(a, "跑了八年"))
        assertFalse(ArticleSearch.matches(a, "跑了八年"))
    }
}
