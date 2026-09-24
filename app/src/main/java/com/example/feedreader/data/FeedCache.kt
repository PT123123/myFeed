package com.example.feedreader.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 一个源的缓存内容。 */
data class CachedFeed(
    val sourceId: String,
    val fetchedAt: Long,
    val articles: List<Article>,
)

/** 缓存文件的大小与抓取时间，清理策略按它决策。 */
data class CacheEntry(val sourceId: String, val fetchedAt: Long, val bytes: Long)

/** 当前缓存占用，设置页显示用。 */
data class CacheStats(val bytes: Long = 0L, val entries: Int = 0)

/** 一次清理的结果。 */
data class PruneResult(
    val removed: Int = 0,
    val freedBytes: Long = 0L,
    val remainingBytes: Long = 0L,
)

/**
 * 订阅内容的磁盘缓存：**一个源一个文件**，存解析后的文章列表。
 *
 * 几个取舍：
 * - 存解析结果而不是原始 XML。体积小一个数量级，冷启动直接反序列化，
 *   也就不用再跑一遍 GBK/UTF-8 那套解码分支。
 * - 放 filesDir 而不是 cacheDir。cacheDir 会被系统在存储紧张时静默清空，
 *   而这份缓存是「断网也能看」的依据，生命周期得自己管（天数/大小上限
 *   和清空入口都在设置里）。
 * - 用 java.io 二进制格式而不是 JSON。自带长度前缀，不用手写转义；
 *   更关键的是 org.json 在本地 JVM 单测里是 stub，用 java.io 的话
 *   这套读写逻辑能直接单测。
 * - 写盘先写 .tmp 再原子改名。中途被杀不会留下半截文件被当成有效缓存。
 */
class FeedCache(private val dir: File) {

    fun read(sourceId: String): CachedFeed? = readFile(fileFor(sourceId))

    /** 读全部可用缓存。损坏的文件会被就地删掉并跳过。 */
    fun readAll(): List<CachedFeed> = binFiles().mapNotNull { readFile(it) }

