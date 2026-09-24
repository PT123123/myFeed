package com.example.feedreader.data.recall

import android.util.Xml
import com.example.feedreader.data.Article
import com.example.feedreader.data.DateParser
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.TextCleaner
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale

/**
 * arXiv 全文搜索（关键词 → 论文 / 文献）。
 *
 * 选它的理由：`https://export.arxiv.org/api/query` 是 arXiv 官方搜索接口，
 * 免费、无需 key、支持关键词查询，返回 Atom（RSS/Atom 一类）。实测（2026-09-25）
 * pharmacology / large language model 都能稳定返回 10 条。它正好填补
 * 「药学 / 神经科学 / LLM / 肌肥大」这类兴趣词在订阅源里**完全没有对应语料**的空缺——
 * 用户说的「搜关键词出有价值的文献」指的就是这种。
 *
 * **为什么不复用 [RssParser]**：arXiv 的 `<author>` 是
 * `<author><name>…</name></author>`，而 RssParser 只直接读 `<author>` 的文本内容
 * （那里是空的），作者会全部回落成通道名。论文的作者是核心价值之一，所以这里单独
 * 走一套 Atom 解析把作者名摘出来。其余字段（title / summary / link / published / id）
 * 和 RssParser 处理的是同一套，时间解析复用 [DateParser]。
 *
 * `prefersLatin = true`：arXiv 是英文语料，中文关键词发过去必然空手而归，所以只在
 * 查询串存在拉丁变体时才发请求（[RecallChannel.recall] 会自动换词），既省请求数
 * 也不污染结果。
 */
class ArxivChannel(
    private val createParser: () -> XmlPullParser = { Xml.newPullParser() },
) : RecallChannel {

    override val id = "arxiv"
    override val name = "arXiv 文献"
    override val category = "学术"
    override val limit = 15
    override val prefersLatin = true
    override val accept = "application/atom+xml, */*"

    override fun url(query: String, limit: Int): String =
        "https://export.arxiv.org/api/query?search_query=all:${SearchEngine.encode(query)}" +
            "&max_results=$limit&sortBy=submittedDate&sortOrder=descending"

    /**
     * 契约：**畸形 / 空输入返回空表，不抛异常** —— 一路解析炸了不该拖垮整次刷新。
     * arXiv 偶发的格式异常（被限流时返回 HTML 而非 Atom）也要被收成空表。
     */
    override fun parse(body: String): List<Article> = runCatching { parseAtom(body) }
        .getOrDefault(emptyList())

    private fun parseAtom(xml: String): List<Article> {
        val parser = createParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val articles = ArrayList<Article>(limit)
        var entry: EntryBuilder? = null
        // 收集 <author><name> 时的中间态：非 null 表示正处在 author 上下文里
        var authorName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    when {
                        tag == "entry" -> {
                            entry = EntryBuilder()
                            authorName = null
                        }
                        // 只认 rel 为 alternate（或未声明）的 link，论文页地址；
                        // rel=related 的 pdf / doi 链接不要。
                        tag == "link" && entry != null -> {
                            val rel = parser.getAttributeValue(null, "rel")
                            val href = parser.getAttributeValue(null, "href")
                            if ((rel == null || rel == "alternate") && !href.isNullOrBlank()) {
                                entry.link = href
                            }
                        }
                        // author 开标签：进入「收集 name」上下文
                        tag == "author" && entry != null -> authorName = ""
                        // author 内的 name，或 entry 直接的 title/summary/published/id
                        (tag == "name" && authorName != null) ||
                            (entry != null && tag in ENTRY_TEXT_FIELDS) -> entry?.pending = tag
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> entry?.let { e ->
                    val pending = e.pending ?: return@let
                    val text = parser.text.orEmpty()
                    when (pending) {
                        "title" -> e.title.append(text)
                        "summary" -> e.summary.append(text)
                        "published" -> if (e.publishedRaw.isEmpty()) e.publishedRaw = text
                        "id" -> if (e.guid.isEmpty()) e.guid = text
                        "name" -> authorName = (authorName ?: "") + text
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    when {
                        tag == "entry" -> {
                            entry?.build()?.let { articles += it }
                            entry = null
                            authorName = null
                            if (articles.size >= this.limit) return articles
                        }
                        tag == "author" && entry != null -> {
                            val name = TextCleaner.plain(authorName.orEmpty(), MAX_AUTHOR)
                            if (name.isNotEmpty()) entry.authors += name
                            entry.pending = null
                        }
                        tag == "name" -> entry?.pending = null
                        entry != null && tag in ENTRY_TEXT_FIELDS -> entry.pending = null
                    }
                }
            }
            event = parser.next()
        }
        return articles
    }

    /** 单条 arXiv 条目的字段累积器。 */
    private class EntryBuilder {
        var pending: String? = null
        val title = StringBuilder()
        val summary = StringBuilder()
        var link: String = ""
        var publishedRaw: String = ""
        var guid: String = ""
        val authors = ArrayList<String>()

        fun build(): Article? {
            val t = TextCleaner.plain(title.toString(), MAX_TITLE)
            if (t.isBlank()) return null

            val excerpt = TextCleaner.plain(summary.toString(), MAX_EXCERPT)
            // 去重键取论文页链接；缺链接时退回 id，再缺就退回标题
            val identity = guid.trim().ifEmpty { link.ifEmpty { t } }
            val author = authors.joinToString(", ").ifEmpty { "arXiv 文献" }

            return Article(
                id = "arxiv:$identity",
                title = t,
                excerpt = excerpt,
                link = link.trim(),
                author = author,
                sourceId = "arxiv",
                sourceName = "arXiv 文献",
                category = "学术",
                publishedAt = DateParser.parse(publishedRaw),
                publishedRaw = publishedRaw,
            )
        }
    }

    companion object {
        /** entry 直接挂的文本字段（不含嵌套在 author 里的 name）。 */
        private val ENTRY_TEXT_FIELDS = setOf("title", "summary", "published", "id")

        private const val MAX_TITLE = 200
        private const val MAX_EXCERPT = 280
        private const val MAX_AUTHOR = 64
    }
}
