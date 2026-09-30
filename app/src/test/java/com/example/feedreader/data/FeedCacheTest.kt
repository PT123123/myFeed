package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 缓存的读写与清理策略。走 [TemporaryFolder]，不需要设备。
 */
class FeedCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private fun cache() = FeedCache(tmp.root)

    private fun article(id: String, sourceId: String = "hn", pad: Int = 0) = Article(
        id = id,
        title = "标题 $id",
        excerpt = if (pad > 0) "摘".repeat(pad) else "摘要 $id",
        link = "https://example.com/$id",
        author = "作者",
        sourceId = sourceId,
        sourceName = "Hacker News",
        category = "科技",
        publishedAt = now - 3600_000L,
        publishedRaw = "Wed, 15 Nov 2023 06:13:20 GMT",
    )

    @Test
    fun `写入后能原样读回`() {
        val cache = cache()
        val list = listOf(article("a"), article("b"))
        cache.write("hn", list)

        val loaded = cache.read("hn")
        assertNotNull(loaded)
        assertEquals("hn", loaded!!.sourceId)
        // 含中文、含 publishedRaw 的兜底字段，整条要一模一样
        assertEquals(list, loaded.articles)
    }

    @Test
    fun `读没写过的源返回 null`() {
        assertNull(cache().read("nope"))
    }

    /**
     * 正文要能过磁盘。
     *
     * 离线铺出来的那份列表和联网那轮读到的必须是同一篇正文：阅读页拿的是 `body`，
     * 缓存把它丢了，症状是「下拉刷新后能读全文，冷启动后只剩摘要」—— 一条只在重启
     * 后出现的缺陷最难想起来去查缓存格式。
     */
    @Test
    fun `缓存往返保留正文`() {
        val cache = cache()
        val list = listOf(
            article("a").copy(body = "第一段\n\n第二段\n\n第三段"),
            article("b").copy(body = ""),
        )
        cache.write("hn", list)

        assertEquals(list, cache.read("hn")!!.articles)
        assertEquals("第一段\n\n第二段\n\n第三段", cache.read("hn")!!.articles.first().body)
    }

    /**
     * 上一版格式（没有 `body` 字段）的文件必须整体作废，而且**被删掉**。
     *
     * 结构完全合法的 v1 文件最容易出事：字段错位读出来不会抛，只会把 link 当 author、
     * 把 publishedAt 当长度 —— 症状是满屏乱码文章。光返回 null 还不够，清理策略看不见
     * 读不出头部信息的文件，它会永远占着磁盘。
     */
    @Test
    fun `上一版缓存文件作废并被删掉`() {
        val cache = cache()
        val file = File(tmp.root, "hn.feed")
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x4D464348) // "MFCH"，MAGIC 是私有常量，这里按字面钉住
            out.writeInt(1) // 旧版本号
            val id = "hn".toByteArray(Charsets.UTF_8)
            out.writeInt(id.size)
            out.write(id) // sourceId
            out.writeLong(1_700_000_000_000L) // fetchedAt
            out.writeInt(0) // 0 条 —— 结构合法，专门用来骗过宽松的实现
            out.flush()
        }

        assertNull(cache.read("hn"))
        assertTrue("旧版本文件该被清掉，不该留在磁盘上", !file.exists())
    }

    @Test
    fun `空结果不会覆盖已有缓存`() {
        val cache = cache()
        cache.write("hn", listOf(article("a")))
        // 源连得上但解析出 0 条时，不该把之前的好缓存冲掉
        cache.write("hn", emptyList())

        assertEquals(1, cache.read("hn")!!.articles.size)
    }

    @Test
    fun `损坏的缓存文件会被丢弃并删掉`() {
        val cache = cache()
        cache.write("hn", listOf(article("a")))
        val file = tmp.root.listFiles()!!.first { it.name.endsWith(".feed") }
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertNull(cache.read("hn"))
        assertTrue("坏文件应被就地清掉", !file.exists())
    }

    @Test
    fun `超期缓存按天数清掉`() {
        val cache = cache()
        cache.write("stale", listOf(article("a", "stale")), now = now - 8 * day)
        cache.write("fresh", listOf(article("b", "fresh")), now = now - 1 * day)

        val result = cache.prune(retentionDays = 7, maxBytes = 0, now = now)

        assertEquals(1, result.removed)
        assertNull(cache.read("stale"))
        assertNotNull(cache.read("fresh"))
    }

    @Test
    fun `保留天数为 0 时一年前的缓存也不清`() {
        val cache = cache()
        cache.write("ancient", listOf(article("a", "ancient")), now = now - 3650L * day)

        val result = cache.prune(retentionDays = 0, maxBytes = 0, now = now)

        assertEquals(0, result.removed)
        assertNotNull(cache.read("ancient"))
    }

    @Test
    fun `超出容量上限时从最旧的开始删`() {
        val cache = cache()
        cache.write("old", listOf(article("a", "old", pad = 4000)), now = now - 1000)
        val oneSourceBytes = cache.stats().bytes

        cache.write("new", listOf(article("b", "new", pad = 4000)), now = now)
        assertEquals(2, cache.stats().entries)

        // 上限只够放一份，必须扔掉更旧的那个
        val result = cache.prune(retentionDays = 0, maxBytes = oneSourceBytes, now = now)

        assertEquals(1, result.removed)
        assertNull(cache.read("old"))
        assertNotNull(cache.read("new"))
        assertEquals(1, cache.stats().entries)
    }

    @Test
    fun `没超上限就不动缓存`() {
        val cache = cache()
        cache.write("hn", listOf(article("a")))
        val size = cache.stats().bytes

        val result = cache.prune(retentionDays = 0, maxBytes = size + 1, now = 1L)

        assertEquals(0, result.removed)
        assertNotNull(cache.read("hn"))
    }

    @Test
    fun `stats 统计文件数与字节数`() {
        val cache = cache()
        assertEquals(0, cache.stats().entries)

        cache.write("a", listOf(article("x", "a")))
        cache.write("b", listOf(article("y", "b")))

        val stats = cache.stats()
        assertEquals(2, stats.entries)
        assertTrue("字节数应大于 0", stats.bytes > 0L)
    }

    @Test
    fun `clear 清空全部缓存`() {
        val cache = cache()
        cache.write("a", listOf(article("x", "a")))
        cache.write("b", listOf(article("y", "b")))

        val cleared = cache.clear()

        assertEquals(2, cleared.removed)
        assertEquals(0, cache.stats().entries)
    }

    @Test
    fun `readAll 返回全部可读缓存`() {
        val cache = cache()
        cache.write("a", listOf(article("x", "a")))
        cache.write("b", listOf(article("y", "b")))

        assertEquals(setOf("a", "b"), cache.readAll().map { it.sourceId }.toSet())
    }

    @Test
    fun `源 id 里的路径字符不会跑出缓存目录`() {
        val cache = cache()
        val evil = "../../evil"
        cache.write(evil, listOf(article("a", evil)))

        assertEquals(1, cache.stats().entries)
        assertEquals(1, cache.read(evil)!!.articles.size)
        // 缓存目录之外不该多出任何文件
        val escaped = File(tmp.root, "../../evil.feed").canonicalFile
        assertTrue("不该在缓存目录外写出文件：$escaped", !escaped.exists())
    }
}
