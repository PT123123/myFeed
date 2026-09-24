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

    /**
     * 内置订阅源。
     *
     * 前 14 个是各站官方 RSS，直连即可。
     *
     * 「热点」那 3 个没有官方 RSS，走 RSSHub 公共镜像。镜像可用性实测（2026-09-24）：
     *   rsshub.liumingye.cn   通
     *   rsshub.woodland.cafe  通
     *   rsshub.rssforever.com 通
     *   官方 rsshub.app       本网络下 DNS 被污染（解析到 31.13.106.4），不可用
     * 镜像会挂，如果哪个源开始报错，换成其它镜像即可；实在不行在设置里关掉。
     *
     * 没内置的：小红书、Twitter/X —— 它们的 RSSHub 路由需要实例侧配置
     * 登录凭证（小红书 cookie / TWITTER_AUTH_TOKEN），公共镜像实测全部 503。
     * 想要的话得自己部署一个 RSSHub 实例并配上 cookie。
     */
    val DEFAULT: List<FeedSource> = listOf(
        // —— 科技 ——
        FeedSource("hn", "Hacker News", "https://hnrss.org/frontpage", "科技"),
        FeedSource("techcrunch", "TechCrunch", "https://techcrunch.com/feed/", "科技"),
        FeedSource("solidot", "Solidot 奇客", "https://www.solidot.org/index.rss", "科技"),
        FeedSource("ithome", "IT之家", "https://www.ithome.com/rss/", "科技"),
        FeedSource("ifanr", "爱范儿", "https://www.ifanr.com/feed", "科技"),
        FeedSource("qbitai", "量子位", "https://www.qbitai.com/feed", "科技"),

        // —— 开发 ——
        FeedSource("infoq", "InfoQ 中文", "https://www.infoq.cn/feed", "开发"),
        FeedSource("oschina", "OSCHINA", "https://www.oschina.net/news/rss", "开发"),
        FeedSource("juejin", "掘金", "https://juejin.cn/rss", "开发"),
        FeedSource("ruanyf", "阮一峰的网络日志", "https://www.ruanyifeng.com/blog/atom.xml", "开发"),
        FeedSource(
            "ghtrending",
            "GitHub Trending",
            "https://rsshub.rssforever.com/github/trending/daily/any",
            "开发",
        ),

        // —— 综合 ——
        FeedSource("sspai", "少数派", "https://sspai.com/feed", "综合"),
        FeedSource("appinn", "小众软件", "https://www.appinn.com/feed/", "综合"),
        FeedSource("gcores", "机核", "https://www.gcores.com/rss", "综合"),

        // —— 热点（RSSHub 镜像）——
        FeedSource("zhihuhot", "知乎热榜", "https://rsshub.liumingye.cn/zhihu/hot", "热点"),
        FeedSource("zhihudaily", "知乎日报", "https://rsshub.woodland.cafe/zhihu/daily", "热点"),
        FeedSource("bilihot", "B站热搜", "https://rsshub.liumingye.cn/bilibili/hot-search", "热点"),
    )

    const val ALL = "全部"

    /**
     * 分类筛选用的选项，按给定源集合里的出现顺序去重。
     * 传当前启用的源进来，这样关掉某个源后对应的分类也跟着消失。
     */
    fun categoriesOf(sources: List<FeedSource>): List<String> =
        listOf(ALL) + sources.map { it.category }.distinct()
}
