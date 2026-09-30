package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「电脑端采集」页那三行状态，以及这一页新加的两块判定：每条源采到多少内容、一次连接自检的结论。
 *
 * 这块之所以要钉住：它是用户判断「那台 PC 到底有没有在给我供内容」的唯一依据。
 * 数字算错（比如换过一次 IP 就翻倍）会让人以为订阅被复制了，进而去手删一半 ——
 * 而删掉的那批要靠下次同步才补得回来。
 */
class RelayStatusTest {

    private val base = "http://192.168.1.20:8099"

    @Test
    fun `只数这台 PC 带进来的订阅`() {
        val sources = listOf(
            source("http://192.168.1.20:8099/zhihu-a.xml"),
            source("http://192.168.1.20:8099/zhihu-b.xml"),
            source("https://openai.com/blog/rss.xml"),
        )

        assertEquals(2, RelayStatus.endpointCount(base, sources))
    }

    /** 换过 IP 之后老地址不该还算在这台机器头上 —— 那正是「订阅被复制成两份」的错觉来源。 */
    @Test
    fun `别的地址上的同源文件不算`() {
        val sources = listOf(
            source("http://192.168.1.21:8099/zhihu-a.xml"),
            source("http://192.168.1.20:9000/zhihu-a.xml"),
        )

        assertEquals(0, RelayStatus.endpointCount(base, sources))
    }

    /** 对照：主机写法的大小写和尾斜杠差异都只是写法，数出来的该是同一批。 */
    @Test
    fun `地址写法的差异不影响计数`() {
        val sources = listOf(source("http://192.168.1.20:8099/zhihu-a.xml"))

        assertEquals(1, RelayStatus.endpointCount("http://192.168.1.20:8099/", sources))
        assertEquals(1, RelayStatus.endpointCount("HTTP://192.168.1.20:8099", sources))
    }

    @Test
    fun `还没配过地址时不给一个像有数的零`() {
        assertEquals(0, RelayStatus.endpointCount("", listOf(source("http://192.168.1.20:8099/a.xml"))))
        assertEquals("还没配过 PC 地址", RelayStatus.addressLabel(""))
        assertEquals(base, RelayStatus.addressLabel(base))
    }

    @Test
    fun `上次同步的时刻`() {
        val now = 1_700_000_000_000L

        assertEquals("还没读到过它的清单", RelayStatus.lastSyncLabel(0L, now))
        assertEquals(
            "上次读到清单：2 小时前",
            RelayStatus.lastSyncLabel(now - 2 * 3600_000L, now),
        )
    }

    /**
     * 「这台电脑采到的内容」那一节：每条 relay 源一行，一条都没采到的也要在。
     *
     * 0 条那一行是这一节最有信息量的地方 —— 它正是「手机端好像拿不到」的答案，
     * 把空源筛掉等于把唯一能说清问题的那行藏起来。
     */
    @Test
    fun `每条 relay 源一行，空的那条也在`() {
        val now = 1_700_000_000_000L
        val a = source("http://192.168.1.20:8099/zhihu-a.xml")
        val b = source("http://192.168.1.20:8099/zhihu-b.xml")
        val other = source("https://www.ruanyifeng.com/blog/atom.xml")

        val rows = RelayStatus.feedRows(
            base,
            listOf(a, b, other),
            listOf(article(a, now - 3 * 86_400_000L), article(a, now), article(other, now)),
        )

        assertEquals(listOf(a.id, b.id), rows.map { it.sourceId })
        assertEquals(listOf(2, 0), rows.map { it.itemCount })
        assertEquals(listOf("zhihu-a.xml", "zhihu-b.xml"), rows.map { it.endpoint })
        assertEquals(now, rows.first().newestAt)
        assertEquals(0L, rows[1].newestAt)
        assertEquals(2, RelayStatus.totalItems(rows))
    }

    /** 没配过地址时这一节是空的，而不是「一排 0 条」—— 那会让人以为那台机器坏了。 */
    @Test
    fun `没配过地址时不摆一排零`() {
        val a = source("http://192.168.1.20:8099/zhihu-a.xml")

        assertEquals(
            emptyList<RelayFeedRow>(),
            RelayStatus.feedRows("", listOf(a), listOf(article(a))),
        )
    }

    @Test
    fun `一行的副标题把条数和最新说清楚`() {
        val now = 1_700_000_000_000L
        val row = RelayFeedRow(
            sourceId = "c1",
            name = "知乎 · 示例戌",
            endpoint = "zhihu-a.xml",
            itemCount = 37,
            newestAt = now - 5 * 3_600_000L,
        )

        assertEquals("37 条 · 最新 5 小时前", RelayStatus.rowLabel(row, now))
        assertEquals(
            "手机上还没有它的内容",
            RelayStatus.rowLabel(row.copy(itemCount = 0, newestAt = 0L), now),
        )
        assertEquals(
            "37 条 · 最新一条没有时间",
            RelayStatus.rowLabel(row.copy(newestAt = 0L), now),
        )
    }

