package com.example.feedreader.data

/**
 * crawlbase relay 的「电脑端该做什么」判定。
 *
 * relay 这条链的一半不在手机上：手机只是去读 `harvest serve` 吐出的静态文件。所以界面报出来
 * 的每个错，真正能修它的动作都在那台 PC 上（起服务、跑一轮采集、重新登录、解除停机）。
 * 光说「连不上」用户不知道该去点哪一行，这一层就是把**症状翻译成命令**。
 *
 * 判定和文案都在这里，UI 只负责摆出来 —— 命令的字面量要跟 crawlbase 那边的 CLI 对齐，
 * 改动必然连带测试，放在 Compose 里就没人守得住了。
 *
 * 命令一律写成「cd 到仓库根目录之后整行可用」的形式，不借助 shell 变量：用户往往只复制
 * 其中一行去敲，而那一行依赖上一条命令定义过的变量，等于埋一个「我照做了却报错」的坑。
 *
 * 纯逻辑，无 Android 依赖（同 [RelayRules]、[ScanRules]）。
 */
enum class RelayIssue {
    /** 还没配过 PC 地址：整条链第一次接入。 */
    FIRST_RUN,

    /** 输入框里那段不像个地址。 */
    BAD_ADDRESS,

    /** 域名解析失败：手机和 PC 不在同一个网络，或 PC 换了地址。 */
    DIFFERENT_NETWORK,

    /** 拒绝连接：地址能找到，但那个端口上没有服务（serve 没起）。 */
    SERVICE_DOWN,

    /** 连接超时：地址上多半不是这台 PC（关机、睡眠、换 IP、被防火墙挡住）。 */
    UNREACHABLE,

    /** 连上了但没返回 OPML：端口读错了（浏览器的调试端口不是它），或者还没生成清单。 */
    NOT_A_MANIFEST,

    /** 清单能读，里面一条都没有：那边没 discover、或全都 enabled=false、或没采出产物。 */
    EMPTY_MANIFEST,

    /** HTTP 4xx/5xx：那个地址上没有这份文件。 */
    NO_FILE,

    /** 认不出形状的失败：只给通用排查清单，别编原因。 */
    UNKNOWN,
}

/** 一行提示：[text] 说明为什么/做什么，[command] 是 PC 上真能粘进 PowerShell 的那一行。 */
data class GuideLine(val text: String, val command: String? = null)

/** 一组动作。[note] 是这一组的前提或代价，只在需要时才写。 */
data class GuideSection(val title: String, val note: String? = null, val lines: List<GuideLine>)

object RelayGuidance {

    /** 每行命令的共同前缀：crawlbase 仓库自带的虚拟环境 + harvest 入口。 */
    private const val HARVEST = """& .\.venv\Scripts\python.exe -m harvest"""

    /** 首次接入：登录 → 自动发现订阅 → 采一轮 → 起服务。顺序不能换，后面三步都吃前面的产物。 */
    private fun setup() = GuideSection(
        "电脑端第一次接入（只做一次）",
        note = "登录是唯一要人动手的一步，其余全自动。这一串的产物就是手机要读的那份 feeds.opml。",
        lines = listOf(
            GuideLine("在电脑上打开 PowerShell，进 crawlbase 仓库根目录：", "cd <crawlbase 所在目录>"),
            GuideLine("弹真浏览器到登录页，人工登一次；cookie 一出现就收工：", "$HARVEST login --feed zhihu-example"),
            GuideLine("读「我是谁 + 我关注了谁」，自动写进订阅配置，不用手抄 url_token：", "$HARVEST discover --apply"),
            GuideLine("采一轮，产出每个订阅的 RSS 和那份清单：", "$HARVEST run"),
            GuideLine("起服务（长驻别关，关掉那个窗口手机就连不上了；末尾那个开关决定手机能不能叫它现采，见下一节）：", "$HARVEST serve --host auto --port 8099 --allow-commands"),
            GuideLine("serve 起来后 stderr 会打印手机能用的地址，手机浏览器开它就能看到扫码页。"),
        ),
    )

