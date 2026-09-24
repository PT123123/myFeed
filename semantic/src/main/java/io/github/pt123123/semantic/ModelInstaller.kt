package io.github.pt123123.semantic

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 一个可下载的模型来源。
 *
 * 配多个来源是因为**没有一个镜像能保证一直可达**：实测 huggingface.co 直连超时、
 * hf-mirror 限速严重（24MB 五分钟没下完）、modelscope.cn 快但属于第三方。
 * 顺序即优先级，前一个失败自动换下一个，已下载的部分会接着下。
 */
data class ModelSource(
    /** 展示给用户的名字。下载全失败时要能说清「哪几家挂了」。 */
    val name: String,
    val url: String,
    /** 小写十六进制 sha256。**必须校验，不能只比大小** —— 被劫持/被截断的响应大小可能刚好对。 */
    val sha256: String,
    val sizeBytes: Long,
)

/** 模型安装状态，给设置页直接渲染。 */
sealed interface ModelState {
    data object Absent : ModelState
    data class Downloading(val source: String, val received: Long, val total: Long) : ModelState
    data class Ready(val bytes: Long) : ModelState
    data class Failed(val message: String) : ModelState
}

class ModelInstallException(message: String) : IOException(message)

/**
 * 模型文件的下载与校验。
 *
 * 模型 24MB，不随 APK 分发，首次用到时才下载。四个必须处理好的点：
 *
 * 1. **多镜像回退**：任一来源失败换下一个，不是直接报错。
 * 2. **断点续传**：24MB 在移动网络下很容易中途断，`Range` 请求接着下，
 *    避免「下到 90% 失败又从头来」。
 * 3. **sha256 强校验**：下完比对内容摘要，不一致就丢弃重来。
 * 4. **原子落位**：先写 `.part`，校验通过才改名到正式路径。
 *    否则「下载到一半被杀进程」会留下一个大小看着还行、内容截断的模型文件，
 *    而 ONNX 加载截断文件报的错五花八门，极难归因。
 *
 * 只用 `HttpURLConnection`，不引 okhttp —— 这个模块要单独出 AAR，
 * 每多一个传递依赖，接入方就多一次冲突排查。
 */
