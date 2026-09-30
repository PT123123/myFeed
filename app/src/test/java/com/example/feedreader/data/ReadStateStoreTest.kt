package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 已读记录的读写与清理。全部走 [TemporaryFolder]，不需要设备。
 *
 * 这套测试盯的是两类真实事故：**记录整体清零**（格式判坏、并发写串位），
 * 和**记录无限膨胀**（源换了 guid 之后孤儿键永远匹配不上）。
 */
class ReadStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val minute = 60_000L
    private val day = 24L * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    private fun store() = ReadStateStore(File(tmp.root, "read_state.bin"))

    @Test
    fun `没写过时读出来是空，不算损坏`() {
        assertEquals(emptyMap<String, Long>(), store().load(now))
    }

    /** control：这条不过，说明写入这条路根本没落盘，后面几条的结论都不可信。 */
    @Test
    fun `落盘的文件确实在磁盘上`() {
        val store = store()
        assertTrue(store.save(mapOf("a" to now), now))
        assertTrue(store.file.isFile)
        assertTrue("文件得有点东西", store.file.length() > 0L)
    }

    @Test
    fun `写入后能原样读回`() {
        val entries = mapOf(
            "zhihu:answer/1" to now,
            "https://example.com/a,b?x=1" to now - minute,
            "含中文的 guid" to now - day,
        )
        store().save(entries, now)
        assertEquals(entries, store().load(now))
    }

    @Test
    fun `重复保存是覆盖不是追加`() {
        val store = store()
        store.save(mapOf("old" to now), now)
        store.save(mapOf("new" to now), now)
        assertEquals(mapOf("new" to now), store.load(now))
    }

    @Test
    fun `过期的已读记录自动丢掉`() {
        val store = store()
        store.save(
            mapOf(
                "fresh" to now - 10 * day,
                "stale" to now - 200 * day,
            ),
            now,
        )
        assertEquals(setOf("fresh"), store.load(now).keys)
    }

    @Test
    fun `条数超上限时丢最旧的`() {
        val store = store()
        val entries = (1..4_500).associate { "id-$it" to now - it * minute }
        store.save(entries, now)
        val loaded = store.load(now)
        assertEquals(4_000, loaded.size)
        // 最新的 4000 条留下，最旧的 500 条被丢
        assertTrue(loaded.containsKey("id-1"))
        assertFalse(loaded.containsKey("id-4500"))
    }

    @Test
    fun `清空写空表就行`() {
        val store = store()
        store.save(mapOf("a" to now), now)
        store.save(emptyMap(), now)
        assertEquals(emptyMap<String, Long>(), store.load(now))
    }

    @Test
    fun `半截文件当没读过并且就地删掉`() {
        val file = File(tmp.root, "read_state.bin")
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(0x4D46_5244) // "MFRD"
                data.writeInt(1)
                data.writeInt(2) // 声称两条，只写一条就断
                data.writeInt(1)
                data.writeBytes("a")
                data.writeLong(now)
            }
        }
        val store = store()
        assertEquals(emptyMap<String, Long>(), store.load(now))
        // 删掉才有下次干净写入的机会；留着的话每次启动都白解一遍
        assertFalse(store.file.exists())
    }

    @Test
    fun `版本对不上时当没读过`() {
        val file = File(tmp.root, "read_state.bin")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(0x4D46_5244)
            out.writeInt(99)
            out.writeInt(0)
        }
        assertEquals(emptyMap<String, Long>(), store().load(now))
    }

    @Test
    fun `别的二进制文件不会被当成已读记录`() {
        val file = File(tmp.root, "read_state.bin")
        // FeedCache 的魔数 "MFCH"，长度前缀格式又几乎一样，靠魔数区分
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(0x4D46_4348)
            out.writeInt(2)
        }
        assertEquals(emptyMap<String, Long>(), store().load(now))
    }

    @Test
    fun `写入后不留 tmp 残留`() {
        val store = store()
        store.save(mapOf("a" to now, "b" to now - minute), now)
        val leftovers = tmp.root.listFiles { f -> f.name.endsWith(".tmp") }?.map { it.name }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `prune 只按时间或只按条数生效时另一条不误伤`() {
        val entries = (1..10).associate { "id-$it" to now - it * day }
        assertEquals(10, ReadStateStore.prune(entries, now).size)
        // 全在 90 天内 → 一条不丢
        assertEquals(
            10,
            ReadStateStore.prune((1..10).associate { "id-$it" to now - it * minute }, now).size,
        )
        // 全过期 → 全丢，和条数无关
        assertEquals(
            0,
            ReadStateStore.prune(entries, now + 200 * day).size,
        )
    }

    @Test
    fun `readAt 为 0 或负的脏记录不会挤掉正常记录`() {
        val store = store()
        store.save(mapOf("zero" to 0L, "neg" to -5L, "ok" to now), now)
        assertEquals(setOf("ok"), store.load(now).keys)
    }
}