    /**
     * 连接自检的三种结论。
     *
     * 钉住的是「清单读到了但没有内容」必须和「全通了」分开说：`feeds.opml` 和各条 feed
     * 是 harvest 分两步写出来的，只写过一半时前者照样读得到 —— 报成「通了」是假阳性。
     */
    @Test
    fun `自检的三种结论`() {
        assertEquals(
            "连不上：连接超时 ｜ 电脑端：${RelayGuidance.headline(RelayIssue.UNREACHABLE)}",
            RelayStatus.selfTestLabel(
                RelaySelfTest(manifestReached = false, issue = RelayIssue.UNREACHABLE, reason = "连接超时"),
            ),
        )
        assertEquals(
            "通了：清单 21 条（新增 2、换址 1，下拉刷新才有内容） · 抽查「知乎 · 示例戌」37 条",
            RelayStatus.selfTestLabel(
                RelaySelfTest(
                    manifestReached = true,
                    manifestFeeds = 21,
                    added = 2,
                    moved = 1,
                    sampleName = "知乎 · 示例戌",
                    sampleItems = 37,
                ),
            ),
        )
        assertEquals(
            "清单读到了（21 条），但抽查「知乎 · 示例戌」失败：HTTP 404",
            RelayStatus.selfTestLabel(
                RelaySelfTest(
                    manifestReached = true,
                    manifestFeeds = 21,
                    sampleName = "知乎 · 示例戌",
                    sampleError = "HTTP 404",
                ),
            ),
        )
    }

    /** 一条源都没有时不该谎报抽查结果 —— 「抽查」那半句凭空消失才对。 */
    @Test
    fun `通了但没得抽查`() {
        assertEquals(
            "通了：清单 1 条",
            RelayStatus.selfTestLabel(RelaySelfTest(manifestReached = true, manifestFeeds = 1)),
        )
    }

    // ---- 命令口（1.4.9：手机能让电脑再采一轮） ----

    /**
     * 没配对时这一节要改口说话。
     *
     * 钉的是「不许承诺」：token 空着时那一击必然吃 401，而 401 听起来像链路坏了。
     * 所以这一行得先承认它只出静态 RSS，再把人指到写着命令的那一节 —— 命令字面量只许
     * 在 [RelayGuidance] 出现一次，这里复述一遍就会在改参数时对不上。
     */
    @Test
    fun `命令口开没开`() {
        assertEquals("没配对：它只出静态 RSS", RelayStatus.pairingLabel(""))
        assertEquals("已配对：能让它现采", RelayStatus.pairingLabel("t-123456"))

        assertEquals(
            "还没配过这台电脑的地址：先同步一次或扫码",
            RelayStatus.commandAvailabilityLabel("", ""),
        )
        assertEquals(
            "这台电脑只出静态 RSS，命令口没开。下面「让手机能叫电脑现采」那一节写着怎么开、怎么扫码",
            RelayStatus.commandAvailabilityLabel(base, ""),
        )
        assertEquals(
            "命令会真的打到那台电脑上：run 用的是它的登录态，限速照旧生效",
            RelayStatus.commandAvailabilityLabel(base, "t-123456"),
        )
    }

    /**
     * plan 的汇总按原因分组。
     *
     * 对面 `interval` 的后半句每条源都不一样（「还差 3 分钟」「还差 5 分钟」），
     * 不取冒号前那段的话，一类会被拆成十行，读者反而看不出「全是同一个原因」。
     */
    @Test
    fun `plan 汇总把同类原因并成一行`() {
        val rows = listOf(
            RelayCommands.PlanRow("zhihu-a", wouldRun = true, reason = "due", knownItems = 39, lastStatus = "ok"),
            RelayCommands.PlanRow("zhihu-b", wouldRun = false, reason = "interval: 还差 3 分钟", knownItems = 37, lastStatus = "ok"),
            RelayCommands.PlanRow("zhihu-c", wouldRun = false, reason = "interval: 还差 5 分钟", knownItems = 30, lastStatus = "ok"),
            RelayCommands.PlanRow("zhihu-d", wouldRun = false, reason = "needs_login: 登录态没了", knownItems = 0, lastStatus = "needs_login"),
        )

        assertEquals("这轮会跑 1 条，其余 3 条被挡着：未到间隔 2 条、要登录 1 条", RelayStatus.planLabel(rows))
        assertEquals(
            "那台电脑的配置里一条 feed 都没有",
            RelayStatus.planLabel(emptyList()),
        )
        assertEquals(
            "这轮会跑 2 条",
            RelayStatus.planLabel(rows.take(1) + rows[0].copy(feed = "zhihu-x")),
        )
    }

