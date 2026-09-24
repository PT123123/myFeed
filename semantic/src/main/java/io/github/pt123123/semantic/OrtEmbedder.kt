package io.github.pt123123.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * 会话调优参数。
 *
 * 默认值是按「小模型 + 多核手机」这个组合定的，两个反直觉的地方：
 *
 * 1. **[intraOpThreads] 默认 1。** 这是个 4 层 / 512 维的小模型，单次前向的算子根本铺不满
 *    一根流水线；算子级并行的同步开销比省下的算力还多。真正吃满多核的正确做法是
 *    **多个 run 并发**（见 [EncodePool]）—— 每个会话 1 线程，N 个会话并行。
 *    如果这里再开 N 线程，总线程数变成 N²，上下文切换会把收益吃光。
 *
 * 2. **硬件加速默认关。** NNAPI / XNNPACK 在这个模型上收益存疑，且我无法在真机上
 *    验证（不改动用户设备是项目约定）。做成开关而不是默认开启，是为了「最坏情况
 *    只是没加速」，而不是「某些机型上直接崩或算出错数」。
 */
data class SessionConfig(
    val intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS,
    /** 尝试注册 NNAPI 执行提供者（需要 Android 8.1+）。默认关。 */
    val enableNnapi: Boolean = false,
    /** 尝试注册 XNNPACK 执行提供者。默认关。 */
    val enableXnnpack: Boolean = false,
) {
    val usesHardwareAcceleration: Boolean get() = enableNnapi || enableXnnpack

    companion object {
        const val DEFAULT_INTRA_OP_THREADS = 1
    }
}

/**
 * 端侧 ONNX 推理内核（bge-small-zh-v1.5 int8，4 层 / 512 维）。
 *
 * 实测延迟（桌面单线程，手机大核再乘 2~3）：12 token 约 2.6ms、54 token 约 7.2ms。
 * 单条已经很快，所以**不做 batch**：实测 batch=8 反而变成 4.85ms/条、batch=32 是 6.92ms/条，
 * 因为同批不同长度的文本要补齐到最长，浪费的算力比省下的调度开销多。
 * 要提吞吐走 [EncodePool] 的并发路线。
 *
 * 另外一条实测结论同样重要：**编码一次就落盘缓存，不要每次刷新重编码全量**。
 * 排序本身只是点积（500 条 × 512 维 < 1ms），瓶颈全在编码。
 *
 * 线程安全：[encode] 可以从多个线程并发调用。ORT 的 `InferenceSession::Run` 本身支持并发，
 * 而 [BertTokenizer] 构造后只剩只读的 `vocab`，没有可变状态。
 */
