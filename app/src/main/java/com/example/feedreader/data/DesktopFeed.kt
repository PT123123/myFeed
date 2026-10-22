package com.example.feedreader.data

import com.example.feedreader.data.json.Json
import com.example.feedreader.data.json.JsonValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 电脑端一条帖子里手机用得到的那部分。
 *
 * 只取阅读和排序要用的字段（正文、来源、时间、类型、策展理由、demo 标记）：
 * 质量分、标签、连载、人格这些是电脑端编辑与生成侧的概念，搬到手机上没有消费方，
 * 搬过来只会让「两个实现对同一份数据理解不一致」这件事更难查。
 */
data class DesktopPost(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val summary: String,
    val sourceUrl: String,
    val sourceTitle: String,
    val publishedAt: Long,
    val publishedRaw: String,
    val reason: String,
    val demo: Boolean,
)

/** 服务端记的阅读/收藏状态。手机上只消费已读那两项，其余照原样收着不解释。 */
data class DesktopState(
    val postId: String,
    val saved: Boolean,
    val hidden: Boolean,
    val readProgress: Double,
    val lastReadAt: Long,
)

/** 一次 `GET /sync/changes` 的结果。 */
data class ChangePage(
    val posts: List<DesktopPost>,
    val deletedIds: List<String>,
    val states: List<DesktopState>,
    val nextSeq: Long,
    val hasMore: Boolean,
)

/** 一次 `GET /sync/snapshot` 的结果。 */
data class Snapshot(
    val posts: List<DesktopPost>,
    val states: List<DesktopState>,
    val latestSeq: Long,
)

/** 一条待上传的操作。手机上只会产生「读过」这一种，所以字段是窄的。 */
data class DesktopOp(
    val opId: String,
    val clientSeq: Long,
    val postId: String,
    val occurredAt: Long,
)

/** 服务端对某条操作的裁决。`error` 只在 status=error 时有值。 */
data class OpResult(val opId: String, val status: String, val errorCode: String, val errorMessage: String)

/**
 * 电脑端同步协议的解析与映射 —— **不含网络**。
 *
 * 拆开的原因是这套字段名是全仓库最容易写错的地方（服务端 snake_case、Kotlin camelCase、
 * 而 org.json 在 JVM 单测里是 stub）。放成纯函数就能拿真实回包的字节当夹具直接在单测里打，
 * 不必装机、不必电脑端活着。网络壳见 [DesktopSyncClient]。
 */
object DesktopFeed {

    /** 快照：`{latest_seq, posts[], post_states[]}`；其余块（topics/comments/interests）手机不消费。 */
    fun snapshot(text: String): Snapshot {
        val root = obj(text)
        return Snapshot(
            posts = root.arr("posts").mapNotNull { post(it.objValue()) },
            states = root.arr("post_states").mapNotNull { state(it.objValue()) },
            latestSeq = root.lng("latest_seq"),
        )
    }

    /** 增量页：`{changes[], next_seq, has_more}`。未知 object_type 直接忽略，不报错。 */
    fun changes(text: String): ChangePage {
        val root = obj(text)
        val posts = ArrayList<DesktopPost>()
        val deleted = ArrayList<String>()
        val states = ArrayList<DesktopState>()
        for (change in root.arr("changes")) {
            val row = change.objValue()
            when (row.str("object_type")) {
                "post" -> if (row.str("operation") == "delete") {
                    deleted += row.str("object_id")
                } else {
                    post(row.obj("payload"))?.let { posts += it }
                }
                "post_state" -> state(row.obj("payload"))?.let { states += it }
                // topic / comment / interest 由 WebView 那侧的完整客户端管，原生不重复实现。
            }
        }
        return ChangePage(posts, deleted, states, root.lng("next_seq"), root.bln("has_more"))
    }

    /** 服务端裁决表：`{results: [{op_id, status, error{code,message}}]}`。 */
    fun opResults(text: String): List<OpResult> =
        obj(text).arr("results").map { row ->
            val it = row.objValue()
            val error = it.obj("error")
            OpResult(
                opId = it.str("op_id"),
                status = it.str("status"),
                errorCode = error.str("code"),
                errorMessage = error.str("message"),
            )
        }

