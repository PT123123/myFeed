package com.example.feedreader.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 「哪些条目读过」的磁盘记录。
 *
 * 首页是一条兴趣流，二十几个源各带最多 30 条，一次刷新就是六百多张卡片。
 * 没有已读记录的话，读过的和没读过的长得一模一样，下次刷新还会再排到前面 ——
 * 所以这一份记录要解决的只有两件事：**看得出现在该看哪条**、**重启之后还在**。
 *
 * 几个取舍：
 * - 键是 `Article.id`（guid → link → title 那条身份链），不是 link。同一篇的链接
 *   常有带 token 的变体，用 link 记会把同一条读成「读过两次」。
 * - 单独一个二进制文件，不塞进 SharedPreferences。偏好文件是整份 XML，每次点开源
 *   都要全量重写，而且 org.json / prefs 在本地 JVM 单测里是 stub —— 和
 *   [FeedCache] 同理，用 java.io 才能把这套读写逻辑直接单测。
 * - **只记「点开过」，不记「读完了」。** 判定读完要停留时长和滚动位置，那些得为每篇
 *   条目存状态；代价是划过去误点一下也算已读，所以首页默认只是把读过的压暗，
 *   隐藏要用户自己点「只看未读」。
 * - 清理按「时间 + 条数」双上限（见 [MAX_AGE_MS]、[MAX_ENTRIES]），读写两边都做：
 *   源改版换了 guid 会留下永远匹配不上的孤儿键，不清就无限长。
 */
class ReadStateStore(val file: File) {

    /**
     * 读出已读记录，返回 `article.id -> 什么时候读的`。
     *
     * 没有文件、版本对不上、半截文件都当「没读过」：已读记录丢了只是回到全都未读，
     * 不影响内容本身，所以宁可轻判也不要弹错误态。损坏文件顺手删掉，下次写就是干净的。
     */
    fun load(now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (!file.isFile || file.length() == 0L || file.length() > MAX_FILE) return emptyMap()
        val loaded = try {
            read(file)
        } catch (_: Exception) {
            file.delete()
            return emptyMap()
        }
        if (loaded == null) {
            file.delete()
            return emptyMap()
        }
        val pruned = prune(loaded, now)
        // 读的时候顺手把过期的清掉：装了一年没动的设备不该每次都解一万条再丢
        if (pruned.size != loaded.size) runCatching { write(pruned) }
        return pruned
    }

    /**
     * 整份覆盖写入（原子：先写 .tmp 再改名）。返回是否落盘成功。
     */
    fun save(entries: Map<String, Long>, now: Long = System.currentTimeMillis()): Boolean {
        val pruned = prune(entries, now)
        val dir = file.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) return false
        return runCatching { write(pruned) }.isSuccess
    }

    /** 已读条目数，设置页显示用。 */
    fun count(): Int = load().size

    private fun read(file: File): Map<String, Long>? =
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != VERSION) return null
            val count = input.readInt()
            if (count < 0 || count > MAX_ENTRIES) return null
            val entries = LinkedHashMap<String, Long>(count)
            repeat(count) {
                val id = input.readStr()
                val at = input.readLong()
                if (id.isNotBlank()) entries[id] = at
            }
            entries
        }

    /**
     * `@Synchronized`：点开源就写一次，连点几条会并发写同一个 .tmp，
     * 交错出来的半截文件下次启动直接判损坏 —— 已读记录会整体清零。
     */
    @Synchronized
    private fun write(entries: Map<String, Long>) {
        val tmp = File(file.parentFile ?: File("."), file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(entries.size)
                entries.forEach { (id, at) ->
                    out.writeStr(id)
                    out.writeLong(at)
                }
            }
            if (!tmp.renameTo(file)) {
                // 个别文件系统上 renameTo 不覆盖已存在的目标
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("重命名失败")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        fun forApp(context: android.content.Context): ReadStateStore =
            ReadStateStore(File(context.filesDir, FILE_NAME))

        /**
         * 只留最近 [MAX_AGE_MS] 内读的、最多 [MAX_ENTRIES] 条，两个上限同时生效。
         *
         * 条数上限按「新读的优先」丢：已读记录越新越可能还在首页里，老的早被缓存
         * 保留天数刷掉了，留着只是孤儿键。
         */
        internal fun prune(entries: Map<String, Long>, now: Long): Map<String, Long> {
            val kept = entries.filterValues { it > 0L && now - it <= MAX_AGE_MS }
            if (kept.size <= MAX_ENTRIES) return kept
            return kept.entries.sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }

        /** "MFRD" —— 和 [FeedCache] 的 "MFCH" 同一套命名。 */
        private const val MAGIC = 0x4D46_5244
        private const val VERSION = 1
        private const val FILE_NAME = "read_state.bin"
        private const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 4_000

        /**
         * 合法文件的体积上限。4000 条 × 平均 130 字节 ≈ 520KB，取整留一倍余量 ——
         * 卡在 512KB 那种「刚好等于上限」的读法，症状是用了大半年的已读记录突然全清。
         */
        private const val MAX_FILE = 2L * 1024 * 1024
    }
}
