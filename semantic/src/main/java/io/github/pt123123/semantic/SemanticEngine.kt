package io.github.pt123123.semantic

import android.content.Context
import java.io.File

/**
 * **编码器**的可用状态，给设置页显示。
 *
 * 只描述「编码器加载」这一轴。「模型有没有下载到本地」是另一条独立的轴，
 * 由调用方拿 [ModelInstaller.isInstalled] / [ModelInstaller.partialBytes] 自己拼 ——
 * 两者混在一个枚举里会出现「已下载但加载失败」「未下载且未加载」这类要额外解释的中间态。
 *
 * 把失败原因暴露出来是有意为之：端侧模型有几种**静默降级**的失败方式
 * （模型没下载、native 库缺 ABI、内存不足、NNAPI 驱动崩了），不显示的话
 * 用户只会觉得「推荐怎么这么差」，而不知道语义排序根本没跑起来。
 */
sealed interface SemanticStatus {

    /** 编码器尚未加载。模型没下载、或还没人触发加载，都是这个状态。 */
    data object NotLoaded : SemanticStatus

    /** 正在读模型 + 建图 + 预热。 */
    data object Loading : SemanticStatus

    data class Ready(
        val dimension: Int,
        /** 建会话耗时（读 24MB + 建图）。 */
        val loadMs: Long,
        /** 首次推理耗时。正常是后续单条的几十倍，属于一次性的内核/内存池初始化。 */
        val warmupMs: Long,
        /** 并发编码的线程数。 */
        val workers: Int,
        /**
         * 并发编码的**实测**吞吐（毫秒/条），加载时用真实模型跑 [benchmarkSamples] 条算出来的。
         *
         * 之所以要在真机上实测而不是写个估算值：端侧性能全看这颗 SoC 的大小核调度，
         * 桌面单线程跑出来的 2.6ms 在手机上可能差一个数量级，拿它推算冷启动时间会严重误导。
         */
        val msPerItem: Double,
        /** 自检编码的条数，配合 [msPerItem] 判断这个数字可不可信。 */
        val benchmarkSamples: Int,
    ) : SemanticStatus

    data class Failed(val reason: String) : SemanticStatus
}

/**
 * 语义能力的唯一入口：**管模型文件 + 管编码器生命周期 + 发并发编码池**。
 *
 * 三件事必须由它管，不能交给调用方：
 *
 * 1. **只加载一次。** 读 24MB 模型再建图，几百毫秒起步，还要预热。
 *    每次刷新重来一遍是不可接受的。
 * 2. **失败要能兜住。** 任何原因导致加载不了，都退回纯词法排序（[NoSemanticEncoder]），
 *    功能降级但界面照常能用 —— 不能因为模型问题让整个应用不可用。
 * 3. **并发安全。** 界面可能重复触发加载（进设置页看一眼又回来、连点刷新）。
 *
 * **模型不进 APK。** 24MB 的模型改成本地按需下载，词表（109KB）才随库打包 ——
 * 词表必须和模型严格配套，而 109KB 不值得为它多一次网络往返和一次失败可能。
 */