    /**
     * 命令口：手机 → 电脑的那半条链，**可选开关**。
     *
     * 不开也照样能读订阅，只是手机只能等电脑按自己的限速写好的文件；开了之后手机上点
     * 一下就能让它现采一轮。命令的对象只有三个（看这轮谁跑 / 采一轮 / 重出产物），
     * 而限速、冷却、被拦停机在电脑端照旧 —— 手机拿到的是「请求」，不是「绕过」。
     */
    private fun commandPort() = GuideSection(
        "让手机能叫电脑现采（命令口）",
        note = "配对就是扫一次码：钥匙存在电脑的 state/command_token 里，重启 serve 不用重新扫。",
        lines = listOf(
            GuideLine("起服务时带上这个开关，命令口才开（不带就只是静态 RSS）：", "$HARVEST serve --host auto --port 8099 --allow-commands"),
            GuideLine("电脑自己先打开配对页看看有没有二维码，手机扫的就是它：", "http://127.0.0.1:8099/pair"),
            GuideLine("手机在「电脑端采集」页扫这个码，一次就够；不扫也不影响读订阅。"),
            GuideLine("不想再让手机发命令：去掉开关重启，或者把钥匙删了再重启：", "Remove-Item harvest\\state\\command_token"),
        ),
    )

    /** 日常：内容长时间不变就是这一条没跑。限速在电脑端，不该让人误以为是手机坏了。 */
    private fun routine() = GuideSection(
        "日常刷新",
        note = "采集的限速是电脑端定的：4 小时最多一轮、每天最多 4 次。手机下拉刷新只是去读已经写好的文件，" +
            "不会让电脑多采一次。",
        lines = listOf(
            GuideLine("采一轮新内容：", "$HARVEST run"),
            GuideLine("不碰网络，先看这一轮哪些源会跑、为什么被跳过：", "$HARVEST plan"),
            GuideLine("想省事就用 Windows 任务计划每 4 小时跑一次 run，serve 那边长驻。"),
        ),
    )

    /** 通用排查：认不出具体原因时用。 */
    private fun triage() = GuideSection(
        "先确认服务还在",
        lines = listOf(
            GuideLine("那个端口在听吗？没有就是 serve 没起：", "Get-NetTCPConnection -LocalPort 8099"),
            GuideLine("电脑自己的浏览器先打开看看 —— 手机连不上时，这一步能分清是服务的问题还是网络的问题：", "http://127.0.0.1:8099/feeds.opml"),
            GuideLine("重新起服务，让它自己探局域网地址：", "$HARVEST serve --host auto --allow-commands"),
        ),
    )

    /** 订阅进来了但内容不对：0 条、或者只有一段摘要。 */
    private fun contentQuality() = GuideSection(
        "源在列表里，但内容不对",
        note = "知乎那批订阅目前只有摘要（两三百字）—— 列表接口就只回摘要，整篇要电脑端按回答 id 再请求一次详情。" +
            "那是拿登录态多打知乎，风控代价由你决定，手机这边做不了。",
        lines = listOf(
            GuideLine("看每条源的状态和条目数（0 条是那边没采到，不是手机弄丢了）：", "$HARVEST list"),
            GuideLine("只重采某一条（--force 忽略限速，但不豁免被拦停机）：", "$HARVEST run --feed zhihu-<id> --force"),
            GuideLine("改过配置或换了电脑地址，重新生成 RSS / 清单 / 扫码页（离线，不采集）：", "$HARVEST regen"),
        ),
    )

    /** 登录态与被拦停机：这两类手机完全看不见，只能靠这一段被想起来。 */
    private fun loginAndCooldown() = GuideSection(
        "登录态过期 / 被风控停机",
        note = "小红书这类当日被拦两次会停机，必须人工解除 —— 这是故意的，--force 也豁免不了。",
        lines = listOf(
            GuideLine("只看 cookie，不访问内容页：", "$HARVEST check"),
            GuideLine("哪条要重新登录就登那一条：", "$HARVEST login --feed <id>"),
            GuideLine("解除被拦停机或冷却：", "$HARVEST reset --feed <id>"),
        ),
    )

