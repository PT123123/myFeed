package com.example.feedreader.data

/**
 * relay 清单的**静默**自动同步：刷新时顺带把已确认过那台 PC 的 `feeds.opml` 再拉一遍。
 *
 * 为什么要有这一步：扫码/深链那条确认流程是「一次性的」，而 harvest 那边是持续在长的 ——
 * 加一个账号、改一次 IP，按现在的流程都得用户重新扫一次整包码。用户说过「有多少就实时放多少」，
 * 那手机侧也该实时跟上，不然 PC 采得再多也进不来。
 *
 * 这里只放**决策**（该不该同步、哪些条目不能自动加回来），不放网络：
 * 两条都是纯函数，单测覆盖得到，而不必为了测一个布尔值去 mock SharedPreferences。
 */
object RelayAutoSync {

    /** 两次自动同步的最小间隔。半小时是拍脑袋的下限，但要有下限：刷新可能连点。 */
    const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * 这一轮刷新要不要顺带同步 relay。
     *
     * [lastAttemptAt] 是**上次尝试**的时刻而不是上次成功的时刻 —— PC 关机时每次刷新都去
     * 撞一次连接超时（8 秒）太伤手感，失败也得占住这个间隔。
     * 从没同步过（0）时 `now - 0` 必然大于间隔，所以第一次刷新就会同步。
     */
    fun shouldRun(enabled: Boolean, baseUrl: String, lastAttemptAt: Long, now: Long): Boolean {
        if (!enabled) return false
        if (baseUrl.isBlank()) return false
        return now - lastAttemptAt >= MIN_INTERVAL_MS
    }

    /**
     * 滤掉用户亲手删过的那些 relay 条目。
     *
     * 自动同步**必须**尊重这份名单，手动同步不用 —— 「我删了它，它每半小时自己回来」
     * 是这一片最容易发生的差评，而用户手点一次同步代表他就是想要这份清单。
     *
     * 比对按 [RelayRules.endpointOf]（地址里的文件名）而不是整条地址：
     * 用户删的是「知乎那条」，PC 换 IP 之后那条的地址已经变了，文件名没变。
     */
    fun skipRemoved(incoming: List<FeedSource>, removedEndpoints: Set<String>): List<FeedSource> {
        if (removedEndpoints.isEmpty()) return incoming
        return incoming.filterNot { source ->
            val endpoint = RelayRules.endpointOf(source.url)
            endpoint != null && endpoint in removedEndpoints
        }
    }
}
