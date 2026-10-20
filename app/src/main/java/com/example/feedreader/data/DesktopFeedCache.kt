package com.example.feedreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small atomic on-device snapshot cache so desktop AI posts stay readable offline. */
class DesktopFeedCache(private val file: File) {
    fun read(): List<Article> = runCatching {
        val rows = JSONArray(file.readText(Charsets.UTF_8))
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                add(
                    Article(
                        id = row.optString("id"),
                        title = row.optString("title"),
                        excerpt = row.optString("excerpt"),
                        body = row.optString("body"),
                        link = row.optString("link"),
                        author = row.optString("author"),
                        sourceId = row.optString("sourceId", "paf"),
                        sourceName = row.optString("sourceName", "阅流 AI"),
                        category = "AI 内容",
                        publishedAt = row.optLong("publishedAt"),
                        publishedRaw = row.optString("publishedRaw"),
                        remotePostId = row.optString("remotePostId").ifBlank { null },
                        remoteKind = row.optString("remoteKind"),
                        remoteReason = row.optString("remoteReason"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun write(articles: List<Article>) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val rows = JSONArray()
        articles.take(MAX_POSTS).forEach { article ->
            if (article.remotePostId == null) return@forEach
            rows.put(
                JSONObject()
                    .put("id", article.id)
                    .put("title", article.title)
                    .put("excerpt", article.excerpt)
                    .put("body", article.body)
                    .put("link", article.link)
                    .put("author", article.author)
                    .put("sourceId", article.sourceId)
                    .put("sourceName", article.sourceName)
                    .put("publishedAt", article.publishedAt)
                    .put("publishedRaw", article.publishedRaw)
                    .put("remotePostId", article.remotePostId)
                    .put("remoteKind", article.remoteKind)
                    .put("remoteReason", article.remoteReason),
            )
        }
        runCatching {
            val temp = File(parent, file.name + ".tmp")
            temp.writeText(rows.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file))
            }
        }
    }

    companion object {
        private const val MAX_POSTS = 300
        fun forApp(context: Context) = DesktopFeedCache(File(context.filesDir, "desktop_ai_feed.json"))
    }
}