    /** 针对某个问题的排查段。手机能看到的只有「连不上 / 读不到 / 读到了但是空的」这三类形状。 */
    private fun troubleshooting(issue: RelayIssue): GuideSection = when (issue) {
        RelayIssue.BAD_ADDRESS -> GuideSection(
            "这次同步：那段地址不像能用的",
            lines = listOf(
                GuideLine("填 PC 在局域网里的 IP 就行，不带端口按 8099 算；路径和 feeds.opml 都不用写。"),
                GuideLine("看看 PC 现在的地址：", "Get-NetIPAddress -AddressFamily IPv4 | Format-Table InterfaceAlias,IPAddress"),
            ),
        )

        RelayIssue.DIFFERENT_NETWORK -> GuideSection(
            "这次同步：手机解析不到那台 PC",
            lines = listOf(
                GuideLine("确认手机和 PC 在同一个网络（PC 插网线时，要和手机连的是同一台路由器）。"),
                GuideLine("PC 的地址多半变了（DHCP 重新分配很常见）—— 重新起一次，拿新地址：", "$HARVEST serve --host auto --allow-commands"),
                GuideLine("清单和二维码里的链接也跟着新地址重出一份：", "$HARVEST regen"),
                GuideLine("手机回设置页改成新地址再同步，订阅不会被复制成两份。"),
            ),
        )

        RelayIssue.SERVICE_DOWN -> GuideSection(
            "这次同步：地址能到，但那个端口上没有服务",
            lines = listOf(
                GuideLine("serve 没起或已经关了 —— 它是前台进程，关掉终端窗口就等于下线：", "$HARVEST serve --host auto --allow-commands"),
                GuideLine("端口要和手机上填的一致，默认 8099。"),
                GuideLine("别把浏览器的调试端口填进来：那个只监听 127.0.0.1，手机连不上，里面也不是 RSS。"),
            ),
        )

        RelayIssue.UNREACHABLE -> GuideSection(
            "这次同步：一连那个地址就超时",
            lines = listOf(
                GuideLine("PC 关机、睡眠，或者已经换了 IP —— 先让手机浏览器直接开那个地址，打不开就不是 App 的问题。"),
                GuideLine("重新起一次，让它自己探局域网地址：", "$HARVEST serve --host auto --allow-commands"),
                GuideLine("第一次从这个地址入站要过 Windows 防火墙（要管理员授权，你自己点）：", "New-NetFirewallRule -DisplayName harvest -Direction Inbound -LocalPort 8099 -Protocol TCP -Action Allow"),
            ),
        )

        RelayIssue.NOT_A_MANIFEST -> GuideSection(
            "这次同步：那个地址没返回清单",
            lines = listOf(
                GuideLine("端口要填 harvest 的 serve 端口（默认 8099），不是浏览器调试端口。"),
                GuideLine("还没生成订阅清单：先自动发现再采一轮，清单是这两步的产物：", "$HARVEST discover --apply"),
                GuideLine("只是改了配置或换了地址，重出清单就够了（不采集）：", "$HARVEST regen"),
            ),
        )

        RelayIssue.EMPTY_MANIFEST -> GuideSection(
            "这次同步：清单里一条可用订阅都没有",
            lines = listOf(
                GuideLine("feeds.opml 只列「已启用、而且真采出了文件」的源 —— 没跑过 run 就是一份空清单：", "$HARVEST run"),
                GuideLine("看配置里到底有几条、有没有被关掉：", "$HARVEST list"),
                GuideLine("自动发现订阅（自己填过的账号一条都不会少，重复跑只会更新同一行）：", "$HARVEST discover --apply"),
            ),
        )

        RelayIssue.NO_FILE -> GuideSection(
            "这次同步：那个地址上没有这份文件",
            lines = listOf(
                GuideLine("那条订阅的文件还没被采过，跑一轮就有了：", "$HARVEST run"),
                GuideLine("或者只补这一条：", "$HARVEST run --feed <id>"),
                GuideLine("采完重出清单：", "$HARVEST regen"),
            ),
        )

        RelayIssue.FIRST_RUN -> setup()

        RelayIssue.UNKNOWN -> triage()
    }

    /** 针对某个问题的排查节：同步失败当场直接嵌在错误下面，[pageSections] 的第一节也是它。 */
    fun headSection(issue: RelayIssue): GuideSection = troubleshooting(issue)

