package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手机报出来的症状 → 电脑端该敲的那一行」这层翻译。
 *
 * 这一层唯一的存在理由是：relay 这条链的故障**没有一个是手机上能修的** ——
 * 服务没起、没跑采集、登录态过期、被风控停机，全在 PC 上。所以每个症状必须配一句动作，
 * 而那句动作里的命令必须是 harvest 真有的子命令（测试里那条白名单就是钉这个的）：
 * 界面上写一条不存在的命令，比不写更糟，用户会以为自己敲错了。
 */
class RelayGuidanceTest {

    private val base = "http://192.168.1.20:8099"

    private fun failure(host: String, message: String) =
        FeedFailure(source = "知乎 · 源A", message = message, host = host)

    /** harvest 的 CLI 真有的子命令（crawlbase `harvest/cli.py` 里 add_parser 的那几个）。 */
    private val realSubcommands = setOf("list", "plan", "run", "login", "check", "reset", "serve", "discover", "regen")

    @Test
    fun `每个问题都有一行可直接执行的电脑端动作`() {
        RelayIssue.entries.forEach { issue ->
            assertTrue("headline 空的：$issue", RelayGuidance.headline(issue).isNotBlank())
            val sections = RelayGuidance.pageSections(issue)
            assertTrue("清单空的：$issue", sections.isNotEmpty())
            // 每个问题都得给出至少一条命令：只说「去电脑上看看」等于没说
            assertTrue(
                "没有可执行命令：$issue",
                sections.any { section -> section.lines.any { it.command != null } },
            )
        }
    }

    /** 界面会逐行渲染，text 和 command 都空的行只会留一块空白。 */
    @Test
    fun `没有一行是空的说明加空的命令`() {
        RelayIssue.entries.forEach { issue ->
            RelayGuidance.pageSections(issue).flatMap { it.lines }.forEach { line ->
                assertTrue("空行：$issue", line.text != null || line.command != null)
            }
        }
    }

    /**
     * 命令必须是 harvest 真会接受的子命令。
     *
     * 这条测试是把「对面仓库的命令表」抄在这里当白名单：crawlbase 改了子命令名，
     * 这里的文案不会自己跟着变，但它会红 —— 那时要改的是两边一起。
     */
    @Test
    fun `清单里的 harvest 子命令全都真实存在`() {
        val commands = RelayIssue.entries
            .flatMap { issue -> RelayGuidance.pageSections(issue).flatMap { section -> section.lines } }
            .mapNotNull { it.command }
        assertTrue("一条 harvest 命令都没有，白名单测试等于没测", commands.isNotEmpty())

        val pattern = Regex("""-m harvest (\w+)""")
        val unknown = commands
            .mapNotNull { command -> pattern.find(command)?.groupValues?.get(1) }
            .filter { it !in realSubcommands }
        assertTrue("清单里出现了 harvest 没有的子命令：$unknown", unknown.isEmpty())
    }

    @Test
    fun `首次接入的四步按依赖顺序排`() {
        val setup = RelayGuidance.pageSections(RelayIssue.FIRST_RUN).first()

        val order = setup.lines.mapNotNull { it.command }
            .mapNotNull { Regex("""-m harvest (\w+)""").find(it)?.groupValues?.get(1) }
        // 登录 → 发现订阅 → 采一轮 → 起服务：discover 读的是登录态，run 才产出 feeds.opml，
        // serve 吐的就是 run 的产物。顺序错了手机必然连不上。
        assertEquals(listOf("login", "discover", "run", "serve"), order)
    }

    @Test
    fun `拉取失败的原因逐条翻译成问题`() {
        // 这四条是 FeedRepository.describe() 真会产出的说法（含 okhttp 那句原文）
        assertEquals(RelayIssue.DIFFERENT_NETWORK, RelayGuidance.issueFromFetchMessage("域名解析失败"))
        assertEquals(RelayIssue.UNREACHABLE, RelayGuidance.issueFromFetchMessage("连接超时"))
        assertEquals(RelayIssue.NO_FILE, RelayGuidance.issueFromFetchMessage("HTTP 404"))
        assertEquals(
            RelayIssue.SERVICE_DOWN,
            RelayGuidance.issueFromFetchMessage("Failed to connect to /192.168.1.20:8099"),
        )
    }

