package io.github.pt123123.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 句向量数学部分的单测。不依赖 ONNX，直接喂数组。
 */
class VectorsTest {

    private val epsilon = 1e-5f

    @Test
    fun l2NormalizeProducesUnitLength() {
        val v = floatArrayOf(3f, 4f, 0f, 0f)
        Vectors.l2Normalize(v)
        assertEquals(1f, Vectors.dot(v, v), epsilon)
        assertEquals(0.6f, v[0], epsilon)
        assertEquals(0.8f, v[1], epsilon)
    }

    @Test
    fun l2NormalizeLeavesZeroVectorAlone() {
        // 除零会出 NaN，必须原样返回
        val zero = FloatArray(8)
        Vectors.l2Normalize(zero)
        for (x in zero) assertEquals(0f, x, 0f)
        assertFalse(zero.any { it.isNaN() })
    }

    @Test
    fun cosineMatchesKnownAngles() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        val c = floatArrayOf(1f, 1f)
        assertEquals(1f, Vectors.cosine(a, a), epsilon)
        assertEquals(0f, Vectors.cosine(a, b), epsilon)
        assertEquals(-1f, Vectors.cosine(a, floatArrayOf(-1f, 0f)), epsilon)
        // 45 度
        assertEquals(kotlin.math.cos(Math.PI / 4).toFloat(), Vectors.cosine(a, c), epsilon)
    }

    @Test
    fun cosineIsScaleInvariant() {
        // 未归一化的输入也要给出正确余弦（排序器可能拿到原始向量）
        val a = floatArrayOf(0.2f, -0.5f, 0.9f)
        val b = floatArrayOf(-0.4f, 0.1f, 0.3f)
        val scaledA = a.map { it * 7f }.toFloatArray()
        val scaledB = b.map { it * 0.13f }.toFloatArray()
        assertEquals(Vectors.cosine(a, b), Vectors.cosine(scaledA, scaledB), epsilon)
    }

    @Test
    fun dotEqualsCosineForNormalizedVectors() {
        val a = Vectors.l2Normalize(floatArrayOf(0.3f, 0.7f, -0.2f, 0.5f))
        val b = Vectors.l2Normalize(floatArrayOf(-0.6f, 0.1f, 0.8f, 0.2f))
        assertEquals(Vectors.cosine(a, b), Vectors.dot(a, b), epsilon)
    }

    @Test
    fun quantizationKeepsVectorsCloseEnough() {
        // 用确定性伪随机，避免 flaky
        var seed = 20260924L
        fun nextFloat(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 40) and 0xFFFF).toFloat() / 0xFFFF.toFloat() - 0.5f
        }

        val v = Vectors.l2Normalize(FloatArray(512) { nextFloat() })
        val quantized = QuantizedVector.quantize(v)
        val raw = quantized.toFloats()

        // 反量化后模长会有小幅偏离，所以 toFloats 不保证单位长度；
        // 落盘缓存读回来要用 toUnitFloats
        val restoredNormValue = restoredNorm(raw)
        assertTrue(
            "toFloats 不应做归一化。实际模长=$restoredNormValue" +
                " 峰值=${v.maxOf { abs(it) }} scale=${quantized.scale}" +
                " raw[0]=${raw[0]} v[0]=${v[0]} 前4项=${raw.take(4)}",
            kotlin.math.abs(restoredNormValue - 1f) < 0.05f,
        )
        assertTrue("toUnitFloats 必须是严格单位长度", Vectors.isNormalized(quantized.toUnitFloats(), 1e-4f))

        // 对称 int8 量化：误差上界 = scale/2 = peak/254，这里放宽一倍到 peak/127
        val peak = v.maxOf { abs(it) }
        val bound = peak / 127f
        for (i in v.indices) {
            assertTrue(
                "第 $i 维误差 ${abs(v[i] - raw[i])} 超出上界 $bound",
                abs(v[i] - raw[i]) <= bound + 1e-6f,
            )
        }

        // 关键：量化前后排序结论不能变
        val other = Vectors.l2Normalize(FloatArray(512) { nextFloat() })
        val closeToV = Vectors.l2Normalize(v.map { it + 0.01f }.toFloatArray())
        assertEquals(
            Vectors.dot(v, closeToV) > Vectors.dot(v, other),
            Vectors.dot(quantized.toUnitFloats(), QuantizedVector.quantize(closeToV).toUnitFloats()) >
                Vectors.dot(quantized.toUnitFloats(), QuantizedVector.quantize(other).toUnitFloats()),
        )
    }

    private fun restoredNorm(v: FloatArray): Float =
        kotlin.math.sqrt(v.fold(0.0) { acc, x -> acc + x.toDouble() * x }).toFloat()

    @Test
    fun quantizationRoundTripsThroughBytes() {
        val v = Vectors.l2Normalize(floatArrayOf(0.1f, -0.9f, 0.35f, 0f, 0.2f))
        val quantized = QuantizedVector.quantize(v)

        val raw = quantized.toBytes()
        assertEquals(QuantizedVector.HEADER_BYTES + 5, raw.size)

        val restored = QuantizedVector.fromBytes(raw)
        assertEquals(5, restored.dimension)
        assertEquals(quantized.scale, restored.scale, 0f)

        // 要紧的是和**原始值**比，不能只比两个同源实现：
        // 反量化里乘除写反时两边会一起错，互相比对会假绿（这个 bug 真出现过）。
        val back = restored.toFloats()
        for (i in v.indices) {
            assertTrue(
                "第 $i 维还原值 ${back[i]} 与原值 ${v[i]} 量级不符（差 ${abs(back[i] - v[i])}，" +
                    "超过一个量化步长 ${quantized.scale}）",
                abs(back[i] - v[i]) <= quantized.scale,
            )
        }
    }

    @Test
    fun quantizingZeroVectorDoesNotDivideByZero() {
        val q = QuantizedVector.quantize(FloatArray(4))
        assertFalse(q.scale.isNaN() || q.scale == 0f)
        for (x in q.toFloats()) assertEquals(0f, x, 0f)
    }

    @Test
    fun cosineRejectsMismatchedDimensions() {
        try {
            Vectors.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f))
            throw AssertionError("维度不一致时应当抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("维度不一致"))
        }
    }
}
