package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机读电脑命令口的那一层解析。
 *
 * 夹具是 2026-09-28 本机 `serve --allow-commands` 实际吐出来的字节（plan、run 202、status、
 * 401、400 各一份），不是照着想当然写的样本 —— 上一次跨仓库翻车（harvest 把 `xmlUrl` 改成
 * 相对写法）就是因为只有一边在跟形状，另一边静默把 21 条源全丢了。
 */
class RelayCommandsTest {

    /** 真字节（截了前四条，形状与全 24 条那份一致）。 */
    private val planBody = """
        {"protocol": "harvest/1", "ok": true, "action": "plan",
         "data": {"plan": [
            {"feed": "xhs-example", "platform": "xiaohongshu", "enabled": false,
             "would_run": false, "reason": "disabled", "last_status": "ok", "known_items": 0},
            {"feed": "zhihu-exampleone", "platform": "zhihu", "enabled": true,
             "would_run": true, "reason": "due", "last_status": "ok", "known_items": 39},
            {"feed": "zhihu-sample-miner", "platform": "zhihu", "enabled": true,
             "would_run": false, "reason": "interval: 距上次成功不足，还差约 47 分钟",
             "last_status": "ok", "known_items": 37},
            {"feed": "zhihu-sample-bei-jiu", "platform": "zhihu", "enabled": true,
             "would_run": false, "reason": "needs_login: 缺少登录 cookie，先跑 `python -m harvest login`",
             "last_status": "needs_login", "known_items": 0}]},
         "meta": {"version": "0.1.0"}}
    """.trimIndent()

    @Test
    fun `plan 回包一行不差`() {
        val reply = RelayCommands.parseReply(200, planBody)

        assertTrue("plan 该解析成 Plan，实际 $reply", reply is RelayCommands.Reply.Plan)
        val rows = (reply as RelayCommands.Reply.Plan).rows
        assertEquals(4, rows.size)
        assertEquals("xhs-example", rows[0].feed)
        assertEquals("interval", RelayCommands.reasonKey(rows[2].reason))
        assertEquals(39, rows[1].knownItems)
        assertEquals("needs_login", RelayCommands.reasonKey(rows[3].reason))
        assertEquals(listOf(false, true, false, false), rows.map { it.wouldRun })
    }

    /** `run` 回的是受理回执，不是结果：结果在 `job` 之外的轮询里。 */
    @Test
    fun `run 只回已受理`() {
        val body = """
            {"protocol": "harvest/1", "ok": true, "action": "run",
             "data": {"accepted": true,
                      "job": {"action": "run", "feed": "zhihu-example", "started_at": 1790531674.3091083},
                      "next": "轮询 /command/status 看结果"},
             "meta": {"version": "0.1.0"}}
        """.trimIndent()

        assertEquals(
            RelayCommands.Reply.Accepted("zhihu-example"),
            RelayCommands.parseReply(202, body),
        )
    }

    /** 整轮（不带 feed）时 job 里没有 feed：那句话得说成「开始采一轮」而不是「采 null」。 */
    @Test
    fun `已受理但没有具体源`() {
        val body = """
            {"protocol": "harvest/1", "ok": true, "action": "run",
             "data": {"accepted": true, "job": {"action": "run", "feed": null}},
             "meta": {"version": "0.1.0"}}
        """.trimIndent()

        assertEquals(RelayCommands.Reply.Accepted(null), RelayCommands.parseReply(202, body))
    }