    /**
     * 写入一个源的缓存。空列表不写 —— 免得某次「源能连上但解析出 0 条」
     * 把之前的好缓存冲掉。
     */
    fun write(sourceId: String, articles: List<Article>, now: Long = System.currentTimeMillis()) {
        val list = articles.take(MAX_ARTICLES)
        if (list.isEmpty()) return
        if (!dir.isDirectory && !dir.mkdirs()) return

        val target = fileFor(sourceId)
        val tmp = File(dir, target.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeStr(sourceId)
                out.writeLong(now)
                out.writeInt(list.size)
                list.forEach { out.writeArticle(it) }
            }
            if (!tmp.renameTo(target)) {
                // 个别文件系统上 renameTo 不覆盖已存在的目标
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("重命名失败")
            }
        } catch (_: Exception) {
            // 缓存写失败不该影响主流程，下次刷新再来
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 按策略清理：
     * - [retentionDays] > 0 时，抓取时间早于该天数的缓存直接删（0 = 不按天数清）
     * - 剩余总量超过 [maxBytes] 时，从最旧的开始删到上限以内（0 = 不按大小清）
     *
     * 两条都只会删「旧」的，刚写进去的那批天然安全。
     */
    fun prune(
        retentionDays: Int,
        maxBytes: Long,
        now: Long = System.currentTimeMillis(),
    ): PruneResult {
        val entries = listEntries()
        var removed = 0
        var freed = 0L
        val survivors = ArrayList<CacheEntry>(entries.size)

        val cutoff = if (retentionDays > 0) now - retentionDays * DAY_MS else 0L
        for (entry in entries) {
            if (cutoff > 0 && entry.fetchedAt < cutoff) {
                if (deleteFile(entry.sourceId)) {
                    removed++
                    freed += entry.bytes
                }
            } else {
                survivors += entry
            }
        }

        var remaining = survivors.sumOf { it.bytes }
        if (maxBytes > 0 && remaining > maxBytes) {
            for (entry in survivors.sortedBy { it.fetchedAt }) {
                if (remaining <= maxBytes) break
                if (deleteFile(entry.sourceId)) {
                    removed++
                    freed += entry.bytes
                    remaining -= entry.bytes
                }
            }
        }

        return PruneResult(removed, freed, remaining)
    }

    /** 清空全部缓存。 */
    fun clear(): PruneResult {
        val files = binFiles()
        val bytes = files.sumOf { it.length() }
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return PruneResult(removed, bytes, stats().bytes)
    }

    fun stats(): CacheStats {
        val files = binFiles()
        return CacheStats(bytes = files.sumOf { it.length() }, entries = files.size)
    }

    // —— 内部 ——

    private fun fileFor(sourceId: String): File =
        File(dir, sourceId.replace(UNSAFE, "_") + SUFFIX)

    private fun binFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList() ?: emptyList()

    private fun deleteFile(sourceId: String): Boolean = fileFor(sourceId).delete()

    /** 只读头部，供清理策略判断，不用把整份文章列表解出来。 */
    private fun readEntry(file: File): CacheEntry? = try {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                null
            } else {
                CacheEntry(input.readStr(), input.readLong(), file.length())
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun listEntries(): List<CacheEntry> = binFiles().mapNotNull { readEntry(it) }

    private fun readFile(file: File): CachedFeed? {
        if (!file.isFile || file.length() == 0L || file.length() > MAX_FILE) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC) throw IOException("不是缓存文件")
                val version = input.readInt()
                if (version != VERSION) throw IOException("缓存版本不匹配：$version")
                val sourceId = input.readStr()
                val fetchedAt = input.readLong()
                val count = input.readInt()
                if (count < 0 || count > MAX_ARTICLES) throw IOException("条目数异常：$count")
                val articles = ArrayList<Article>(count)
                repeat(count) { articles += input.readArticle() }
                CachedFeed(sourceId, fetchedAt, articles)
            }
        } catch (_: Exception) {
            // 半截文件、旧版本、被人手改过：删掉，下次刷新会重写
            file.delete()
            null
        }
    }

    companion object {
        /** 应用默认的缓存位置：filesDir 下，自己管生命周期。 */
        fun forApp(context: android.content.Context): FeedCache =
            FeedCache(File(context.filesDir, DIR_NAME))

        private const val MAGIC = 0x4D46_4348       // "MFCH"
        private const val VERSION = 1
        private const val SUFFIX = ".feed"
        private const val MAX_ARTICLES = 500
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DIR_NAME = "feed_cache"
        private val UNSAFE = Regex("[^A-Za-z0-9_.-]")
    }
}

// —— 二进制字段读写。字符串按「长度 + UTF-8 字节」写，避开 writeUTF 的 64KB 上限 ——

private fun DataOutputStream.writeStr(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readStr(): String {
    val size = readInt()
    if (size < 0 || size > MAX_FIELD_BYTES) throw IOException("字段长度异常：$size")
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}

private fun DataOutputStream.writeArticle(article: Article) {
    writeStr(article.id)
    writeStr(article.title)
    writeStr(article.excerpt)
    writeStr(article.link)
    writeStr(article.author)
    writeStr(article.sourceId)
    writeStr(article.sourceName)
    writeStr(article.category)
    writeLong(article.publishedAt)
    writeStr(article.publishedRaw)
}

private fun DataInputStream.readArticle(): Article = Article(
    id = readStr(),
    title = readStr(),
    excerpt = readStr(),
    link = readStr(),
    author = readStr(),
    sourceId = readStr(),
    sourceName = readStr(),
    category = readStr(),
    publishedAt = readLong(),
    publishedRaw = readStr(),
)

/** 单字段上限，只用来挡住损坏文件导致的离谱内存分配。 */
private const val MAX_FIELD_BYTES = 4 * 1024 * 1024
