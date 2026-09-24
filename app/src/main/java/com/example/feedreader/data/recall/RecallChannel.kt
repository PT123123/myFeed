package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 取一个 URL 的正文。
 *
 * 抽成接口只为一件事：**让通道的 URL 拼装和解析能脱离网络单测**。
 * 六个通道的真实响应都存在 `app/src/test/resources/recall/`，
 * 而「字段名写错 / 类型不符 / 缺字段 / 嵌套层级记错」正是这类解析最容易出错的地方，
 * 从外面看全都表现为「这条通道永远 0 结果」，不起网络根本查不出来。
 */
fun interface HttpGet {
    /** 失败抛 [IOException]（含 HTTP 非 2xx），由调用方记为该通道的一次失败。 */
    @Throws(IOException::class)
    suspend fun text(url: String, accept: String): String
}

class OkHttpGet(
    private val client: OkHttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpGet {

    override suspend fun text(url: String, accept: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            if (body.contentLength() > MAX_BYTES) throw IOException("内容过大")
            // 不需要手动解 gzip：OkHttp 自己加 Accept-Encoding 并透明解压。
            // （StackExchange 的接口是**无条件** gzip 的，换成别的 HTTP 客户端
            //   时这一点必须自己处理，否则拿到的是一堆二进制。）
            body.string()
        }
    }

    companion object {
        /**
         * 用浏览器 UA 而不是 App 标识：GitHub 搜索 API 不带 UA 直接 403，
         * 而 CSDN / sov2ex 对陌生 UA 会降级或拒绝。
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 FeedReader/1.0"

        private const val MAX_BYTES = 4L * 1024 * 1024
    }
}

/**
 * 一路召回通道。
 *
 * [url] 和 [parse] 分开暴露是有意的：这两件最容易出错的事都要能单测，
 * 而它们恰好是纯函数 —— 一个从查询串得到地址，一个从响应正文得到文章列表。
 */
interface RecallChannel {

    /** 稳定标识，用于设置页的开关持久化，**不要随意改**。 */
    val id: String

    val name: String

    /** 列表里显示的来源标签，会写进 [Article.category]。 */
    val category: String

    /** 一条通道一次最多要几条。国内 JSON 接口和翻墙的国外 API 成本差得远，不设统一值。 */
    val limit: Int

    /** 面向英文语料：中文查询串发过去必然空手而归，改用 [RecallQuery.latin]。 */
    val prefersLatin: Boolean get() = false

    val accept: String get() = "application/json"

    /** 拼请求地址。[limit] 已由调用方收敛到不超过 [limit]。 */
    fun url(query: String, limit: Int): String

    /**
     * 解析响应正文。
     *
     * 契约：**畸形 / 空输入返回空表，不抛异常** —— 一路解析炸了不该拖垮整次刷新，
     * 更不该让另外五路白跑。
     */
    fun parse(body: String): List<Article>

    /** 发请求 + 解析。网络异常留给调用方捕获，记为该通道的一次失败。 */
    suspend fun recall(http: HttpGet, query: RecallQuery): List<Article> {
        val term = if (prefersLatin) query.latin else query.keyword
        if (term.isEmpty()) return emptyList()
        return parse(http.text(url(term, minOf(limit, query.limit)), accept))
    }
}
