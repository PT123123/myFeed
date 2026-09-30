package com.example.feedreader.data

/**
 * 「电脑端采集」页的状态 —— 手机这一侧对那台 PC 真正知道的事。
 *
 * 全局状态三件：配过哪个地址、那个地址带进来几条订阅、上次去读清单是多久之前。
 * 再加一条链的**结果**：每条源在本地存下了多少条内容（[feedRows]），以及一次连接自检的
 * 结论（[selfTestLabel]）—— 有了这两样，「手机端好像拿不到」这一类问题在手机上说得出答案。
 *
 * 1.4.9 起手机还能反过来问那台电脑：配过对（[pairingLabel]）之后 plan / regen / run
 * 三条命令打得出去，电脑端跑到哪一步、上一轮采成什么样都能问回来（[commandReplyLabel]、
 * [statusLabel]）。**没配对时**「那边最近一次采集是几点」问的还是 crawlbase 里 state 目录下
 * 的状态文件，手机读不到，所以这里不编 —— 那一句在页面上是电脑端的命令（见 [RelayGuidance]），
 * 不是这里的状态行。
 *
 * 纯逻辑，无 Android 依赖（同 [RelayRules]、[RelayGuidance]）。
 */
object RelayStatus {

    /**
     * 这台 relay 带进来的订阅条数。
     *
     * 按**主机 + 端口**匹配而不是源名：那台 PC 的 IP 会变（家里 DHCP），同步时老地址那几条
     * 会被原地改成新地址（见 `SourceStore.syncRelaySources`），所以换过一次 IP 之后这个数
     * 不该翻倍。指向别的机器的自建源也不该算在这台 PC 头上。
     */
    fun endpointCount(base: String, sources: List<FeedSource>): Int {
        if (base.isBlank()) return 0
        val authority = RelayRules.authorityOf(base)
        return sources.count { RelayRules.authorityOf(it.url) == authority }
    }

    /**
     * 「上次读到清单」那一行。只有一次**读成功并落库**的同步会推进它（手动和静默都算），
     * 所以这句话回答的是「手上这份清单是多久之前的」。
     * 连不上、超时那些尝试不会改这个数 —— 它们由界面上的失败提示和电脑端清单负责。
     */
    fun lastSyncLabel(lastSyncAt: Long, now: Long): String =
        if (lastSyncAt <= 0L) "还没读到过它的清单" else "上次读到清单：${DateParser.relative(lastSyncAt, now)}"

    /** 状态块第一行：地址，或者「还没配过」这个更该先解决的事实。 */
    fun addressLabel(base: String): String =
        if (base.isBlank()) "还没配过 PC 地址" else base

    /**
     * 这台 PC 带进来的每一条源，以及手机本地已经存下它的多少条内容。
     *
     * 顺序跟着订阅源列表（也就是那份清单的顺序），**一条都没有的源也摆出来**：
     * 「21 条源、0 条内容」正是「手机到底拿没拿到」的答案，把它筛掉等于把最有信息量
     * 的那一行藏起来。
     *
     * 条目数取自**当前首页那份列表**而不是磁盘缓存目录：那才是用户此刻能翻到的东西，
     * 而缓存里还可能有已经关掉的旧源。代价是「关掉已订阅源通道」时这里会显示 0 条 ——
     * 那也是事实，界面上会把这句话说明白。
     */
    fun feedRows(base: String, sources: List<FeedSource>, articles: List<Article>): List<RelayFeedRow> {
        if (base.isBlank()) return emptyList()
        val authority = RelayRules.authorityOf(base)
        val bySource = articles.groupBy { it.sourceId }

        return sources
            .filter { it.custom && RelayRules.authorityOf(it.url) == authority }
            .map { source ->
                val items = bySource[source.id].orEmpty()
                RelayFeedRow(
                    sourceId = source.id,
                    name = source.name,
                    endpoint = RelayRules.endpointOf(source.url) ?: source.url,
                    itemCount = items.size,
                    newestAt = items.maxOfOrNull { it.publishedAt } ?: 0L,
                )
            }
    }

    /** 这些源加起来一共多少条。 */
    fun totalItems(rows: List<RelayFeedRow>): Int = rows.sumOf { it.itemCount }

