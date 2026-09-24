package com.example.feedreader.data

import android.content.Context
import com.example.feedreader.data.recall.RecallChannels

/**
 * 兴趣偏好与召回通道的持久化。
 *
 * 与 [SettingsStore] 一样用 SharedPreferences —— 数据量就这么点，不值得引 DataStore。
 */
class InterestStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        migrateChannelDefaults()
        migrateInterestDefaults()
    }

    /**
     * 出厂默认值改版时的迁移：把新纳入默认关闭的通道补进禁用集合。
     *
     * 为什么需要版本号，和 `SourceStore.migrate` 是同一个问题：**「默认关掉某条通道」
     * 只对从没动过开关的人天然生效**。一旦用户动过任何一个通道开关，磁盘上就有一份
     * 集合了，此时再改 `RecallChannels.DEFAULT_DISABLED` 对他完全没影响。
     *
     * 代价也一样：用户手动打开过某条默认关闭的通道，版本号一涨会被重新关掉。
     */
    private fun migrateChannelDefaults() {
        if (prefs.getInt(KEY_CHANNEL_DEFAULTS_VERSION, 0) >= CHANNEL_DEFAULTS_VERSION) return

        val next = if (prefs.contains(KEY_DISABLED_CHANNELS)) {
            disabledRecallChannels + RecallChannels.DEFAULT_DISABLED
        } else {
            RecallChannels.DEFAULT_DISABLED
        }
        prefs.edit()
            .putStringSet(KEY_DISABLED_CHANNELS, next)
            .putInt(KEY_CHANNEL_DEFAULTS_VERSION, CHANNEL_DEFAULTS_VERSION)
            .apply()
    }

    /**
     * 出厂默认兴趣改版时的迁移：把新默认推给「还停留在旧默认」的用户。
     *
     * 和 [migrateChannelDefaults] 是同一类问题，但语义更克制：**只覆盖仍停在
     * [Interests.OLD_DEFAULT] 的用户**，已经个性化（哪怕只是改过某个权重）的列表
     * 一律不动——兴趣是高度个人化的东西，静默覆盖比「默认不生效」更糟。
     *
     * 从没写过兴趣的用户（`KEY_INTERESTS` 不存在）不用写盘，getter 会自动回退到新的
     * [Interests.DEFAULT]；这里只负责把版本号涨上去，免得以后又跑一遍。
     */
    private fun migrateInterestDefaults() {
        if (prefs.getInt(KEY_INTERESTS_DEFAULTS_VERSION, 0) >= INTERESTS_DEFAULTS_VERSION) return

        val current = if (prefs.contains(KEY_INTERESTS)) {
            InterestCodec.decode(prefs.getString(KEY_INTERESTS, "").orEmpty())
        } else {
            null
        }

        if (current != null && current == Interests.OLD_DEFAULT) {
            prefs.edit()
                .putString(KEY_INTERESTS, InterestCodec.encode(Interests.DEFAULT))
                .apply()
        }
        prefs.edit().putInt(KEY_INTERESTS_DEFAULTS_VERSION, INTERESTS_DEFAULTS_VERSION).apply()
    }

    /**
     * 用户的兴趣列表。
     *
     * **空列表是合法状态**（用户把兴趣全删了，首页给引导），所以用 `contains` 区分
     * 「从没写过」和「写过但是空的」—— 不能写成 `decode(...).ifEmpty { DEFAULT }`，
     * 那样用户删光之后重启又会被默认值填回来。
     */
    var interests: List<Interest>
        get() = if (prefs.contains(KEY_INTERESTS)) {
            InterestCodec.decode(prefs.getString(KEY_INTERESTS, "").orEmpty())
        } else {
            Interests.DEFAULT
        }
        set(value) {
            prefs.edit().putString(KEY_INTERESTS, InterestCodec.encode(value)).apply()
        }

    /**
     * **被关掉**的召回通道 id（见 `RecallChannel.id`）。
     *
     * 存「关掉的」而不是「开着的」，是因为默认要全开：空集合天然表示「一个都没关」，
     * 不需要一个 `""` 之类的哨兵值去表示默认 —— 那种写法在类型上会退化成 `Any`
     * （`String` 和 `Set<String>` 求最大公共父类），是真实的踩坑点。
     * 同理，以后新增通道默认也是开的，老用户不用手动打开。
     *
     * 唯一的例外是 [RecallChannels.DEFAULT_DISABLED]（目前只有 CSDN 那路内容农场）。
     */
    var disabledRecallChannels: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_CHANNELS, null)?.toSet()
            ?: RecallChannels.DEFAULT_DISABLED
        set(value) {
            prefs.edit().putStringSet(KEY_DISABLED_CHANNELS, value).apply()
        }

    /** 某个通道当前是否启用。UI 侧统一走这个，别自己判空集合。 */
    fun isRecallChannelEnabled(id: String): Boolean = id !in disabledRecallChannels

    /**
     * 自建 RSSHub 实例的基地址（结尾不带斜杠）。
     *
     * **空 = 用内置的公共镜像**（见 `RssHubChannel.DEFAULT_MIRROR`），不是「关掉这条通道」。
     * 填了就用自建实例 —— 公共镜像只对少数关键词路由可用
     * （实测 `/weibo/keyword` 通，`/github/search`、`/douban/search` 被限流 429，
     * `/bilibili/search`、`/zhihu/search` 已 404），所以实例越稳体验越好。
     * 想彻底关掉就在 [disabledRecallChannels] 里加上 `rsshub`。
     */
    var rssHubBaseUrl: String
        get() = prefs.getString(KEY_RSSHUB, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_RSSHUB, value.trim().trimEnd('/')).apply()
        }

    /** 是否启用语义排序。关掉后只剩词法匹配，用于在低端机上兜底。 */
    var semanticEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEMANTIC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SEMANTIC, value).apply()
        }

    /** 列表里是否显示「命中哪个兴趣」的推荐理由。 */
    var showRecommendReason: Boolean
        get() = prefs.getBoolean(KEY_SHOW_REASON, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_REASON, value).apply()
        }

    companion object {
        /**
         * 出厂默认通道开关的版本号。**改 [RecallChannels.DEFAULT_DISABLED] 就要加一**，
         * 否则改动对已经动过开关的用户无效（原因见 [migrateChannelDefaults]）。
         */
        private const val CHANNEL_DEFAULTS_VERSION = 1

        /** 出厂默认兴趣的版本号。**改 [Interests.DEFAULT] 就要加一**，否则改动对停在旧默认的用户无效（见 [migrateInterestDefaults]）。 */
        private const val INTERESTS_DEFAULTS_VERSION = 1

        private const val FILE = "myfeed_interests"
        private const val KEY_INTERESTS = "interest_list"
        private const val KEY_INTERESTS_DEFAULTS_VERSION = "interest_defaults_version"
        private const val KEY_DISABLED_CHANNELS = "recall_channels_disabled"
        private const val KEY_CHANNEL_DEFAULTS_VERSION = "recall_channel_defaults_version"
        private const val KEY_RSSHUB = "rsshub_base_url"
        private const val KEY_SEMANTIC = "semantic_enabled"
        private const val KEY_SHOW_REASON = "show_recommend_reason"
    }
}
