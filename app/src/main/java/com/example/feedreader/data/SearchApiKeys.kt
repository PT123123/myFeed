package com.example.feedreader.data

import android.content.Context

/**
 * 各个「填 key 才能用」的搜索服务的 key 存储。
 *
 * 和订阅源、兴趣分开存，是因为它们性质不同：这些 key 一旦填了，对应通道就参与召回，
 * 而且属于敏感凭据 —— 单独一个文件、明文存在 SharedPreferences（本机单用户，和别的偏好同级别），
 * 不进任何日志、不进备份导出、不随订阅 OPML 走。
 *
 * 目前接进来的四家（2026-09-25 实测都仍开放免费额度、且都有「免 key 搜不出」的痛点）：
 *  - **Perplexity Sonar**：`POST /chat/completions`，返回带引用的来源，最适合「搜出一堆有价值来源」。
 *  - **秘塔 Metaso**：中文 AI 搜索，国内可达，最适合中文兴趣词。
 *  - **Tavily**：AI 优化的全网搜索，返回带摘要的结果。
 *  - **Brave Search**：独立索引的网页搜索（**用 `X-Subscription-Token` 而不是 Bearer**）。
 */
class SearchApiKeys(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var perplexity: String
        get() = prefs.getString(KEY_PPLX, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PPLX, value.trim()).apply()

    var metaso: String
        get() = prefs.getString(KEY_METASO, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_METASO, value.trim()).apply()

    var tavily: String
        get() = prefs.getString(KEY_TAVILY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TAVILY, value.trim()).apply()

    var brave: String
        get() = prefs.getString(KEY_BRAVE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BRAVE, value.trim()).apply()

    /** 按 provider id 取 key；不在清单里返回空串。 */
    operator fun get(id: String): String = when (id) {
        "perplexity" -> perplexity
        "metaso" -> metaso
        "tavily" -> tavily
        "brave" -> brave
        else -> ""
    }

    /** 写回某个 provider 的 key。 */
    fun setKey(id: String, key: String) {
        when (id) {
            "perplexity" -> perplexity = key
            "metaso" -> metaso = key
            "tavily" -> tavily = key
            "brave" -> brave = key
        }
    }

    /** 给 [com.example.feedreader.data.recall.RecallChannels.builtIn] 用的快照：id -> key（含空串）。 */
    fun toMap(): Map<String, String> = mapOf(
        "perplexity" to perplexity,
        "metaso" to metaso,
        "tavily" to tavily,
        "brave" to brave,
    )

    companion object {
        private const val FILE = "myfeed_search_keys"
        private const val KEY_PPLX = "perplexity"
        private const val KEY_METASO = "metaso"
        private const val KEY_TAVILY = "tavily"
        private const val KEY_BRAVE = "brave"

        /** 设置页要展示的 provider 清单（id / 名字 / 获取 key 的网站）。 */
        val PROVIDERS: List<SearchKeyProvider> = listOf(
            SearchKeyProvider("perplexity", "Perplexity Sonar", "https://www.perplexity.ai/settings/api"),
            SearchKeyProvider("metaso", "秘塔 Metaso", "https://metaso.cn/search-api/api-keys"),
            SearchKeyProvider("tavily", "Tavily", "https://tavily.com"),
            SearchKeyProvider("brave", "Brave Search", "https://api-dashboard.search.brave.com"),
        )
    }
}

/**
 * 设置页「填 key」区的一行。
 *
 * [siteUrl] 是「快速跳去拿 key」的入口 —— 用户填 key 前得先去对应官网注册/开额度，
 * 这个链接直接把他带过去，比让他自己搜强。
 */
data class SearchKeyProvider(
    val id: String,
    val name: String,
    val siteUrl: String,
    /** 当前已保存的 key（可能为空）。UI 用密码框遮罩展示。 */
    val key: String = "",
)
