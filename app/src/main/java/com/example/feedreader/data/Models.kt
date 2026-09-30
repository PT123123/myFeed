package com.example.feedreader.data

/**
 * 一条订阅内容。字段是各源（RSS 2.0 / Atom / RDF）解析后的公共子集。
 */
data class Article(
    val id: String,
    val title: String,
    val excerpt: String,
    /**
     * 源里带的正文（纯文本，可能为空）。
     *
     * 和 [excerpt] 分成两个字段，是因为用途不同：卡片只要一行，而阅读页要整篇。
     * 拿 280 字的摘要去填阅读页，等于逼用户点回原页 —— 而知乎 / 小红书 / X 的原页在
     * 应用内的 WebView 里根本读不到东西（要登录态）。harvest 已经把回答全文写进 RSS 了，
     * 这份正文就是那条路的出口。见 [ReaderRouting]。
     */
    val body: String = "",
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
    /**
     * 用户自己加的源（界面上能删）。出厂内置的只能关不能删 —— 删掉了没法再加回来，
     * 而内置列表是随版本更新的。
     */
    val custom: Boolean = false,
    /**
     * 出厂的默认开关状态，**只对内置源有意义**。
     *
     * 摆在源的定义上而不是在别处维护一份 id 黑名单，是为了让「这个源为什么不默认拉」
     * 和源本身长在一起 —— 否则读代码的人得在两个文件之间来回跳，
     * 而且删源的时候很容易漏掉黑名单里那一行。
     */
    val defaultEnabled: Boolean = true,
)

/**
 * 单个源拉取失败的原因。
 *
 * [host] 是这台源所在的主机（`地址里的 host:port`，召回通道那种没有地址的就是空串）。
 * 界面上「这个源是不是 relay 那台 PC」只能靠它判断 —— 源名会随用户改名变，
 * 而 relay 的 21 条源本来就共用一个主机。
 */
data class FeedFailure(val source: String, val message: String, val host: String = "")

/** 一次全量拉取的结果：成功的文章 + 失败的源。 */
data class FetchOutcome(
    val articles: List<Article>,
    val failures: List<FeedFailure>,
)

/**
 * 「添加订阅源」时探测地址的结果。
 *
 * 存在的意义是**别让列表里攒死链**：用户粘一个地址进来，先真拉一次再决定收不收。
 * 判定复用了 [FeedRepository] 已有的那套（HTTP 状态 / 是不是 HTML / 有没有 XML），
 * 所以探测通过 ≈ 这个源以后每次刷新都能用，而不是「现在能打开」而已。
 */
sealed interface ProbeResult {
    /**
     * @param title 频道标题，**可能为空** —— 有些 feed 就是不写 channel title，
     *   调用方拿 [SourceRules.fallbackName] 用域名兜底。
     * @param itemCount 这一份里解析出的条目数，用来给用户一个「这源活着」的直观证据。
     */
    data class Ok(val title: String, val itemCount: Int) : ProbeResult

    /** [message] 是能直接展示给用户的中文说明。 */
    data class Failed(val message: String) : ProbeResult
}

object FeedSources {

