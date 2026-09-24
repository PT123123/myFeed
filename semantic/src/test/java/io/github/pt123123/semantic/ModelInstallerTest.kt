package io.github.pt123123.semantic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模型下载/安装的端到端单测。
 *
 * 用 JDK 自带的 `com.sun.net.httpserver` 起一个真 HTTP 服务，而不是 mock 掉网络层 ——
 * 这里要验的恰恰是**协议层的行为**：Range 续传有没有生效、服务器不支持 Range 时
 * 是从头写还是接着写、206 与 200 的分支、sha256 校验、半成品的清理。
 * mock 掉网络就等于把这几个最可能出错的点全绕过去了。
 */
class ModelInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var server: StubServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    fun `下载成功后落位并写标记`() {
        val payload = payload()
        val stub = serve(payload).also { server = it }

        val target = modelFile()
        val installer = installerOf(target, stub)

        val installed = installer.install()

        assertEquals(target.absolutePath, installed.absolutePath)
        assertTrue("文件内容应与源一致", target.readBytes().contentEquals(payload))
        assertTrue(installer.isInstalled())
        // .part 必须改名而不是拷贝：留着会让下一次续传接在脏数据后面
        assertFalse(File(target.parentFile, target.name + ".part").exists())
    }

    @Test
    fun `已安装则不重复下载`() {
        val payload = payload()
        val stub = serve(payload).also { server = it }
        val installer = installerOf(modelFile(), stub)

        installer.install()
        assertEquals(1, stub.requests.get())

        installer.install()
        assertEquals("第二次不该再发请求", 1, stub.requests.get())
    }

    @Test
    fun `进度回调随下载量递增且终值等于总大小`() {
        val payload = payload(size = 1_200_000)
        val stub = serve(payload).also { server = it }

        val seen = ArrayList<Pair<Long, Long>>()
        val installer = ModelInstaller(
            modelFile(),
            listOf(ModelSource(stub.name, stub.url("/model"), sha256Of(payload), payload.size.toLong())),
            onState = { state ->
                if (state is ModelState.Downloading) seen += state.received to state.total
            },
        )

        installer.install()

        assertTrue("应至少有开始和结束两次进度", seen.size >= 2)
        assertEquals("终值必须是完整大小", payload.size.toLong(), seen.last().first)
        assertEquals(payload.size.toLong(), seen.last().second)
        assertTrue("进度必须单调不减", seen.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue("总大小应始终上报", seen.all { it.second == payload.size.toLong() })
        assertTrue("出现 received > total 说明续传偏移算错了：$seen", seen.all { it.first <= it.second })
    }

    @Test
    fun `删除后回到未安装`() {
        val stub = serve(payload()).also { server = it }
        val target = modelFile()
        val installer = installerOf(target, stub)
        installer.install()

        installer.remove()

        assertFalse(installer.isInstalled())
        assertFalse(target.exists())
    }

    // ------------------------------------------------------------------ 校验与失败

    @Test
    fun `sha256 不匹配时丢弃半成品并抛错`() {
        // 服务端给的内容与声明的摘要不符 —— 被劫持、被截断、或镜像挂了脏文件都会这样
        val wrong = payload(size = 300_000)
        val truth = payload(size = 300_000, salt = 7)
        val stub = serve(truth).also { server = it }

        val target = modelFile()
        val installer = installWith(target, stub, sha256 = sha256Of(wrong))

        val failure = runCatching { installer.install() }.exceptionOrNull()
        assertTrue("应抛 ModelInstallException，实际 $failure", failure is ModelInstallException)
        assertFalse("校验失败的文件不能留在正式路径", target.exists())
        assertFalse(
            "半成品必须清掉：留着会让下次续传把错误前缀带进新摘要",
            File(target.parentFile, target.name + ".part").exists(),
        )
    }

    @Test
    fun `字节数不足时报错而不是当成成功`() {
        val truth = payload(size = 400_000)
        val stub = serve(truth).also { server = it }

        val target = modelFile()
        // 声明的比服务端能给的大 —— 相当于中途断开
        val installer = installWith(target, stub, sha256 = sha256Of(truth), size = 500_000)

        val failure = runCatching { installer.install() }.exceptionOrNull()
        assertTrue("应报错，实际 $failure", failure is ModelInstallException)
        assertFalse(target.exists())
    }

    @Test
    fun `第一个来源失败时自动换下一个`() {
        val payload = payload()
        val stub = serve(payload).also { server = it }
        val target = modelFile()

        val sources = listOf(
            // 路径写错 → 404
            ModelSource("挂掉的镜像", stub.url("/nope"), sha256Of(payload), payload.size.toLong()),
            ModelSource("可用镜像", stub.url("/model"), sha256Of(payload), payload.size.toLong()),
        )

        ModelInstaller(target, sources).install()

        assertTrue(target.readBytes().contentEquals(payload))
        assertTrue("两个来源都应该被访问过", stub.requests.get() >= 2)
    }

    @Test
    fun `第一个来源内容损坏时换下一个且不残留脏数据`() {
        val good = payload()
        val corrupted = payload(salt = 99)
        val stub = serve(good).also { server = it }
        val target = modelFile()

        val sources = listOf(
            ModelSource("内容损坏的镜像", stub.url("/corrupt"), sha256Of(good), good.size.toLong()),
            ModelSource("可用镜像", stub.url("/model"), sha256Of(good), good.size.toLong()),
        )
        stub.corrupt = corrupted

        ModelInstaller(target, sources).install()

        assertTrue("最终必须是正确内容", target.readBytes().contentEquals(good))
    }

    @Test
    fun `所有来源都失败时错误信息里列出每个来源`() {
        val payload = payload()
        val stub = serve(payload).also { server = it }

        val sources = listOf(
            ModelSource("甲镜像", stub.url("/missing-a"), sha256Of(payload), payload.size.toLong()),
            ModelSource("乙镜像", stub.url("/missing-b"), sha256Of(payload), payload.size.toLong()),
        )

        val failure = runCatching {
            ModelInstaller(modelFile(), sources).install()
        }.exceptionOrNull()

        assertTrue(failure is ModelInstallException)
        val message = failure!!.message.orEmpty()
        assertTrue("报错要能指出是哪几家挂的，实际：$message", message.contains("甲镜像"))
        assertTrue("报错要能指出是哪几家挂的，实际：$message", message.contains("乙镜像"))
    }

    // ------------------------------------------------------------------ 续传

    @Test
    fun `已下部分可续传`() {
        val payload = payload(size = 600_000)
        val stub = serve(payload).also { server = it }
        val target = modelFile()

        // 造一个「上次下到 200KB 就断了」的现场
        target.parentFile.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.writeBytes(payload.copyOfRange(0, 200_000))

        val installer = installerOf(target, stub)
        assertEquals("续传量应被上报", 200_000L, installer.partialBytes())

        installer.install()

        assertTrue("续传后内容必须完整正确", target.readBytes().contentEquals(payload))
        assertEquals("应当只发一次带 Range 的请求", 1, stub.rangedRequests.get())
        assertTrue("续传不该把已下部分重下", stub.sentBytes.get() < payload.size)
    }

    @Test
    fun `服务端不支持续传时从头重下且内容正确`() {
        val payload = payload(size = 600_000)
        val stub = serve(payload).also { server = it }
        stub.supportRange = false
        val target = modelFile()

        target.parentFile.mkdirs()
        File(target.parentFile, target.name + ".part").writeBytes(payload.copyOfRange(0, 200_000))

        installerOf(target, stub).install()

        assertTrue("不支持 Range 时必须丢弃旧片重下，否则文件会是「半新半旧」", target.readBytes().contentEquals(payload))
    }

    @Test
    fun `半成品比目标还大时丢弃重下`() {
        val payload = payload(size = 300_000)
        val stub = serve(payload).also { server = it }
        val target = modelFile()

        target.parentFile.mkdirs()
        // 换了模型、或上次写坏，都可能留下比目标更大的半成品
        File(target.parentFile, target.name + ".part").writeBytes(ByteArray(500_000))

        installerOf(target, stub).install()

        assertTrue(target.readBytes().contentEquals(payload))
    }

    // ------------------------------------------------------------------ 标记

    @Test
    fun `内容正确但没标记时重算摘要后接受`() {
        val payload = payload()
        val stub = serve(payload).also { server = it }
        val target = modelFile()
        target.parentFile.mkdirs()
        target.writeBytes(payload)
        assertFalse("标记不存在", File(target.parentFile, target.name + ".sha256").exists())

        val installer = installerOf(target, stub)

        // 从备份恢复文件、或旧版本装的模型没写标记，都会走到这条路上。
        // 直接判「未安装」会让用户白下 24MB，所以这里真算一次摘要。
        assertTrue(installer.isInstalled())
        assertTrue("接受后应补上标记，避免每次冷启动都重算 24MB", File(target.parentFile, target.name + ".sha256").exists())
    }

    @Test
    fun `大小正确但内容错误时判为未安装`() {
        val payload = payload()
        val stub = serve(payload).also { server = it }
        val target = modelFile()
        target.parentFile.mkdirs()
        target.writeBytes(payload.copyOf())
        target.writeBytes(ByteArray(payload.size))   // 同尺寸、全零

        assertFalse(installerOf(target, stub).isInstalled())
    }

    // ------------------------------------------------------------------ 辅助

    private fun payload(size: Int = 600_000, salt: Int = 0): ByteArray =
        ByteArray(size) { index -> ((index * 31 + salt) % 251).toByte() }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun modelFile(): File = File(temp.newFolder("model"), BgeSmallZh.FILE_NAME)

    private fun installerOf(target: File, stub: StubServer): ModelInstaller =
        installWith(target, stub, sha256Of(stub.payload), stub.payload.size.toLong())

    private fun installWith(
        target: File,
        stub: StubServer,
        sha256: String,
        size: Long = stub.payload.size.toLong(),
    ): ModelInstaller = ModelInstaller(
        target,
        listOf(ModelSource(stub.name, stub.url("/model"), sha256, size)),
    )

    private fun serve(payload: ByteArray): StubServer = StubServer(payload).also { it.start() }

    /**
     * 极简 HTTP 服务，用裸 `ServerSocket` 实现。
     *
     * **为什么不用 JDK 自带的 `com.sun.net.httpserver`**：Android 单测的 Kotlin 编译带
     * `-no-jdk`，只对着 `android.jar` 编译，而 `com.sun.net.httpserver` 不在 android.jar 里，
     * 会直接编译不过。`java.net` 在，所以就着它手写一层，几十行的事。
     *
     * `/model` 返回模型内容并支持 `Range`；`/corrupt` 返回损坏内容；其余路径 404。
     *
     * `sentBytes` 用来断言「续传确实只传了剩下的那部分」—— 只看最终文件是否完整
     * 是抓不出「重下了整份但结果碰巧也对」的。
     */
    private class StubServer(val payload: ByteArray) {

        val requests = AtomicInteger()
        val rangedRequests = AtomicInteger()
        val sentBytes = AtomicInteger()

        /** 非空时 `/corrupt` 返回这份内容，用来测「镜像给了脏数据」。 */
        @Volatile
        var corrupt: ByteArray? = null

        @Volatile
        var supportRange: Boolean = true

        val name = "测试镜像"

        private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

        @Volatile
        private var running = false

        private val thread = Thread({ acceptLoop() }, "stub-http").apply { isDaemon = true }

        fun start() {
            running = true
            thread.start()
        }

        fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        fun url(path: String): String = "http://127.0.0.1:${serverSocket.localPort}$path"

        private fun acceptLoop() {
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    return // stop() 关掉监听套接字后 accept 会抛，正常退出
                }
                // 串行处理：这些测试一次只发一个请求，不值得为它引线程池
                runCatching { handle(socket) }
            }
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                connection.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())

                val requestLine = readLine(input) ?: return
                val path = requestLine.split(' ').getOrNull(1).orEmpty()

                var rangeHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                requests.incrementAndGet()

                val body = when (path) {
                    "/model" -> payload
                    "/corrupt" -> corrupt ?: payload
                    else -> {
                        writeResponse(output, 404, "Not Found", ByteArray(0), null)
                        return
                    }
                }

                val start = if (rangeHeader != null && supportRange) {
                    rangeHeader.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                } else {
                    0L
                }

                if (start > 0L) {
                    rangedRequests.incrementAndGet()
                    val slice = body.copyOfRange(start.toInt(), body.size)
                    sentBytes.addAndGet(slice.size)
                    writeResponse(
                        output,
                        206,
                        "Partial Content",
                        slice,
                        "bytes $start-${body.size - 1}/${body.size}",
                    )
                } else {
                    sentBytes.addAndGet(body.size)
                    writeResponse(output, 200, "OK", body, null)
                }
            }
        }

        private fun writeResponse(
            output: OutputStream,
            code: Int,
            reason: String,
            body: ByteArray,
            contentRange: String?,
        ) {
            val header = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (contentRange != null) append("Content-Range: $contentRange\r\n")
                // 每个请求一个连接，省掉 keep-alive 的状态管理
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        }

        /**
         * 读一行，逐字节拼。
         *
         * 刻意不构造 `String(ByteArray, Charset)` —— 那个重载是 API 26 才有的，
         * 而本项目 minSdk 21。请求行和头部都是 ASCII，逐字节拼没有编码问题。
         */
        private fun readLine(input: InputStream): String? {
            val line = StringBuilder(64)
            while (true) {
                val next = input.read()
                if (next < 0) return if (line.isEmpty()) null else line.toString()
                if (next == '\n'.code) {
                    if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                    return line.toString()
                }
                line.append(next.toChar())
            }
        }

        private companion object {
            const val SOCKET_TIMEOUT_MS = 10_000
        }
    }
}
