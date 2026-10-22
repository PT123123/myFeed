package com.example.feedreader.data

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 电脑端服务的一次拒绝。[code] 是服务端自己给的错误码，界面上要说人话由调用方决定。 */
class DesktopSyncException(
    val code: String,
    val status: Int,
    message: String,
) : IOException(message)

/**
 * 手机直连电脑端同步接口（`/api/v1/sync` 下的三条路由）的那层网络壳。
 *
 * 地址一律过 [LlmServicePolicy]：WebView 里的完整客户端和原生这条薄客户端必须遵守
 * 同一条规矩，否则「手机只连用户确认过的那台电脑」这句话就有了第二个解释。
 *
 * 只有三个接口 —— 快照、增量、上报操作。生成任务、提示词、角色、来源、用量那些
 * 是电脑端的生产侧，继续留在 WebView（见 `docs/native-desktop-sync.md`）。
 */
class DesktopSyncClient(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient,
) {

    /** 还没配电脑地址时整条链路静默跳过：这不是错误，是没接入。 */
    val configured: Boolean get() = baseUrl.isNotBlank()

    fun snapshot(): Snapshot {
        val text = get("/api/v1/sync/snapshot")
        return try {
            DesktopFeed.snapshot(text)
        } catch (_: Exception) {
            throw DesktopSyncException("BAD_SNAPSHOT", 200, "电脑端回来的快照读不懂")
        }
    }

    fun changes(sinceSeq: Long, limit: Int): ChangePage {
        val text = get("/api/v1/sync/changes?since_seq=$sinceSeq&limit=$limit")
        return try {
            DesktopFeed.changes(text)
        } catch (_: Exception) {
            throw DesktopSyncException("BAD_CHANGES", 200, "电脑端回来的变更读不懂")
        }
    }

    /** 上报操作。返回逐条裁决；空队列不发请求。 */
    fun push(deviceId: String, ops: List<DesktopOp>): List<OpResult> {
        val body = DesktopFeed.opsRequest(deviceId, ops)
        if (body.isEmpty()) return emptyList()
        val request = Request.Builder()
            .url(url("/api/v1/sync/operations"))
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val text = execute(request)
        return runCatching { DesktopFeed.opResults(text) }.getOrElse {
            throw DesktopSyncException("BAD_OPS", 200, "电脑端对上报的回答读不懂")
        }
    }

    private fun get(path: String): String =
        execute(Request.Builder().url(url(path)).get().build())

    /**
     * 一次请求，拿回响应体文本。
     *
     * 非 2xx 也读一次 body：电脑端的错误说明写在 `detail`/`error` 里，只看状态码的话
     * 用户就只会看到「HTTP 410」这种没法照着办的话。网络层的失败统一换成
     * [FeedRepository.describe] 的口语描述，包成同一种异常，调用方只需接一类。
     */
    private fun execute(request: Request): String = try {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val (code, message) = DesktopFeed.errorCode(response.code, text)
                throw DesktopSyncException(code, response.code, humanize(code, response.code, message))
            }
            text
        }
    } catch (error: DesktopSyncException) {
        throw error
    } catch (error: IOException) {
        throw DesktopSyncException("NETWORK", 0, FeedRepository.describe(error))
    }

    /**
     * 相对地址拼到用户保存的那台电脑上。走 [HttpUrl.resolve] 而不是字符串相加：
     * 查询串、编码、端口都由它核对，本机再拼一遍就多一处能拼错的地方。
     */
    private fun url(path: String): HttpUrl {
        val base = try {
            LlmServicePolicy.base(baseUrl)
        } catch (error: Exception) {
            throw DesktopSyncException("BAD_BASE", 0, error.message ?: "请先在设置里填写电脑服务的根地址")
        }
        return base.resolve(path)
            ?: throw DesktopSyncException("BAD_BASE", 0, "电脑地址拼不出可请求的地址")
    }

    /** 401/404/410 这三类光报 HTTP 码没人知道下一步该做什么，所以各给一句能照着办的话。 */
    private fun humanize(code: String, status: Int, message: String): String = when {
        code == "CURSOR_EXPIRED" || status == 410 -> "电脑端的记录比本机新太多，改成整份重取"
        status == 401 || status == 403 -> "这台电脑拒绝了同步请求：令牌不对或没填"
        status == 404 -> "电脑端没有同步接口：确认服务开着、版本够新"
        message.isNotBlank() -> message
        else -> "电脑端返回 HTTP $status"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
