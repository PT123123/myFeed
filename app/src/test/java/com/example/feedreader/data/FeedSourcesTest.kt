package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源列表本身的一致性检查。列表会持续加源，手滑写重 id / 漏 https / 分类打错
 * 在运行时只表现为「某个源没内容」，很难查，所以在这里钉死。
 */
class FeedSourcesTest {

    @Test
    fun `源 id 不重复`() {
        val ids = FeedSources.DEFAULT.map { it.id }
        assertEquals("存在重复的 source id", ids.size, ids.distinct().size)
    }

    @Test
    fun `源地址不重复且都是 https`() {
        val urls = FeedSources.DEFAULT.map { it.url }
        assertEquals("存在重复的源地址", urls.size, urls.distinct().size)
        urls.forEach { url ->
            assertTrue("不是 https：$url", url.startsWith("https://"))
        }
    }

    @Test
    fun `名称与分类都非空`() {
        FeedSources.DEFAULT.forEach { source ->
            assertTrue("名称为空：${source.id}", source.name.isNotBlank())
            assertTrue("分类为空：${source.id}", source.category.isNotBlank())
        }
    }

    @Test
    fun `分类选项含全部且覆盖列表里出现过的分类`() {
        val categories = FeedSources.categoriesOf(
            listOf(article("科技"), article("开发"), article("科技")),
        )
        assertEquals(FeedSources.ALL, categories.first())
        assertTrue("科技" in categories)
        assertTrue("开发" in categories)
    }

    @Test
    fun `分类选项按首次出现顺序去重`() {
        // 顺序就是列表里的顺序（= 推荐分数序），抽屉和 chips 的排列依赖这一点
        val categories = FeedSources.categoriesOf(
            listOf(article("开发"), article("科技"), article("开发"), article("综合")),
        )
        assertEquals(listOf(FeedSources.ALL, "开发", "科技", "综合"), categories)
    }

    /**
     * 空列表是合法状态（还没加载出内容、或者过滤之后一条都不剩），
     * 不能崩，也不能漏掉「全部」这个唯一能选的项。
     */
    @Test
    fun `没有文章时只剩全部`() {
        assertEquals(listOf(FeedSources.ALL), FeedSources.categoriesOf(emptyList()))
    }

    /** 订阅源上的分类必须非空 —— 它会原样抄进文章，成为分类 chips 的来源。 */
    @Test
    fun `源的分类直接决定了分类选项`() {
        val categories = FeedSources.categoriesOf(
            FeedSources.DEFAULT.map { article(it.category, id = it.id) },
        )
        // 同一分类的多个源只占一个选项
        assertEquals(
            listOf(FeedSources.ALL) + FeedSources.DEFAULT.map { it.category }.distinct(),
            categories,
        )
    }
}

private fun article(category: String, id: String = category) = Article(
    id = id,
    title = "标题",
    excerpt = "",
    link = "https://example.test/$id",
    author = "",
    sourceId = "stub",
    sourceName = "stub",
    category = category,
    publishedAt = 0L,
)
