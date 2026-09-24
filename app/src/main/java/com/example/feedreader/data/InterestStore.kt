package com.example.feedreader.data

import android.content.Context

/**
 * 兴趣偏好与召回通道的持久化。
 *
 * 与 [SettingsStore] 一样用 SharedPreferences —— 数据量就这么点，不值得引 DataStore。
 */
class InterestStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
     */
    var disabledRecallChannels: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_CHANNELS, null)?.toSet() ?: emptySet()
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
        private const val FILE = "myfeed_interests"
        private const val KEY_INTERESTS = "interest_list"
        private const val KEY_DISABLED_CHANNELS = "recall_channels_disabled"
        private const val KEY_RSSHUB = "rsshub_base_url"
        private const val KEY_SEMANTIC = "semantic_enabled"
        private const val KEY_SHOW_REASON = "show_recommend_reason"
    }
}
