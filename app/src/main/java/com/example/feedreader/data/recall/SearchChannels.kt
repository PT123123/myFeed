package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import com.example.feedreader.data.DateParser
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.TextCleaner
import com.example.feedreader.data.json.Json
import com.example.feedreader.data.json.JsonValue
import com.example.feedreader.data.json.arrOrEmpty
import com.example.feedreader.data.json.longOr
import com.example.feedreader.data.json.longOrNull
import com.example.feedreader.data.json.objOrEmpty
import com.example.feedreader.data.json.stringOr
import com.example.feedreader.data.json.stringOrNull

/**
 * 五个「关键词 → 文章列表」的 JSON 接口通道。
 *
 * 选它们的依据是**实测过的**「免费 + 无需 key + 支持关键词查询」，逐个列在下面。
 * 上一轮排查的结论是「免费搜索 API 都堵死了」（见 [SearchEngine] 的注释），
 * 那条结论只针对**通用网页搜索**；这几个属于**垂直语料**，是另一回事，
 * 反而全部可用，而且两个是中文的。
 *
 * 字段名全部按真实响应校准过，响应样本存在 `app/src/test/resources/recall/`。
 */

// —— 共用小工具 ——

/**
 * 去掉链接上的跟踪参数。
 *
 * 必要性有两层：CSDN 的 `url` 里塞了 `utm_*` / `ops_request_misc` / `request_id`，
 * 而这些参数带**每次请求都不同**的 trace id —— 不清理的话同一篇文章每次召回都是
 * 新链接，去重完全失效，列表里会反复出现同一篇。
 *
 * 只删明确的跟踪参数，不删整个 query：`watch?v=xxx` 这类功能性参数删了链接就废了，
 * 而且不同视频会撞成同一个地址。
 */
internal fun cleanLink(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""

    val hashless = trimmed.substringBefore('#')
    val mark = hashless.indexOf('?')
    if (mark < 0) return hashless

    // 注意是拿 base（'?' 之前那段）去拼，不是 hashless ——
    // 用 hashless 会把原查询串又接一遍，得到 `…?v=abc?v=abc`。
    val base = hashless.substring(0, mark)
    val kept = hashless.substring(mark + 1)
        .split('&')
        .filter { it.isNotEmpty() && !isTrackingParam(it.substringBefore('=')) }

    return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
}

private val TRACKING_PARAMS = setOf(
    "ops_request_misc", "request_id", "dist_request_id", "biz_id", "spm", "share_token",
    "from_source", "trace_id", "articlefrom", "spm_id_from", "vd_source", "share_source",
)

private fun isTrackingParam(name: String): Boolean {
    val key = name.lowercase()
    // utm_* 是标准跟踪参数族，不用逐个列
    return key.startsWith("utm_") || key in TRACKING_PARAMS
}

/** 标题里的 `<em>` 高亮标记要剥掉，长度也要收紧 —— 列表一行放不下。 */
private const val MAX_TITLE = 140
private const val MAX_EXCERPT = 220

private fun text(raw: JsonValue?, max: Int = Int.MAX_VALUE): String =
    TextCleaner.plain(raw.stringOrNull(), max)

/** 按顺序取第一个非空的文本字段。接口里同一份内容常有多个别名（CSDN 的 digest / description）。 */
private fun textAny(candidates: List<JsonValue?>, max: Int = Int.MAX_VALUE): String {
    for (candidate in candidates) {
        val value = text(candidate, max)
        if (value.isNotEmpty()) return value
    }
    return ""
}

private fun articleOf(
    channel: RecallChannel,
    nativeId: String,
    title: String,
    excerpt: String,
    link: String,
    author: String,
    publishedAt: Long,
    publishedRaw: String = "",
): Article = Article(
    id = "${channel.id}:$nativeId",
    title = title,
    excerpt = excerpt,
    link = link,
    // 作者缺失时退回通道名，列表里不会出现空作者
    author = author.ifEmpty { channel.name },
    sourceId = channel.id,
    sourceName = channel.name,
    category = channel.category,
    publishedAt = publishedAt,
    publishedRaw = publishedRaw,
)