class OrtEmbedder private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: BertTokenizer,
    private val maxLength: Int,
) : TextEncoder, AutoCloseable {

    override val dimension: Int = BgeSmallZh.DIMENSION

    private val inputNames: Set<String> = session.inputNames

    private val wantsTokenTypeIds = inputNames.contains(INPUT_TOKEN_TYPE_IDS)

    override fun encode(text: String): FloatArray {
        val ids = tokenizer.encode(text, maxLength)
        val size = ids.size

        // 注意：ONNX Runtime 的 OnnxTensor.createTensor **没有 LongBuffer 重载**
        // （Buffer 系列只提供 Float/Double/Byte）。int64 输入必须走 Object 重载传 long[][]，
        // 形状由嵌套数组推导。这个 API 差异只有读过 class 才看得出来。
        val idData = Array(1) { LongArray(size) { i -> ids[i].toLong() } }
        val maskData = Array(1) { LongArray(size) { 1L } }
        val typeData = Array(1) { LongArray(size) }

        val feeds = HashMap<String, OnnxTensor>(3)

        return OnnxTensor.createTensor(environment, idData).use { idsTensor ->
            feeds[INPUT_IDS] = idsTensor
            OnnxTensor.createTensor(environment, maskData).use { maskTensor ->
                feeds[INPUT_ATTENTION_MASK] = maskTensor
                OnnxTensor.createTensor(environment, typeData).use { typeTensor ->
                    if (wantsTokenTypeIds) feeds[INPUT_TOKEN_TYPE_IDS] = typeTensor

                    session.run(feeds).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result.get(0).value as Array<Array<FloatArray>>
                        // 图里没有池化层，CLS 池化要自己做：取第 0 个位置。
                        //
                        // 这里**不 copyOf()**：ORT 的 getValue 返回的就是一份新的 Java 数组，
                        // 不是 native 内存的视图，可以放心原地归一化。省掉每次调用 2KB 的分配。
                        Vectors.l2Normalize(hidden[0][0])
                    }
                }
            }
        }
    }

    /**
     * 预热：跑一次短输入。
     *
     * 第一次 `run` 会触发算子内核实例化、内存池（arena）开辟、线程池启动，
     * 耗时可能是后续调用的几十倍。放到加载阶段（后台线程）做掉，
     * 用户第一次刷新就不会撞上这个尖峰。返回本次耗时（毫秒），供设置页显示。
     */
    fun warmup(): Long {
        val started = System.nanoTime()
        runCatching { encode(WARMUP_TEXT) }
        return (System.nanoTime() - started) / 1_000_000
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val INPUT_IDS = "input_ids"
        private const val INPUT_ATTENTION_MASK = "attention_mask"
        private const val INPUT_TOKEN_TYPE_IDS = "token_type_ids"

        /** 预热用的输入要短，目的是把内核掀起来，不是测性能。 */
        private const val WARMUP_TEXT = "预热"

        /**
         * 打开一个编码器。**阻塞，必须在后台线程调用**（读 24MB 模型 + 建图）。
         *
         * @param customConfig 想尝试的配置。若它导致建会话失败（某些机型的 NNAPI 驱动
         *   会在建图阶段直接抛异常），会自动退回纯 CPU 配置重试一次，
         *   而不是让整个应用失去语义排序。
         */
        fun open(
            modelFile: File,
            tokenizer: BertTokenizer,
            customConfig: SessionConfig = SessionConfig(),
            maxLength: Int = BgeSmallZh.MAX_LENGTH,
        ): OrtEmbedder {
            val environment = OrtEnvironment.getEnvironment()
            val attempts = listOf(
                customConfig,
                customConfig.copy(enableNnapi = false, enableXnnpack = false),
            ).distinct()

            var lastError: Throwable? = null
            for (config in attempts) {
                try {
                    val session = environment.createSession(modelFile.absolutePath, optionsOf(config))
                    return OrtEmbedder(environment, session, tokenizer, maxLength)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw (lastError ?: IllegalStateException("无法创建推理会话"))
        }

        private fun optionsOf(config: SessionConfig): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(config.intraOpThreads.coerceAtLeast(1))
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // 顺序执行：这个图没有可以并行跑的分支，开并行只会多一层调度。
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)

                // ORT 默认用「自旋等待」来降延迟 —— 在手机上这是纯粹的电量消耗：
                // 推理间隙里几个核空转等着下一个任务。小模型本来就是短任务，
                // 省下的那点唤醒延迟换不来耗电。
                //
                // 用 runCatching 包住：不同 ORT 版本对配置键的校验不一样，
                // 未知键在某些版本会直接抛异常，而「关不掉自旋」绝不该导致会话建不起来。
                runCatching { addConfigEntry("session.intra_op.allow_spinning", "0") }
                runCatching { addConfigEntry("session.inter_op.allow_spinning", "0") }

                if (config.enableXnnpack) {
                    runCatching { addXnnpack(mapOf("intra_op_num_threads" to "${config.intraOpThreads}")) }
                }
                if (config.enableNnapi) {
                    runCatching { addNnapi() }
                }
            }
    }
}
