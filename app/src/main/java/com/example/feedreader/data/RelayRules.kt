package com.example.feedreader.data

/**
 * crawlbase relay 的地址规则。
 *
 * relay = PC 上 `python -m harvest serve` 起来的那台静态 RSS 服务：
 * `<base>/feeds.opml` 是订阅清单，`<base>/<feed-id>.xml` 是各条 feed。
 *
 * ### 为什么非要在这里改写 xmlUrl
 *
 * harvest 导出 OPML 时写的是**相对**地址（`xmlUrl="zhihu-xxx.xml"`）：那台服务只知道
 * 自己本机的端口，往清单里写 `127.0.0.1:8099` 对手机是错的，写死任何别的地址同样是
 * 错的。基地址只能由**这一侧**给 —— App 才知道自己是按哪个地址把清单拉下来的。
 *
 * 于是 [rebaseUrl] 干两件事：相对写法补上基地址；指向回环的绝对写法也换掉 —— 手机上的
 * 127.0.0.1 是手机自己，照原样收进来只会攒出一堆「域名解析失败」式的死链。老导出
 * （绝对 127.0.0.1）和用户自己改过的清单都靠这条分支兜住。非回环的绝对地址原样放过。
 *
 * 注意相对项的补全必须**在 [OpmlParser.parse] 里就发生**（传 `baseUrl`）：那边的准入
 * 校验只认绝对地址，等清单解析完再重基就已经一条不剩了 —— 界面表现为
 * 「这份 OPML 里没有可用的订阅源」，而那正是 2026-09-27 首次真机联调踩到的坑。
 *
 * 纯字符串逻辑，无 Android 依赖，可直接单测（同 [SourceRules]）。
 */
object RelayRules {

    /** harvest serve 的默认端口；用户只填 IP 时补上它。 */
    const val DEFAULT_PORT = 8099

    /** relay 的订阅清单固定叫这个名字，用户不用连路径一起填。 */
    const val OPML_FILE = "feeds.opml"

    /**
     * 规整用户填进来的 relay 基地址，得到 `http://host:port`。
     *
     * 接受这几类写法，因为它们都是用户真会粘进输入框的东西：
     * `192.168.1.20` / `192.168.1.20:8099` / `http://192.168.1.20:8099` /
     * `http://192.168.1.20:8099/feeds.opml`（从浏览器地址栏直接复制）。
     * 路径、查询、尾部斜杠一律丢掉，只留 scheme + host + port。
     *
     * @return 规整后的基地址；不像个地址时返回 null（界面据此提示，不抛异常）。
     */
    fun normalizeBase(raw: String): String? {
        val text = raw.trim().trimEnd('/', ' ')
        if (text.isEmpty()) return null

        val scheme = when {
            // 协议头按 RFC 是大小写无关的；扫码和粘贴到手输框是两条入口，
            // 这里严格区分小写会让 `HTTP://…` 得到一句「不像个地址」。
            // 但不能整串 lowercase —— feed id 里有大小写（`zhihu-Upper_Case_Gains`）。
            text.startsWith("https://", ignoreCase = true) -> "https"
            text.startsWith("http://", ignoreCase = true) -> "http"
            // 别的协议写在这里没有意义（relay 只有明文 HTTP），与其悄悄换成 http
            // 不如直说 —— 用户会以为自己连的是那个协议。
            text.contains("://") -> return null
            // 没写协议就按明文 http：这是家里局域网，而 https 只会让「证书无效」
            // 变成第一道坎。要 TLS 的人自己把 https:// 带进来。
            else -> "http"
        }
        val authority = text.substringAfter("://", text)
            .substringBefore('/')
            .substringBefore('?')
            .trim()

        val host: String
        val port: Int
        val bracketed = authority.startsWith("[")
        if (bracketed) {
            // IPv6 字面量必须带方括号，否则里面的冒号和端口分隔符分不开
            val close = authority.indexOf(']')
            if (close < 4) return null
            host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            port = when {
                tail.isEmpty() -> DEFAULT_PORT
                tail.startsWith(":") -> parsePort(tail.substring(1)) ?: return null
                else -> return null
            }
        } else {
            val separator = authority.lastIndexOf(':')
            if (separator < 0) {
                host = authority
                port = DEFAULT_PORT
            } else {
                host = authority.substring(0, separator)
                port = parsePort(authority.substring(separator + 1)) ?: return null
            }
        }
        // 方括号那条分支自己已经保证了结构，字符校验只适用于主机名写法
        if (!bracketed && !validHost(host)) return null

        return "$scheme://$host:$port"
    }

