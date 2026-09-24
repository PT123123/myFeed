package com.example.feedreader.data.recall

import com.example.feedreader.data.Article
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.RssParser
import com.example.feedreader.data.SearchEngine

/**
 * 自建 / 公共 RSSHub 实例的关键词路由。
 *
 * RSSHub 返回的是标准 RSS，所以这里直接复用 [RssParser] —— 不用再写一套解析，
 * 而且 `<em>` 高亮、命名空间、日期格式这些问题那边都已经处理过了。
 *
 * **默认走公共镜像，实测（2026-09-24）的结果如下**，别再凭印象加路由：
 *
 * | 路由 | rsshub.woodland.cafe |
 * |---|---|
 * | `/weibo/keyword/:q` | **通**，返回 10 条真实微博搜索结果 |
 * | `/github/search/:q` | 429 限流（路由存在，公共实例限速） |
 * | `/douban/search/:q` | 429 限流 |
 * | `/bilibili/search/:q` | 404（路由已不存在） |
 * | `/zhihu/search/:q` | 404 |
 * | `/juejin/search/:q` | 404 |
 *
 * 另外两个公共镜像的更差：`rsshub.rssforever.com` 全站 503；
 * `rsshub.liumingye.cn` 只有非搜索路由（`/zhihu/hot`）能用，搜索路由一律 404。
 *
 * 微博关键词这条路的质量一般（会出现只沾边的内容），但它**免费、无需部署、
 * 是唯一能覆盖中文社交媒体的通道**，多出来的噪声交给排序阶段过滤。
 * 觉得不够用就在设置里填自己那台实例的地址。
 */
class RssHubChannel(
    /** 实例基地址。空串 = 用内置公共镜像；填了就用自建实例（更稳、额度更宽）。 */
    private val baseUrl: String = "",
    /** 路由模板，`{q}` 会被替换成 URL 编码后的关键词。 */
    private val route: String = RssHubRoute.WEIBO_KEYWORD.template,
    private val parser: RssParser = RssParser(),
) : RecallChannel {

    override val id = ID
    override val name = "RSSHub 关键词"
    override val category = "中文"
    override val limit = 20

    /** 实际用的实例地址。空串走默认镜像。 */
    val instance: String get() = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_MIRROR }

    override fun url(query: String, limit: Int): String =
        instance + route.replace(PLACEHOLDER, SearchEngine.encode(query))

    override fun parse(body: String): List<Article> = runCatching {
        // RssParser 解析失败会抛 XmlPullParserException（和 FeedRepository 走的是同一条路径），
        // 这里按 RecallChannel 的契约把异常收成空表 —— 一路解析炸了不该拖垮整次刷新。
        parser.parse(body, SOURCE, this.limit)
    }.getOrDefault(emptyList())

    companion object {
        const val ID = "rsshub"

        private const val PLACEHOLDER = "{q}"

        /**
         * 默认镜像。选它是因为三个候选里只有它能通搜索路由
         * （见类注释里的实测表）。镜像会挂 —— 挂了就在设置里换成自建实例。
         */
        const val DEFAULT_MIRROR = "https://rsshub.woodland.cafe"

        private val SOURCE = FeedSource(ID, "RSSHub 关键词", DEFAULT_MIRROR, "中文")
    }
}

/**
 * 设置页里可选的路由预设。只收录**实测存在**的，不凭印象编。
 */
enum class RssHubRoute(
    val label: String,
    val template: String,
    /** 给用户看的说明，主要是「这条要不要在实例侧配凭证」。 */
    val hint: String,
) {
    WEIBO_KEYWORD(
        label = "微博关键词",
        template = "/weibo/keyword/{q}",
        hint = "公共镜像实测可用；内容偏社交，质量一般",
    ),
    GITHUB_SEARCH(
        label = "GitHub 搜索",
        template = "/github/search/{q}",
        hint = "实例侧需配 GITHUB_ACCESS_TOKEN；公共镜像限流（429）",
    ),
    DOUBAN_SEARCH(
        label = "豆瓣搜索",
        template = "/douban/search/{q}",
        hint = "公共镜像限流（429），建议自建实例",
    ),
}
