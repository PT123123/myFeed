package com.example.feedreader.data

/**
 * 首页搜索框的匹配规则。
 *
 * 抽成纯对象是因为它原来的写法是界面里的一串 `contains`，只看得到标题、摘要、来源 ——
 * 而订阅源带全文（[Article.body]，上限 6000 字）之后，「这篇里出现过某个词」恰恰是
 * 最常见的搜法：知乎一篇回答六七千字，摘要只有开头 280 字，中间提到的东西一律搜不到。
 *
 * 匹配字段会回传给界面标出来。不标的话，「为什么这条会出现在结果里」得自己点开翻。
 */
object ArticleSearch {

    /**
     * 单个字先不搜正文。
     *
     * 一是六百篇 × 六千字每敲一个字都要扫一遍，一个字的查询命中率高得没意义
     * （基本等于全命中）；二是这时用户要的多半是「找那篇标题里有它的」。
     */
    const val MIN_BODY_QUERY_LEN = 2

    /** 命中在哪个字段上，回传给界面标注；没命中返回 null。 */
    fun fieldHit(article: Article, rawKeyword: String): String? {
        val keyword = rawKeyword.trim()
        if (keyword.isEmpty()) return null
        if (article.title.contains(keyword, ignoreCase = true)) return HIT_TITLE
        if (article.excerpt.contains(keyword, ignoreCase = true)) return HIT_EXCERPT
        if (article.sourceName.contains(keyword, ignoreCase = true)) return HIT_SOURCE
        if (keyword.length >= MIN_BODY_QUERY_LEN &&
            article.body.contains(keyword, ignoreCase = true)
        ) {
            return HIT_BODY
        }
        return null
    }

    /** 空查询 = 不过滤（首页默认状态），不是「什么都不匹配」。 */
    fun matches(article: Article, rawKeyword: String): Boolean =
        rawKeyword.isBlank() || fieldHit(article, rawKeyword) != null

    const val HIT_TITLE = "标题"
    const val HIT_EXCERPT = "摘要"
    const val HIT_SOURCE = "来源"
    const val HIT_BODY = "正文"

    /**
     * 给界面的标签。命中标题就不用再标 —— 那条本来就在结果最上面，用户看得见为什么；
     * 只有「点开才发现中间藏着一个词」的正文命中需要解释一句。
     */
    fun label(hit: String?): String? = if (hit == HIT_BODY) "正文里提到" else null
}