class SemanticEngine(
    context: Context,
    private val config: SessionConfig = SessionConfig(),
    /** 用户自定义的模型地址；非空时排在内置镜像之前。 */
    private val customModelUrl: String? = null,
) : AutoCloseable {

    private val appContext: Context = context.applicationContext

    private val lock = Any()

    private var encoder: TextEncoder? = null

    private var encodePool: EncodePool? = null

    @Volatile
    private var current: SemanticStatus = SemanticStatus.NotLoaded

    /** 模型的落地路径。下载、校验、删除都围着它转。 */
    val modelFile: File = File(File(appContext.filesDir, DIR_NAME), BgeSmallZh.FILE_NAME)

    /** 当前实际使用的下载来源（自定义地址会排在最前）。 */
    val sources: List<ModelSource> = BgeSmallZh.sourcesWith(customModelUrl)

    val status: SemanticStatus get() = current

    // ------------------------------------------------------------------ 模型文件

    fun isModelInstalled(): Boolean = ModelInstaller(modelFile, sources).isInstalled()

    /** 半成品已下载的字节数（用于显示「可继续下载」）。 */
    fun partialBytes(): Long = ModelInstaller(modelFile, sources).partialBytes()

    /**
     * 下载并安装模型。**阻塞，必须在 IO 线程上跑。**
     * 成功返回模型文件；全部来源失败抛 [ModelInstallException]。
     */
    fun installModel(onState: (ModelState) -> Unit = {}): File =
        synchronized(lock) { ModelInstaller(modelFile, sources, onState).install() }

    /** 删除模型与已加载的编码器，回到「未安装」。 */
    fun removeModel() = synchronized(lock) {
        closeLocked()
        ModelInstaller(modelFile, sources).remove()
        current = SemanticStatus.NotLoaded
    }

    // ------------------------------------------------------------------ 编码器

    /**
     * 拿到可用的编码器；模型缺失或加载失败时返回 `null`。
     *
     * **在调用线程上同步加载**，所以调用方负责把它放到 IO/Default 线程
     * （不在这里切线程是为了让单测不需要协程）。
     */
    fun getOrLoad(): TextEncoder? {
        encoder?.let { return it }
        synchronized(lock) {
            encoder?.let { return it }

            if (!isModelInstalled()) {
                current = SemanticStatus.NotLoaded
                return null
            }

            current = SemanticStatus.Loading
            return try {
                val started = System.currentTimeMillis()
                val created = OrtEmbedder.open(modelFile, tokenizer(), config)
                val loadMs = System.currentTimeMillis() - started
                // 预热放在这里而不是第一次真实刷新里：把一个几十倍于常规调用
                // 的尖峰从用户可见路径上挪到加载路径上。
                val warmupMs = created.warmup()
                val pool = EncodePool(created)
                // 顺手实测一遍吞吐：冷启动要多久完全取决于这个数，
                // 而它是整条链路上唯一没法靠推理估出来、必须真跑的量。
                val (msPerItem, samples) = benchmarkThrough(pool)

                encoder = created
                encodePool = pool
                current = SemanticStatus.Ready(
                    dimension = created.dimension,
                    loadMs = loadMs,
                    warmupMs = warmupMs,
                    workers = pool.workerCount,
                    msPerItem = msPerItem,
                    benchmarkSamples = samples,
                )
                created
            } catch (error: Throwable) {
                // 这里是**唯一**该捕 Throwable 的地方：native 库没打进 APK 时抛的是
                // UnsatisfiedLinkError，它继承 Error 而不是 Exception。
                // 只捕 Exception 的话界面会直接崩在加载模型这一步。
                current = SemanticStatus.Failed(error.message ?: error.javaClass.simpleName)
                null
            }
        }
    }

    /** 与当前编码器同生命周期的并发编码池；模型不可用时为 `null`。 */
    fun pool(): EncodePool? = encodePool

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        encodePool?.let { runCatching { it.close() } }
        encodePool = null
        (encoder as? AutoCloseable)?.let { runCatching { it.close() } }
        encoder = null
        current = SemanticStatus.NotLoaded
    }

    /**
     * 词表随库打包。缓存起来：解析 21128 行要十来毫秒，
     * 而它只依赖 APK 里的静态资产，没有失效的可能。
     */
    private val cachedTokenizer: BertTokenizer by lazy {
        appContext.assets.open(BgeSmallZh.VOCAB_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            BertTokenizer.fromLines(reader.readLines().asSequence())
        }
    }

    private fun tokenizer(): BertTokenizer = cachedTokenizer

    /** 跑一遍真实编码并算吞吐。返回「毫秒/条」与实际条数。 */
    private fun benchmarkThrough(pool: EncodePool): Pair<Double, Int> {
        val texts = List(BENCHMARK_SAMPLES) { BENCHMARK_TEXTS[it % BENCHMARK_TEXTS.size] }
        val started = System.nanoTime()
        pool.encode(texts)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        return if (texts.isEmpty()) 0.0 to 0 else elapsedMs / texts.size to texts.size
    }

    companion object {
        const val DIR_NAME = "semantic"

        /** 自检条数。16 条足够摊平首次调用的抖动，又不至于让加载明显变慢。 */
        const val BENCHMARK_SAMPLES = 16

        /**
         * 自检用的样本文本：长度和内容都贴着真实文章（标题 + 摘要，约 40~80 token）。
         *
         * 用**不同**的文本而不是同一句重复，是为了避免任何按输入缓存或分支预测
         * 把成本算低了 —— 这一层测的就是「一批各不相同的文本要花多久」。
         */
        private val BENCHMARK_TEXTS = listOf(
            "大模型推理优化：从量化到投机采样，把 7B 模型的延迟压到 50ms 以内",
            "Rust 异步运行时 tokio 发布新版本，任务调度器重写后吞吐提升明显",
            "前端构建工具的性能对比：Vite 冷启动为何比 Webpack 快一个数量级",
            "向量数据库选型的几个坑：HNSW 参数、内存放大与召回率的取舍",
            "独立开发者做产品的第一年：从零到一千个付费用户都做对了什么",
            "Transformer 位置编码的演进：从正弦函数到旋转位置编码",
            "Kotlin 协程的取消与异常传播，以及为什么结构化并发这么重要",
            "CSS 容器查询落地实践：告别媒体查询，让组件自己决定布局",
        )
    }
}