/** 拼「N 分 · N 评论」这类元信息，缺的项直接不出现。 */
private fun meta(vararg parts: String?): String = parts.filterNotNull().joinToString(" · ")

// ------------------------------------------------------------------ Hacker News

/**
 * HN Algolia 搜索。
 *
 * 选它的理由：`http://hn.algolia.com/api/v1/search` 是 HN 官方的全文搜索镜像，
 * 免费、无需 key、无频率限制（实测连续请求无 429）。
 * 返回的 `created_at_i` 是**秒级**时间戳，别当毫秒用。
 */
class HackerNewsChannel : RecallChannel {
    override val id = "hn-search"
    override val name = "HN 搜索"
    override val category = "海外"
    override val limit = 30
    override val prefersLatin = true

    override fun url(query: String, limit: Int): String =
        "https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=$limit" +
            "&query=" + SearchEngine.encode(query)

    override fun parse(body: String): List<Article> = parseHackerNews(body, this)

    companion object {
        /**
         * `url` 字段可以为 null（Ask HN / Show HN 这类自帖），
         * 这时退回 HN 的讨论页 —— 不能丢，那正是原文所在处。
         */
        internal fun parseHackerNews(body: String, channel: RecallChannel): List<Article> {
            val hits = Json.parseOrNull(body)?.objOrEmpty()?.get("hits").arrOrEmpty()
            return hits.mapNotNull { hit ->
                val obj = hit.objOrEmpty()
                val objectId = obj["objectID"].stringOrNull() ?: return@mapNotNull null
                val title = text(obj["title"], MAX_TITLE)
                if (title.isEmpty()) return@mapNotNull null

                val seconds = obj["created_at_i"].longOr(0L)
                articleOf(
                    channel = channel,
                    nativeId = objectId,
                    title = title,
                    excerpt = meta(
                        obj["points"].longOrNull()?.let { "$it 分" },
                        obj["num_comments"].longOrNull()?.let { "$it 评论" },
                        text(obj["story_text"], MAX_EXCERPT).ifEmpty { null },
                    ),
                    link = cleanLink(obj["url"].stringOrNull())
                        .ifEmpty { "https://news.ycombinator.com/item?id=$objectId" },
                    author = obj["author"].stringOr(""),
                    publishedAt = if (seconds > 0L) seconds * 1000L else 0L,
                    publishedRaw = obj["created_at"].stringOr(""),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ GitHub

/**
 * GitHub 仓库搜索。
 *
 * **未认证时限制 10 次/分钟**，是这几条通道里最紧的一条 —— 所以 [limit] 压到 10，
 * 并且默认只在词表能给出拉丁变体时才用得上（中文词搜仓库基本为空）。
 * 每次刷新对每个兴趣词发一次，5 个兴趣词就是 5 次，还在额度内。
 *
 * 用 `pushed_at` 而不是 `created_at` 作为时间：一个 2016 年建、昨天刚推的仓库
 * 是「新鲜内容」，而按创建时间算会被排到最底下。
 */
class GitHubSearchChannel : RecallChannel {
    override val id = "github-search"
    override val name = "GitHub 搜索"
    override val category = "代码"
    override val limit = 10
    override val prefersLatin = true

    override fun url(query: String, limit: Int): String =
        "https://api.github.com/search/repositories?sort=stars&order=desc&per_page=$limit" +
            "&q=" + SearchEngine.encode(query)

    override fun parse(body: String): List<Article> = parseGitHub(body, this)

    companion object {
        internal fun parseGitHub(body: String, channel: RecallChannel): List<Article> {
            val items = Json.parseOrNull(body)?.objOrEmpty()?.get("items").arrOrEmpty()
            return items.mapNotNull { item ->
                val obj = item.objOrEmpty()
                val fullName = obj["full_name"].stringOrNull() ?: return@mapNotNull null
                val stars = obj["stargazers_count"].longOr(0L)

                articleOf(
                    channel = channel,
                    nativeId = fullName,
                    title = fullName.take(MAX_TITLE),
                    excerpt = meta(
                        textAny(listOf(obj["description"]), MAX_EXCERPT).ifEmpty { null },
                        obj["language"].stringOrNull(),
                        if (stars > 0L) "$stars ★" else null,
                    ),
                    link = cleanLink(obj["html_url"].stringOrNull())
                        .ifEmpty { "https://github.com/$fullName" },
                    author = obj["owner"].objOrEmpty()["login"].stringOr(""),
                    publishedAt = DateParser.parse(
                        obj["pushed_at"].stringOrNull() ?: obj["created_at"].stringOrNull()
                    ),
                    publishedRaw = obj["pushed_at"].stringOr(""),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ StackExchange

/**
 * Stack Exchange 搜索（默认站点 stackoverflow）。
 *
 * 响应**无条件 gzip**，靠 OkHttp 的透明解压兜住（见 [OkHttpGet]）。
 * `creation_date` 同样是秒级时间戳。
 */
class StackExchangeChannel(private val site: String = "stackoverflow") : RecallChannel {
    override val id = "stackexchange"
    override val name = "Stack Overflow"
    override val category = "问答"
    override val limit = 20
    override val prefersLatin = true

    override fun url(query: String, limit: Int): String =
        "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance" +
            "&site=$site&pagesize=$limit&q=" + SearchEngine.encode(query)

    override fun parse(body: String): List<Article> = parseStackExchange(body, this)

    companion object {
        internal fun parseStackExchange(body: String, channel: RecallChannel): List<Article> {
            val items = Json.parseOrNull(body)?.objOrEmpty()?.get("items").arrOrEmpty()
            return items.mapNotNull { item ->
                val obj = item.objOrEmpty()
                val questionId = obj["question_id"].longOrNull() ?: return@mapNotNull null
                val title = text(obj["title"], MAX_TITLE)
                if (title.isEmpty()) return@mapNotNull null

                val tags = obj["tags"].arrOrEmpty()
                    .mapNotNull { it.stringOrNull() }
                    .take(4)
                    .joinToString(" ")

                val seconds = obj["creation_date"].longOr(0L)
                articleOf(
                    channel = channel,
                    nativeId = questionId.toString(),
                    title = title,
                    excerpt = meta(
                        tags.ifEmpty { null },
                        obj["score"].longOrNull()?.let { "$it 赞" },
                        obj["answer_count"].longOrNull()?.let { "$it 回答" },
                        if ((obj["is_answered"] as? JsonValue.Bool)?.value == true) "已解决" else null,
                    ),
                    link = cleanLink(obj["link"].stringOrNull()),
                    author = obj["owner"].objOrEmpty()["display_name"].stringOr(""),
                    publishedAt = if (seconds > 0L) seconds * 1000L else 0L,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ CSDN

/**
 * CSDN 博客搜索。
 *
 * 这是**中文技术语料里召回量最大的一路** —— 同一个关键词能返回 26 条
 * （其他通道都在 5~10 条），而且接口轻（17KB / 无分页压力）。
 *
 * 两个坑：
 *  1. `title` / `description` 里带 `<em>大模型</em>` 高亮标记，必须走 [TextCleaner] 剥掉，
 *     否则标题会原样显示成 `<em>大模型</em>之技术生态`。
 *  2. `create_time` 是**毫秒时间戳的字符串**（`"1722528000000"`），
 *     而 `create_time_str` / `created_at` 是给人看的日期。优先用前者（精确）。
 */
class CsdnChannel : RecallChannel {
    override val id = "csdn"
    override val name = "CSDN 搜索"
    override val category = "中文"
    override val limit = 30

    override fun url(query: String, limit: Int): String =
        "https://so.csdn.net/api/v3/search?t=blog&p=1&q=" + SearchEngine.encode(query)

    override fun parse(body: String): List<Article> = parseCsdn(body, this)

    companion object {
        internal fun parseCsdn(body: String, channel: RecallChannel): List<Article> {
            val items = Json.parseOrNull(body)?.objOrEmpty()?.get("result_vos").arrOrEmpty()
            return items.mapNotNull { item ->
                val obj = item.objOrEmpty()
                val title = text(obj["title"], MAX_TITLE)
                if (title.isEmpty()) return@mapNotNull null

                val nativeId = obj["articleid"].stringOrNull()
                    ?: obj["id"].stringOrNull()
                    ?: return@mapNotNull null

                articleOf(
                    channel = channel,
                    nativeId = nativeId,
                    title = title,
                    excerpt = meta(
                        // digest 和 description 内容基本一样，取到第一个非空的就够了
                        textAny(listOf(obj["digest"], obj["description"]), MAX_EXCERPT)
                            .ifEmpty { null },
                        obj["view"].longOrNull()?.let { "$it 阅读" },
                        obj["digg"].longOrNull()?.let { "$it 赞" },
                    ),
                    link = cleanLink(
                        obj["url_location"].stringOrNull() ?: obj["url"].stringOrNull()
                    ),
                    // 昵称比账号名更适合展示，账号名（m0_59092412 这种）做兜底
                    author = obj["nickname"].stringOr("").ifEmpty { obj["author"].stringOr("") },
                    // create_time 是毫秒字符串；为 "0" / 缺失时退回解析 created_at
                    publishedAt = obj["create_time"].longOrNull()?.takeIf { it > 0L }
                        ?: DateParser.parse(obj["created_at"].stringOrNull()),
                    publishedRaw = obj["create_time_str"].stringOr(""),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ V2EX

/**
 * V2EX 帖子搜索（走第三方 OpenSearch 实例 sov2ex）。
 *
 * **嵌套层级是个坑**：顶层 `hits` 直接就是**数组**，每个元素是 ES 的
 * `{_score, _id, _source}` 包装体，真正的内容在 `_source` 里 ——
 * 不是 ES 默认的 `hits.hits[]`（sov2ex 在顶层做了展平）。
 * 按 `hits.hits` 取会得到空表，而且不报错，属于「永远 0 结果」那一类静默失败。
 *
 * `created` 是**不带时区的本地时间**（`2026-01-14T07:46:47`）。V2EX 用北京时间，
 * 交给 [DateParser] 按设备时区解析 —— 手机在 +08:00 时正确，其他时区会偏。
 * 对「3 天衰减」的排序影响很小，不值得为它引时区库。
 */
class V2exChannel : RecallChannel {
    override val id = "v2ex"
    override val name = "V2EX 搜索"
    override val category = "中文"
    override val limit = 20

    override fun url(query: String, limit: Int): String =
        "https://www.sov2ex.com/api/search?sort=sumup&size=$limit&q=" + SearchEngine.encode(query)

    override fun parse(body: String): List<Article> = parseV2ex(body, this)

    companion object {
        internal fun parseV2ex(body: String, channel: RecallChannel): List<Article> {
            val hits = Json.parseOrNull(body)?.objOrEmpty()?.get("hits").arrOrEmpty()
            return hits.mapNotNull { hit ->
                val obj = hit.objOrEmpty()["_source"].objOrEmpty()
                val id = obj["id"].longOrNull() ?: return@mapNotNull null
                val title = text(obj["title"], MAX_TITLE)
                if (title.isEmpty()) return@mapNotNull null

                articleOf(
                    channel = channel,
                    nativeId = id.toString(),
                    title = title,
                    excerpt = meta(
                        text(obj["content"], MAX_EXCERPT).ifEmpty { null },
                        obj["replies"].longOrNull()?.let { "$it 回复" },
                    ),
                    link = "https://www.v2ex.com/t/$id",
                    author = obj["member"].stringOr(""),
                    publishedAt = DateParser.parse(obj["created"].stringOrNull()),
                    publishedRaw = obj["created"].stringOr(""),
                )
            }
        }
    }
}
