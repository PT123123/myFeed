package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import com.example.feedreader.data.DateParser
import com.example.feedreader.data.json.Json
import com.example.feedreader.data.json.JsonValue
import com.example.feedreader.data.json.arrOrEmpty
import com.example.feedreader.data.json.objOrEmpty
import com.example.feedreader.data.json.stringOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * 需要 API key 的搜索通道的基类。
 *
 * 和 [RecallChannel] 默认「GET 一个 URL、用 [HttpGet] 取正文」不一样，这类通道是
 * **POST（或带特殊鉴权头的 GET）+ JSON body**，所以这里覆写 [recall]，改走 [KeyedHttp]。
 *
 * 没填 key 时 [recall] 直接返回空表 —— 这条通道等于不存在：不打网络、不记失败、
 * 也不打扰用户。填了 key 但请求失败（网络不通 / key 错）就照常抛异常，
 * 由 [RecallService] 记成一次失败，和别的通道一样，让用户能看清「为什么没出来」。
 */
abstract class KeyedSearchChannel(
    override val id: String,
    override val name: String,
    override val category: String,
    override val limit: Int,
    protected val apiKey: String,
    protected val keyedHttp: KeyedHttp,
) : RecallChannel {

    /** 普通通道用的 url()/parse() 在这里用不到（召回走 [recall]），留空实现。 */
    override fun url(query: String, limit: Int): String = ""
    override fun parse(body: String): List<Article> = emptyList()

    override suspend fun recall(_http: HttpGet, query: RecallQuery): List<Article> {
        if (apiKey.isBlank()) return emptyList()
        // 顺手 trim：用户兴趣词可能带首尾空格（尤其是从别处粘贴），带着空格发给搜索
        // API 既浪费又可能让结果变怪；trim 后才判空，纯空格的兴趣词直接不召回。
        val term = (if (prefersLatin) query.latin else query.keyword).trim()
        if (term.isEmpty()) return emptyList()
        val req = buildRequest(term, minOf(limit, query.limit))
        val body = keyedHttp.request(req)
        return parseResults(body)
    }

    /** 把关键词和条数拼成一次请求。 */
    abstract fun buildRequest(term: String, limit: Int): KeyedRequest

    /** 把响应正文解析成文章列表。畸形 JSON 自己 return emptyList()，别抛（否则静默丢结果）。 */
    internal abstract fun parseResults(body: String): List<Article>

    /** 子类共用：从一组 JSON 对象里取字段拼成一条文章；拿不到链接的那条直接丢弃。 */
    protected fun article(
        link: String?,
        title: String?,
        excerpt: String?,
        publishedRaw: String?,
        sourceId: String,
        sourceName: String,
    ): Article? {
        val url = link?.takeIf { it.isNotBlank() } ?: return null
        return Article(
            id = "$sourceId:$url",
            title = title?.takeIf { it.isNotBlank() } ?: url,
            excerpt = excerpt?.takeIf { it.isNotBlank() } ?: "",
            link = url,
            author = "",
            sourceId = sourceId,
            sourceName = sourceName,
            category = category,
            publishedAt = DateParser.parse(publishedRaw),
            publishedRaw = publishedRaw?.takeIf { it.isNotBlank() } ?: "",
        )
    }
}

/** JSON 字符串里的最小转义：关键词进 body 时把引号/反斜杠/换行处理掉。 */
private fun jsonEscaped(s: String): String = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}

/**
 * Perplexity Sonar。
 *
 * `POST /chat/completions`，模型用 `sonar`（便宜又快）。返回里 `search_results[]`
 * 是带 title/url/date/snippet 的结构化来源 —— 正好当文章卡片；只有裸 `citations`
 * 时也兜底成文章。限定近一个月，保证新鲜度。
 */
class PerplexityChannel(
    apiKey: String,
    keyedHttp: KeyedHttp,
) : KeyedSearchChannel("perplexity", "Perplexity Sonar", "AI搜索", 10, apiKey, keyedHttp) {

    override fun buildRequest(term: String, limit: Int): KeyedRequest = KeyedRequest(
        method = "POST",
        url = "https://api.perplexity.ai/chat/completions",
        headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ),
        body = """{"model":"sonar","messages":[{"role":"user","content":"${jsonEscaped(term)}"}],"search_recency_filter":"month"}""",
    )

    override fun parseResults(body: String): List<Article> {
        val root = Json.parseOrNull(body)?.objOrEmpty() ?: return emptyList()
        val results = root["search_results"].arrOrEmpty()
        // 兜底：有的返回只有 citations（裸 URL 数组），也得能出内容
        val items = if (results.isNotEmpty()) {
            results
        } else {
            root["citations"].arrOrEmpty().mapNotNull { v ->
                (v as? JsonValue.Str)?.value?.let { JsonValue.Obj(mapOf("url" to JsonValue.Str(it))) }
            }
        }
        return items.mapNotNull { v ->
            val o = v.objOrEmpty()
            article(
                link = o["url"]?.stringOrNull(),
                title = o["title"]?.stringOrNull(),
                excerpt = o["snippet"]?.stringOrNull() ?: o["content"]?.stringOrNull(),
                publishedRaw = o["date"]?.stringOrNull(),
                sourceId = "pplx",
                sourceName = "Perplexity",
            )
        }
    }
}