    /** 订阅清单的地址。[base] 是 [normalizeBase] 的产物，但传进来前已经被存好了。 */
    fun opmlUrl(base: String): String = "${base.trimEnd('/')}/$OPML_FILE"

    /**
     * 地址里的主机 + 端口（小写，去掉协议和路径），解析不出来时原样返回。
     *
     * 「这两条失败是不是同一台机器」只能按这个比 —— 整条地址每条 feed 都不一样，
     * 源名又是用户可以改的。见 [FeedRepository.collapseFailures] 与
     * [RelayGuidance.issueForRelayFailures]。
     */
    fun authorityOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').lowercase()

    /**
     * 把 OPML 里的一条 feed 地址落到基地址上。
     *
     * 非回环的绝对地址原样返回（用户可能自己改了 harvest 的 base，那是对的，不该动）；
     * 相对写法补上基地址。
     */
    fun rebaseUrl(url: String, base: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        val root = base.trimEnd('/')

        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme == "http" || scheme == "https") {
            return if (hostOf(trimmed) in LOOPBACK_HOSTS) {
                join(root, pathOf(trimmed))
            } else {
                trimmed
            }
        }
        return join(root, trimmed.trimStart('/'))
    }

    /** 地址里的主机（小写，不带端口、方括号和 `user@`）；解析不出来时原样返回，交给调用方判非回环。 */
    internal fun hostOf(url: String): String {
        var authority = url.substringAfter("://").substringBefore('/')
        if ('@' in authority) authority = authority.substringAfterLast('@')
        return if (authority.startsWith("[")) {
            authority.substring(1, authority.indexOf(']').coerceAtLeast(1))
        } else {
            authority.substringBefore(':')
        }.lowercase()
    }

    /** 整份清单重基，顺带丢掉重基后不成其为地址的项。 */
    fun rebaseSources(sources: List<FeedSource>, base: String): List<FeedSource> =
        sources.mapNotNull { source ->
            val url = rebaseUrl(source.url, base)
            if (!SourceRules.looksLikeUrl(url)) return@mapNotNull null
            // id 由地址派生（见 [SourceRules.customId]），所以地址一动 id 必须跟着动
            if (url == source.url) source
            else source.copy(id = SourceRules.customId(url), url = url)
        }

    /**
     * 一条 relay feed 的身份 —— 地址里的文件名，形如 `zhihu-example.xml`。
     *
     * 用它而不是整条地址判重，是为了让「PC 的 IP 变了」被识别成**同一条订阅换了地址**，
     * 而不是「一条新订阅，外加一条永远失败的老订阅」。见 [SourceStore.syncRelaySources]。
     */
    fun endpointOf(url: String): String? {
        val path = url.substringAfter("://", url).substringAfter('/', "")
        return path.substringBefore('?').trimEnd('/').ifBlank { null }
    }

    /** authority 之后的那一段（含查询），没有则空串。 */
    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://")
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "" else afterScheme.substring(slash + 1)
    }

    private fun join(root: String, path: String): String =
        if (path.isEmpty()) root else "$root/$path"

    private fun parsePort(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** 只认主机名/IPv4 字面量的字符集；不带方括号的 IPv6（`fe80::1`）会在这里被挡掉。 */
    private fun validHost(host: String): Boolean =
        host.length >= 3 && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }

    internal val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0", "::1")
}
