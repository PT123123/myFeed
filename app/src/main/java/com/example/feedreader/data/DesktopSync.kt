package com.example.feedreader.data

import okhttp3.OkHttpClient

/**
 * 一轮同步的回报。
 *
 * [articles] 是**本轮结束时本机持有的全部电脑端内容**（成功时是刚拉回来的，失败时是缓存里的），
 * 调用方直接拿去刷列表就行，不需要自己再去读缓存 —— 这也是「同步失败界面还得有东西可看」的依据。
 */
data class DesktopSyncReport(
    val articles: List<Article> = emptyList(),
    val readMarks: Map<String, Long> = emptyMap(),
    val pushed: Int = 0,
    val failed: Int = 0,
    val incoming: Int = 0,
    val bootstrapped: Boolean = false,
    /** 空串 = 这一轮没错误。`skipped` 用非错误的话填（没配地址、退避中）。 */
    val error: String = "",
    val skipped: Boolean = false,
)

/**
 * 原生这一侧的同步编排：先推队列、再拉变更，落进 [FeedCache] 里 `paf` 那一个文件。
 *
 * 节拍照电脑端客户端（`llm-ui/src/services/sync.ts`）来，因为服务端协议就是按那套写的：
 * - **先 push 后 pull**。反过来的话，刚拉回来的服务端阅读状态会被自己攒着的那条「读过」顶掉，
 *   同一件事在同一轮里被机器自己否认一次。
 * - **410 只换全量快照，绝不动 outbox**。游标过期是「记录太旧」，不是「本地操作作废」；
 *   清队列等于把用户读过这件事悄悄丢掉。
 * - **裁决分三类**：`applied`/`conflict` 出队（后者是服务端已有更高明的判断，重试也不会赢），
 *   其它错误码标失败留档，没回裁决的条目原地不动等下次。
 *
 * 失败不抛出去：这一层跑在刷新流程里，电脑端关机是常态，不能让订阅刷新跟着红。
 */
