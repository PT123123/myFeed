package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机这一侧对电脑端同步协议的理解。
 *
 * 夹具 `desktop/snapshot.json`、`desktop/changes.json`、`desktop/state-change.json` 出自
 * 2026-10-22 本机 `apps/server`（v0.6.0）`GET /sync/snapshot` 与 `GET /sync/changes` 的实际回包：
 * **字段名、嵌套形状、`latest_seq`、帖子/状态条数、`kind`、`demo`、`read_progress`、
 * 以及那三种混着用的时间字符串全部是真回包原样**；帖子 id、标题、正文、来源地址与主题改成了合成值
 * （仓库是 PUBLIC，阅读记录本身不进公开历史）。所以钉的是「对方给的字节长什么样」，不是「我本机读过什么」。
 *
 * 之所以要钉在对方真产物上：这份协议三处各写了一遍（服务端 pydantic、电脑端 `llm-ui/src/services/sync.ts`、
 * 原生 [DesktopFeed]），而 snake_case 的字段名写错是**静默的** —— 解析不到就当空字符串，
 * 症状是「列表是空的」这种谁都会去怀疑网络的假象。真字节还带出几种手写样本不会想到的形状，
 * 见下面「微秒」「不带时区」「published_at 为空」那几颗。
 */
class DesktopFeedContractTest {

    /** 本轮时间：只用于「服务端没给时刻时退回什么」那两颗断言，任何值都不影响别的用例。 */
    private val NOW = 1_791_000_000_000L

    private val changesText: String by lazy { resource("desktop/changes.json") }
    private val stateChangeText: String by lazy { resource("desktop/state-change.json") }

    /** 真回包的形状：latest_seq 632、3 条帖子（含 1 条 demo）、14 条阅读状态。 */
    private val snapshot = DesktopFeed.snapshot(resource("desktop/snapshot.json"))

    @Test
    fun `真实快照按服务端的字段名解析`() {
        assertEquals(632L, snapshot.latestSeq)
        assertEquals(3, snapshot.posts.size)
        assertEquals(14, snapshot.states.size)

        val sourced = snapshot.posts.first { it.kind == "sourced" }
        // 合成正文，但 id 形状（服务端 32 位 hex 换成的短桩）、kind、时间字符串都是真的
        assertEquals("fixture-02", sourced.id)
        assertEquals("https://example.com/isometric-training", sourced.sourceUrl)
        assertEquals("Example source title", sourced.sourceTitle)
        assertFalse(sourced.demo)
    }

    /**
     * 未读帖子上机后要能被原生列表认领。
     *
     * `paf:` 前缀是[已读串台]那颗钉子：RSS 那边文章 id 也带源前缀，不加前缀两条流会撞同一个裸 id。
     */
    @Test
    fun `帖子映射成文章时挂上桌面源与前缀`() {
        val post = snapshot.posts.first { it.kind == "sourced" }
        val article = DesktopFeed.toArticle(post)

        assertEquals("paf:${post.id}", article.id)
        assertEquals(post.id, article.remotePostId)
        assertEquals(FeedSources.DESKTOP_ID, article.sourceId)
        assertEquals("sourced", article.remoteKind)
        assertTrue(article.remoteReason.isNotBlank())
        assertTrue(article.category == FeedSources.DESKTOP_CATEGORY)
    }

    /**
     * 服务端 345 条帖子里 306 条 `published_at` 是 null（实测占比 89%）。
     *
     * 也就是说「退回 created_at」不是兜底分支，是主路径：这一行没写对，几乎整列内容都排错序。
     */
    @Test
    fun `没有 published_at 时按 created_at 排`() {
        val published = snapshot.posts.first { it.kind == "sourced" }
        assertEquals("2026-04-23T17:05:41Z", published.publishedRaw)
        assertEquals(1776963941000L, published.publishedAt)

        val fallback = snapshot.posts.first { it.kind == "synthetic_explainer" }
        assertEquals(fallback.publishedRaw, "2026-10-11T14:37:12.986983Z")
        assertEquals(1791729432986L, fallback.publishedAt)
    }

