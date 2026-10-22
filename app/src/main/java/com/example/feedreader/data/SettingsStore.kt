package com.example.feedreader.data

import android.content.Context

/**
 * 应用偏好。就几个开关，用 SharedPreferences 够了，不值得为它引 DataStore。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    /**
     * crawlbase relay 的基地址（`http://192.168.0.2:8099`）。空串 = 没配过。
     *
     * 存的是 [RelayRules.normalizeBase] 规整**之后**的值，所以读出来就能直接拼地址，
     * 不用每次再解析一遍、也不用担心界面上显示的和实际用的不是一回事。
     */
    var relayBaseUrl: String
        get() = prefs.getString(KEY_RELAY_BASE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_RELAY_BASE, value).apply()
        }

    /** 阅流电脑端服务基地址。 */
    var desktopFeedBaseUrl: String
        get() = prefs.getString(KEY_DESKTOP_FEED_BASE, "").orEmpty()
        set(value) {
            val next = value.trim().trimEnd('/')
            val changed = next != desktopFeedBaseUrl
            prefs.edit().putString(KEY_DESKTOP_FEED_BASE, next).apply()
            if (changed) {
                // 地址换了就是另一台电脑：旧钥匙和旧游标都不该跟着搬过去。
                // 游标留着最多让服务端回一次 CURSOR_EXPIRED，而令牌带错机器是把凭据递给外人。
                clearDesktopFeedState()
            }
        }

    /**
     * 电脑端服务的访问令牌。由 WebView 那边把它 IndexedDB 里的值递过来一次
     * （`llmTransport` 的 `credentials` 动作），也可以在设置页手填。
     *
     * 只写偏好文件，**不进日志**：日志会被 bug 报告带走，而仓库是公开的。
     */
    var desktopFeedToken: String
        get() = prefs.getString(KEY_DESKTOP_FEED_TOKEN, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_DESKTOP_FEED_TOKEN, value.trim()).apply() }

    /** 变更日志游标；-1 = 从没同步过，下一轮先取全量快照。 */
    var desktopFeedSyncSeq: Long
        get() = prefs.getLong(KEY_DESKTOP_FEED_SEQ, -1L)
        set(value) { prefs.edit().putLong(KEY_DESKTOP_FEED_SEQ, value).apply() }

    /** 上一次同步成功的时刻（毫秒），0 = 没有过。失败时刻不记在这里（见 [desktopFeedSyncError]）。 */
    var desktopFeedSyncAt: Long
        get() = prefs.getLong(KEY_DESKTOP_FEED_SYNC_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_DESKTOP_FEED_SYNC_AT, value).apply() }

    /** 上一次同步失败的原因（`CODE: 文案`）。空串 = 当前没有错误。 */
    var desktopFeedSyncError: String
        get() = prefs.getString(KEY_DESKTOP_FEED_SYNC_ERROR, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_DESKTOP_FEED_SYNC_ERROR, value.trim()).apply() }

    /** 连续失败次数，退避时长按它翻倍；任何一轮成功就归零。 */
    var desktopFeedSyncFailures: Int
        get() = prefs.getInt(KEY_DESKTOP_FEED_FAILURES, 0)
        set(value) { prefs.edit().putInt(KEY_DESKTOP_FEED_FAILURES, value).apply() }

    /** 退避到什么时候（毫秒）。电脑端关机时不该每次刷新都白等一次网络超时。 */
    var desktopFeedBackoffUntil: Long
        get() = prefs.getLong(KEY_DESKTOP_FEED_BACKOFF_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_DESKTOP_FEED_BACKOFF_UNTIL, value).apply() }

    /**
     * 这台设备在同步协议里的身份。**稳定**：装一次生一次，跨进程重启、跨电脑换地址都不变，
     * 因为服务端的幂等判定按 `(device_id, client_seq)` 认，换 id 等于把同一批操作重新算一遍。
     */
    var desktopDeviceId: String
        get() = prefs.getString(KEY_DESKTOP_DEVICE_ID, "").orEmpty().ifBlank {
            java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_DESKTOP_DEVICE_ID, it).apply()
            }
        }
        set(value) { prefs.edit().putString(KEY_DESKTOP_DEVICE_ID, value.trim()).apply() }

    /** 本机已发出的操作序号（同一 device 内单调递增，服务端按它排序与幂等去重）。 */
    var desktopClientSeq: Long
        get() = prefs.getLong(KEY_DESKTOP_CLIENT_SEQ, 0L)
        set(value) { prefs.edit().putLong(KEY_DESKTOP_CLIENT_SEQ, value).apply() }

    /**
     * 清掉「这台电脑」的同步状态：令牌、游标、时刻、错误、退避。
     *
     * 不含 [desktopDeviceId] 与 [desktopClientSeq] —— 那是本机身份，换电脑不换人；
     * 序号重置会让服务端把同一批操作当新操作重算。
     */
    fun clearDesktopFeedState() {
        prefs.edit()
            .remove(KEY_DESKTOP_FEED_TOKEN)
            .remove(KEY_DESKTOP_FEED_SEQ)
            .remove(KEY_DESKTOP_FEED_SYNC_AT)
            .remove(KEY_DESKTOP_FEED_SYNC_ERROR)
            .remove(KEY_DESKTOP_FEED_FAILURES)
            .remove(KEY_DESKTOP_FEED_BACKOFF_UNTIL)
            .apply()
    }

    /**
     * 命令口配对 token（扫码时从 `myfeed://relay-sync?base=…&tok=…` 带进来）。
     *
     * 空就是没配对：这时候界面上「让这台电脑再采一轮」那一节只说话不发请求。
     * 它跟着 relay 地址一起被清 —— 地址换了就是另一台电脑，旧钥匙不该还在口袋里。
     */
    var relayCommandToken: String
        get() = prefs.getString(KEY_RELAY_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_RELAY_TOKEN, value.trim()).apply()
        }

    /**
     * 刷新时顺带把已确认过的 relay 清单再拉一遍（见 `docs/crawlbase-relay.md` 的自动同步一节）。
     * 默认开 —— 用户既然扫过一次码，「PC 上加个账号手机就该有」是这条链的本意。
     */
    var relayAutoSync: Boolean
        get() = prefs.getBoolean(KEY_RELAY_AUTO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RELAY_AUTO, value).apply()
        }

    /** 上一次自动同步的时刻（毫秒）。失败也记，免得 PC 关机时每次刷新都白等一次网络超时。 */
    var relayLastAutoSyncAt: Long
        get() = prefs.getLong(KEY_RELAY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_RELAY_LAST_SYNC, value).apply()
        }

    /** 内置浏览器的网页暗色模式，存 [WebDarkMode.id]。 */
    var webDarkModeId: String
        get() = prefs.getString(KEY_WEB_DARK_MODE, null) ?: WebDarkMode.DEFAULT.id
        set(value) {
            prefs.edit().putString(KEY_WEB_DARK_MODE, value).apply()
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

        /**
         * 偏好文件。**对 [SourceStore] 公开**：源的开关历史上存在这个文件的
         * `enabled_sources` key 里，那边做格式迁移时要原地把它读出来。
         * 源码里的 key 名见 [SourceStore] 的 `KEY_LEGACY_ENABLED`。
         */
        const val FILE = "myfeed_settings"

        private const val KEY_IN_APP_BROWSER = "in_app_browser"
        private const val KEY_RETENTION = "cache_retention_days"
        private const val KEY_MAX_MB = "cache_max_mb"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_DESKTOP_FEED_BASE = "desktop_feed_base_url"
        private const val KEY_DESKTOP_FEED_TOKEN = "desktop_feed_token"
        private const val KEY_DESKTOP_FEED_SEQ = "desktop_feed_sync_seq"
        private const val KEY_DESKTOP_FEED_SYNC_AT = "desktop_feed_sync_at"
        private const val KEY_DESKTOP_FEED_SYNC_ERROR = "desktop_feed_sync_error"
        private const val KEY_DESKTOP_FEED_FAILURES = "desktop_feed_failures"
        private const val KEY_DESKTOP_FEED_BACKOFF_UNTIL = "desktop_feed_backoff_until"
        private const val KEY_DESKTOP_DEVICE_ID = "desktop_device_id"
        private const val KEY_DESKTOP_CLIENT_SEQ = "desktop_client_seq"
        internal const val KEY_RELAY_BASE = "relay_base_url"
        private const val KEY_RELAY_TOKEN = "relay_command_token"
        private const val KEY_RELAY_AUTO = "relay_auto_sync"
        private const val KEY_RELAY_LAST_SYNC = "relay_last_sync_at"
        private const val KEY_WEB_DARK_MODE = "web_dark_mode"
    }
}

/**
 * 内置浏览器的网页暗色模式。
 *
 * `FOLLOW_SYSTEM` 跟的是**设备的夜间模式**：WebView 会把它转达成
 * `prefers-color-scheme: dark`，站点自带暗色的（微博 m 站那 80 条规则）能白捡。
 * `ALWAYS` 是「设备亮色也要暗」——这时候媒体查询不会自己变，
 * 只能由注入器用 CSS 兜住，见 `ui/web/DarkPageInjector`。
 */
enum class WebDarkMode(val id: String, val label: String) {
    FOLLOW_SYSTEM("follow_system", "跟随系统夜间模式"),
    ALWAYS("always", "总是暗色"),
    OFF("off", "不启用"),
    ;

    companion object {
        val DEFAULT = FOLLOW_SYSTEM

        /** 设置页的候选顺序就按这个来。 */
        val CHOICES = values().toList()

        /** 认不出来的 id（版本回退、手改过偏好文件）一律回到默认，不抛异常。 */
        fun of(id: String?): WebDarkMode = CHOICES.firstOrNull { it.id == id } ?: DEFAULT
    }
}
