package com.example.feedreader.data

/**
 * 一条订阅内容。字段是各源（RSS 2.0 / Atom / RDF）解析后的公共子集。
 */
data class Article(
    val id: String,
    val title: String,
    val excerpt: String,
    val link: String,
    val author: String,
    val sourceId: String,
    val sourceName: String,
    val category: String,
    /** 发布时间的 epoch 毫秒；解析不出来时为 0。 */
    val publishedAt: Long,
    /** 原始日期字符串，作为 [publishedAt] 解析失败时的展示兜底。 */
    val publishedRaw: String = "",
)

/** 一个 RSS 订阅源。 */
data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
)

/** 单个源拉取失败的原因。 */
data class FeedFailure(val source: String, val message: String)

/** 一次全量拉取的结果：成功的文章 + 失败的源。 */
data class FetchOutcome(
    val articles: List<Article>,
    val failures: List<FeedFailure>,
)

object FeedSources {

    /** 内置订阅源。挑的是体积可控、结构规范的几个。 */
    val DEFAULT: List<FeedSource> = listOf(
        FeedSource("hn", "Hacker News", "https://hnrss.org/frontpage", "科技"),
        FeedSource("techcrunch", "TechCrunch", "https://techcrunch.com/feed/", "科技"),
        FeedSource("solidot", "Solidot 奇客", "https://www.solidot.org/index.rss", "科技"),
        FeedSource("infoq", "InfoQ 中文", "https://www.infoq.cn/feed", "开发"),
        FeedSource("oschina", "OSCHINA", "https://www.oschina.net/news/rss", "开发"),
        FeedSource("sspai", "少数派", "https://sspai.com/feed", "综合"),
    )

    const val ALL = "全部"

    /**
     * 分类筛选用的选项，按给定源集合里的出现顺序去重。
     * 传当前启用的源进来，这样关掉某个源后对应的分类也跟着消失。
     */
    fun categoriesOf(sources: List<FeedSource>): List<String> =
        listOf(ALL) + sources.map { it.category }.distinct()
}
