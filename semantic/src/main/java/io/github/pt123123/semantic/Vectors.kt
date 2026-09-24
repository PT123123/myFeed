package io.github.pt123123.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 句向量的纯数学部分。有意不依赖 ONNX，方便单测直接喂数组验算。
 */
object Vectors {

    /** 原地 L2 归一化，返回同一个数组。零向量原样返回，避免除零出 NaN。 */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (x in vector) sum += x.toDouble() * x
        val norm = sqrt(sum)
        if (norm < 1e-12) return vector
        val inv = (1.0 / norm).toFloat()
        for (i in vector.indices) vector[i] *= inv
        return vector
    }

    /** 余弦相似度。双方都已归一化时等价于点积，这里仍按通用式算以备未归一化的输入。 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "维度不一致: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-12) 0f else (dot / denom).toFloat()
    }

    /** 两个已归一化向量的点积，就是余弦。排序热路径用它，比 [cosine] 少两次开方。 */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s.toFloat()
    }

    fun isNormalized(v: FloatArray, tolerance: Float = 1e-3f): Boolean {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        return kotlin.math.abs(sqrt(sum) - 1.0) <= tolerance
    }
}

/**
 * int8 量化后的句向量，用于落盘缓存。
 *
 * 512 维 float32 是 2048 B/条（5000 条 9.8MB），int8 只要 516 B/条（2.5MB）。
 * 量化误差对排序没有实际影响（向量已 L2 归一化，各分量量级接近，动态范围很窄）。
 *
 * 存储布局：[4 字节小端 float 缩放系数][512 字节 int8]。
 */
class QuantizedVector private constructor(
    private val bytes: ByteArray,
    val scale: Float,
) {
    val dimension: Int get() = bytes.size

    /**
     * 还原成浮点数组，**不做归一化**，保持「量化-反量化」的纯粹往返语义，
     * 方便单测断言量化的真实误差上界。
     *
     * 注意是 **乘** [scale]：量化时算的是 `q = v / scale`，所以反量化是 `v = q * scale`。
     * 写成除法会让还原值放大 `1/scale²` 倍（512 维实测差 293 万倍）。
     */
    fun toFloats(): FloatArray {
        val out = FloatArray(bytes.size)
        for (i in bytes.indices) out[i] = bytes[i].toFloat() * scale
        return out
    }

    /**
     * 还原并重新归一化 —— **从磁盘读缓存时必须用这个**。
     *
     * int8 量化会让模长偏离 1 最多约 2%（512 维实测超过 1%），
     * 而排序热路径用点积代替余弦（省两次开方）。不归一化的话点积就不是余弦，
     * 分数会带上模长误差。这里一次性修好，排序侧就能放心用 [Vectors.dot]。
     */
    fun toUnitFloats(): FloatArray = Vectors.l2Normalize(toFloats())

    /** 量化误差上限，用来在单测里断言精度。 */
    fun maxAbsError(original: FloatArray): Float {
        var worst = 0f
        val restored = toFloats()
        for (i in original.indices) {
            val err = kotlin.math.abs(original[i] - restored[i])
            if (err > worst) worst = err
        }
        return worst
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(scale)
        buffer.put(bytes)
        return buffer.array()
    }

    companion object {
        const val HEADER_BYTES = 4

        /** 对称量化：以绝对值最大分量为基准映射到 int8。全零向量给一个 1 的缩放系数兜底。 */
        fun quantize(vector: FloatArray): QuantizedVector {
            var peak = 0f
            for (x in vector) {
                val a = kotlin.math.abs(x)
                if (a > peak) peak = a
            }
            val scale = if (peak <= 0f) 1f else max(peak / 127f, 1e-12f)
            val bytes = ByteArray(vector.size)
            for (i in vector.indices) {
                val q = (vector[i] / scale).roundToInt().coerceIn(-127, 127)
                bytes[i] = q.toByte()
            }
            return QuantizedVector(bytes, scale)
        }

        fun fromBytes(raw: ByteArray): QuantizedVector {
            require(raw.size > HEADER_BYTES) { "字节数不合法: ${raw.size}" }
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val scale = buffer.float
            val payload = ByteArray(raw.size - HEADER_BYTES)
            buffer.get(payload)
            return QuantizedVector(payload, scale)
        }
    }
}
