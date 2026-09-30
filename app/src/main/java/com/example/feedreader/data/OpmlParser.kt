package com.example.feedreader.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * OPML 订阅清单的最小可用解析器。
 *
 * OPML 就是个 XML：`<opml><body><outline …/></body></opml>`，真正的条目是带
 * `xmlUrl` 属性的 `<outline>`。判据只有这一条 —— 带 `type="rss"` 但不是 outline
 * 的写法在现实里不存在，而 `type` 属性本身各家写得五花八门（`rss` / `atom` /
 * 干脆不写），按它判反而会漏。
 *
 * **目录也是 `<outline>`**（不带 xmlUrl），所以「这条源在哪一层目录下」必须靠一个栈
 * 维护。用兄弟节点顺序去推是错的：OPML 允许多层嵌套，目录还能和源混在同一层。
 *
 * 关掉命名空间处理，跟 [RssParser] 一致；属性名按大小写不敏感匹配，因为各家导出器
 * 写出来的既有 `xmlUrl` 也有 `xmlurl` / `XMLURL`。
 *
 * @param createParser 解析器工厂。默认用系统实现；单测里注入 JVM 版（kxml2），
 *   这套解析逻辑就能脱离设备验证。
 */
class OpmlParser(
    private val createParser: () -> XmlPullParser = { Xml.newPullParser() },
) {

    /**
     * @param fallbackCategory 源不在任何目录下时给它的分类。
     * @param baseUrl 清单里 `xmlUrl` 的解析基准，null（默认）表示按字面收 —— 见 [parse]。
     */
    fun parse(
        xml: String,
        fallbackCategory: String = SourceRules.FALLBACK_CATEGORY,
        limit: Int = MAX_SOURCES,
        baseUrl: String? = null,
    ): List<FeedSource> {
        val parser = createParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val out = ArrayList<FeedSource>()
        val seenIds = HashSet<String>()
        // 每一项对应一个尚未闭合的 <outline>：目录存它的名字，源存 null。
        // 靠它配对 `</outline>`，从而在多层目录里定位「当前在哪一层」。
        val openOutlines = ArrayDeque<String?>()
        var sawRoot = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (!sawRoot) {
                        sawRoot = true
                        // 根元素必须是 <opml>。不校验的话，用户选错文件（网页、JSON、
                        // 甚至一个 PDF）都会安静地解析出 0 个源，界面上报的是「文件里没有
                        // 找到订阅源」—— 那句话把「选错文件了」和「这个 OPML 确实是空的」
                        // 混成一件事，用户会拿着一个完全正常的 OPML 反复重试。
                        if (!parser.name.equals(TAG_OPML, ignoreCase = true)) {
                            throw IllegalArgumentException("根元素是 <${parser.name}>，不是 <opml>")
                        }
                    }
                    if (parser.name.equals(TAG_OUTLINE, ignoreCase = true)) {
                        val xmlUrl = attr(parser, ATTR_XML_URL)?.trim().orEmpty()
                        val label = attr(parser, ATTR_TEXT)?.takeIf { it.isNotBlank() }
                            ?: attr(parser, ATTR_TITLE)

                        if (xmlUrl.isEmpty()) {
                            // 目录。没名字的目录压一个空串，只是为了让栈的深度对得上。
                            openOutlines.addLast(label?.let { SourceRules.sanitizeName(it) }.orEmpty())
                        } else {
                            openOutlines.addLast(null)
                            // baseUrl 非空时先重基再校验：harvest 导出的清单写的是相对
                            // xmlUrl，而 looksLikeUrl 只认绝对地址 —— 不在这里补全，相对
                            // 项会在下面这行被当成坏数据丢掉，调用方的 rebaseSources 根本
                            // 拿不到东西可以重基（表现是「这份 OPML 里没有可用的订阅源」）。
                            val resolved = baseUrl?.let { RelayRules.rebaseUrl(xmlUrl, it) } ?: xmlUrl
                            val url = SourceRules.normalizeUrl(resolved)
                            if (SourceRules.looksLikeUrl(url) && seenIds.add(SourceRules.customId(url))) {
                                out += FeedSource(
                                    id = SourceRules.customId(url),
                                    name = SourceRules.sanitizeName(label.orEmpty())
                                        .ifEmpty { SourceRules.fallbackName(url) },
                                    url = url,
                                    category = currentFolder(openOutlines)
                                        .ifEmpty { fallbackCategory },
                                    custom = true,
                                )
                                if (out.size >= limit) return out
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals(TAG_OUTLINE, ignoreCase = true) && openOutlines.isNotEmpty()) {
                        openOutlines.removeLast()
                    }
                }
            }
            event = parser.next()
        }

        // 纯文本（用户选了个 .txt）走完循环也碰不到任何元素。kxml2 在这里**不抛异常**，
        // 只是安静地报 END_DOCUMENT —— 所以这个判断必须自己写，否则坏输入会伪装成空文件。
        if (!sawRoot) throw IllegalArgumentException("文件里没有任何 XML 元素")
        return out
    }

    /** 最近的一层**目录**。栈顶可能是源（null），所以要往回找第一个非空项。 */
    private fun currentFolder(stack: ArrayDeque<String?>): String =
        stack.lastOrNull { !it.isNullOrBlank() }.orEmpty()

    /** 按名字取属性，大小写不敏感 —— 各家导出器写法不统一。 */
    private fun attr(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    companion object {
        /**
         * 单个文件最多导入多少个源。
         *
         * 给一个上限是防呆：用户拿错文件（比如一份几千行的播放列表 OPML）时，
         * 不该让应用去跑几千次源注册。正常的阅读器导出不会接近这个数。
         */
        const val MAX_SOURCES = 200

        private const val TAG_OPML = "opml"
        private const val TAG_OUTLINE = "outline"
        private const val ATTR_XML_URL = "xmlUrl"
        private const val ATTR_TEXT = "text"
        private const val ATTR_TITLE = "title"
    }
}