    /**
     * 一行源的副标题：条数 + 最新一条是什么时候。
     *
     * 时间用的是**原文发布时间**而不是「什么时候抓到的」—— 用户判断「这台电脑有没有在给我
     * 采新东西」看的就是这个；知乎那批的发布时间可能是几年前，那是内容本身的属性，
     * 不是链路的故障，所以这里只陈述日期，不下判断。
     */
    fun rowLabel(row: RelayFeedRow, now: Long): String = when {
        row.itemCount == 0 -> "手机上还没有它的内容"
        row.newestAt <= 0L -> "${row.itemCount} 条 · 最新一条没有时间"
        else -> "${row.itemCount} 条 · 最新 ${DateParser.relative(row.newestAt, now)}"
    }

    /**
     * 连接自检那句话。
     *
     * 三种形状：连不上（带上那台机器上该敲的动作）、通了、清单读到了但抽查那条 feed 失败
     * （后者是真实在的坏情况 —— 清单和 feed 文件是 harvest 分两步写的，只写过一半时
     * 清单在、文件不在，光看清单会误判成「好了」）。
     */
    fun selfTestLabel(test: RelaySelfTest): String = when {
        !test.manifestReached -> "连不上：${test.reason}" +
            (test.issue?.let { " ｜ 电脑端：${RelayGuidance.headline(it)}" } ?: "")

        !test.sampleError.isNullOrBlank() -> "清单读到了（${test.manifestFeeds} 条），" +
            "但抽查「${test.sampleName}」失败：${test.sampleError}"

        else -> buildString {
            append("通了：清单 ${test.manifestFeeds} 条")
            // 自检刻意不触发整轮刷新（一点就转十分钟受不了），所以新搬进来的那几条
            // 此刻还没有内容 —— 这句话不说出来，上面那一节的「0 条」就像自检没生效
            if (test.added > 0 || test.moved > 0) {
                append("（新增 ${test.added}、换址 ${test.moved}，下拉刷新才有内容）")
            }
            test.sampleItems?.let { append(" · 抽查「${test.sampleName}」$it 条") }
        }
    }

    /**
     * 状态块里「命令口」那一行：这台电脑收不收手机下的命令。
     *
     * 只说配没配上，不说 token 是什么 —— 那串字符在页面上摆出来没有任何用处
     * （它是扫码时自动存的），却会让人以为要自己记住它。
     */
    fun pairingLabel(token: String): String =
        if (token.isBlank()) "没配对：它只出静态 RSS" else "已配对：能让它现采"

    /**
     * 「让这台电脑再采一轮」那一节的副标题：先说这台电脑配没配对上。
     *
     * 没配对时不要写「点一下就能采」—— 那一击必然失败，而失败原因（对面回 401）
     * 听起来像链路坏了，其实只是缺钥匙。
     *
     * 这里不复述命令：开命令口那几行字面量只在 [RelayGuidance] 有一处，改 CLI 参数时
     * 不会两处对不上。
     */
    fun commandAvailabilityLabel(base: String, token: String): String = when {
        base.isBlank() -> "还没配过这台电脑的地址：先同步一次或扫码"
        token.isBlank() -> "这台电脑只出静态 RSS，命令口没开。" +
            "下面「让手机能叫电脑现采」那一节写着怎么开、怎么扫码"
        else -> "命令会真的打到那台电脑上：run 用的是它的登录态，限速照旧生效"
    }

    /** `plan` 的回包：这轮谁会跑、谁被什么挡住。 */
    fun planLabel(rows: List<RelayCommands.PlanRow>): String {
        if (rows.isEmpty()) return "那台电脑的配置里一条 feed 都没有"
        val due = rows.count { it.wouldRun }
        val blocked = rows.filter { !it.wouldRun }
            .groupingBy { RelayCommands.reasonKey(it.reason) }
            .eachCount()
        return buildString {
            append("这轮会跑 $due 条")
            if (blocked.isNotEmpty()) {
                append("，其余 ${rows.size - due} 条被挡着：")
                append(blocked.entries.sortedByDescending { it.value }
                    .joinToString("、") { "${RelayCommands.reasonLabel(it.key)} ${it.value} 条" })
            }
        }
    }

