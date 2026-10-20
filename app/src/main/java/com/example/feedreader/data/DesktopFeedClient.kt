package com.example.feedreader.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Reader for the desktop server's published, read-only mobile feed endpoint. */
class DesktopFeedClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    fun snapshot(): List<Article> {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "电脑端地址需要以 http:// 或 https:// 开头"
        }
        val request = Request.Builder()
            .url("$base/api/v1/mobile-feed/snapshot")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "电脑端拒绝了同步请求"
                    404 -> "电脑端没有同步接口，请检查地址和版本"
                    else -> "电脑端返回 HTTP ${response.code}"
                }
                throw java.io.IOException(message)
            }
            val body = response.body?.string().orEmpty()
            val posts = JSONObject(body).optJSONArray("posts") ?: return emptyList()
            return buildList {
                for (i in 0 until minOf(posts.length(), MAX_POSTS)) {
                    val post = posts.optJSONObject(i) ?: continue
                    val id = post.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val title = post.optString("title")
                    val text = post.optString("body")
                    val sourceUrl = post.optString("source_url")
                    val sourceTitle = post.optString("source_title")
                    val kind = post.optString("kind")
                    val created = post.optString("published_at")
                        .takeIf { it.isNotBlank() }
                        ?: post.optString("created_at")
                    add(
                        Article(
                            id = "paf:$id",
                            title = title,
                            excerpt = post.optString("summary").ifBlank { text.take(280) },
                            body = text,
                            link = sourceUrl,
                            author = sourceTitle.ifBlank { "阅流 AI" },
                            sourceId = "paf",
                            sourceName = sourceTitle.ifBlank { "阅流 AI" },
                            category = "AI 内容",
                            publishedAt = DateParser.parse(created),
                            publishedRaw = created,
                            remotePostId = id,
                            remoteKind = kind,
                            remoteReason = post.optString("recommend_reason"),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_POSTS = 300
    }
}