/**
 * 秘塔 Metaso。
 *
 * `POST /api/v1/search`，中文 AI 搜索，国内可达。响应结构没正式文档，按字段名宽容找：
 * `webpages` / `results` / `data.results` 都认；链接字段 `link` 和 `url` 都认。
 */
class MetasoChannel(
    apiKey: String,
    keyedHttp: KeyedHttp,
) : KeyedSearchChannel("metaso", "秘塔 Metaso", "AI搜索(中文)", 10, apiKey, keyedHttp) {

    override fun buildRequest(term: String, limit: Int): KeyedRequest = KeyedRequest(
        method = "POST",
        url = "https://metaso.cn/api/v1/search",
        headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ),
        body = """{"q":"${jsonEscaped(term)}","scope":"webpage","size":$limit,"includeSummary":true}""",
    )

    override fun parseResults(body: String): List<Article> {
        val root = Json.parseOrNull(body)?.objOrEmpty() ?: return emptyList()
        val array = root["webpages"].arrOrEmpty()
            .ifEmpty { root["results"].arrOrEmpty() }
            .ifEmpty { root["data"].objOrEmpty().get("results").arrOrEmpty() }
        return array.mapNotNull { v ->
            val o = v.objOrEmpty()
            article(
                link = o["link"]?.stringOrNull() ?: o["url"]?.stringOrNull(),
                title = o["title"]?.stringOrNull(),
                excerpt = o["snippet"]?.stringOrNull()
                    ?: o["description"]?.stringOrNull()
                    ?: o["content"]?.stringOrNull(),
                publishedRaw = o["date"]?.stringOrNull(),
                sourceId = "metaso",
                sourceName = "秘塔 Metaso",
            )
        }
    }
}

/**
 * Tavily。
 *
 * `POST /search`，AI 优化的全网搜索。返回 `results[]`：title/url/content/score。
 * 没有日期字段，publishedAt 留 0（展示时回退到「时间未知」）。
 */
class TavilyChannel(
    apiKey: String,
    keyedHttp: KeyedHttp,
) : KeyedSearchChannel("tavily", "Tavily", "全网搜索", 10, apiKey, keyedHttp) {

    override fun buildRequest(term: String, limit: Int): KeyedRequest = KeyedRequest(
        method = "POST",
        url = "https://api.tavily.com/search",
        headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ),
        body = """{"query":"${jsonEscaped(term)}","max_results":$limit,"search_depth":"basic","include_images":false}""",
    )

    override fun parseResults(body: String): List<Article> {
        val root = Json.parseOrNull(body)?.objOrEmpty() ?: return emptyList()
        return root["results"].arrOrEmpty().mapNotNull { v ->
            val o = v.objOrEmpty()
            article(
                link = o["url"]?.stringOrNull(),
                title = o["title"]?.stringOrNull(),
                excerpt = o["content"]?.stringOrNull(),
                publishedRaw = null,
                sourceId = "tavily",
                sourceName = "Tavily",
            )
        }
    }
}

/**
 * Brave Search。
 *
 * **GET** `https://api.search.brave.com/res/v1/web/search`，鉴权用 `X-Subscription-Token`
 * 头（不是 Bearer，这是它最容易踩的坑）。返回 `web.results[]`：title/url/description。
 */
class BraveChannel(
    apiKey: String,
    keyedHttp: KeyedHttp,
) : KeyedSearchChannel("brave", "Brave Search", "全网搜索", 10, apiKey, keyedHttp) {

    override fun buildRequest(term: String, limit: Int): KeyedRequest {
        val q = URLEncoder.encode(term, "UTF-8")
        return KeyedRequest(
            method = "GET",
            url = "https://api.search.brave.com/res/v1/web/search?q=$q&count=$limit",
            headers = mapOf(
                "X-Subscription-Token" to apiKey,
                "Accept" to "application/json",
            ),
        )
    }

    override fun parseResults(body: String): List<Article> {
        val root = Json.parseOrNull(body)?.objOrEmpty() ?: return emptyList()
        val results = root["web"].objOrEmpty().get("results").arrOrEmpty()
        return results.mapNotNull { v ->
            val o = v.objOrEmpty()
            article(
                link = o["url"]?.stringOrNull(),
                title = o["title"]?.stringOrNull(),
                excerpt = o["description"]?.stringOrNull(),
                publishedRaw = null,
                sourceId = "brave",
                sourceName = "Brave Search",
            )
        }
    }
}
