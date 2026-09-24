package io.github.pt123123.semantic

/**
 * 内置的默认模型：`Xenova/bge-small-zh-v1.5` 的 int8 量化 ONNX 版本。
 *
 * 选它的理由（都是实测出来的，不是按名字挑的）：
 * - 4 层 / hidden 512 / 8 head / vocab 21128，约 24M 参数，端侧够跑；
 * - 量化版本里 `model_quantized.onnx` 最小（24.0MB）—— 注意 `model_q4.onnx` 反而更大（52MB），
 *   别按名字猜；
 * - 图里**没有池化层**，CLS 池化要自己做；官方 `1_Pooling/config.json` 确认 `pooling_mode_cls_token: true`；
 * - 中文检索质量在同尺寸里是头部，且带 `tokenizer.json`，省掉自己转模型。
 *
 * 词表（vocab.txt，109KB）随库打包而不是跟模型一起下：它必须和模型严格配套，
 * 而 109KB 不值得为它多一次网络往返和一次失败可能。
 */
object BgeSmallZh {

    const val ID = "bge-small-zh-v1.5-int8"

    /** 句向量维度 = 模型 hidden size。缓存格式和它绑死，换模型必须一起换。 */
    const val DIMENSION = 512

    /** 与模型 `max_position_embeddings` 一致，也是 bge 官方推荐的短文本上限。 */
    const val MAX_LENGTH = 128

    const val FILE_NAME = "model_quantized.onnx"

    const val SHA256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"

    const val SIZE_BYTES = 24_010_842L

    /** 词表在库 assets 里的路径。 */
    const val VOCAB_ASSET = "semantic/vocab.txt"

    /**
     * 下载来源，顺序即优先级。
     *
     * 实测（本机，住宅宽带）：
     * - `modelscope.cn` 直链最快，24MB 几十秒；
     * - `hf-mirror.com` 可达但限速明显（上一轮 24MB 撞过 300s 超时），所以放第二位；
     * - `huggingface.co` 直连**超时不可达**（000），没写进来 —— 留着一个必然失败的源
     *   只会让「全都失败」的报错里多一行噪声。
     *
     * 两者的 sha256 与大小完全一致（同一份 LFS 内容），所以任何一个下到一半，
     * 都能由另一个接手续传。
     */
    val SOURCES: List<ModelSource> = listOf(
        ModelSource(
            name = "ModelScope",
            url = "https://modelscope.cn/api/v1/models/Xenova/bge-small-zh-v1.5/repo" +
                "?Revision=master&FilePath=onnx%2Fmodel_quantized.onnx",
            sha256 = SHA256,
            sizeBytes = SIZE_BYTES,
        ),
        ModelSource(
            name = "HF 镜像",
            url = "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx",
            sha256 = SHA256,
            sizeBytes = SIZE_BYTES,
        ),
    )

    /** 带用户自定义地址的来源表：自定义排最前，内置的作为兜底。 */
    fun sourcesWith(customUrl: String?): List<ModelSource> {
        val custom = customUrl?.trim().orEmpty()
        if (custom.isEmpty()) return SOURCES
        return listOf(
            ModelSource("自定义地址", custom, SHA256, SIZE_BYTES),
        ) + SOURCES
    }
}
