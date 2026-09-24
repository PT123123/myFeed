package com.example.feedreader.data.recall

/**
 * 设置页要展示的一条通道。**不等于** [RecallChannel] —— 订阅语料池也在这一份列表里，
 * 但它不是 [RecallChannel]（它不按关键词召回、不需要网络），
 * 所以展示用的元数据和「怎么召回」是两张表。
 *
 * [hint] 是给用户看的成本说明。几路通道的代价差得很远（有的要翻墙、有的有限流），
 * 不说清楚用户没法做取舍。
 */
data class RecallChannelInfo(
    val id: String,
    val name: String,
    val hint: String,
)

/**
 * 内置召回通道的登记处。
 *
 * 顺序 = 设置页展示顺序，也让「先看哪一路」这件事有确定性。
 */
object RecallChannels {

    /**
     * 订阅语料池的开关 id。
     *
     * 它指的不是一条 [RecallChannel]，而是「把已订阅源抓到的文章也混进兴趣流」。
     * 保留这个开关是有意义的：用户完全可能只想要搜索来的内容，
     * 而把 RSS 当成「备选的一个语料来源」而不是主体。
     */
    const val ID_SUBSCRIPTION = "subscription"

    /**
     * 完整的召回通道列表。
     *
     * @param rssHubBaseUrl 用户填的自建实例地址，空串走内置公共镜像。
     */
    fun builtIn(rssHubBaseUrl: String = ""): List<RecallChannel> = listOf(
        CsdnChannel(),
        V2exChannel(),
        HackerNewsChannel(),
        GitHubSearchChannel(),
        StackExchangeChannel(),
        RssHubChannel(rssHubBaseUrl),
    )

    /** 设置页共用的展示表：订阅池 + 所有通道。 */
    fun settings(rssHubBaseUrl: String = ""): List<RecallChannelInfo> =
        listOf(
            RecallChannelInfo(
                ID_SUBSCRIPTION,
                "已订阅源",
                "从内置 RSS 源抓到的文章；关掉后只看搜索来的内容",
            ),
        ) + builtIn(rssHubBaseUrl).map { RecallChannelInfo(it.id, it.name, hintOf(it.id)) }

    private fun hintOf(id: String): String = when (id) {
        "csdn" -> "中文技术博客，召回量最大；无需 key"
        "v2ex" -> "中文社区帖子；走第三方 OpenSearch 实例"
        "hn-search" -> "Hacker News 全文搜索；海外，速度取决于网络"
        "github-search" -> "GitHub 仓库搜索；未认证限 10 次/分钟"
        "stackexchange" -> "Stack Overflow 问答；海外"
        RssHubChannel.ID -> "微博关键词；公共镜像可用，也可填自建实例以更稳"
        else -> ""
    }
}