class DesktopSync(
    private val settings: SettingsStore,
    private val cache: FeedCache,
    private val outbox: DesktopOutbox,
    private val http: OkHttpClient,
) {

    val configured: Boolean get() = settings.desktopFeedBaseUrl.isNotBlank()

    /** 攒一条「这篇读完了」。没网、电脑端没开都能调：只写盘，不发请求。 */
    fun enqueueRead(article: Article, now: Long = System.currentTimeMillis()): Boolean {
        val postId = article.remotePostId ?: return false
        val seq = settings.desktopClientSeq + 1
        settings.desktopClientSeq = seq
        return outbox.enqueue(postId, seq, now)
    }

    fun run(force: Boolean = false, now: Long = System.currentTimeMillis()): DesktopSyncReport {
        if (!configured) return cachedReport(error = "", skipped = true)
        if (!force && now < settings.desktopFeedBackoffUntil) {
            return cachedReport(error = "", skipped = true)
        }
        val client = DesktopSyncClient(settings.desktopFeedBaseUrl, settings.desktopFeedToken, http)
        return try {
            val pushed = push(client)
            val pull = pull(client, now)
            settings.desktopFeedSyncAt = now
            settings.desktopFeedSyncError = ""
            settings.desktopFeedSyncFailures = 0
            settings.desktopFeedBackoffUntil = 0L
            DesktopSyncReport(
                articles = pull.articles,
                readMarks = pull.readMarks,
                pushed = pushed.first,
                failed = pushed.second,
                incoming = pull.incoming,
                bootstrapped = pull.bootstrapped,
            )
        } catch (error: DesktopSyncException) {
            noteFailure(error, now)
            cachedReport(error = "${error.code}: ${error.message}")
        } catch (error: Exception) {
            noteFailure(DesktopSyncException("SYNC", 0, error.message ?: "同步失败"), now)
            cachedReport(error = error.message ?: "同步失败")
        }
    }

    // —— 上行 ——

    /** 返回 (已接受, 被打回)。 */
    private fun push(client: DesktopSyncClient): Pair<Int, Int> {
        val all = outbox.load()
        val pending = all.filterNot { it.failed }.sortedBy { it.clientSeq }
        if (pending.isEmpty()) return 0 to 0
        val deviceId = settings.desktopDeviceId
        val resolved = HashSet<String>(pending.size)
        var pushed = 0
        var failed = 0
        val marks = HashMap<String, String>()
        for (slice in pending.chunked(PUSH_BATCH)) {
            val ops = slice.map { DesktopOp(it.opId, it.clientSeq, it.postId, it.occurredAt) }
            val verdict = client.push(deviceId, ops).associateBy { it.opId }
            for (op in slice) {
                val result = verdict[op.opId] ?: continue
                when (result.status) {
                    "applied", "conflict" -> {
                        resolved += op.opId
                        pushed++
                    }
                    else -> {
                        resolved += op.opId
                        marks[op.opId] = "${result.errorCode}: ${result.errorMessage}"
                        failed++
                    }
                }
            }
        }
        if (resolved.isEmpty()) return pushed to failed
        val next = all.filterNot { it.opId in resolved } + marks.map { (opId, why) ->
            val entry = all.first { it.opId == opId }
            entry.copy(failed = true, error = why)
        }
        outbox.save(next)
        return pushed to failed
    }

    // —— 下行 ——

    private data class Pulled(
        val articles: List<Article>,
        val readMarks: Map<String, Long>,
        val incoming: Int,
        val bootstrapped: Boolean,
    )

    private fun pull(client: DesktopSyncClient, now: Long): Pulled {
        val readMarks = HashMap<String, Long>()
        var articles = cache.read(FeedSources.DESKTOP_ID)?.articles ?: emptyList()
        var incoming = 0
        var seq = settings.desktopFeedSyncSeq
        var bootstrapped = false

        // 快照与增量页走同一条并表逻辑：快照就是「deletedIds 为空、游标是 latest_seq 的一页」
        fun merge(page: ChangePage): Int {
            articles = DesktopFeed.merged(articles, page)
            readMarks.putAll(DesktopFeed.readMarks(page.states, now))
            return page.posts.count { !it.demo && it.id.isNotBlank() }
        }

        if (seq < 0L) {
            val snapshot = client.snapshot()
            incoming += merge(ChangePage(snapshot.posts, emptyList(), snapshot.states, snapshot.latestSeq, false))
            seq = snapshot.latestSeq
            bootstrapped = true
        } else {
            var hasMore = true
            var pages = 0
            while (hasMore && pages < MAX_PAGES) {
                val page = try {
                    client.changes(seq, PAGE)
                } catch (error: DesktopSyncException) {
                    if (error.code != "CURSOR_EXPIRED" && error.status != 410) throw error
                    // 游标过期：整份重取。outbox 已经推完，这里一行都不能碰它。
                    val snapshot = client.snapshot()
                    incoming += merge(ChangePage(snapshot.posts, emptyList(), snapshot.states, snapshot.latestSeq, false))
                    seq = snapshot.latestSeq
                    bootstrapped = true
                    break
                }
                incoming += merge(page)
                seq = page.nextSeq
                settings.desktopFeedSyncSeq = seq
                hasMore = page.hasMore
                pages++
            }
        }

        cache.write(FeedSources.DESKTOP_ID, articles, now)
        settings.desktopFeedSyncSeq = seq
        return Pulled(articles, readMarks, incoming, bootstrapped)
    }

    // —— 失败与退避 ——

    private fun noteFailure(error: DesktopSyncException, now: Long) {
        val failures = settings.desktopFeedSyncFailures + 1
        settings.desktopFeedSyncFailures = failures
        settings.desktopFeedBackoffUntil = now + backoffMs(failures)
        settings.desktopFeedSyncError = "${error.code}: ${error.message}"
    }

    private fun cachedReport(error: String, skipped: Boolean = false): DesktopSyncReport =
        DesktopSyncReport(
            articles = cache.read(FeedSources.DESKTOP_ID)?.articles ?: emptyList(),
            error = error.ifBlank { settings.desktopFeedSyncError },
            skipped = skipped,
        )

    companion object {
        /** 与电脑端客户端同一组常数：连续失败时别把用户的电脑当网关锤。 */
        const val PUSH_BATCH = 50
        const val PAGE = 500
        private const val MAX_PAGES = 20
        private const val BACKOFF_BASE_MS = 5_000L
        private const val BACKOFF_MAX_MS = 5 * 60_000L

        fun backoffMs(failures: Int): Long {
            if (failures <= 0) return 0L
            var delay = BACKOFF_BASE_MS
            repeat(minOf(failures - 1, 10)) { delay *= 2 }
            return minOf(delay, BACKOFF_MAX_MS)
        }
    }
}