    /**
     * 六位微秒不能当成毫秒数累加。
     *
     * `SimpleDateFormat` 的 `SSS` 是「一个数字」而不是「三位小数」，lenient 模式下 `.986983`
     * 会被读成 986983 毫秒 —— 时间整体往后推 16 分钟。真回包里 345 条有 216 条带 6 位小数，
     * 而 `created_at` 又是上一条那个主路径，所以这颗钉子覆盖的是全量内容。
     */
    @Test
    fun `微秒按小数截断不是按毫秒累加`() {
        val micros = DesktopFeed.snapshot("""{"latest_seq":1,"posts":[{"id":"a","kind":"sourced","title":"t",""" +
            """"body":"b","created_at":"2026-10-11T14:37:12.986983Z"}],"post_states":[]}""")
        // 2026-10-11T14:37:12.986Z；截断到毫秒，不外加 986983ms
        assertEquals(1791729432986L, micros.posts.single().publishedAt)

        val millis = DesktopFeed.snapshot("""{"latest_seq":1,"posts":[{"id":"a","kind":"sourced","title":"t",""" +
            """"body":"b","created_at":"2026-10-11T14:37:12.986Z"}],"post_states":[]}""")
        assertEquals(1791729432986L, millis.posts.single().publishedAt)
    }

    /**
     * 服务端给的时间有三种写法，必须落在同一个瞬间。
     *
     * 快照里的 `PostOut` 被 pydantic 校验器补了 `Z`；变更日志的 payload 是当初写进去的裸串
     * （`2026-10-11T07:51:20.213414`，没有时区）；`post_state.last_read_at` 在快照里裸、
     * 在变更页里带 `+00:00`。裸串不补 UTC 就会被按本机时区解释 —— 本机是 +08:00，
     * 每一条读记录会整体偏掉 8 小时，而手机上「已读」是按时刻取较晚的那份来合并的。
     */
    @Test
    fun `裸时间按 UTC 认而不是按本机时区`() {
        val naive = DesktopFeed.snapshot(naiveState("2026-10-11T09:19:04.557840"))
        val zulu = DesktopFeed.snapshot(naiveState("2026-10-11T09:19:04.557840Z"))
        val offset = DesktopFeed.snapshot(naiveState("2026-10-11T09:19:04.557840+00:00"))

        assertEquals(1791710344557L, DesktopFeed.readMarks(naive.states, NOW).values.single())
        assertEquals(DesktopFeed.readMarks(zulu.states, NOW), DesktopFeed.readMarks(naive.states, NOW))
        assertEquals(DesktopFeed.readMarks(offset.states, NOW), DesktopFeed.readMarks(naive.states, NOW))

        val page = DesktopFeed.changes(stateChangeText)
        assertEquals(1791710344557L, DesktopFeed.readMarks(page.states, NOW).values.first())
    }

    /** 真数据里 14 条状态：13 条 `read_progress=1`，1 条 progress=0 且 `last_read_at` 为 null。 */
    @Test
    fun `服务端没记到的不算读过`() {
        val marks = DesktopFeed.readMarks(snapshot.states, NOW)

        assertEquals(13, marks.size)
        assertTrue(marks.keys.all { it.startsWith("paf:") })
        assertFalse("progress=0 且无时刻的那条不该生成已读",
            marks.containsKey(FeedSources.desktopArticleId("fixture-16")))
    }

    /**
     * 读过但没时间戳时，时刻取本轮时间而不是 0。
     *
     * 已读记录是**按时间老化**的（[ReadStateStore] 的 `prune` 丢掉 `it > 0` 之外的键），
     * 所以「先塞个哨兵值，调用方自己看着办」会在这条记录第一次写盘时被当成孤儿键清掉：
     * 症状是电脑端明明读过的内容，手机重启后又变回未读。
     */
    @Test
    fun `没时间戳的已读也用本轮时间`() {
        val marks = DesktopFeed.readMarks(
            DesktopFeed.snapshot(naiveState("")).states,
            now = NOW,
        )

        assertEquals(mapOf(FeedSources.desktopArticleId("p1") to NOW), marks)
    }

    @Test
    fun `增量页只取用得到的两类对象`() {
        val page = DesktopFeed.changes(changesText)

        assertEquals(4, page.posts.size)
        assertTrue(page.posts.all { !it.demo })
        assertTrue(page.deletedIds.isEmpty())
        assertEquals(501L, page.nextSeq)
        assertTrue(page.hasMore)

        val states = DesktopFeed.changes(stateChangeText)
        assertEquals(0, states.posts.size)
        assertEquals(2, states.states.size)
    }

