package io.github.pt123123.semantic

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 向量缓存的占用情况。 */
data class VectorStats(val bytes: Long = 0L, val entries: Int = 0, val vectors: Int = 0)

/** 一次清理的结果。 */
data class VectorPruneResult(val removed: Int = 0, val freedBytes: Long = 0L)

/**
 * 文章句向量的磁盘缓存。**一个源一个文件**，与 [com.example.feedreader.data.FeedCache] 同构。
 *
 * 这是整个方案性能上最要紧的一处：**每篇文章的向量只算一次，之后永远从盘上读**。
 * 编码是唯一的重活（512 维 4 层模型，标题级输入约 2.6ms/条，手机再乘 2~3），
 * 而排序只是点积（500 条 × 512 维 < 1ms）。如果做成「每次刷新重编码全量」，
 * 冷启动会有十几秒的卡顿，体验直接废掉。
 *
 * 存 int8 量化后的向量：516 B/条，5000 条约 2.5MB（float32 要 9.8MB）。
 * 读回来必须走 [QuantizedVector.toUnitFloats] 重新归一化，排序侧才能用点积当余弦。
 *
 * 放 filesDir 不放 cacheDir —— 系统清 cacheDir 是静默的，而这份缓存丢了
 * 意味着下次刷新要重编码全量，用户会明显感到卡。
 */
