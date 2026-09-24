package com.example.feedreader.data

import android.content.Context

/**
 * 应用偏好。就几个开关，用 SharedPreferences 够了，不值得为它引 DataStore。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 已启用的订阅源 id。
     * 没写过（首次运行）时默认全部启用；全部关掉是合法状态，首页会给引导。
     */
    var enabledSourceIds: Set<String>
        get() = prefs.getStringSet(KEY_SOURCES, null)?.toSet()
            ?: FeedSources.DEFAULT.map { it.id }.toSet()
        set(value) {
            prefs.edit().putStringSet(KEY_SOURCES, value).apply()
        }

    /** true = 用内置浏览器打开文章；false = 交给系统浏览器。 */
    var useInAppBrowser: Boolean
        get() = prefs.getBoolean(KEY_IN_APP_BROWSER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IN_APP_BROWSER, value).apply()
        }

    /** 缓存保留天数，超期自动清掉；0 = 永久保留。 */
    var cacheRetentionDays: Int
        get() = prefs.getInt(KEY_RETENTION, DEFAULT_RETENTION_DAYS)
        set(value) {
            prefs.edit().putInt(KEY_RETENTION, value).apply()
        }

    /** 缓存容量上限（MB），超出后从最旧的开始删；0 = 不限制。 */
    var cacheMaxMb: Int
        get() = prefs.getInt(KEY_MAX_MB, DEFAULT_MAX_MB)
        set(value) {
            prefs.edit().putInt(KEY_MAX_MB, value).apply()
        }

    /** 把「上限 MB」换算成字节数给 [FeedCache.prune] 用。 */
    fun cacheMaxBytes(): Long = if (cacheMaxMb <= 0) 0L else cacheMaxMb * 1024L * 1024L

    /** 网页搜索用哪个引擎（[SearchEngine.id]）。 */
    var searchEngineId: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, null) ?: SearchEngine.DEFAULT.id
        set(value) {
            prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()
        }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        const val DEFAULT_MAX_MB = 20

        /** 设置页可选的保留天数；0 = 永久。 */
        val RETENTION_CHOICES = listOf(1, 3, 7, 30, 0)

        /** 设置页可选的容量上限（MB）；0 = 不限。 */
        val SIZE_CHOICES_MB = listOf(5, 20, 50, 0)

        fun retentionLabel(days: Int): String = if (days <= 0) "永久保留" else "$days 天"

        fun sizeLabel(mb: Int): String = if (mb <= 0) "不限制" else "$mb MB"

        private const val FILE = "myfeed_settings"
        private const val KEY_SOURCES = "enabled_sources"
        private const val KEY_IN_APP_BROWSER = "in_app_browser"
        private const val KEY_RETENTION = "cache_retention_days"
        private const val KEY_MAX_MB = "cache_max_mb"
        private const val KEY_SEARCH_ENGINE = "search_engine"
    }
}
