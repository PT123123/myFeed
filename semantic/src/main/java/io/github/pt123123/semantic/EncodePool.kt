package io.github.pt123123.semantic

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并发编码池 —— 冷启动提速的主要手段。
 *
 * ## 为什么是「并发」而不是「批量」或「多线程会话」
 *
 * 三种提吞吐的路线都实测过，只有这条路走通：
 *
 * - **批量（batch）**：把 N 条拼成一个 `[N,T]` 输入跑一次。实测更慢
 *   （batch=8 是 4.85ms/条 vs 单条 2.6ms），因为同批不同长度要补齐到最长，
 *   补出来的 token 是白算的。按长度分桶能缓解，但收益不稳定，不值得那份复杂度。
 * - **加大会话内线程数**：4 层的小模型铺不满流水线，算子级并行的同步开销
 *   比省下的算力多，收益接近零。
 * - **并发多个 `run`**：✅ 有效。ORT 的 `InferenceSession::Run` 本身线程安全且
 *   为并发设计，把「每条一次 run」分给 N 个线程，就是 N 倍的吞吐。
 *   前提是会话的 `intraOpThreads` 保持 1（见 [SessionConfig]），否则线程数相乘。
 *
 * 冷启动 500 条、手机单条约 10ms 的估算下：串行 5s → 4 路并发约 1.3s。
 *
 * ## 线程数为什么封顶 4
 *
 * 手机的核心是大小核混排的（常见 1+3+4），把 8 个核全占上会先撞功耗墙再撞温度墙，
 * 大核降频之后反而比 4 路更慢。而且编码是后台活，要和界面线程、网络线程抢 CPU，
 * 留出余量比榨干更重要。
 */
class EncodePool(
    private val encoder: TextEncoder,
    workers: Int = defaultWorkers(),
) : AutoCloseable {

    val workerCount: Int = workers.coerceAtLeast(1)

    private val executor: ExecutorService = Executors.newFixedThreadPool(
        workerCount,
        object : ThreadFactory {
            private val counter = AtomicInteger(1)
            override fun newThread(runnable: Runnable): Thread =
                Thread(runnable, "semantic-encode-${counter.getAndIncrement()}").apply {
                    // 守护线程：编码是尽力而为的后台活，不该拖着进程不退出
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
        },
    )

    /**
     * 并发编码一批文本。
     *
     * 返回**与输入等长**的数组，某一项编码失败（尺寸不符、native 抛异常）时为 `null`。
     * 不因为一条失败就整批失败 —— 排序侧对缺向量的条目有降级路径（走纯词法），
     * 丢掉整批反而会让这一轮刷新全是无向量的。
     *
     * @param onProgress 每完成一条回调一次，参数是已完成条数。在**工作线程**上调用。
     */
    fun encode(texts: List<String>, onProgress: (Int) -> Unit = {}): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        // 只有一条时没必要过线程池：提交/取回的开销比推理本身还大
        if (texts.size == 1 || workerCount == 1) {
            return texts.mapIndexed { index, text ->
                encodeOne(text).also { onProgress(index + 1) }
            }
        }

        val done = AtomicInteger(0)
        val futures = ArrayList<Future<FloatArray?>>(texts.size)
        for (text in texts) {
            futures += executor.submit(
                Callable {
                    // 先更新计数再取结果：异常路径也要算「完成」，否则进度条会卡住
                    try {
                        encodeOne(text)
                    } finally {
                        onProgress(done.incrementAndGet())
                    }
                },
            )
        }

        return futures.map { future -> awaitOrNull(future) }
    }

    private fun encodeOne(text: String): FloatArray? = try {
        val vector = encoder.encode(text)
        // 维度不符说明模型换了而编码器没换，这种向量在排序侧做点积会越界
        if (vector.size == encoder.dimension) vector else null
    } catch (_: Throwable) {
        // 捕 Throwable 是有意的：native 层的失败抛的是 Error 而不是 Exception
        null
    }

    private fun awaitOrNull(future: Future<FloatArray?>): FloatArray? = try {
        future.get()
    } catch (_: InterruptedException) {
        // 调用方被取消（用户切走页面/取消刷新）就别再等剩下的了
        future.cancel(true)
        Thread.currentThread().interrupt()
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: java.util.concurrent.CancellationException) {
        null
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** 并发上限。见类注释里「为什么封顶 4」。 */
        const val MAX_WORKERS = 4

        /**
         * 默认并发数：留一个核给界面和网络，其余用于编码，最多 [MAX_WORKERS]。
         * 双核机上会退化成 1（等于串行），这是对的 —— 那种设备上并发只会互相拖慢。
         */
        fun defaultWorkers(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 1).coerceIn(1, MAX_WORKERS)
        }
    }
}