class VectorStore(
    private val dir: File,
    /**
     * 当前模型的向量维度。
     *
     * 缓存格式和模型是绑死的：换一个不同维度的模型后，旧缓存里的向量拿去和
     * 新模型的兴趣向量做点积会数组越界。所以维度写进文件头，读的时候对不上就
     * 判为损坏丢弃 —— 走的是和「半截文件」同一条自愈路径，不用额外写迁移代码。
     * 做成构造参数是为了单测能造出「另一版模型写的缓存」。
     */
    private val expectedDimension: Int = EXPECTED_DIMENSION,
) {

    /**
     * 读一个源的全部向量。损坏文件就地删除并返回空表，
     * 上层会把缺失的向量当作「未编码」重新算，不会出错。
     */
    fun read(sourceId: String): Map<String, QuantizedVector> {
        val file = fileFor(sourceId)
        if (!file.isFile || file.length() == 0L || file.length() > MAX_FILE) return emptyMap()

        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC) throw IOException("不是向量缓存文件")
                val version = input.readInt()
                if (version != VERSION) throw IOException("向量缓存版本不匹配：$version")
                input.readUTFString()
                // 存的是原始 sourceId，文件名是净化后的版本，两者本来就可能不等。
                // 这里只读出来推进流位置，**不做一致性校验** —— 文件名已经承担了定位职责，
                // 拿它当断言只会在含特殊字符的源 id 上把好数据误删。
                input.readUTFString()
                val dimension = input.readInt()
                if (dimension != expectedDimension) {
                    throw IOException("向量维度不匹配：文件 $dimension，当前模型 $expectedDimension")
                }
                val count = input.readInt()
                if (count < 0 || count > MAX_ENTRIES) throw IOException("条目数异常：$count")

                val out = HashMap<String, QuantizedVector>(count * 2)
                repeat(count) {
                    val articleId = input.readUTFString()
                    val size = input.readInt()
                    if (size <= QuantizedVector.HEADER_BYTES || size > MAX_VECTOR_BYTES) {
                        throw IOException("向量长度异常：$size")
                    }
                    val raw = ByteArray(size)
                    input.readFully(raw)
                    out[articleId] = QuantizedVector.fromBytes(raw)
                }
                out
            }
        } catch (_: Exception) {
            // 半截文件、旧版本、被手改过：删掉，下次刷新重算
            file.delete()
            emptyMap()
        }
    }

    fun readAll(): Map<String, QuantizedVector> =
        binFiles().flatMap { file -> read(sourceIdOf(file)).entries }
            .associate { it.key to it.value }

    /**
     * 写入一个源的向量表。空表不写 —— 免得某次「源连得上但解析出 0 条」
     * 把之前算好的向量冲掉，那会白白多一次全量编码。
     *
     * 维度不符的向量也不写：整表的维度是格式的一部分，混进一条就不是有效的缓存文件。
     */
    fun write(sourceId: String, vectors: Map<String, QuantizedVector>) {
        if (vectors.isEmpty()) return
        if (vectors.values.any { it.dimension != expectedDimension }) return
        if (!dir.isDirectory && !dir.mkdirs()) return

        val target = fileFor(sourceId)
        val tmp = File(dir, target.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTFString(FORMAT_TAG)
                out.writeUTFString(sourceId)
                out.writeInt(expectedDimension)
                out.writeInt(vectors.size)
                for ((articleId, vector) in vectors) {
                    out.writeUTFString(articleId)
                    val raw = vector.toBytes()
                    out.writeInt(raw.size)
                    out.write(raw)
                }
            }
            if (!tmp.renameTo(target)) {
                // 个别文件系统上 renameTo 不覆盖已存在的目标
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("重命名失败")
            }
        } catch (_: Exception) {
            // 写失败不该影响主流程，下次刷新再来
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun clear(): VectorPruneResult {
        val files = binFiles()
        val bytes = files.sumOf { it.length() }
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return VectorPruneResult(removed, bytes)
    }

    fun stats(): VectorStats {
        val files = binFiles()
        var vectors = 0
        files.forEach { vectors += entryCount(it) }
        return VectorStats(bytes = files.sumOf { it.length() }, entries = files.size, vectors = vectors)
    }

    // —— 内部 ——

    private fun entryCount(file: File): Int = try {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                0
            } else {
                input.readUTFString()
                input.readUTFString()
                // 维度对不上的文件下一轮 read 就会删掉，统计时不该把它算进来
                if (input.readInt() != expectedDimension) 0 else input.readInt().coerceIn(0, MAX_ENTRIES)
            }
        }
    } catch (_: Exception) {
        0
    }

    private fun fileFor(sourceId: String): File = File(dir, safeName(sourceId) + SUFFIX)

    private fun sourceIdOf(file: File): String = file.name.removeSuffix(SUFFIX)

    /**
     * 把源 id 变成安全的文件名。
     *
     * 源 id 是从订阅源列表来的，理论上可控，但缓存目录**不能**因为一个带 `../` 的 id
     * 就把文件写到外面去。三条处理：
     *  1. 非 `[A-Za-z0-9_.-]` 的连续段压成一个 `_`；
     *  2. `..` 压成 `_` —— 光替换 `/` 不够，`.` 本身是"安全字符"，
     *     `../../evil` 会变成 `.._.._evil`，虽然不会真的越界，但留着一串无意义的前缀；
     *  3. 去掉首尾的 `.` / `_`，清空就退回 [UNNAMED]。
     *
     * 注意**不能**只取最后一段路径：`a/b` 和 `c/b` 是不同的源，取尾段会撞成同一个文件。
     */
    private fun safeName(sourceId: String): String =
        sourceId.replace(UNSAFE_RUN, "_")
            .replace(DOTS, "_")
            .trim('.', '_', ' ')
            .ifEmpty { UNNAMED }

    private fun binFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList() ?: emptyList()

    companion object {
        /** 应用默认位置：filesDir/semantic/vectors，自己管生命周期。 */
        fun forApp(context: android.content.Context): VectorStore =
            VectorStore(File(File(context.filesDir, "semantic"), DIR_NAME))

        private const val MAGIC = 0x4D46_5645          // "MFVE"
        /** 2：文件头加了「向量维度」字段。老文件应当作废重算，不是迁移。 */
        private const val VERSION = 2
        /**
         * 缓存格式标记。含池化方式和模型 id —— 换成 mean pooling、或换成
         * 同维度的另一个模型，写进文件里的向量都不能再用了。
         */
        private const val FORMAT_TAG = BgeSmallZh.ID + "-cls"
        /** 句向量维度。缓存格式和模型绑死，所以从模型描述符取，不留第二份真值。 */
        const val EXPECTED_DIMENSION = BgeSmallZh.DIMENSION
        private const val SUFFIX = ".vec"
        private const val MAX_ENTRIES = 2000
        private const val MAX_FILE = 16L * 1024 * 1024
        private const val MAX_VECTOR_BYTES = 4096      // 512 维 int8 + 头，留足余量
        private const val DIR_NAME = "vectors"
        private const val UNNAMED = "unnamed"
        private val UNSAFE_RUN = Regex("[^A-Za-z0-9_.-]+")
        private val DOTS = Regex("\\.{2,}")
    }
}

/** 字符串按「长度 + UTF-8 字节」写，避开 `writeUTF` 的 64KB 上限（与 FeedCache 一致）。 */
private fun DataOutputStream.writeUTFString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readUTFString(): String {
    val size = readInt()
    if (size < 0 || size > MAX_FIELD_BYTES) throw IOException("字段长度异常：$size")
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}

private const val MAX_FIELD_BYTES = 1024 * 1024