    /** 真 status 回包：跑完的汇总。 */
    @Test
    fun `状态回包`() {
        val body = """
            {"protocol": "harvest/1", "ok": true, "action": "status",
             "data": {"running": false, "current": null, "last": {
                "action": "run", "feed": "zhihu-example", "started_at": 1790531674.3091083,
                "ok": true, "seconds": 0.3, "ran": 0, "skipped": 1, "needs_login": 0,
                "errors": 0, "new_items": 0,
                "feeds": [{"feed": "zhihu-example", "status": "skipped"}],
                "finished_at": 1790531674.6081913}},
             "meta": {"version": "0.1.0"}}
        """.trimIndent()

        val status = RelayCommands.parseStatus(body)!!

        assertEquals(false, status.running)
        assertNull(status.currentFeed)
        assertNull(status.startedAt)
        val last = status.last!!
        assertEquals(RelayCommands.ACTION_RUN, last.action)
        assertTrue(last.ok)
        assertEquals("zhihu-example", last.feed)
        assertEquals(0, last.ran)
        assertEquals(1, last.skipped)
        assertEquals(0, last.newItems)
        // 0.3 秒夹成 0：一句「采了 0 秒」是真的，但下一轮几十秒时这里跟着变
        assertEquals(0, last.seconds)
        // 对面给的是 epoch 秒，这边全线是毫秒 —— 忘了换单位不会崩，只会把「3 分钟前」
        // 说成 1970 年，所以这一颗单独钉住
        assertEquals(1_790_531_674_608L, last.finishedAt)
    }

    /** 在跑时 `current` 有值：界面上那句「正在采 X」靠它。 */
    @Test
    fun `状态回包之正在跑`() {
        val body = """
            {"protocol": "harvest/1", "ok": true, "action": "status",
             "data": {"running": true,
                      "current": {"action": "run", "feed": "zhihu-sample-miner", "started_at": 1790531674.3},
                      "last": null},
             "meta": {"version": "0.1.0"}}
        """.trimIndent()

        val status = RelayCommands.parseStatus(body)!!

        assertEquals(true, status.running)
        assertEquals("zhihu-sample-miner", status.currentFeed)
        assertEquals(1_790_531_674_300L, status.startedAt)
        assertNull(status.last)
    }

    /** 读不懂就返回 null：调用方要保留上一份状态，而不是显示半截。 */
    @Test
    fun `状态回包读不懂时不硬编`() {
        assertNull(RelayCommands.parseStatus("<html>502 Bad Gateway</html>"))
        assertNull(RelayCommands.parseStatus("""{"ok": true}"""))
    }

    @Test
    fun `401 说清是缺钥匙`() {
        val body = """
            {"protocol": "harvest/1", "ok": false, "action": "command", "data": {},
             "meta": {"version": "0.1.0"},
             "error": {"code": "NEEDS_TOKEN", "message": "缺 token 或不对：去电脑上打开 /pair 扫码配对"}}
        """.trimIndent()

        assertEquals(
            RelayCommands.Reply.Refused(
                "NEEDS_TOKEN",
                "缺 token 或不对：去电脑上打开 /pair 扫码配对",
            ),
            RelayCommands.parseReply(401, body),
        )
    }

    @Test
    fun `400 说清是这条 feed 不存在`() {
        val body = """
            {"protocol": "harvest/1", "ok": false, "action": "run",
             "data": {"feeds": ["zhihu-a", "zhihu-b"]}, "meta": {"version": "0.1.0"},
             "error": {"code": "BAD_FEED", "message": "配置里没有这条 feed：no-such-feed"}}
        """.trimIndent()

        val reply = RelayCommands.parseReply(400, body)

        assertEquals(
            RelayCommands.Reply.Refused("BAD_FEED", "配置里没有这条 feed：no-such-feed"),
            reply,
        )
    }

    /** 409 是「那边正忙」，不是失败：要单独一种形状，因为它带着正在跑的那条源。 */
    @Test
    fun `409 带上正在跑的那一条`() {
        val body = """
            {"protocol": "harvest/1", "ok": false, "action": "run",
             "data": {"current": {"action": "run", "feed": "zhihu-sample-miner", "started_at": 1.0}},
             "meta": {"version": "0.1.0"},
             "error": {"code": "BUSY", "message": "已经有一轮在跑了，等它结束再来"}}
        """.trimIndent()

        assertEquals(RelayCommands.Reply.Busy("zhihu-sample-miner"), RelayCommands.parseReply(409, body))
    }

