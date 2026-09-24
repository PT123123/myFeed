package io.github.pt123123.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = VectorStore(temp.root)

    private fun vector(seed: Int, dim: Int = 512): FloatArray =
        Vectors.l2Normalize(FloatArray(dim) { ((seed * (it + 7)) % 101).toFloat() - 50f })

    @Test
    fun roundTripsVectorsForOneSource() {
        val store = store()
        val original = mapOf(
            "article-1" to QuantizedVector.quantize(vector(1)),
            "article-2" to QuantizedVector.quantize(vector(2)),
        )
        store.write("hn", original)

        val loaded = store.read("hn")
        assertEquals(2, loaded.size)
        for ((id, expected) in original) {
            // 注意别写成 `assertNotNull(x)!!`：JUnit4 的 assertNotNull 返回 void，
            // `!!` 作用在 Unit 上得到的是 Unit，后面调 toUnitFloats() 会编译不过。
            val actual = loaded[id]
            assertNotNull("$id 应能从磁盘读回", actual)
            // 落盘后必须仍能还原成单位向量，排序侧才能用点积当余弦
            val floats = actual!!.toUnitFloats()
            assertTrue("$id 归一化后应接近单位长度", Vectors.isNormalized(floats, 1e-4f))
            val expectedFloats = expected.toUnitFloats()
            for (i in floats.indices) {
                assertEquals("$id 第 $i 维", expectedFloats[i], floats[i], 1e-6f)
            }
        }
    }

    @Test
    fun differentSourcesAreIndependent() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1))))
        store.write("sspai", mapOf("b" to QuantizedVector.quantize(vector(2)), "c" to QuantizedVector.quantize(vector(3))))

        assertEquals(setOf("a"), store.read("hn").keys)
        assertEquals(setOf("b", "c"), store.read("sspai").keys)
        assertEquals(2, store.stats().entries)
        assertEquals(3, store.stats().vectors)
    }

    @Test
    fun readingUnknownSourceReturnsEmpty() {
        assertEquals(emptyMap<String, QuantizedVector>(), store().read("nope"))
    }

    /** 空表不能写 —— 否则「源连得上但解析出 0 条」会把算好的向量冲掉，白多一次全量编码。 */
    @Test
    fun emptyWriteIsIgnored() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1))))
        store.write("hn", emptyMap())
        assertEquals(1, store.read("hn").size)
    }

    @Test
    fun rewriteReplacesOldContent() {
        val store = store()
        store.write("hn", mapOf("old" to QuantizedVector.quantize(vector(1))))
        store.write("hn", mapOf("new" to QuantizedVector.quantize(vector(2))))

        val loaded = store.read("hn")
        assertEquals(setOf("new"), loaded.keys)
        assertNull(loaded["old"])
    }

    /** 半截文件、被手改过的文件都要能自愈：删掉并返回空表，而不是抛异常炸掉刷新流程。 */
    @Test
    fun corruptedFileIsDiscardedNotThrown() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1))))

        val file = temp.root.listFiles()!!.single { it.name.endsWith(".vec") }
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

        assertEquals(emptyMap<String, QuantizedVector>(), store.read("hn"))
        assertTrue("损坏文件应被就地删除", temp.root.listFiles()!!.none { it.name.endsWith(".vec") })
    }

    @Test
    fun sourceIdWithUnsafeCharactersIsSanitized() {
        val store = store()
        // 源 id 里混进路径字符不能跑出缓存目录
        store.write("../../evil", mapOf("a" to QuantizedVector.quantize(vector(1))))

        val files = temp.root.listFiles()!!
        assertEquals(1, files.size)
        assertEquals("evil.vec", files.single().name)
        assertEquals(1, store.read("../../evil").size)
    }

    /**
     * 净化不能把不同源压成同一个文件名。
     * 「只取最后一段路径」这类实现会踩这个坑：`a/b` 和 `c/b` 会互相覆盖。
     */
    @Test
    fun sanitizingKeepsDistinctSourcesApart() {
        val store = store()
        store.write("a/b", mapOf("x" to QuantizedVector.quantize(vector(1))))
        store.write("c/b", mapOf("y" to QuantizedVector.quantize(vector(2))))

        assertEquals(2, temp.root.listFiles()!!.size)
        assertEquals(setOf("x"), store.read("a/b").keys)
        assertEquals(setOf("y"), store.read("c/b").keys)
    }

    /** 净化必须幂等：`readAll()` 拿文件名反查时会把净化过的名字再净化一遍。 */
    @Test
    fun sanitizingIsIdempotent() {
        val store = store()
        store.write(" 奇怪的 名字 ", mapOf("x" to QuantizedVector.quantize(vector(1))))

        val all = store.readAll()
        assertEquals(setOf("x"), all.keys)
    }

    @Test
    fun statsCountVectorsAndBytes() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1))))
        store.write("sspai", mapOf("b" to QuantizedVector.quantize(vector(2))))

        val stats = store.stats()
        assertEquals(2, stats.entries)
        assertEquals(2, stats.vectors)
        assertTrue("体积应大于 0", stats.bytes > 0)
        // 512 维 int8 = 516 B/条，两条源文件各自的头部开销之外应在这个量级
        assertTrue("体积异常：${stats.bytes}", stats.bytes in 1000..4000)
    }

    @Test
    fun clearRemovesEverything() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1))))
        store.write("sspai", mapOf("b" to QuantizedVector.quantize(vector(2))))

        val result = store.clear()
        assertEquals(2, result.removed)
        assertEquals(0, store.stats().entries)
        assertEquals(0L, store.stats().bytes)
    }

    @Test
    fun readAllMergesEverySource() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1))))
        store.write("sspai", mapOf("b" to QuantizedVector.quantize(vector(2))))

        val all = store.readAll()
        assertEquals(setOf("a", "b"), all.keys)
    }

    /**
     * 换模型（维度变了）之后旧缓存必须作废。
     * 不作废的话排序时 `Vectors.dot` 会拿 512 维和 64 维互点，直接数组越界。
     * 这里用「另一版模型的 store」造出真实的旧文件。
     */
    @Test
    fun cacheFromDifferentModelDimensionIsDiscarded() {
        val otherModel = VectorStore(temp.root, expectedDimension = 64)
        otherModel.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1, dim = 64))))
        assertTrue("前置条件：旧缓存应已写入", temp.root.listFiles()!!.any { it.name.endsWith(".vec") })

        assertEquals(emptyMap<String, QuantizedVector>(), store().read("hn"))
        assertTrue("维度不符的缓存应被就地删除", temp.root.listFiles()!!.none { it.name.endsWith(".vec") })
    }

    /** 维度不符的向量不落盘 —— 免得半个表进文件，格式就废了。 */
    @Test
    fun writeRejectsWrongDimension() {
        store().write("hn", mapOf("a" to QuantizedVector.quantize(vector(1, dim = 64))))
        assertEquals(0, store().stats().entries)
    }

    @Test
    fun dimensionSurvivesRoundTrip() {
        val store = store()
        store.write("hn", mapOf("a" to QuantizedVector.quantize(vector(1, dim = 512))))

        val loaded = store.read("hn")
        assertEquals(512, loaded.getValue("a").dimension)
        assertEquals(VectorStore.EXPECTED_DIMENSION, loaded.getValue("a").dimension)
    }
}
