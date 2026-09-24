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
    fun `每个源的分类都能在筛选选项里找到`() {
        val categories = FeedSources.categoriesOf(FeedSources.DEFAULT)
        assertEquals(FeedSources.ALL, categories.first())
        FeedSources.DEFAULT.forEach { source ->
            assertTrue("分类 ${source.category} 未出现在筛选选项里", source.category in categories)
        }
    }

    @Test
    fun `分类选项按源的顺序去重且不含重复项`() {
        val categories = FeedSources.categoriesOf(FeedSources.DEFAULT)
        assertEquals(categories.size, categories.distinct().size)
        // 顺序 = 首次出现的顺序，抽屉里分类的排列依赖这一点
        assertEquals(
            listOf(FeedSources.ALL) + FeedSources.DEFAULT.map { it.category }.distinct(),
            categories,
        )
    }

    @Test
    fun `只看启用的源时分类跟着变少`() {
        val onlyTech = FeedSources.DEFAULT.filter { it.category == "科技" }
        val categories = FeedSources.categoriesOf(onlyTech)
        assertEquals(listOf(FeedSources.ALL, "科技"), categories)
    }
}
