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

    private companion object {
        const val FILE = "myfeed_settings"
        const val KEY_SOURCES = "enabled_sources"
        const val KEY_IN_APP_BROWSER = "in_app_browser"
    }
}
