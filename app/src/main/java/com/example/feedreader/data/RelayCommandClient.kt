package com.example.feedreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 手机 → 电脑的采集命令口。
 *
 * 只做三件事：拼地址、带 token、把回包交给 [RelayCommands] 解析。
 * 判定与文案都不在这里 —— 前者在 [RelayCommands]，后者在 [RelayStatus]。
 *
 * 超时单独配：命令口的 `run` 是「受理」不是「跑完」（一轮要几十秒到几分钟），
 * 所以这里几秒就该给出答案；真结果由调用方轮询 [status]。
 */
class RelayCommandClient(
    private val base: String,
    private val token: String,
    client: OkHttpClient,
) {

    private val http = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .build()

    /** [feed] 非空就是「只采这一条」；[RelayCommands.ACTION_PLAN]/[RelayCommands.ACTION_REGEN] 忽略它。 */
    suspend fun send(action: String, feed: String?): RelayCommands.Reply = request(
        path = "/command/$action",
        body = if (feed.isNullOrBlank()) "{}" else """{"feed":"${jsonString(feed)}"}""",
    )

    suspend fun status(): RelayCommands.Status? = withContext(Dispatchers.IO) {
        val url = url("/command/status") ?: return@withContext null
        val request = Request.Builder().url(url).get().build()
        execute(request).getOrNull()?.let { (code, text) ->
            if (code in 200..299) RelayCommands.parseStatus(text) else null
        }
    }

    private suspend fun request(path: String, body: String): RelayCommands.Reply =
        withContext(Dispatchers.IO) {
            val url = url(path)
                ?: return@withContext RelayCommands.Reply.Refused("BAD_BASE", "还没配这台电脑的地址")
            if (token.isBlank()) {
                // 没 token 就别发出去：对面会回 401，而那句 401 说的话和这里一样，
                // 但白跑一次网络往返换不来任何信息
                return@withContext RelayCommands.Reply.Refused(
                    "NEEDS_TOKEN", "这台电脑还没配对：去电脑上打开 /pair 扫码",
                )
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .header("Authorization", "Bearer $token")
                .build()
            execute(request).fold(
                onSuccess = { (code, text) -> RelayCommands.parseReply(code, text) },
                onFailure = { RelayCommands.Reply.Transport(FeedRepository.describe(it)) },
            )
        }

    private fun execute(request: Request): Result<Pair<Int, String>> = try {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code in 200..299 || text.isNotBlank()) Result.success(response.code to text)
            else Result.failure(IOException("HTTP ${response.code}"))
        }
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun url(path: String): String? {
        val normalized = base.trim().trimEnd('/')
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "$normalized$path"
        } else {
            null
        }
    }

    /**
     * feed id 是从源地址的文件名来的，理论上只有 `[A-Za-z0-9_.-]`，
     * 但它也可能来自一张手改过的 feeds.json —— 一个引号就能把这条请求体变成别的字段。
     */
    private fun jsonString(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '"', '\\' -> append('\\').append(char)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else append(char)
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 8L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
