package com.example.feedreader.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 手机真的把命令发出去的那一段。
 *
 * 用一个一次性假服务器（`ServerSocket`，随起随关），因为这几件事只能在**送出之前**验：
 * 路径对不对、`Authorization` 头带没带全、请求体里那个 feed id 会不会因为一个引号变成
 * 别的字段、没配对时是不是真的一个包都没发。这些在真机上出问题都只表现为
 * 「电脑拒绝了这条命令」，看不出原因。
 */
class RelayCommandClientTest {

    private val okHttp = OkHttpClient()

    @Test
    fun `plan 走的是 POST 带 Bearer 空体`() {
        FakeRelay().use { relay ->
            val reply = runBlocking {
                RelayCommandClient(relay.base, "t-123456", okHttp).send(RelayCommands.ACTION_PLAN, null)
            }

            assertEquals(RelayCommands.Reply.Plan(emptyList()), reply)
            assertEquals(1, relay.seen.get())
            assertEquals("POST /command/plan HTTP/1.1", relay.firstLine)
            assertEquals("Bearer t-123456", relay.headers["authorization"])
            assertEquals("{}", relay.requestBody)
        }
    }

    /** 「只采这一条」的全部信息就是那一个字段。 */
    @Test
    fun `run 带上 feed`() {
        FakeRelay().use { relay ->
            runBlocking {
                RelayCommandClient(relay.base, "t-1", okHttp)
                    .send(RelayCommands.ACTION_RUN, "zhihu-sample-zhi-75-54")
            }

            assertEquals("""{"feed":"zhihu-sample-zhi-75-54"}""", relay.requestBody)
        }
    }

    /**
     * 引号不许把请求体变成两个字段。
     *
     * feed id 正常是 `[A-Za-z0-9_.-]`，但它来自一张可能被手改过的 feeds.json，
     * 而这条路上没有任何东西替我挡 `"`。
     */
    @Test
    fun `feed id 里的引号与控制字符被转义`() {
        FakeRelay().use { relay ->
            runBlocking {
                RelayCommandClient(relay.base, "t-1", okHttp)
                    .send(RelayCommands.ACTION_RUN, "a\"}, \"x\": 1, \"y\": \"\n\t\\z")
            }

            val sent = MiniJson.objectOf(MiniJson.parse(relay.requestBody))!!

            assertEquals("请求体只能有 feed 这一个键", setOf("feed"), sent.keys)
            assertEquals("a\"}, \"x\": 1, \"y\": \"\n\t\\z", sent["feed"])
        }
    }

    /** 没配对就别发包：对面那句 401 说的话和这里一样，白跑一趟换不来信息。 */
    @Test
    fun `没有 token 时一个请求都不发`() {
        FakeRelay().use { relay ->
            val reply = runBlocking {
                RelayCommandClient(relay.base, "", okHttp).send(RelayCommands.ACTION_RUN, null)
            }

            assertEquals(0, relay.seen.get())
            assertTrue(reply is RelayCommands.Reply.Refused)
            assertEquals("NEEDS_TOKEN", (reply as RelayCommands.Reply.Refused).code)
        }
    }

    /** 地址还没配过 —— 同样不该发。 */
    @Test
    fun `地址不像地址时不发`() {
        val reply = runBlocking {
            RelayCommandClient("不是地址", "t-1", okHttp).send(RelayCommands.ACTION_PLAN, null)
        }

        assertEquals("BAD_BASE", (reply as RelayCommands.Reply.Refused).code)
    }

    /** 对面的 401 原样翻成 Refused，并把那句话带回来（界面上指着 /pair 扫码）。 */
    @Test
    fun `对面拒绝时照它的话说`() {
        FakeRelay(
            code = 401,
            responseBody = """{"protocol": "harvest/1", "ok": false, "action": "command", "data": {},""" +
                """ "meta": {}, "error": {"code": "NEEDS_TOKEN", "message": "去电脑上打开 /pair 扫码配对"}}""",
        ).use { relay ->
            val reply = runBlocking {
                RelayCommandClient(relay.base, "wrong-token", okHttp).send(RelayCommands.ACTION_REGEN, null)
            }

            assertEquals(
                RelayCommands.Reply.Refused("NEEDS_TOKEN", "去电脑上打开 /pair 扫码配对"),
                reply,
            )
        }
    }

