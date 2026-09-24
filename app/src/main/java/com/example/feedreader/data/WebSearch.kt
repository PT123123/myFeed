package com.example.feedreader.data

import java.net.URLEncoder

/**
 * 内置的网页搜索入口。
 *
 * 为什么不是「接搜索 API」——三条路都实测堵死了（2026-09-24 在本机网络上测的）：
 *
 * 1. **搜索 API**：Bing Web Search API 已经停售，没有免费额度；Google Custom
 *    Search / Brave / SerpAPI 都要申请 key。而且 Google、DuckDuckGo、Brave、
 *    SearXNG 公共实例在这里全部直接超时（000），有 key 也连不上。
 * 2. **搜索结果做成 feed**：Bing 的 `?format=RSS` 已失效，现在返回的是网页
 *    （`<!doctype html>`，15KB）；Google News RSS 不可达。
 * 3. **RSSHub 搜索路由**：公共镜像上 `/zhihu/search`、`/bilibili/search`、
 *    `/juejin/search`、`/douban/search` 都是 404（路由不存在），
 *    `/weibo/keyword`、`/github/search`、`/bing/search` 是 503（需实例侧配凭证）。
 *
 * 所以走第四条：**用内置浏览器打开搜索结果页**。零 key、零后端，和已有的
 * 内置浏览器完全复用 —— 用户在结果页里点进任意文章，返回键先在网页里回退。
 *
 * 实测可达的引擎：cn.bing.com（真结果页）、sogou.com。百度对非浏览器客户端
 * 返回验证页（1.5KB text/plain），所以没内置。
 */
enum class SearchEngine(
    val id: String,
    val label: String,
    private val template: String,
) {
    BING("bing", "必应", "https://cn.bing.com/search?q=%s"),
    SOGOU("sogou", "搜狗", "https://www.sogou.com/web?query=%s");

    /** 拼出结果页地址。query 按 URL 规则编码，中文和特殊字符都不会破坏地址。 */
    fun urlFor(query: String): String = template.format(encode(query))

    companion object {
        val DEFAULT = BING

        /** 按偏好里存的 id 取引擎，认不出来就回退到默认。 */
        fun of(id: String?): SearchEngine = values().firstOrNull { it.id == id } ?: DEFAULT

        /**
         * URL 查询参数编码。`URLEncoder` 把空格编成 `+`，这在 query string 里
         * 正好等价于空格，搜索引擎都认。
         */
        fun encode(query: String): String = URLEncoder.encode(query.trim(), "UTF-8")
    }
}