    /** 对照：认不出的原因绝不该编一个动作出来。 */
    @Test
    fun `认不出的原因不给提示`() {
        assertNull(RelayGuidance.issueFromFetchMessage("XML 解析失败"))
        assertNull(RelayGuidance.issueFromFetchMessage("该地址返回的是网页，不是订阅源"))
        assertNull(RelayGuidance.issueFromFetchMessage(""))
    }

    @Test
    fun `只有失败的那台就是配过的 relay 时才开口`() {
        val failures = listOf(failure("192.168.1.20:8099", "连接超时"))

        assertEquals(RelayIssue.UNREACHABLE, RelayGuidance.issueForRelayFailures(failures, base))
        // 同一批失败、但 PC 地址是另一台：那是两件不相干的事
        assertNull(RelayGuidance.issueForRelayFailures(failures, "http://192.168.1.21:8099"))
        // 从没配过 relay：横幅不该凭空冒出「去电脑上起 serve」
        assertNull(RelayGuidance.issueForRelayFailures(failures, ""))
        assertTrue(RelayGuidance.issueForRelayFailures(emptyList(), base) == null)
    }

    /** 端口参与主机比对：8099 和 9000 是两个服务。 */
    @Test
    fun `端口不同不算同一台`() {
        assertNull(
            RelayGuidance.issueForRelayFailures(
                listOf(failure("192.168.1.20:9000", "连接超时")),
                base,
            ),
        )
    }

    /** 地址大小写与尾斜杠不该让匹配失效（存进偏好的写法不完全可控）。 */
    @Test
    fun `主机的写法差异仍然认得出`() {
        val failures = listOf(failure("192.168.1.20:8099", "域名解析失败"))

        assertEquals(RelayIssue.DIFFERENT_NETWORK, RelayGuidance.issueForRelayFailures(failures, "http://192.168.1.20:8099/"))
    }

    /** relay 那批源全挂时，第一台命中的人给提示；后面的原因不同也不影响。 */
    @Test
    fun `一批失败里有多台机器时认第一台 relay`() {
        val failures = listOf(
            failure("openai.com", "连接超时"),
            failure("192.168.1.20:8099", "HTTP 404"),
        )

        assertEquals(RelayIssue.NO_FILE, RelayGuidance.issueForRelayFailures(failures, base))
    }

    @Test
    fun `复制的内容只有命令行`() {
        val text = RelayGuidance.commandsOf(RelayGuidance.allSections())

        assertTrue(text.isNotEmpty())
        // 说明句以中文标点收尾，命令句不会 —— 粘进 PowerShell 的东西里不能混进散文
        text.lines().forEach { line ->
            assertTrue("复制到了中文说明：$line", !line.contains("。") && !line.contains("；"))
        }
        assertTrue(text.contains("harvest serve"))
        assertTrue(text.contains("harvest run"))
    }

    /** 整份清单六段齐全 —— 「电脑端采集」那页的正文就是它，缺一段页面上就少一件事可做。 */
    @Test
    fun `整份清单六段齐全`() {
        val titles = RelayGuidance.allSections().map { it.title }

        assertEquals(6, titles.size)
        assertTrue(titles[0].contains("第一次接入"))
        assertTrue(titles.any { it.contains("日常刷新") })
        assertTrue(titles.any { it.contains("命令口") })
        assertTrue(titles.any { it.contains("先确认服务还在") })
        assertTrue(titles.any { it.contains("内容不对") })
        assertTrue(titles.any { it.contains("登录态过期") })
    }