    /** 端口上没人听 —— 要说成「没连上」，而不是「电脑拒绝了」。 */
    @Test
    fun `连不上时是 Transport`() {
        val dead = FakeRelay().also { it.close() }
        val reply = runBlocking {
            RelayCommandClient(dead.base, "t-1", okHttp).send(RelayCommands.ACTION_PLAN, null)
        }

        assertTrue("实际 $reply", reply is RelayCommands.Reply.Transport)
    }

    /** 轮询那条路是 GET，而且只在 2xx 时才认回包。 */
    @Test
    fun `状态用 GET 且非 2xx 不给半截`() {
        FakeRelay(
            responseBody = """{"protocol": "harvest/1", "ok": true, "action": "status",
                       "data": {"running": true, "current": {"action": "run", "feed": "zhihu-a",
                       "started_at": 1790531674.5}, "last": null}, "meta": {}}""",
        ).use { relay ->
            val status = runBlocking { RelayCommandClient(relay.base, "t-1", okHttp).status() }

            assertEquals("GET /command/status HTTP/1.1", relay.firstLine)
            assertEquals(true, status?.running)
            assertEquals("zhihu-a", status?.currentFeed)
            assertEquals(1790531674500L, status?.startedAt)
        }

        FakeRelay(code = 500, responseBody = """{"error": {"code": "X", "message": "y"}}""").use { relay ->
            val status = runBlocking { RelayCommandClient(relay.base, "t-1", okHttp).status() }

            // 读不懂就当「这次没问到」：让调用方保留上一份状态，而不是显示成「没在跑」
            assertNull(status)
        }
    }

    /** 没配地址时 status 也不该发。 */
    @Test
    fun `没地址时状态问都不问`() {
        assertNull(runBlocking { RelayCommandClient("", "t-1", okHttp).status() })
    }

    /**
     * 一次性的假 relay：接一个连接、把请求记下来、回一份写死的响应。
     *
     * 每个用例起一份最省事（`Connection: close`），端口 0 让系统挑，免得并行跑测试时撞车。
     */
    private class FakeRelay(
        private val code: Int = 200,
        private val responseBody: String =
            """{"protocol": "harvest/1", "ok": true, "action": "plan", "data": {"plan": []}, "meta": {}}""",
    ) : AutoCloseable {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

        val base: String = "http://127.0.0.1:${socket.localPort}"
        val seen = AtomicInteger(0)
        var firstLine: String = ""
            private set
        var headers: Map<String, String> = emptyMap()
            private set
        var requestBody: String = ""
            private set

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (error: Exception) {
                        return@thread
                    }
                    serve(connection)
                }
            }
        }

        private fun serve(connection: java.net.Socket) {
            connection.use {
                val reader = it.getInputStream().bufferedReader()
                firstLine = reader.readLine().orEmpty()
                seen.incrementAndGet()
                headers = generateSequence { reader.readLine().takeIf { line -> line.isNotBlank() } }
                    .mapNotNull { line ->
                        val key = line.substringBefore(':', "").trim().lowercase()
                        if (key.isEmpty()) null else key to line.substringAfter(':', "").trim()
                    }
                    .toMap()
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length > 0) {
                    val chars = CharArray(length)
                    var done = 0
                    while (done < length) {
                        val read = reader.read(chars, done, length - done)
                        if (read < 0) break
                        done += read
                    }
                    requestBody = chars.concatToString()
                }

                val payload = responseBody.toByteArray(Charsets.UTF_8)
                it.outputStream.use { stream ->
                    stream.write(
                        (
                            "HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    stream.write(payload)
                    stream.flush()
                }
            }
        }

        override fun close() {
            socket.close()
        }
    }
}
