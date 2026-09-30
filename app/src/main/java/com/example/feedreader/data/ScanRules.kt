package com.example.feedreader.data

/**
 * 扫一扫识别出的文本 → 要做的事。
 *
 * 收好几种形态，因为 crawlbase 那侧真会产出它们，而用户在别处复制到的又是另一种：
 *
 *  - `myfeed://…`             App 自己的深链（go.html 上那个按钮跳的就是它）
 *  - `…/go.html?f=<id>`       harvest 二维码的内容；`f` 缺省或 `__relay__` = 整包同步
 *  - `…/feeds.opml`           直接扫到清单
 *  - 其它 http(s) 地址         当成一条 feed 交给 `addSource`，探针拉不到就不入库
 *
 * 单源那条靠的是 harvest 的产物固定叫 `<feed-id>.xml` —— 与 `feeds.opml` 里那个相对
 * `xmlUrl` 是同一条约定（见 [RelayRules]），不是这里另编一套命名。
 *
 * 纯字符串逻辑，无 Android 依赖，可直接单测。
 */
object ScanRules {

    /** harvest go.html 里代表「整包 relay 同步」的伪 feed id，与 crawlbase 的 `portal.ALL_KEY` 同值 */
    const val RELAY_KEY = "__relay__"

    /** feed id 的长度上限：正常 id（`zhihu-<url_token>`）远短于此，超了就是别的东西 */
    const val MAX_FEED_ID = 120

    sealed interface Outcome {

        data class Link(val link: DeepLink) : Outcome

        /** 扫到了，但不是能用的订阅码 —— 界面把原因说清楚，让人接着扫 */
        data class Rejected(val reason: String) : Outcome
    }

    fun classify(raw: String?): Outcome {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Outcome.Rejected("没识别到内容")

        // myfeed:// 优先：它带好了意图，不需要再猜
        DeepLink.parse(text)?.let { return Outcome.Link(it) }

        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return Outcome.Rejected(
                "这不是订阅地址（协议「${scheme.ifBlank { "无" }}」），换个二维码试试"
            )
        }

        val base = RelayRules.normalizeBase(text)
            ?: return Outcome.Rejected("地址不完整，认不出主机名")

        if (RelayRules.hostOf(base) in RelayRules.LOOPBACK_HOSTS) {
            // 手机上的 127.0.0.1 是手机自己：不点破的话，用户只会看到一个「连不上」
            return Outcome.Rejected(
                "$base 是回环地址，手机上打开等于连手机自己。" +
                    "PC 上改用 serve --host auto 重出二维码再扫。"
            )
        }

        val path = text.substringAfter("://").substringAfter('/', "").substringBefore('?')
        return when {
            path.endsWith("feeds.opml") -> Outcome.Link(DeepLink.RelaySync(base))

            path.endsWith("go.html") -> fromGoHtml(text, base)

            else -> Outcome.Link(DeepLink.Subscribe(url = text, title = ""))
        }
    }

    /** `?f=` 的三种去处：整包、单个源、以及 id 不像话时拒绝。 */
    private fun fromGoHtml(text: String, base: String): Outcome {
        val feed = deepLinkQuery(text)["f"].orEmpty()
        if (feed.isBlank() || feed == RELAY_KEY) return Outcome.Link(DeepLink.RelaySync(base))

        // id 会被拼进地址，所以只认 harvest 真会产出的字符集。放行 `../x` 这种
        // 就等于让一张外部二维码决定去连哪个路径。
        if (feed.length > MAX_FEED_ID || ".." in feed ||
            !feed.all { it.isLetterOrDigit() || it in "-_." }
        ) {
            return Outcome.Rejected("二维码里的订阅标识「${feed.take(24)}」不像个 id，先不订阅")
        }
        return Outcome.Link(DeepLink.Subscribe(url = "$base/$feed.xml", title = ""))
    }

    private fun deepLinkQuery(url: String): Map<String, String> =
        url.substringAfter('?', "").split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').trim()
            if (key.isEmpty()) null else key to pair.substringAfter('=', "").trim()
        }.toMap()
}