    /**
     * 一次命令的回包怎么说。
     *
     * `run` 的「已受理」必须带上「结果靠轮询」这句话，否则用户以为点完就有新内容。
     */
    fun commandReplyLabel(reply: RelayCommands.Reply): String = when (reply) {
        is RelayCommands.Reply.Plan -> planLabel(reply.rows)
        is RelayCommands.Reply.Regen -> "已重出 ${reply.feeds} 条产物（电脑端的文件换了新址时用）"
        is RelayCommands.Reply.Accepted ->
            if (reply.feed.isNullOrBlank()) "已受理：这台电脑开始采一轮，下面会跟着报"
            else "已受理：开始采「${reply.feed}」这一条"

        is RelayCommands.Reply.Busy -> "那边已经有一轮在跑了" +
            (reply.currentFeed?.let { "（正在采 $it）" } ?: "") + "，等它完事再来"
        is RelayCommands.Reply.Refused -> reply.message.ifBlank { "电脑拒绝了这条命令" }
        is RelayCommands.Reply.Transport -> "没连上那台电脑：${reply.message}"
        is RelayCommands.Reply.Unreadable -> "连上了，但读不懂它的回包：${reply.message}"
    }

    /**
     * 轮询到的状态怎么说：在跑说在跑，跑完给一句汇总。
     *
     * 没有状态可说时返回 null（界面就不摆这一行），而不是「还没跑过」—— 那一节刚进来
     * 本来就没跑过，写出来只会占一行。
     */
    fun statusLabel(status: RelayCommands.Status?, now: Long): String? = when {
        status == null -> null
        status.running -> "电脑端在采：" +
            (status.currentFeed ?: "配置里全部启用的源") +
            (status.startedAt?.let { "（开始于 ${DateParser.relative(it, now)}）" } ?: "")

        else -> lastRunLabel(status.last, now)
    }

    private fun lastRunLabel(last: RelayCommands.Status.Last?, now: Long): String? {
        last ?: return null
        if (!last.ok) return "上一轮 ${last.action} 失败：${last.message.ifBlank { "电脑没给出原因" }}"
        val ago = last.finishedAt?.let { " · ${DateParser.relative(it, now)}" } ?: ""
        return when (last.action) {
            RelayCommands.ACTION_RUN -> "上一轮采了 ${last.seconds} 秒：跑成 ${last.ran} 条、" +
                "跳过 ${last.skipped} 条、新增 ${last.newItems} 条" +
                (if (last.needsLogin > 0) "、要登录 ${last.needsLogin} 条" else "") +
                (if (last.errors > 0) "、失败 ${last.errors} 条" else "") + ago

            RelayCommands.ACTION_REGEN -> "已重出这台电脑的订阅产物$ago"
            else -> "上一步 ${last.action} 完成$ago"
        }
    }
}

/** 「电脑端采集」页内容区的一行。见 [RelayStatus.feedRows]。 */
data class RelayFeedRow(
    val sourceId: String,
    val name: String,
    /** feed 的文件名（`zhihu-xxx.xml`），一条 relay 源的身份。 */
    val endpoint: String,
    val itemCount: Int,
    /** 这一条源里最新的文章是什么时候发布的；一条都没有时为 0。 */
    val newestAt: Long,
)

/**
 * 一次连接自检的事实，展示用 [RelayStatus.selfTestLabel]。
 *
 * 拆成字段而不是直接生成句子，是因为句子要单测、而网络要真跑 —— 判定层拿到这些数就能
 * 断言三种形状各自的文案，不必连上一台 PC。
 */
data class RelaySelfTest(
    /** 读 `feeds.opml` 这一步成没成。 */
    val manifestReached: Boolean,
    /** 失败时的症状（决定页面把哪一节电脑端命令摆前面）。 */
    val issue: RelayIssue? = null,
    val reason: String = "",
    val manifestFeeds: Int = 0,
    val added: Int = 0,
    val moved: Int = 0,
    /** 抽查的那条 feed 的源名；一条源都没有时不抽查，为 null。 */
    val sampleName: String? = null,
    /** 抽查拿到的条目数；没抽查到时为 null。 */
    val sampleItems: Int? = null,
    /** 抽查失败的原因；成功或没抽查时为 null。 */
    val sampleError: String? = null,
)