    /**
     * 内置订阅源。
     *
     * 这份列表以前基本就是「中文科技媒体聚合」，而那个品类的主体商业模式就是软文 ——
     * 于是首页喂出来的全是营销号长文。现在按**「这东西是谁写的」**重排：
     * 一手（官方博客）、独立（具体某个人的博客）、策展（有人筛过的）默认开；
     * 靠流量和软文变现的媒体号默认关（见下面 `defaultEnabled` 那段）。
     *
     * ### 实测记录（2026-09-25，本机家宽 + 浏览器 UA）
     *
     * 每个源都是 curl 探过「HTTP 200 + 根标签是 rss/feed + 有条目」才写进来的。
     * 踩到的坑一并记下 —— 下次某个源报错时能直接对号入座：
     *
     * - **RSSHub 公共镜像在退化。** `/github/trending/daily/any` 在三个镜像上分别是
     *   503 / 503 / 000（503 = 实例侧没配这条路由要的凭证），所以 GitHub Trending
     *   换成了 `mshibanami` 那个 GitHub Pages 静态 feed。
     *   `/zhihu/hot`、`/bilibili/hot-search` 现在只回 1 条（原本是整张热榜），
     *   这两个本来就是默认关闭的，先留着。官方 `rsshub.app` 本网络 DNS 被污染
     *   （解析到 31.13.106.4），一律不可用。
     * - **danluu.com 的 atom 有 6.6MB，超过 `FeedRepository.MAX_BYTES`（5MB），
     *   拉下来会直接报「内容过大」。** 所以它没被内置 —— 这条特别容易漏，
     *   因为浏览器打开完全正常，只有按字节量一次才发现。
     * - 返回 200 但**根本不是 feed** 的：`zhihu.com/rss` 已经 0 字节（站方废弃）、
     *   `liaoxuefeng.com/feed` 给的是 HTML。只看状态码会把这些全放过。
     * - 连不上（000）：`v2ex.com/index.xml`、`linux.cn`、`segmentfault.com/feeds/blogs`、
     *   `huggingface.co/blog/feed.xml`、`android-developers.googleblog.com`（被墙）；
     *   403：`hacks.mozilla.org`、`bleepingcomputer.com`；
     *   404：`baoyu.io/feed`、`brendangregg.com`、`anthropic.com/rss.xml`。
     * - 只有 1 条的（等于没有流）：`hellogithub.com/rss`、`tldr.tech`、
     *   `blog.google/technology/ai/rss`。
     *
     * **中文高质量源是真的少。** 实测下来能用的只有酷壳、云风、美团技术团队、张鑫旭
     * 这几个。中文的好内容大多长在公众号 / 知乎 / 小红书，而它们没有官方 RSS，
     * RSSHub 那些路由要么要实例侧配 cookie、要么已经被限流。所以这份列表里英文源
     * 占了大头 —— 不是偏好问题，是供给问题。
     *
     * 没内置的：小红书、Twitter/X —— RSSHub 路由需要实例侧配登录凭证
     * （小红书 cookie / TWITTER_AUTH_TOKEN），公共镜像实测全部 503。
     * 想要的话得自己部署一个 RSSHub 实例并配上 cookie。
     *
     * ### 关于 `defaultEnabled = false` 那批
     *
     * 它们的选题不差，问题在**商业模式**：稿费来自厂商、流量来自标题、停留时长
     * 来自把一件小事写成三千字。这不是「源挑得不好」，是中文科技媒体这个品类本身 ——
     * 换个同类源也治不好，所以不删，只是默认不拉（想看的能在设置里打开）。
     *
     * 判别一个源该不该默认开，看的不是内容长短，是**有没有具体的人、有没有一手信息、
     * 写不写自己的判断**。阮一峰一条源的信噪比超过那批加起来，就是这个道理。
     *
     * ### 第二批实测（2026-09-25 傍晚，补医学 / 健身 / 财经覆盖）
     *
     * 用户兴趣扩到药学、肌肥大、金融民生后，这几个领域在内置源里是空的，得补。
     * 本机家宽 + 浏览器 UA 探过：
     *
     * - **通过的英文源**（200 + `<rss` + items>0 + < 5MB）：Fierce Biotech(25) /
     *   STAT News(20) / ScienceDaily 医学健康(60)（医学）；Stronger By Science(15) /
     *   Fitness Volt(10)（健身）；MarketWatch(10)（财经）。已全部内置。
     * - **被挡的**：BioPharma Dive、Medical Xpress 403（Cloudflare）；BarBend 404；
     *   Nature Biotech 是 RDF（item 少且解析不稳，没要）；CNBC Markets 只 1 条（等于没有）。
     * - **中文财经源全军覆没**：华尔街见闻 / 第一财经 / 财新国际 / 21 世纪经济报道 /
     *   东方财富(RSSHub) / 证券时报 全部 404/403/空。所以财经只能用英文 MarketWatch
     *   兜底，再靠 [SynonymDict] 的「金融 / 经济 / 涨价 / 民生 / 扩产」同义词去捞
     *   中文语境的文章——纯语义对小模型跨语言不稳，词法扩展更可靠。
     *
     * 用户自己加的源不受这里影响（见 [FeedSource.custom]）—— 那才是「什么算垃圾」
     * 这个问题真正的答案：让用户自己定，而不是替他定。
     */
    val DEFAULT: List<FeedSource> = listOf(
        // —— 科技：一手 / 独立 / 策展 ——
        FeedSource("hn", "Hacker News", "https://hnrss.org/frontpage", "科技"),
        FeedSource("hnbest", "HN 高质量榜", "https://hnrss.org/best", "科技"),
        FeedSource("techcrunch", "TechCrunch", "https://techcrunch.com/feed/", "科技"),
        FeedSource("solidot", "Solidot 奇客", "https://www.solidot.org/index.rss", "科技"),
        FeedSource("ithome", "IT之家", "https://www.ithome.com/rss/", "科技"),
        FeedSource(
            "openai",
            "OpenAI Blog",
            "https://openai.com/blog/rss.xml",
            "科技",
        ),
        FeedSource(
            "simonwillison",
            "Simon Willison",
            "https://simonwillison.net/atom/everything/",
            "科技",
        ),
        FeedSource(
            "schneier",
            "Schneier on Security",
            "https://www.schneier.com/feed/atom/",
            "科技",
        ),

        // —— 开发：一手 / 独立 / 策展 ——
        FeedSource("ruanyf", "阮一峰的网络日志", "https://www.ruanyifeng.com/blog/atom.xml", "开发"),
        FeedSource("coolshell", "酷壳", "https://coolshell.cn/feed", "开发"),
        FeedSource("codingnow", "云风的 BLOG", "https://blog.codingnow.com/atom.xml", "开发"),
        FeedSource("meituan", "美团技术团队", "https://tech.meituan.com/feed/", "开发"),
        FeedSource("zhangxinxu", "张鑫旭的博客", "https://www.zhangxinxu.com/wordpress/feed/", "开发"),
        FeedSource("lobsters", "Lobsters", "https://lobste.rs/rss", "开发"),
        FeedSource("hnshow", "Show HN", "https://hnrss.org/show", "开发"),
        FeedSource(
            "rustblog",
            "Rust Blog",
            "https://blog.rust-lang.org/feed.xml",
            "开发",
        ),
        FeedSource(
            "cloudflare",
            "Cloudflare Blog",
            "https://blog.cloudflare.com/rss/",
            "开发",
        ),
        FeedSource("infoq", "InfoQ 中文", "https://www.infoq.cn/feed", "开发"),
        FeedSource("oschina", "OSCHINA", "https://www.oschina.net/news/rss", "开发"),
        // 走 GitHub Pages 上的静态 feed：RSSHub 的 /github/trending 路由在公共镜像上全 503 了
        FeedSource(
            "ghtrending",
            "GitHub Trending",
            "https://mshibanami.github.io/GitHubTrendingRSS/daily/all.xml",
            "开发",
        ),

        // —— 综合 ——
        FeedSource("appinn", "小众软件", "https://www.appinn.com/feed/", "综合"),

        // —— 热点：V2EX 热帖是短讨论帖，正好对冲「全是长文」 ——
        FeedSource(
            "v2exhot",
            "V2EX 热门主题",
            "https://rsshub.liumingye.cn/v2ex/topics/hot",
            "热点",
        ),

        // —— 医学 / 生物（2026-09-25 补：用户追药学 / 病理 / 新药）——
        // 全是英文权威源：中文医学 RSS 几乎没有能用的。
        FeedSource("fiercebiotech", "Fierce Biotech", "https://www.fiercebiotech.com/rss/xml", "医学"),
        FeedSource("statnews", "STAT News", "https://www.statnews.com/feed/", "医学"),
        FeedSource("sciencedaily_health", "ScienceDaily 医学健康", "https://www.sciencedaily.com/rss/health_medicine.xml", "医学"),

        // —— 健身 / 肌肥大（2026-09-25 补：用户追增肌 / 力量）——
        FeedSource("strongerbyscience", "Stronger By Science", "https://www.strongerbyscience.com/feed/", "健身"),
        FeedSource("fitnessvolt", "Fitness Volt", "https://fitnessvolt.com/feed/", "健身"),

        // —— 财经 / 民生（2026-09-25 补：用户追涨价 / 扩产 / 全球市场）——
        // 中文财经 RSS 实测全 404/403，只能用英文源兜底；靠 SynonymDict 的
        // 「金融 / 经济 / 涨价 / 民生 / 扩产」同义词去捞中文语境的文章。
        FeedSource("marketwatch", "MarketWatch", "https://www.marketwatch.com/rss/topstories", "财经"),

        // —— 以下是默认不拉的：靠流量和软文变现的媒体号 ——
        FeedSource("ifanr", "爱范儿", "https://www.ifanr.com/feed", "科技", defaultEnabled = false),
        FeedSource("qbitai", "量子位", "https://www.qbitai.com/feed", "科技", defaultEnabled = false),
        FeedSource("juejin", "掘金", "https://juejin.cn/rss", "开发", defaultEnabled = false),
        FeedSource("sspai", "少数派", "https://sspai.com/feed", "综合", defaultEnabled = false),
        FeedSource("gcores", "机核", "https://www.gcores.com/rss", "综合", defaultEnabled = false),
        FeedSource("zhihuhot", "知乎热榜", "https://rsshub.liumingye.cn/zhihu/hot", "热点", defaultEnabled = false),
        FeedSource("zhihudaily", "知乎日报", "https://rsshub.woodland.cafe/zhihu/daily", "热点", defaultEnabled = false),
        FeedSource("bilihot", "B站热搜", "https://rsshub.liumingye.cn/bilibili/hot-search", "热点", defaultEnabled = false),
    )

    /** 出厂默认**不**拉取的源 id。首次运行、以及默认值版本升级时的迁移都用它。 */
    val defaultDisabledIds: Set<String> =
        DEFAULT.filterNot { it.defaultEnabled }.map { it.id }.toSet()

    const val ALL = "全部"

    /**
     * 分类筛选用的选项，按给定**文章列表**里的出现顺序去重。
     *
     * 传当前列表里的文章进来，而不是「启用的源」：换成兴趣流之后内容来自六条
     * 召回通道加订阅源，按源推导出来的分类根本覆盖不到 —— CSDN、V2EX、
     * Hacker News 这些来源压根不在订阅源列表里，用户永远筛不到它们。
     * 顺序取首次出现顺序，也就是分数序，最相关的来源排在最前面。
     */
    fun categoriesOf(articles: List<Article>): List<String> =
        listOf(ALL) + articles.map { it.category }.distinct()
}
