package io.github.pt123123.semantic

/**
 * 把一段文本编码成句向量。
 *
 * 抽成接口是有意为之：排序逻辑的全部单测都跑在一个假编码器上
 * （把文本映射到「主题维」上的稀疏向量），这样权重组合、降级路径、
 * 反馈偏移这些**逻辑**能被断言，而不必依赖 ONNX 原生库和 24MB 模型。
 *
 * 实现必须满足两条约定：
 * 1. 返回**已 L2 归一化**的向量（长度约等于 1），排序侧用点积代替余弦；
 * 2. 每次返回**新数组** —— 调用方会原地归一化，共享同一个数组会被改坏。
 */
interface TextEncoder {

    /** 返回已 L2 归一化的句向量。 */
    fun encode(text: String): FloatArray

    /** 向量维度。 */
    val dimension: Int
}

/**
 * 模型不可用时的替身：任何输入都编码成零向量。
 *
 * 用零向量而不是「干脆不排序」，是为了让排序逻辑**不用分叉**：
 * 语义项是 `dot(0, v)`，恒等于 0，整套加权自然退化成「纯词法 + 时间」，
 * 权重一个都不用改，也不会有「语义没跑」和「语义跑了但没命中」两种分支要维护。
 *
 * 每次返回新数组而不是共享同一个：向量会被原地归一化（见 [Vectors.l2Normalize]），
 * 共享的话第一次调用就把零向量改坏了。
 */
object NoSemanticEncoder : TextEncoder {

    override val dimension: Int = BgeSmallZh.DIMENSION

    override fun encode(text: String): FloatArray = FloatArray(dimension)
}
