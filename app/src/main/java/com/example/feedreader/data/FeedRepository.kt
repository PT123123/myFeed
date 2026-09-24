package com.example.feedreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** 抓取/解析环节自己抛的错，带可以直接展示给用户的中文说明。 */
class FeedException(message: String) : IOException(message)

class FeedRepository(
    private val client: OkHttpClient = defaultClient(),
    private val parser: RssParser = RssParser(),
) {

    /**
     * 并发拉取全部源。单个源失败不影响其它源，失败信息随结果一起返回。
     */
    suspend fun fetchAll(sources: List<FeedSource>): FetchOutcome = coroutineScope {
        val tasks = sources.map { source ->
            async(Dispatchers.IO) { source to runCatching { fetchOne(source) } }
        }

        val articles = ArrayList<Article>()
        val failures = ArrayList<FeedFailure>()

        for (task in tasks) {
            val (source, result) = task.await()
            result.onSuccess { articles += it }
                .onFailure { failures += FeedFailure(source.name, describe(it)) }
        }

        articles.sortByDescending { it.publishedAt }
        FetchOutcome(articles, failures)
    }

    private fun fetchOne(source: FeedSource): List<Article> = client.newCall(requestFor(source)).execute().use { response ->
        ensureOk(response)

        val body = response.body ?: throw FeedException("响应为空")
        if (body.contentLength() > MAX_BYTES) throw FeedException("内容过大")

        val bytes = body.bytes()
        if (bytes.isEmpty()) throw FeedException("响应为空")
        if (looksLikeHtml(bytes)) throw FeedException("该地址返回的是网页，不是订阅源")

        val text = decode(bytes, body.contentType()?.charset())
        if (!text.contains('<')) throw FeedException("内容不是有效的 XML")

        return parser.parse(text, source)
    }

    private fun requestFor(source: FeedSource): Request = Request.Builder()
        .url(source.url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .build()

    private fun ensureOk(response: Response) {
        if (!response.isSuccessful) {
            throw FeedException("HTTP ${response.code}")
        }
    }

    /**
     * 按 XML 声明里的 encoding 解码。中国区不少老订阅源是 GBK，而服务器
     * Content-Type 常常错误地写成 utf-8，所以以声明为准、Content-Type 兜底。
     */
    private fun decode(bytes: ByteArray, contentTypeCharset: Charset?): String {
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
        val declared = ENCODING.find(head)?.groupValues?.get(1)
        val charset = declared?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        } ?: contentTypeCharset ?: Charsets.UTF_8

        return String(bytes, charset)
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
            .trimStart()
            .lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun describe(error: Throwable): String = when (error) {
        is FeedException -> error.message ?: "拉取失败"
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException -> "连接超时"
        is XmlPullParserException -> "XML 解析失败"
        is IOException -> error.message ?: "网络错误"
        else -> error.message ?: error.javaClass.simpleName
    }

    companion object {
        private const val MAX_BYTES = 5L * 1024 * 1024

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 FeedReader/1.0"

        private val ENCODING = Regex("""encoding\s*=\s*["']([\w.:-]+)["']""", RegexOption.IGNORE_CASE)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            // 十几个源并发拉，整体等待时间取决于最慢的那个；给个比 read 稍宽的上限兜住重定向链
            .callTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
