package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    @Test
    fun `中文与空格编码成合法地址`() {
        assertEquals(
            "https://cn.bing.com/search?q=%E5%AE%89%E5%8D%93+%E5%BC%80%E5%8F%91",
            SearchEngine.BING.urlFor("安卓 开发"),
        )
    }

    @Test
    fun `关键词里的特殊字符不会截断查询串`() {
        // & = ? 这些不转义的话会把查询参数切断
        assertEquals(
            "https://www.sogou.com/web?query=a%26b%3Dc%3Fd",
            SearchEngine.SOGOU.urlFor("a&b=c?d"),
        )
    }

    @Test
    fun `首尾空白会被去掉`() {
        assertEquals(SearchEngine.BING.urlFor("android"), SearchEngine.BING.urlFor("  android  "))
    }

    @Test
    fun `认不出的引擎 id 回退到默认`() {
        assertEquals(SearchEngine.DEFAULT, SearchEngine.of(null))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.of("不存在的引擎"))
        assertEquals(SearchEngine.SOGOU, SearchEngine.of("sogou"))
        assertEquals(SearchEngine.BING, SearchEngine.of("bing"))
    }

    @Test
    fun `每个引擎都能拼出 https 地址`() {
        SearchEngine.values().forEach { engine ->
            val url = engine.urlFor("test")
            assertTrue("${engine.label} 应为 https：$url", url.startsWith("https://"))
            assertTrue("${engine.label} 应带上关键词：$url", url.endsWith("test"))
        }
    }
}
