package io.github.pt123123.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并发编码池的行为测试。
 *
 * 这里最要紧的一条是 [任务确实被分散到多个线程上]：并发池的全部价值就在于
 * 「真的并行跑了」，而这件事**从结果上完全看不出来** —— 串行实现会得到一模一样的
 * 向量数组。所以必须直接观测工作线程，否则这个类退化成串行也不会有任何测试报警。
 */
class EncodePoolTest {

    @Test
    fun `空输入返回空`() {
        EncodePool(FakeEncoder(), workers = 2).use { pool ->
            assertEquals(emptyList<FloatArray?>(), pool.encode(emptyList()))
        }
    }

    @Test
    fun `结果与输入一一对应`() {
        EncodePool(FakeEncoder(), workers = 3).use { pool ->
            val texts = (1..20).map { "文本 $it" }
            val vectors = pool.encode(texts)

            assertEquals(texts.size, vectors.size)
            assertTrue("全部都应编码成功", vectors.all { it != null })
            assertTrue(vectors.all { Vectors.isNormalized(it!!) })
        }
    }

    @Test
    fun `任务确实被分散到多个线程上`() {
        // 每条故意慢 5ms：太快的话第一个工作线程可能在提交完成前就把队列吃空了，
        // 断言会偶发失败。5ms × 24 条 / 4 线程 ≈ 30ms，代价可以接受。
        val encoder = FakeEncoder(delayMs = 5)
        EncodePool(encoder, workers = 4).use { pool ->
            pool.encode((1..24).map { "文本 $it" })
        }

        assertTrue(
            "并发池必须真的并行；只用到 ${encoder.threadNames.size} 个线程：${encoder.threadNames}",
            encoder.threadNames.size > 1,
        )
        assertTrue("不该超出请求的线程数：${encoder.threadNames}", encoder.threadNames.size <= 4)
    }

    @Test
    fun `单条时不启额外线程`() {
        val encoder = FakeEncoder(delayMs = 5)
        EncodePool(encoder, workers = 4).use { pool ->
            val result = pool.encode(listOf("只有一条"))
            assertNotNull(result.single())
        }
        // 单条走串行快路：提交到线程池再取回的开销比推理本身还大
        assertEquals(1, encoder.threadNames.size)
    }

    @Test
    fun `某条失败只影响该条`() {
        val encoder = FakeEncoder(failOn = "会炸的")
        EncodePool(encoder, workers = 3).use { pool ->
            val vectors = pool.encode(listOf("正常一", "会炸的", "正常二"))

            assertEquals(3, vectors.size)
            assertNotNull("失败条目的邻居不受影响", vectors[0])
            assertNull(vectors[1])
            assertNotNull(vectors[2])
        }
    }

    @Test
    fun `维度不符的向量被丢弃`() {
        // 模型换了而编码器没换：这种向量在排序侧做点积会直接越界
        val encoder = FakeEncoder(dimension = 8, returnedSize = 4)
        EncodePool(encoder, workers = 1).use { pool ->
            assertNull(pool.encode(listOf("维度不对")).single())
        }
    }

    @Test
    fun `进度回调覆盖全部条目`() {
        val progress = AtomicInteger(0)
        EncodePool(FakeEncoder(), workers = 3).use { pool ->
            pool.encode((1..15).map { "文本 $it" }) { progress.incrementAndGet() }
        }
        assertEquals(15, progress.get())
    }

    @Test
    fun `失败条目的进度也计入`() {
        // 否则进度条会永远停在失败那一条上，界面看着像卡死了
        val progress = AtomicInteger(0)
        EncodePool(FakeEncoder(failOn = "会炸的"), workers = 2).use { pool ->
            pool.encode(listOf("会炸的", "会炸的", "正常")) { progress.incrementAndGet() }
        }
        assertEquals(3, progress.get())
    }

    @Test
    fun `线程数被夹到合法范围`() {
        EncodePool(FakeEncoder(), workers = 0).use { assertEquals(1, it.workerCount) }
        EncodePool(FakeEncoder(), workers = -5).use { assertEquals(1, it.workerCount) }
    }

    @Test
    fun `默认线程数留出余量且不超上限`() {
        val workers = EncodePool.defaultWorkers()
        assertTrue("至少 1 个", workers >= 1)
        assertTrue("不超过 ${EncodePool.MAX_WORKERS}，实际 $workers", workers <= EncodePool.MAX_WORKERS)
    }

    // ------------------------------------------------------------------ 辅助

    private class FakeEncoder(
        override val dimension: Int = 8,
        /** 实际返回的向量长度，用来造「模型换了」的现场。 */
        private val returnedSize: Int = dimension,
        private val delayMs: Long = 0L,
        private val failOn: String? = null,
    ) : TextEncoder {

        private val seen = ConcurrentHashMap.newKeySet<String>()

        val threadNames: Set<String> get() = seen

        override fun encode(text: String): FloatArray {
            seen += Thread.currentThread().name
            if (delayMs > 0L) Thread.sleep(delayMs)
            if (text == failOn) throw IllegalStateException("模拟编码失败：$text")
            // 单位向量：排序侧拿到的必须是已归一化的
            return FloatArray(returnedSize) { if (it == 0) 1f else 0f }
        }
    }
}
