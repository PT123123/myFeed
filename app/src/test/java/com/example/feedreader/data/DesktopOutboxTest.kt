package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「手机上读过、还没告诉电脑端」这份队列。
 *
 * 它是整条有机链条上唯一会**丢用户动作**的地方：拉回来的内容丢了能再拉，
 * 用户点过「读过」却没上报，电脑端那边就一直以为没人看，策展权重跟着错。
 * 所以幂等键、去重、老化上限这三件事都得钉住，而不是等真机联调时看日志。
 */
class DesktopOutboxTest {

    private val dir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "outbox-${System.nanoTime()}").apply { mkdirs() }
    }
    private val file get() = File(dir, "paf_outbox.bin")
    private val outbox get() = DesktopOutbox(file)

    @Test
    fun `排队与落盘之间只走同一条 id 链`() {
        assertTrue(outbox.enqueue("post-1", clientSeq = 1L, now = 1_000L))
        assertTrue(outbox.enqueue("post-2", clientSeq = 2L, now = 2_000L))

        val reloaded = DesktopOutbox(file).load()
        assertEquals(listOf("post-1", "post-2"), reloaded.map { it.postId })
        assertEquals(listOf(1L, 2L), reloaded.map { it.clientSeq })
        assertEquals(1_000L, reloaded.first().occurredAt)
        assertEquals(2, outbox.pendingCount())
        assertEquals(0, outbox.failedCount())
    }

    /**
     * 同一篇帖子重复点不重复排队。
     *
     * 但**失败过**的那条不挡新的：失败意味着服务端明确驳回过一次，用户再读一次值得再报一次，
     * 挡掉就等于这个动作永久丢失。
     */
    @Test
    fun `同篇不重复排队但失败过的可以让路`() {
        assertTrue(outbox.enqueue("post-1", 1L, 1_000L))
        assertFalse("已有待发操作时不该再排一条", outbox.enqueue("post-1", 2L, 2_000L))
        assertEquals(1, outbox.pendingCount())

        val entry = outbox.load().single()
        outbox.save(listOf(entry.copy(failed = true, error = "FORBIDDEN: 令牌不对")))
        assertTrue(outbox.enqueue("post-1", 3L, 3_000L))
        assertEquals(1, outbox.pendingCount())
        assertEquals(1, outbox.failedCount())
    }

    /** 重试必须复用同一个 opId：服务端把它当幂等键，换 id 就是让同一件事再发生一次。 */
    @Test
    fun `opId 跨进程不变`() {
        outbox.enqueue("post-1", 1L, 1_000L)
        val first = outbox.load().single().opId

        assertEquals(first, DesktopOutbox(file).load().single().opId)
    }

    /** 电脑端长期关机时队列有上限，丢最旧的（已读是单调操作，少报几条不会把状态算错）。 */
    @Test
    fun `队列超出上限时丢最旧`() {
        val many = (1..600L).map { OutboxEntry("op-$it", it, "post-$it", it * 1_000L) }
        outbox.save(many)

        val kept = DesktopOutbox(file).load()
        assertEquals(500, kept.size)
        assertEquals("op-101", kept.first().opId)
        assertEquals("op-600", kept.last().opId)
    }

    @Test
    fun `半截文件当空队列而不是崩`() {
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertTrue(outbox.load().isEmpty())
        assertEquals(0, outbox.pendingCount())
    }

    /**
     * 连续失败的退避：5 秒起、翻倍、封顶 5 分钟。
     *
     * 电脑端关机是常态而不是异常，退避失效意味着手机每 15 秒对一个已经下线的进程发一次请求。
     */
    @Test
    fun `退避按次数翻倍并封顶`() {
        assertEquals(0L, DesktopSync.backoffMs(0))
        assertEquals(5_000L, DesktopSync.backoffMs(1))
        assertEquals(10_000L, DesktopSync.backoffMs(2))
        assertEquals(20_000L, DesktopSync.backoffMs(3))
        assertEquals(5L * 60_000L, DesktopSync.backoffMs(8))
        assertEquals("封顶后不再翻倍", 5L * 60_000L, DesktopSync.backoffMs(30))
    }
}