    /** 一行话：横幅和报错后面跟着的那句「那去电脑上做什么」。 */
    fun headline(issue: RelayIssue): String = when (issue) {
        RelayIssue.FIRST_RUN -> "电脑上跑 harvest run 采一轮，再把 serve 起来"
        RelayIssue.BAD_ADDRESS -> "电脑上确认 PC 的局域网 IP，手机上只填 IP 就行"
        RelayIssue.DIFFERENT_NETWORK -> "电脑上重新起 serve，拿新的局域网地址"
        RelayIssue.SERVICE_DOWN -> "电脑上把 harvest 的 serve 起来（关掉那个终端窗口它就是下线了）"
        RelayIssue.UNREACHABLE -> "电脑上确认 PC 醒着、还在同一个网络，重新起 serve"
        RelayIssue.NOT_A_MANIFEST -> "电脑上跑 harvest discover --apply 再 run，清单是它们的产物"
        RelayIssue.EMPTY_MANIFEST -> "电脑上跑 harvest run：清单只列已经采出文件的源"
        RelayIssue.NO_FILE -> "电脑上跑 harvest run 补那份文件，再 regen 重出清单"
        RelayIssue.UNKNOWN -> "电脑上确认 serve 还在听 8099"
    }

    /**
     * 整份清单：首次接入 → 日常 → 命令口 → 通用排查 → 内容 → 登录与停机。
     *
     * 摆成一份「随时能翻」的东西，所以不含针对性排查 —— 那种只在报错当场有用。
     */
    fun allSections(): List<GuideSection> =
        listOf(setup(), routine(), commandPort(), triage(), contentQuality(), loginAndCooldown())

    /**
     * 「电脑端采集」那一页的正文。[focus] 是从首页失败横幅带进来的症状：非空时把对症
     * 那一节摆到最前面 —— 用户点进来第一眼要看到的是「这次为什么」，整份文档只是顺手留着查。
     * FIRST_RUN 的对症节和「首次接入」同名，按标题去重，不把同一节摆两遍。
     */
    fun pageSections(focus: RelayIssue?): List<GuideSection> {
        val sections =
            if (focus == null) allSections() else listOf(headSection(focus)) + allSections()
        val seen = mutableSetOf<String>()
        return sections.filter { seen.add(it.title) }
    }

    /**
     * 把「拉取失败」那句中文原因翻译成问题。
     *
     * 只认 `FeedRepository.describe()` 真会产出的那几种说法，认不出就返回 null ——
     * 宁可少提示，也不要对着一台好好的 PC 编出「去把服务起来」。测试把这几对钉住。
     */
    fun issueFromFetchMessage(message: String): RelayIssue? = when {
        message == "域名解析失败" -> RelayIssue.DIFFERENT_NETWORK
        message == "连接超时" -> RelayIssue.UNREACHABLE
        message.startsWith("HTTP ") -> RelayIssue.NO_FILE
        // ConnectException 没被 describe 特殊处理，落到 okhttp 的原文「Failed to connect to …」
        message.contains("Failed to connect", ignoreCase = true) -> RelayIssue.SERVICE_DOWN
        else -> null
    }

    /**
     * 这批失败里有没有 relay 那台 PC —— 有就返回该给的操作提示。
     *
     * 判断按**主机**而不是源名：21 条知乎源共用一台 PC，它们一起失败就是「那台机器不在线」
     * 这一件事（聚合在 `FeedRepository.collapseFailures`）。地址不同的两次失败是两件不相干
     * 的事，所以只在主机与配过的 relay 地址一致时才开口。
     */
    fun issueForRelayFailures(failures: List<FeedFailure>, relayBaseUrl: String): RelayIssue? {
        if (relayBaseUrl.isBlank()) return null
        val authority = RelayRules.authorityOf(relayBaseUrl)
        if (authority.isBlank()) return null
        val group = failures.firstOrNull { it.host == authority } ?: return null
        return issueFromFetchMessage(group.message)
    }

    /** 「复制命令」给的内容：只有命令行，粘进 PowerShell 就能一路执行下去。 */
    fun commandsOf(sections: List<GuideSection>): String =
        sections.flatMap { section -> section.lines.mapNotNull { it.command } }.joinToString("\n")
}
