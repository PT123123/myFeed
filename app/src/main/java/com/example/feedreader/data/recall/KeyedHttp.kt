package com.example.feedreader.data.recall

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 一把带鉴权的 HTTP 请求：方法 + 地址 + 头 + 可选 body。
 *
 * 和 [HttpGet] 只管 GET + 固定 UA/Accept 不一样，搜索 API（Perplexity / 秘塔 / Tavily /
 * Brave）全是 **POST + Bearer token**（Brave 更特殊，用 `X-Subscription-Token` 头）。
 * 所以单独开一条，keyed 通道在 [RecallChannel.recall] 里覆写默认实现、改用它，
 * 普通通道完全不受影响。
 */
data class KeyedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/**
 * 带 key 的搜索 API 走这条。
 *
 * 失败抛 [IOException]（含 HTTP 非 2xx），交给 [RecallService] 记成一次失败 —— 和别的通道一致，
 * 这样「key 填错了 / 网络不通」能如实反映到失败横幅里，而不是静默吞掉让用户以为没生效。
 */
fun interface KeyedHttp {
    @Throws(IOException::class)
    suspend fun request(req: KeyedRequest): String
}

class OkHttpKeyed(
    private val client: OkHttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : KeyedHttp {

    override suspend fun request(req: KeyedRequest): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(req.url)
            .header("User-Agent", userAgent)
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = req.body?.toRequestBody(JSON)
        builder.method(req.method.uppercase(), body)

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("响应为空")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 FeedReader/1.0"
    }
}

/** 测试用：不真正发请求，直接返回空串。 */
object KeyedHttpNoop : KeyedHttp {
    override suspend fun request(req: KeyedRequest): String = ""
}