    /**
     * 并进现有那份：后到赢、删除摘掉、demo 永不进手机。
     *
     * 「后到赢」的前提是服务端 revision 单调；这里给同一 id 两份，验证的是原生不把自己那份顶回去。
     */
    @Test
    fun `增量并表新的赢且 demo 进不来`() {
        val keep = DesktopFeed.toArticle(snapshot.posts.first { it.kind == "sourced" })
        val drop = DesktopFeed.toArticle(snapshot.posts.first { it.demo })
        val current = listOf(keep, drop)
        val updated = snapshot.posts.first { it.kind == "sourced" }.copy(title = "改过标题的同一条")

        val page = ChangePage(posts = listOf(updated), deletedIds = listOf(drop.remotePostId!!), states = emptyList(),
            nextSeq = 633L, hasMore = false)
        val merged = DesktopFeed.merged(current, page)

        assertEquals(1, merged.size)
        assertEquals("改过标题的同一条", merged.single().title)
        assertEquals(keep.id, merged.single().id)
    }

    @Test
    fun `增量并表按删除标记摘掉`() {
        val keep = DesktopFeed.toArticle(snapshot.posts.first { it.kind == "sourced" })
        val gone = DesktopFeed.toArticle(snapshot.posts.first { it.kind == "synthetic_explainer" })
        val page = ChangePage(posts = emptyList(), deletedIds = listOf(gone.remotePostId!!),
            states = emptyList(), nextSeq = 1L, hasMore = false)

        assertEquals(listOf(keep.id), DesktopFeed.merged(listOf(keep, gone), page).map { it.id })
    }

    /**
     * 上行请求体的形状钉死在服务端 `schemas_sync.SyncOpIn`。
     *
     * 字段名写错=服务端 422；`op_id`（幂等键）和 `(device_id, client_seq)`（重放判定）写错=
     * 用户读过的记录在服务端要么丢要么重。这两类都不会在手机上留下现象，只能靠这颗钉子拦。
     */
    @Test
    fun `上报体对齐服务端 schemas_sync`() {
        val body = DesktopFeed.opsRequest(
            deviceId = "android-abcd",
            ops = listOf(DesktopOp(opId = "op-1", clientSeq = 7, postId = "p/1", occurredAt = 1791729432986L)),
        )

        assertTrue(body, body.contains(""""device_id":"android-abcd""""))
        assertTrue(body, body.contains(""""op_id":"op-1""""))
        assertTrue(body, body.contains(""""client_seq":7"""))
        assertTrue(body, body.contains(""""object_type":"post""""))
        assertTrue(body, body.contains(""""object_id":"p/1""""))
        assertTrue(body, body.contains(""""action":"view""""))
        assertTrue(body, body.contains(""""payload":{"progress":1.0}"""))
        assertTrue(body, body.contains(""""base_revision":null"""))
        assertTrue(body, body.contains(""""occurred_at":"2026-10-11T14:37:12.986Z""""))
        assertFalse(body, body.contains("undefined"))
        assertEquals("", DesktopFeed.opsRequest("d", emptyList()))
    }

    @Test
    fun `帖子 id 里的引号不会撬开请求体`() {
        val body = DesktopFeed.opsRequest("d\"x", listOf(DesktopOp("o\\1", 1, "p\"\n2", 0L)))

        assertTrue(body, body.contains(""""device_id":"d\"x""""))
        assertTrue(body, body.contains(""""op_id":"o\\1""""))
        assertTrue(body, body.contains(""""object_id":"p\"\n2""""))
    }

    /** 410 是协议里唯一「必须整份重取」的信号。 */
    @Test
    fun `410 认成游标过期其余退回状态码`() {
        assertEquals("CURSOR_EXPIRED", DesktopFeed.errorCode(410, """{"detail":"游标过期"}""").first)
        assertEquals("BAD_REQUEST" to "x", DesktopFeed.errorCode(400, """{"detail":{"code":"BAD_REQUEST","message":"x"}}"""))
        assertEquals("UNAUTHORIZED" to "no", DesktopFeed.errorCode(401, """{"error":{"code":"UNAUTHORIZED","message":"no"}}"""))
        assertEquals("HTTP_500", DesktopFeed.errorCode(500, "not json at all").first)
    }

    private fun naiveState(raw: String) =
        """{"latest_seq":1,"posts":[],"post_states":[{"post_id":"p1","saved":false,"hidden":false,""" +
            """"read_progress":1,"last_read_at":"$raw"}]}"""

    private fun resource(path: String): String =
        DesktopFeedContractTest::class.java.getResourceAsStream("/$path")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("缺夹具 /$path")
}