    /**
     * `POST /sync/operations` 的请求体。
     *
     * 形状钉死在服务端 schemas_sync：`{device_id, operations:[{op_id, client_seq, object_type,
     * object_id, action, payload, base_revision, occurred_at}]}`，`op_id` 是幂等键、
     * `(device_id, client_seq)` 是服务端的重放判定。手机上只会上报「读过」，
     * `payload` 固定 `{"progress": 1.0}`（服务端 view 走 progress，缺省即 1.0）。
     */
    fun opsRequest(deviceId: String, ops: List<DesktopOp>): String {
        if (ops.isEmpty()) return ""
        val items = ops.joinToString(",") { op ->
            """{"op_id":${jsonString(op.opId)},"client_seq":${op.clientSeq},"object_type":"post",""" +
                """"object_id":${jsonString(op.postId)},"action":"view","payload":{"progress":1.0},""" +
                """"base_revision":null,"occurred_at":${jsonString(isoUtc(op.occurredAt))}}"""
        }
        return """{"device_id":${jsonString(deviceId)},"operations":[$items]}"""
    }

    /**
     * 帖子 → 原生文章。
     *
     * id 用 `paf:<帖子 id>`（[FeedSources.desktopArticleId]）而不是帖子 id 本身：
     * RSS 那边的 id 带源前缀，两条流撞车的唯一可能就是共用一个裸 id，
     * 而撞车之后已读记录会串到另一条内容上 —— 那类 bug 事后看不出是谁写错的。
     */
    fun toArticle(post: DesktopPost): Article = Article(
        id = FeedSources.desktopArticleId(post.id),
        title = post.title,
        excerpt = post.summary.ifBlank { post.body.take(280) },
        body = post.body,
        link = post.sourceUrl,
        author = post.sourceTitle.ifBlank { DESKTOP_AUTHOR_FALLBACK },
        sourceId = FeedSources.DESKTOP_ID,
        sourceName = DESKTOP_SOURCE_NAME,
        category = FeedSources.DESKTOP_CATEGORY,
        publishedAt = post.publishedAt,
        publishedRaw = post.publishedRaw,
        remotePostId = post.id,
        remoteKind = post.kind,
        remoteReason = post.reason,
    )

    /**
     * 把一页增量并进现有那份帖子列表。
     *
     * 同一篇帖子重复出现时**新的那份赢**（服务端 revision 递增，后到的更新），
     * 删除按 object_id 摘掉。顺序由调用方在排序阶段统一处理，这里只保证不重不漏。
     */
    fun merged(current: List<Article>, page: ChangePage): List<Article> {
        val deleted = page.deletedIds.map { FeedSources.desktopArticleId(it) }.toSet()
        val incoming = page.posts
            .filterNot { it.demo || it.id.isBlank() }
            .map { toArticle(it) }
        val incomingIds = incoming.map { it.id }.toSet()
        return buildList {
            current.filterNot { it.id in deleted || it.id in incomingIds }.forEach { add(it) }
            incoming.forEach { add(it) }
        }
    }

    /**
     * 服务端说「这些读过」→ 文章 id 与阅读时刻。没缓存到的帖子由调用方按 id 忽略即可。
     *
     * `read_progress=0` 且没给时刻的那条是「打开过但没读」，真回包里就存在这种行
     * （快照 14 条状态里有 1 条），判成已读会把用户没看的东西压暗。
     * 给了 progress 却没给时刻时时刻退回 `now`：已读记录按时间老化（见 [ReadStateStore.prune]），
     * 塞 0 或 1 这类哨兵会在下次写盘时被当成陈年孤儿键清掉，等于这条已读悄悄丢了。
     */
    fun readMarks(states: List<DesktopState>, now: Long): Map<String, Long> = buildMap {
        for (state in states) {
            if (state.lastReadAt <= 0L && state.readProgress <= 0.0) continue
            put(FeedSources.desktopArticleId(state.postId), if (state.lastReadAt > 0L) state.lastReadAt else now)
        }
    }

