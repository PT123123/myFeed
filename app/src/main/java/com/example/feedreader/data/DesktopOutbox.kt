package com.example.feedreader.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 一条等着交给电脑端的操作。
 *
 * `opId` 是服务端认的幂等键，`clientSeq` 是本机在这台设备身份下的单调序号 ——
 * 两个都得持久化：重试同一条必须用**同一个** opId，否则服务端会把它当新操作再算一遍。
 */
data class OutboxEntry(
    val opId: String,
    val clientSeq: Long,
    val postId: String,
    val occurredAt: Long,
    val failed: Boolean = false,
    val error: String = "",
)

/**
 * 未上传操作的磁盘队列。
 *
 * 和 [ReadStateStore] 一样的取舍：单独一个二进制文件，不用 SharedPreferences（整份 XML
 * 每次重写太浪费），也不用 org.json（本地 JVM 单测里是 stub）。整份覆盖 + 原子改名，
 * 队列本来就几十条，增量追加换来的复杂度不值得。
 *
 * 上限 [MAX_ENTRIES]：电脑端长期关机时，「已读」会一直攒。丢最旧的是安全的 ——
 * 已读是单调操作（服务端 read_progress 取 max、last_read_at 用服务器时钟），
 * 少报几条只会让电脑端少看到几行记录，不会把状态算错。
 */
class DesktopOutbox(val file: File) {

    @Synchronized
    fun load(): List<OutboxEntry> {
        if (!file.isFile || file.length() == 0L || file.length() > MAX_FILE) return emptyList()
        val entries = try {
            read()
        } catch (_: Exception) {
            file.delete()
            return emptyList()
        } ?: run {
            file.delete()
            return emptyList()
        }
        return entries.takeLast(MAX_ENTRIES)
    }

    @Synchronized
    fun save(entries: List<OutboxEntry>): Boolean {
        val dir = file.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) return false
        val trimmed = entries.takeLast(MAX_ENTRIES)
        val tmp = File(dir ?: file, file.name + ".tmp")
        return try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(trimmed.size)
                trimmed.forEach { entry ->
                    out.writeStr(entry.opId)
                    out.writeLong(entry.clientSeq)
                    out.writeStr(entry.postId)
                    out.writeLong(entry.occurredAt)
                    out.writeBoolean(entry.failed)
                    out.writeStr(entry.error)
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) return false
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 排入一条「读过了」。已有同一篇的待发操作时不重复排 ——
     * 同一篇帖子被上报两次没有任何信息量，只会让队列里全是重复行。
     */
    @Synchronized
    fun enqueue(postId: String, clientSeq: Long, now: Long = System.currentTimeMillis()): Boolean {
        val entries = load()
        if (entries.any { it.postId == postId && !it.failed }) return false
        return save(entries + OutboxEntry(UUID.randomUUID().toString(), clientSeq, postId, now))
    }

    @Synchronized
    fun pendingCount(): Int = load().count { !it.failed }

    @Synchronized
    fun failedCount(): Int = load().count { it.failed }

    private fun read(): List<OutboxEntry>? =
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != VERSION) return null
            val count = input.readInt()
            if (count < 0 || count > MAX_ENTRIES) return null
            buildList {
                repeat(count) {
                    val opId = input.readStr()
                    val clientSeq = input.readLong()
                    val postId = input.readStr()
                    val occurredAt = input.readLong()
                    val failed = input.readBoolean()
                    val error = input.readStr()
                    if (opId.isNotBlank() && postId.isNotBlank()) {
                        add(OutboxEntry(opId, clientSeq, postId, occurredAt, failed, error))
                    }
                }
            }
        }

    companion object {
        fun forApp(context: android.content.Context): DesktopOutbox =
            DesktopOutbox(File(context.filesDir, FILE_NAME))

        private const val FILE_NAME = "paf_outbox.bin"
        private const val MAGIC = 0x4D46_4F42       // "MFOB"
        private const val VERSION = 1
        private const val MAX_ENTRIES = 500
        private const val MAX_FILE = 4L * 1024 * 1024
    }
}
