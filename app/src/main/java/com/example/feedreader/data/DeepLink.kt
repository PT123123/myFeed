package com.example.feedreader.data

import java.net.URLDecoder

/**
 * `myfeed://` 深链 —— 扫码进 App 后唯一要理解的东西。
 *
 * 二维码本身**不编码** myfeed:// 地址：系统相机和各家浏览器对自定义协议的处理不一致，
 * 有的直接拒。所以 harvest 那侧的二维码指到一个 http 桥接页（`go.html?f=…`），
 * 页面上再给一个跳深链的按钮。见 crawlbase 仓库 `harvest/formats/portal.py`。
 *
 * 支持两种：
 *
 *   myfeed://relay-sync?base=http%3A%2F%2F192.168.1.20%3A8099
 *   myfeed://subscribe?url=http%3A%2F%2F192.168.1.20%3A8099%2Fzhihu-x.xml&title=%E2%80%A6
 *
 * 只吃字符串、用 JDK 的 URLDecoder，是为了能在普通 JVM 单测里跑（同 [RelayRules]）。
 */
sealed interface DeepLink {

    /**
     * 整包同步：[base] 是 PC 上 harvest serve 的地址，原样交给 [RelayRules.normalizeBase] 判定。
     *
     * [token] 是命令口的配对钥匙，只有电脑上用 `serve --allow-commands` 起服务时二维码才带。
     * 没有它也完全正常 —— 那一台只做静态 RSS，手机照旧能读已经采好的东西。
     */
    data class RelaySync(val base: String, val token: String = "") : DeepLink

    /** 单个订阅：[title] 可能为空（App 侧 addSource 会退回探测到的频道标题）。 */
    data class Subscribe(val url: String, val title: String) : DeepLink

    companion object {

        const val SCHEME = "myfeed"

        /** 解析失败一律返回 null，不抛：Intent 的内容来自外部，界面据此静默忽略即可。 */
        fun parse(raw: String?): DeepLink? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val scheme = text.substringBefore("://", "").lowercase()
            if (scheme != SCHEME) return null

            val rest = text.substringAfter("://")
            val host = rest.substringBefore('/').substringBefore('?').lowercase()
            val params = queryParams(rest.substringAfter('?', ""))

            return when (host) {
                "relay-sync" -> params["base"]?.takeIf { it.isNotBlank() }?.let {
                    RelaySync(it, token = safeToken(params["tok"].orEmpty()))
                }
                "subscribe" -> params["url"]?.takeIf { it.isNotBlank() }?.let {
                    Subscribe(it, params["title"].orEmpty())
                }

                else -> null
            }
        }

        private fun queryParams(query: String): Map<String, String> =
            if (query.isBlank()) emptyMap() else query.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (key.isEmpty()) null else key to decode(value)
            }.toMap()

        /**
         * token 来自一张外部二维码，所以先当不可信字符串处理：
         * 只收 harvest 会产出的字符集（`secrets.token_urlsafe` 的字母数字加 `-_`），长度夹到 64。
         * 认不出就当没配对，而不是把半截钥匙存起来让用户反复扫。
         */
        private const val MAX_TOKEN_CHARS = 64

        private fun safeToken(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty() || value.length > MAX_TOKEN_CHARS) return ""
            return if (value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) value else ""
        }

        private fun decode(value: String): String = try {            // 编码侧是 encodeURIComponent，它不会产出裸 '+'，所以 URLDecoder 的
            // 「'+' 当空格」只可能影响手抄的链接，不值得为此换一套解码。
            URLDecoder.decode(value, "UTF-8")
        } catch (error: Exception) {
            // 半截百分号（用户手抄时漏了字符）不值得让整条深链失效
            value
        }
    }
}
