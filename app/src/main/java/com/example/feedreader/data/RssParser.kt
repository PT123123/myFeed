package com.example.feedreader.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale

/**
 * RSS 2.0 / Atom / RDF 的最小可用解析器。
 *
 * 用 Android 自带的 [XmlPullParser]，不引入任何额外依赖。走单遍前向扫描：
 * 进 `<item>` / `<entry>` 开一个 builder，读到 `</item>` 就吐出一条 [Article]。
 *
 * 关掉 namespace 处理，这样 Atom 的 `href` 属性和 `dc:creator` 这类带前缀的标签名
 * 都能直接用字面量匹配，省掉一堆命名空间样板。
 *
 * @param createParser 解析器工厂。默认用系统实现；单测里注入 JVM 版（kxml2），
 *   这套解析逻辑就能脱离设备验证。
 */
class RssParser(
    private val createParser: () -> XmlPullParser = { Xml.newPullParser() },
) {

    fun parse(xml: String, source: FeedSource, limit: Int = MAX_ITEMS): List<Article> {
        val parser = createParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val articles = ArrayList<Article>(minOf(limit, 32))
        var builder: ItemBuilder? = null
        var pendingField: String? = null
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == TAG_ITEM || tag == TAG_ENTRY) {
                        builder = ItemBuilder()
                        pendingField = null
                    } else {
                        val field = FIELD_OF[tag]
                        if (field != null) {
                            builder?.let {
                                it.begin(field, parser)
                                pendingField = field
                            }
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (pendingField != null) {
                        builder?.append(parser.text.orEmpty())
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == TAG_ITEM || tag == TAG_ENTRY) {
                        builder?.build(source)?.let { articles += it }
                        builder = null
                        pendingField = null
                        if (articles.size >= limit) return articles
                    } else if (pendingField != null) {
                        builder?.endField()
                        pendingField = null
                    }
                }
            }
            event = parser.next()
        }
        return articles
    }

    /**
     * 取频道的标题（RSS 的 `<channel><title>` / Atom 的 `<feed><title>`）。
     *
     * **只在第一个 `<item>` / `<entry>` 之前找**：条目自己也带 `<title>`，
     * 一路扫到底会拿到第一篇文章的标题 —— 那个错误很隐蔽，看上去还挺像回事。
     *
     * 没有频道标题时返回 null（有些 feed 确实不写），调用方自己兜底。
     */
    fun feedTitle(xml: String): String? {
        val parser = createParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val buffer = StringBuilder()
        var collecting = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    // 进正文了，说明上面压根没有频道标题
                    if (tag == TAG_ITEM || tag == TAG_ENTRY) return null
                    if (tag == F_TITLE) {
                        collecting = true
                        buffer.setLength(0)
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (collecting) {
                    buffer.append(parser.text.orEmpty())
                }

                XmlPullParser.END_TAG -> if (collecting && parser.name.lowercase(Locale.US) == F_TITLE) {
                    return TextCleaner.plain(buffer.toString(), MAX_TITLE).ifEmpty { null }
                }
            }
            event = parser.next()
        }
        return null
    }

    /** 逐条累积 item 的字段值。 */
    private class ItemBuilder {

        private val values = HashMap<String, String>(8)
        private val buffer = StringBuilder()
        private var field: String? = null

        /**
         * 进入一个字段。Atom 的 `<link href="..."/>` 正文为空，链接只能从属性上取，
         * 所以这里顺手把 href 收下（只认 rel 为 alternate 或未声明的那个）。
         */
        fun begin(name: String, parser: XmlPullParser) {
            field = name
            buffer.setLength(0)
            if (name == F_LINK) {
                val rel = parser.getAttributeValue(null, "rel")
                val href = parser.getAttributeValue(null, "href")
                if ((rel == null || rel == REL_ALTERNATE) && !href.isNullOrBlank()) {
                    put(name, href)
                }
            }
        }

        fun append(text: String) {
            buffer.append(text)
        }

        fun endField() {
            val name = field ?: return
            field = null
            val text = buffer.toString()
            buffer.setLength(0)
            if (text.isNotBlank()) put(name, text)
        }

        /** 同一逻辑字段可能有多个标签（description / content:encoded），取更长的那份。 */
        private fun put(name: String, value: String) {
            val old = values[name]
            if (old == null || value.length > old.length) values[name] = value
        }

        private fun value(name: String): String = values[name].orEmpty()

        fun build(source: FeedSource): Article? {
            val title = TextCleaner.plain(value(F_TITLE), MAX_TITLE)
            if (title.isBlank()) return null

            val link = value(F_LINK).trim()
            val rawDate = value(F_DATE).trim()
            val identity = value(F_GUID).trim().ifEmpty { link.ifEmpty { title } }

            return Article(
                id = "${source.id}:$identity",
                title = title,
                excerpt = TextCleaner.plain(value(F_BODY), MAX_EXCERPT),
                link = link,
                author = TextCleaner.plain(value(F_AUTHOR), MAX_AUTHOR).ifEmpty { source.name },
                sourceId = source.id,
                sourceName = source.name,
                category = source.category,
                publishedAt = DateParser.parse(rawDate),
                publishedRaw = rawDate,
            )
        }
    }

    companion object {
        const val MAX_ITEMS = 30

        private const val MAX_TITLE = 200
        private const val MAX_EXCERPT = 280
        private const val MAX_AUTHOR = 48

        private const val TAG_ITEM = "item"
        private const val TAG_ENTRY = "entry"
        private const val REL_ALTERNATE = "alternate"

        private const val F_TITLE = "title"
        private const val F_BODY = "body"
        private const val F_LINK = "link"
        private const val F_AUTHOR = "author"
        private const val F_DATE = "date"
        private const val F_GUID = "guid"

        /**
         * 标签名 -> 逻辑字段。key 统一小写，覆盖 RSS 2.0 / Atom / RDF 加 DC 扩展的常见写法。
         */
        private val FIELD_OF: Map<String, String> = mapOf(
            "title" to F_TITLE,
            "description" to F_BODY,
            "summary" to F_BODY,
            "content" to F_BODY,
            "encoded" to F_BODY,
            "content:encoded" to F_BODY,
            "link" to F_LINK,
            "author" to F_AUTHOR,
            "dc:creator" to F_AUTHOR,
            "creator" to F_AUTHOR,
            "pubdate" to F_DATE,
            "published" to F_DATE,
            "updated" to F_DATE,
            "dc:date" to F_DATE,
            "date" to F_DATE,
            "guid" to F_GUID,
            "id" to F_GUID,
        )
    }
}
