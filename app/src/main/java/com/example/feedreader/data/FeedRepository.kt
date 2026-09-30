package com.example.feedreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    /** 命令口（[RelayCommandClient]）共用这一份连接池：同一台 PC，没必要再开一套。 */
    val client: OkHttpClient = defaultClient(),
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
        // (源名, 地址, 原因)：先攒着，最后按「同一台主机 + 同一种原因」收成几行
        val rows = ArrayList<Triple<String, String, String>>(sources.size)

        for (task in tasks) {
            val (source, result) = task.await()
            result.onSuccess { articles += it }
                .onFailure { rows += Triple(source.name, source.url, describe(it)) }
        }

        articles.sortByDescending { it.publishedAt }
        FetchOutcome(articles, collapseFailures(rows))
    }

    /**
     * 探测一个地址能不能当订阅源用。给「添加订阅源」做即时校验。
     *
     * 校验逻辑和正式抓取完全共用（[fetchText]），所以「探测通过」的含义是
     * 「以后每次刷新也能拉到」，而不只是「这个地址现在打得开」—— 后者太好满足了，
     * 一个网页也能返回 200。
     *
     * @param url 用户原样粘进来的地址，内部自己 trim。
     */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        // 探测只需要地址，其余字段随便给 —— 解析结果不落库，只用来数条目数
        val stub = FeedSource(
            id = "probe",
            name = "probe",
            url = url.trim(),
            category = "",
        )
        runCatching {
            val text = fetchText(stub)
            val articles = parser.parse(text, stub)
            if (articles.isEmpty()) throw FeedException("地址能打开，但里面一条内容都没有")

            ProbeResult.Ok(
                title = parser.feedTitle(text).orEmpty(),
                itemCount = articles.size,
            )
        }.getOrElse { ProbeResult.Failed(describe(it)) }
    }

    private fun fetchOne(source: FeedSource): List<Article> =
        parser.parse(fetchText(source), source)

    /**
     * 拉回正文并做完所有「这到底是不是个 feed」的检查，返回解码后的 XML 文本。
     *
     * 拆出来是为了让 [probe] 和 [fetchOne] 共用同一套判据 —— 校验逻辑散成两份的话，
     * 迟早会出现「探测说没问题、刷新时又拉不动」这种最难查的不一致。
     */
    private fun fetchText(source: FeedSource): String =
        client.newCall(requestFor(source)).execute().use { response ->
            ensureOk(response)

            val body = response.body ?: throw FeedException("响应为空")
            if (body.contentLength() > MAX_BYTES) throw FeedException("内容过大")

            val bytes = body.bytes()
            if (bytes.isEmpty()) throw FeedException("响应为空")
            if (looksLikeHtml(bytes)) throw FeedException("该地址返回的是网页，不是订阅源")

            val text = decode(bytes, body.contentType()?.charset())
            if (!text.contains('<')) throw FeedException("内容不是有效的 XML")
            text
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

    companion object {
        private const val MAX_BYTES = 5L * 1024 * 1024

        /**
         * 一条异常对用户怎么说。
         *
         * 放在 companion 里是因为 `internal` 的那句「连不上是哪一种」不止这里要用：
         * 命令口 [RelayCommandClient] 也要拿同样的说法去喂 `RelayGuidance.issueFromFetchMessage`，
         * 两边措辞一旦分叉，电脑端对症提示就会在某一条路上悄悄失灵。
         */
        internal fun describe(error: Throwable): String = when (error) {
            is FeedException -> error.message ?: "拉取失败"
            is UnknownHostException -> "域名解析失败"
            is SocketTimeoutException -> "连接超时"
            is XmlPullParserException -> "XML 解析失败"
            is IOException -> error.message ?: "网络错误"
            else -> error.message ?: error.javaClass.simpleName
        }

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 FeedReader/1.0"

        private val ENCODING = Regex("""encoding\s*=\s*["']([\w.:-]+)["']""", RegexOption.IGNORE_CASE)

        /**
         * 同一台主机、同一种失败原因的源收成一行。
         *
         * 21 条 relay 源共用一台 PC —— 它一关机就是 21 行「连接超时」，把真正该看到的
         * 「那台机器不在线」埋进刷屏里；没开 VPN 时国外那批源也是同样的形状。
         *
         * 只按**原因**合并不行：知乎本机和 openai 都超时，那是两件不相干的事，合成一行
         * 就再也分不出该去开哪一头。所以键里带上主机。
         */
        internal fun collapseFailures(rows: List<Triple<String, String, String>>): List<FeedFailure> {
            if (rows.isEmpty()) return emptyList()

            val groups = LinkedHashMap<Pair<String, String>, MutableList<String>>()
            for ((name, url, message) in rows) {
                groups.getOrPut(RelayRules.authorityOf(url) to message) { ArrayList() } += name
            }
            return groups.map { (key, names) ->
                val (host, message) = key
                FeedFailure(
                    source = if (names.size == 1) names.single()
                    else "${names.first()} 等 ${names.size} 个源（$host）",
                    message = message,
                    // 主机随失败一起带出去：界面要能认出「这批失败就是 relay 那台 PC」
                    host = host,
                )
            }
        }

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