    /** 认不出来的原因照原话摆出来，而不是编一个「被挡住」。 */
    @Test
    fun `对面新增原因时这边不撒谎`() {
        assertEquals(
            "这轮会跑 0 条，其余 1 条被挡着：rate_limited 1 条",
            RelayStatus.planLabel(
                listOf(RelayCommands.PlanRow("zhihu-a", false, "rate_limited: 新原因", 1, "ok")),
            ),
        )
    }

    /** 每一条回包形状都得有一句话，否则界面上会出现「点了什么字都没有」。 */
    @Test
    fun `命令回包的六种形状`() {
        assertEquals(
            "已受理：这台电脑开始采一轮，下面会跟着报",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Accepted(null)),
        )
        assertEquals(
            "已受理：开始采「zhihu-a」这一条",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Accepted("zhihu-a")),
        )
        assertEquals(
            "那边已经有一轮在跑了（正在采 zhihu-b），等它完事再来",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Busy("zhihu-b")),
        )
        assertEquals(
            "已重出 21 条产物（电脑端的文件换了新址时用）",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Regen(21)),
        )
        // 拒绝时以对面那句话为主，对面空着才用自己的兜底
        assertEquals(
            "这份配置里没有这条 feed",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Refused("BAD_FEED", "这份配置里没有这条 feed")),
        )
        assertEquals(
            "电脑拒绝了这条命令",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Refused("BAD_FEED", "")),
        )
        assertEquals(
            "没连上那台电脑：连接超时",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Transport("连接超时")),
        )
        assertEquals(
            "连上了，但读不懂它的回包：不是 JSON",
            RelayStatus.commandReplyLabel(RelayCommands.Reply.Unreadable("不是 JSON")),
        )
    }

    /**
     * 轮询到的状态：在跑说在跑，跑完给一句汇总。
     *
     * 「新增 N 条」必须在：那一轮跑完手机要去拉，用户问的是「这次能多几条」。
     */
    @Test
    fun `电脑端进度与上一轮汇总`() {
        val now = 1_700_000_000_000L

        assertEquals(null, RelayStatus.statusLabel(null, now))
        assertEquals(
            "电脑端在采：zhihu-a（开始于 2 分钟前）",
            RelayStatus.statusLabel(
                RelayCommands.Status(running = true, currentFeed = "zhihu-a", startedAt = now - 2 * 60_000L, last = null),
                now,
            ),
        )
        assertEquals(
            "电脑端在采：配置里全部启用的源",
            RelayStatus.statusLabel(
                RelayCommands.Status(running = true, currentFeed = null, startedAt = null, last = null),
                now,
            ),
        )
        assertEquals(
            "上一轮采了 42 秒：跑成 5 条、跳过 16 条、新增 21 条、要登录 1 条、失败 2 条 · 3 分钟前",
            RelayStatus.statusLabel(RelayCommands.Status(false, null, null, lastRun()), now),
        )
        assertEquals(
            "上一轮 run 失败：bridge 起不来",
            RelayStatus.statusLabel(
                RelayCommands.Status(false, null, null, lastRun().copy(ok = false, message = "bridge 起不来")),
                now,
            ),
        )
        assertEquals(
            "已重出这台电脑的订阅产物 · 3 分钟前",
            RelayStatus.statusLabel(
                RelayCommands.Status(false, null, null, lastRun().copy(action = RelayCommands.ACTION_REGEN)),
                now,
            ),
        )
        assertEquals(
            "上一步 plan 完成 · 3 分钟前",
            RelayStatus.statusLabel(
                RelayCommands.Status(false, null, null, lastRun().copy(action = RelayCommands.ACTION_PLAN)),
                now,
            ),
        )
        // 还没跑过时这一行整条不摆，而不是写一句「还没跑过」占位置
        assertEquals(null, RelayStatus.statusLabel(RelayCommands.Status(false, null, null, null), now))
    }

    private fun lastRun() = RelayCommands.Status.Last(
        action = RelayCommands.ACTION_RUN,
        ok = true,
        feed = null,
        ran = 5,
        skipped = 16,
        needsLogin = 1,
        errors = 2,
        newItems = 21,
        seconds = 42,
        finishedAt = 1_700_000_000_000L - 3 * 60_000L,
        message = "",
    )

    private fun source(url: String) = FeedSource(
        id = "custom-$url",
        name = "知乎 · 源A",
        url = url,
        category = "科技",
        custom = true,
    )

    private fun article(source: FeedSource, publishedAt: Long = 1_700_000_000_000L) = Article(
        id = "${source.id}-$publishedAt",
        title = "标题",
        excerpt = "摘要",
        link = "https://www.zhihu.com/question/1/answer/1",
        author = "作者",
        sourceId = source.id,
        sourceName = source.name,
        category = source.category,
        publishedAt = publishedAt,
    )
}
