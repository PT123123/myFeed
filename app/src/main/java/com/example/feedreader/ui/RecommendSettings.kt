package com.example.feedreader.ui

import com.example.feedreader.data.Interest
import com.example.feedreader.data.Interests
import com.example.feedreader.data.SearchKeyProvider
import com.example.feedreader.data.recall.RecallChannels
import io.github.pt123123.semantic.VectorStats

/**
 * 设置页展示用的一条召回通道。
 *
 * 和 `RecallChannelInfo` 分开是有必要的：那个描述「这条通道是什么」，
 * 这个描述「它现在开没开」。开关的真相在 [com.example.feedreader.data.InterestStore] 里
 * （存的是「被关掉的」集合），所以由 ViewModel 合成为一份快照，界面不做任何判断。
 */
data class RecallChannelSetting(
    val id: String,
    val name: String,
    val hint: String,
    val enabled: Boolean,
)

/**
 * 设置页要用到的推荐相关状态。
 *
 * 打成一个包传，是因为 `SettingsScreen` 的参数表已经很长了 —— 再加六七个
 * 散装参数，调用点会变成一屏读不完的键值对。
 */
data class RecommendSettings(
    val interests: List<Interest> = emptyList(),
    val channels: List<RecallChannelSetting> = emptyList(),
    /** 「填 key 才能用」的搜索服务清单；key 本身也在这里回显（设备本地，UI 用密码框遮罩）。 */
    val searchKeys: List<SearchKeyProvider> = emptyList(),
    val rssHubBaseUrl: String = "",
    val semanticEnabled: Boolean = true,
    val showReason: Boolean = true,
    val vectorStats: VectorStats = VectorStats(),
) {

    /** 真正会被用于排序的兴趣词数量。 */
    val activeCount: Int get() = interests.count { it.enabled && it.keyword.isNotBlank() }

    /**
     * 这轮实际会发出去的查询数。
     *
     * 超过 [Interests.EFFECTIVE_COUNT] 的部分发不出请求（召回侧的硬上限），
     * 界面据此提示用户「多出来的这几个这轮用不到」，否则用户会以为调了权重没生效。
     */
    val queryCount: Int get() = minOf(activeCount, Interests.EFFECTIVE_COUNT)

    val overflowCount: Int get() = (activeCount - queryCount).coerceAtLeast(0)

    /** 开启了「已订阅源」这条通道吗。关掉后 RSS 只当缓存用，不进兴趣流。 */
    val subscriptionEnabled: Boolean
        get() = channels.firstOrNull { it.id == RecallChannels.ID_SUBSCRIPTION }?.enabled ?: true
}
