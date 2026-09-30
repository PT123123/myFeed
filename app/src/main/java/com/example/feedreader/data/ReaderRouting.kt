package com.example.feedreader.data

/**
 * 阅读页该加载原页，还是先把订阅里带的全文给用户看。
 *
 * 知乎 / 小红书 / X 这三家的页面在应用内的 WebView 里基本读不到正文：cookie 存在本应用
 * 自己的沙箱里，拿不到别的浏览器的登录态，而它们的接口除了 cookie 还要站点侧签名 ——
 * 把 cookie 搬过来也换不来能用的请求。所以点进去看到的是半屏登录墙，而不是内容。
 *
 * harvest 那条路把回答**摘要**写进了 RSS（2026-09-27 实测：`content:encoded` 和
 * `description` 一样长，单篇最长 268 字 —— 知乎的列表接口只回 `excerpt`，全文要逐条再请求
 * 一次详情才有）。所以这类条目先给订阅里那份内容，「看原页」留成主动选择：比半屏登录墙
 * 好，但不能管它叫全文。内置源里偶有指向这些域的（知乎热榜），同样适用。
 *
 * 只看 host 不看 path：知乎的回答页 / 专栏页 / 问题页都在同一个域下，都要登录，
 * 按 path 白名单放行只会漏。
 *
 * 整片的取舍和实测记录在 [docs/offline-full-text.md](../../../../../../../docs/offline-full-text.md)。
 */
object ReaderRouting {

    /**
     * 需要登录态、应用内读不出正文的注册域。
     *
     * 微博**不在**这里：`m.weibo.cn` 的单条页可以匿名读，它是唯一一个专门写过暗色配色
     * 的站点（见 `ui/web/SiteDarkStyles`）。
     */
    val LOGIN_WALLED_HOSTS = setOf("zhihu.com", "xiaohongshu.com", "twitter.com", "x.com")

    /** 地址是否落在某个需要登录的域下（含其子域）。解析不出主机名算 false。 */
    fun isLoginWalled(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return LOGIN_WALLED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 阅读页要不要显示本地全文页而不是去加载链接。
     *
     * 两条理由：没有链接可去（源没给 link，兜底摘要页）；或者有全文且原页要登录。
     * 全文为空时**不**离线 —— 宁让用户看到登录墙（还能自己登录一次），也别看一段空白。
     */
    fun readOffline(article: Article): Boolean =
        article.link.isBlank() || (article.body.isNotBlank() && isLoginWalled(article.link))

    /**
     * 短于这个字数的离线正文按「摘要」对待，界面不许叫它全文。
     *
     * 600 是照实测画的线：知乎 relay 每条 200 出头（列表接口只回 `excerpt`），
     * 而真正的全文源动辄三四千字，中间没有东西踩在这条线上。
     */
    const val MIN_FULL_BODY_CHARS = 600

    /**
     * 离线页上给用户看的其实只是摘要 —— 有原页可去、正文又短到不可能是整篇。
     *
     * 没有链接时算 false：那时根本没有「原页」这个去处，提示「全文在原页」是骗人。
     */
    fun isExcerptOnly(article: Article): Boolean =
        readOffline(article) &&
            article.link.isNotBlank() &&
            article.body.length < MIN_FULL_BODY_CHARS

    /** 小写主机名，去掉端口和 `user@`；`www.` 留给调用方自己判断。 */
    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "").substringBefore('/')
        if (afterScheme.isEmpty()) return null
        val authority = if ('@' in afterScheme) afterScheme.substringAfterLast('@') else afterScheme
        return authority.substringBefore(':').lowercase().ifEmpty { null }
    }
}