    /**
     * 命令口那一节：开关、扫码、以及「怎么关」都得写着。
     *
     * 这一节是 1.4.9 新加的唯一一处「手机能让电脑做事」的入口。三个都要钉：
     * - 不带 `--allow-commands` 的 serve 命令在这里出现 = 照着抄的人白配一次对；
     * - 删除钥匙那一行是他反悔的唯一出路，不写就等于只能改代码；
     * - [RelayStatus.commandAvailabilityLabel] 把人指到这一节，标题变了那一句就断链。
     */
    @Test
    fun `命令口一节给出开关扫码与退路`() {
        val section = RelayGuidance.allSections().first { it.title.contains("命令口") }
        val commands = section.lines.mapNotNull { it.command }

        assertTrue(section.title.contains("让手机能叫电脑现采"))
        assertTrue("扫码页地址没写出来", commands.any { it.contains("/pair") })
        assertTrue(
            "开命令口的 serve 少了开关",
            commands.any { it.contains("harvest serve") && it.contains("--allow-commands") },
        )
        assertTrue("没写怎么取消配对", commands.any { it.contains("command_token") })
        // 状态行只指路不复述命令：靠标题对上，改标题会让那句指错地方
        assertTrue(RelayStatus.commandAvailabilityLabel(base, "").contains(section.title.take(9)))
    }

    /**
     * 任何一行「重新起 serve」都必须带上命令口开关。
     *
     * 排查清单里那些「重启一次试试」是用户真会抄的；漏了开关，重启之后手机那四个按钮
     * 全部变回「没配对」，而那看起来像 App 坏了 —— 一次排查换来一次倒退，比不排查更糟。
     */
    @Test
    fun `每一行重启 serve 都带着命令口开关`() {
        val serveLines = RelayIssue.entries
            .flatMap { issue -> RelayGuidance.pageSections(issue).flatMap { section -> section.lines } }
            .mapNotNull { it.command }
            .filter { it.contains("-m harvest serve") }

        assertTrue("一条 serve 命令都没有，白名单测试等于没测", serveLines.isNotEmpty())
        serveLines.forEach { line ->
            assertTrue("重启 serve 漏了 --allow-commands：$line", line.contains("--allow-commands"))
        }
    }

    /**
     * 从失败横幅带症状进那一页时，对症节排在最前面 —— 用户点进来第一眼要看到「这次为什么」，
     * 整份文档跟在后面当参考。这一条同时钉住：症状不该把整页换成只剩一段排查。
     */
    @Test
    fun `采集页带症状时对症节在第一屏其余照旧`() {
        val head = RelayGuidance.headSection(RelayIssue.EMPTY_MANIFEST)
        val sections = RelayGuidance.pageSections(RelayIssue.EMPTY_MANIFEST)

        assertTrue(head.title.contains("清单里一条可用订阅都没有"))
        assertEquals(head, sections.first())
        assertEquals(7, sections.size)
        assertTrue(head.lines.any { it.command?.contains("harvest run") == true })
    }

    /** FIRST_RUN 的对症节就是清单里的「第一次接入」那一节，摆两遍等于同一页出现两段一样的话。 */
    @Test
    fun `首次接入的症状不会把同一节摆两遍`() {
        val titles = RelayGuidance.pageSections(RelayIssue.FIRST_RUN).map { it.title }

        assertEquals(RelayGuidance.allSections().size, titles.size)
        assertEquals(titles.distinct(), titles)
    }

    /** 从抽屉平时进来没有「这次」，页面就是那份完整清单，顺序也不能变。 */
    @Test
    fun `不带症状时页面就是整份清单`() {
        assertEquals(
            RelayGuidance.allSections().map { it.title },
            RelayGuidance.pageSections(null).map { it.title },
        )
    }

    /** 停机那条只有人工 reset 能解 —— 这条必须写在清单里，否则用户会以为多刷几次能好。 */
    @Test
    fun `被拦停机给出的是 reset 而不是硬扛`() {
        val login = RelayGuidance.allSections().first { it.title.contains("登录态过期") }

        assertTrue(login.lines.any { it.command?.contains("harvest reset") == true })
        assertTrue(login.note.orEmpty().contains("停机"))
    }
}