    /**
     * 服务端回包里的错误码。
     *
     * FastAPI 那边三种写法都有（HTTPException 的 `detail`、自定义错误体的 `error`、以及裸的
     * `{code,message}`），取到哪个算哪个；都没有就退回 HTTP 状态本身。
     * **410 一定是游标过期**，那是协议里唯一需要走全量快照的信号。
     */
    fun errorCode(status: Int, text: String): Pair<String, String> {
        val root = runCatching { obj(text) }.getOrNull() ?: emptyMap()
        val body = root.obj("detail").ifEmpty { root.obj("error") }.ifEmpty { root }
        val code = body.str("code").ifBlank { if (status == 410) "CURSOR_EXPIRED" else "HTTP_$status" }
        val message = body.str("message").ifBlank {
            body.str("detail").ifBlank { root.str("detail") }.ifBlank { "电脑端返回 HTTP $status" }
        }
        return code to message
    }

    const val DESKTOP_SOURCE_NAME = "阅流 AI"
    const val DESKTOP_AUTHOR_FALLBACK = "阅流 AI"

    // —— 取值 ——

    private fun obj(text: String): Map<String, JsonValue> =
        Json.parseOrNull(text)?.objValue() ?: throw IllegalArgumentException("回包不是 JSON 对象")

    private fun Map<String, JsonValue>.obj(key: String): Map<String, JsonValue> =
        (this[key] ?: JsonValue.Null).objValue()

    private fun JsonValue.objValue(): Map<String, JsonValue> =
        (this as? JsonValue.Obj)?.entries ?: emptyMap()

    private fun Map<String, JsonValue>.arr(key: String): List<JsonValue> =
        (this[key] as? JsonValue.Arr)?.items ?: emptyList()

    private fun Map<String, JsonValue>.str(key: String): String =
        (this[key] as? JsonValue.Str)?.value.orEmpty()

    private fun Map<String, JsonValue>.lng(key: String): Long =
        (this[key] as? JsonValue.Num)?.value?.toLong() ?: 0L

    private fun Map<String, JsonValue>.dbl(key: String): Double =
        (this[key] as? JsonValue.Num)?.value ?: 0.0

    private fun Map<String, JsonValue>.bln(key: String): Boolean =
        (this[key] as? JsonValue.Bool)?.value ?: false

    private fun post(row: Map<String, JsonValue>): DesktopPost? {
        val id = row.str("id")
        if (id.isBlank()) return null
        // 时间取 published_at，缺了退回 created_at（服务端两个都会给，但旧帖子可能有空 published_at）
        val raw = utcText(row.str("published_at").ifBlank { row.str("created_at") })
        return DesktopPost(
            id = id,
            kind = row.str("kind"),
            title = row.str("title"),
            body = row.str("body"),
            summary = row.str("summary"),
            sourceUrl = row.str("source_url"),
            sourceTitle = row.str("source_title"),
            publishedAt = DateParser.parse(raw),
            publishedRaw = raw,
            reason = row.str("recommend_reason"),
            demo = row.bln("demo"),
        )
    }

    private fun state(row: Map<String, JsonValue>): DesktopState? {
        val postId = row.str("post_id")
        if (postId.isBlank()) return null
        return DesktopState(
            postId = postId,
            saved = row.bln("saved"),
            hidden = row.bln("hidden"),
            readProgress = row.dbl("read_progress"),
            lastReadAt = DateParser.parse(utcText(row.str("last_read_at"))),
        )
    }

    /** 没有时区标记就补 `Z`。 */
    private val NO_ZONE = Regex("""(?:Z|[+-]\d{2}:?\d{2})$""", RegexOption.IGNORE_CASE)

    /**
     * 补上 UTC 标记。
     *
     * 服务端 SQLite 存的是裸 UTC 墙钟，序列化时有的补了 `Z`（`PostOut` 的校验器）、有的带
     * `+00:00`（变更页 payload）、有的什么都不带（`last_read_at`）。不补就会被按本机时区解释，
     * 本机 +08:00 意味着每一条读记录整体偏掉 8 小时 —— 而已读是按较晚时刻合并的，
     * 偏早的那份会盖不住偏晚的，症状是「明明在电脑端读过了，手机上还是未读」。
     */
    internal fun utcText(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || NO_ZONE.containsMatchIn(text)) return text
        return "${text}Z"
    }

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    internal fun isoUtc(millis: Long): String = ISO_UTC.format(Date(millis))

    /** 只有 id 与时间戳会进这里，转义仍照做：帖子 id 是服务端生成的，不该由本机假设它安全。 */
    internal fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        value.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < ' ') out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else out.append(char)
            }
        }
        return out.append('"').toString()
    }
}