class ModelInstaller(
    /** 最终模型文件的完整路径。 */
    private val target: File,
    private val sources: List<ModelSource>,
    /** 进度回调。会在下载线程上被反复调用，实现里别做重活。 */
    private val onState: (ModelState) -> Unit = {},
) {

    private val lock = Any()

    private val partFile: File get() = File(target.parentFile, target.name + PART_SUFFIX)

    private val markerFile: File get() = File(target.parentFile, target.name + MARKER_SUFFIX)

    val expectedSize: Long get() = sources.first().sizeBytes

    val expectedSha256: String get() = sources.first().sha256

    /**
     * 模型是否已就绪且可信。
     *
     * 正常情况下只比「大小 + 标记文件」，不去重算 24MB 的摘要 —— 那要几百毫秒，
     * 每次冷启动都做不划算。只有标记缺失时才回退到真算一次摘要：
     * 用户从备份恢复文件、或者旧版本装的模型没写标记，都会走到这条路上。
     */
    fun isInstalled(): Boolean {
        if (!target.isFile || target.length() != expectedSize) return false

        val marker = markerFile
        if (marker.isFile && marker.readText().trim() == expectedSha256) return true

        // 大小对但没有可信标记：宁可多花一次摘要，也不能让来路不明的文件进推理
        return runCatching { sha256Of(target) == expectedSha256 }
            .onSuccess { if (it) writeMarker() }
            .getOrDefault(false)
    }

    /**
     * 下载并安装。成功返回可用的模型文件；所有来源都失败则抛 [ModelInstallException]。
     * **阻塞调用，必须在 IO 线程上跑。**
     */
    fun install(): File = synchronized(lock) {
        if (isInstalled()) {
            onState(ModelState.Ready(target.length()))
            return target
        }

        target.parentFile?.mkdirs()
        val failures = ArrayList<String>(sources.size)

        for (source in sources) {
            try {
                fetch(source)
                commit(source)
                onState(ModelState.Ready(target.length()))
                return target
            } catch (error: Exception) {
                failures += "${source.name}：${error.message ?: error.javaClass.simpleName}"
            }
        }

        val message = "所有来源都失败了 —— " + failures.joinToString("；")
        onState(ModelState.Failed(message))
        throw ModelInstallException(message)
    }

    /** 删掉模型与半成品，回到「未安装」。 */
    fun remove() = synchronized(lock) {
        target.delete()
        partFile.delete()
        markerFile.delete()
        onState(ModelState.Absent)
    }

    /** 半成品占用的字节数，给 UI 显示「已下 8.3MB，可继续」用。 */
    fun partialBytes(): Long = partFile.takeIf { it.isFile }?.length() ?: 0L

    // ------------------------------------------------------------------ 内部

    /** 下载到 `.part`。失败时保留 `.part`，下一个镜像可以接着下。 */
    private fun fetch(source: ModelSource) {
        // 半成品比目标还大只可能是脏数据（换了模型、或上次写坏），直接丢弃重来
        val existing = partFile.takeIf { it.isFile }?.length() ?: 0L
        if (existing > source.sizeBytes) partFile.delete()
        val resumeFrom = partFile.takeIf { it.isFile }?.length() ?: 0L

        val connection = open(source.url, resumeFrom)
        try {
            val code = connection.responseCode
            val appending = code == HTTP_PARTIAL_CONTENT && resumeFrom > 0L
            if (code != HTTP_OK && code != HTTP_PARTIAL_CONTENT) {
                throw ModelInstallException("HTTP $code")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            if (appending) {
                // 续传：已有那一段也要进摘要，否则算出来的 sha256 只覆盖了后半截
                digest.updateWith(partFile)
            } else {
                partFile.delete()
            }

            var received = if (appending) resumeFrom else 0L
            var reported = received
            onState(ModelState.Downloading(source.name, received, source.sizeBytes))

            connection.inputStream.use { input ->
                FileOutputStream(partFile, appending).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        // 每 256KB 报一次。每个缓冲块都回调会把主线程刷爆，
                        // 而 24MB / 8KB = 3000 次回调，足够把 Compose 重组压垮。
                        if (received - reported >= PROGRESS_STEP) {
                            reported = received
                            onState(ModelState.Downloading(source.name, received, source.sizeBytes))
                        }
                    }
                }
            }

            // 进度是节流上报的（每 256KB 一次），最后不足一个步长的那一段不会再触发回调。
            // 不补这一次的话进度条会永远停在 87% 之类的数上，直到状态突然跳成「完成」——
            // 用户看到的是「卡住了然后好了」，而不是「下完了」。
            if (received != reported) {
                onState(ModelState.Downloading(source.name, received, source.sizeBytes))
            }

            if (received != source.sizeBytes) {
                throw ModelInstallException("只下到 $received / ${source.sizeBytes} 字节")
            }

            val actual = digest.digest().toHex()
            if (!actual.equals(source.sha256, ignoreCase = true)) {
                // 内容不对，别留着 —— 否则下一次续传会把错误前缀带进新摘要里
                partFile.delete()
                throw ModelInstallException("校验失败：期望 ${source.sha256.take(12)}…，实际 ${actual.take(12)}…")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 校验通过后才改名到正式路径，再写标记。 */
    private fun commit(source: ModelSource) {
        target.delete()
        if (!partFile.renameTo(target)) {
            throw ModelInstallException("无法把 ${partFile.name} 改名为 ${target.name}")
        }
        markerFile.writeText(source.sha256)
    }

    private fun writeMarker() {
        runCatching { markerFile.writeText(expectedSha256) }
    }

    private fun open(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (resumeFrom > 0L) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        // 刻意不设 Accept-Encoding。HttpURLConnection 一旦看到 gzip 会做透明解压，
        // 那样 Content-Length 与解压后的字节数对不上，续传的偏移量就全错了。
        return connection
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateWith(file)
        return digest.digest().toHex()
    }

    private fun MessageDigest.updateWith(file: File) {
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                update(buffer, 0, read)
            }
        }
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val MARKER_SUFFIX = ".sha256"

        private const val CONNECT_TIMEOUT_MS = 15_000
        /** 读超时给得宽：模型站限速时单块也可能要等，太短会误判成失败。 */
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP = 256 * 1024L

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206

        /** 部分模型站对空 UA 直接 403，给一个常规浏览器 UA 最稳。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