    /** 忙但还没登记上具体源时，那句话不该出现「正在采 null」。 */
    @Test
    fun `409 但没说出在跑哪一条`() {
        val body = """
            {"protocol": "harvest/1", "ok": false, "action": "run", "data": {},
             "meta": {"version": "0.1.0"},
             "error": {"code": "BUSY", "message": "已经有一轮在跑了"}}
        """.trimIndent()

        assertEquals(RelayCommands.Reply.Busy(null), RelayCommands.parseReply(409, body))
    }

    /**
     * 读不懂的三种来路。
     *
     * 中间有个代理塞 HTML、对面换了协议、或者干脆回了个 `{}`：三种都不能当成「命令成功」，
     * 也不能崩 —— 用户看到的得是「连上了但读不懂」，因为那正是下一步排查的方向。
     */
    @Test
    fun `读不懂回包时各有各的说法`() {
        assertEquals(
            true,
            RelayCommands.parseReply(200, "<html><body>proxy</body></html>")
                is RelayCommands.Reply.Unreadable,
        )
        assertEquals(
            RelayCommands.Reply.Unreadable("电脑没说清为什么拒绝"),
            RelayCommands.parseReply(500, """{"protocol": "harvest/1", "ok": false}"""),
        )
        assertTrue(
            RelayCommands.parseReply(200, """{"ok": true, "action": "shutdown"，"data": {}}""")
                is RelayCommands.Reply.Unreadable,
        )
    }

    /** `regen` 的产物条数取 outputs 的键数：那是「重出了几条 feed 的文件」。 */
    @Test
    fun `regen 回包`() {
        val body = """
            {"protocol": "harvest/1", "ok": true, "action": "regen",
             "data": {"outputs": {"zhihu-a": "out/zhihu-a.xml", "zhihu-b": "out/zhihu-b.xml"},
                      "index": "out/index.html", "opml": "out/feeds.opml"},
             "meta": {"version": "0.1.0"}}
        """.trimIndent()

        assertEquals(RelayCommands.Reply.Regen(2), RelayCommands.parseReply(200, body))
    }

    /**
     * 跳过原因的键，跟 crawlbase `harvest/safety.py` 抄一份对照。
     *
     * 这份抄本是故意的：那边新增原因时这边不会报错，只会在界面上把英文机器名摊给用户看。
     * 同一套「白名单钉在测试里」的写法见 `RelayGuidanceTest` 的命令表。
     */
    @Test
    fun `对面的跳过原因这边全都认得`() {
        val fromCrawlbase = setOf(
            "due", "forced", "disabled", "suspended", "needs_login", "cooling", "daily_cap", "interval",
        )

        assertEquals(fromCrawlbase, RelayCommands.knownReasonKeys())
        // 冒号后每条源都不一样（「还差 3 分钟」「还差 47 分钟」），分组只能取前段
        assertEquals("interval", RelayCommands.reasonKey("interval: 距上次成功不足，还差约 47 分钟"))
        assertEquals("未到间隔", RelayCommands.reasonLabel("interval: 还差 3 分钟"))
        assertEquals("rate_limited", RelayCommands.reasonKey("rate_limited"))
        assertEquals("rate_limited", RelayCommands.reasonLabel("rate_limited"))
        assertEquals("没说原因", RelayCommands.reasonLabel("   "))
    }

    /** 手机记着的是文件地址，电脑认的是 feed id：中间只隔一个扩展名。 */
    @Test
    fun `从源地址取出 feed id`() {
        assertEquals("zhihu-sample-zhi-75-54", RelayCommands.feedIdOf("zhihu-sample-zhi-75-54.xml"))
        // 大小写和下划线是 id 的一部分，Windows 上的静态服务不改大小写
        assertEquals("zhihu-Upper_Case_Gains", RelayCommands.feedIdOf("zhihu-Upper_Case_Gains.xml"))
        assertEquals("zhihu-a.b", RelayCommands.feedIdOf("zhihu-a.b.xml"))
    }
}
