package com.example.feedreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.DeepLink
import com.example.feedreader.data.GuideLine
import com.example.feedreader.data.GuideSection
import com.example.feedreader.data.RelayCommands
import com.example.feedreader.data.RelayFeedRow
import com.example.feedreader.data.RelayGuidance
import com.example.feedreader.data.RelayIssue
import com.example.feedreader.data.RelayRules
import com.example.feedreader.data.RelaySelfTest
import com.example.feedreader.data.RelayStatus
import com.example.feedreader.data.RssParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「电脑端采集」页：crawlbase relay 这一侧的全部控制，**加上它采回来的内容**。
 *
 * 单独一页而不是设置页里的三行，是因为这条链的**另一半根本不在手机上**：登录、采集、
 * 起服务都在那台 PC 上，手机能做的只有「去读它写好的静态文件」。用户遇到问题时要做的
 * 动作全在电脑端，所以那些命令值得有一页属于自己的地方，而不是藏在「我添加的源」下面
 * 第四行。
 *
 * 但这一页先得回答「那台电脑给我什么东西」：状态块下面是每条源的条目数（点开就是只看它），
 * 再下面是一次连接自检 —— 手机能不能连上那台机器，这件事原来只能靠刷新一整轮去猜。
 *
 * 配对之后这一页还多了一条**反过来的通道**：手机能给那台电脑下 plan / regen / run，
 * 于是「想让某条源现在就有新内容」不再要先跑到电脑前。没配对时这一节照摆，只是把
 * 「它现在只出静态 RSS」说在按钮该在的位置上。
 *
 * [focus] 是从首页失败横幅带进来的症状：非空时对症那一节摆在全份清单前面（判定见
 * [RelayGuidance.pageSections]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(
    relayBaseUrl: String,
    relayAutoSync: Boolean,
    relaySourceCount: Int,
    relayLastSyncAt: Long,
    relayCommandToken: String,
    feedRows: List<RelayFeedRow>,
    focus: RelayIssue?,
    onSyncRelay: suspend (String) -> RelaySyncResult,
    onSelfTest: suspend () -> RelaySelfTest,
    onSendCommand: suspend (String, String?) -> RelayCommands.Reply,
    onCommandStatus: suspend () -> RelayCommands.Status?,
    onSetRelayAutoSync: (Boolean) -> Unit,
    onScanned: (DeepLink) -> Unit,
    onOpenSource: (String) -> Unit,
    onRunFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sections = remember(focus) { RelayGuidance.pageSections(focus) }
    val now = System.currentTimeMillis()
    val totalItems = RelayStatus.totalItems(feedRows)
    val paired = relayBaseUrl.isNotBlank() && relayCommandToken.isNotBlank()

    var relayDialog by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<RelaySelfTest?>(null) }
    // 命令这一节的三份状态：正在等的命令、最近一次回包、电脑端报来的进度
    var pending by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf<RelayCommands.Reply?>(null) }
    var commandStatus by remember { mutableStateOf<RelayCommands.Status?>(null) }
    var pickingFeed by remember { mutableStateOf(false) }
    // 只有 run 会走这条路：它回的是「已受理」，真结果在那台电脑上慢慢写出来
    var polling by remember { mutableStateOf(false) }

    LaunchedEffect(polling) {
        // 先等再问：run 刚回 202 时那边线程未必已经登记上，一上来就问会读到「没在跑」，
        // 然后下面那句 onRunFinished 就在一篇都没采回来时把首页刷新一遍
        while (polling) {
            delay(COMMAND_POLL_MS)
            val next = runCatching { onCommandStatus() }.getOrNull()
            if (next == null) {
                // 读不到就不再问：命令口关了还转圈，等于把「那边没了」藏成无限等待
                polling = false
            } else {
                commandStatus = next
                if (!next.running) {
                    polling = false
                    onRunFinished()
                }
            }
        }
    }

    fun sendCommand(action: String, feed: String?) {
        if (pending != null) return
        pending = action
        reply = null
        scope.launch {
            // 兜一层：命令送不出去是网络的事，不该让这一行永远转着「正在等回包」
            val result = runCatching { onSendCommand(action, feed) }
                .getOrElse { RelayCommands.Reply.Transport(it.message ?: "命令没送出去") }
            pending = null
            reply = result
            if (result is RelayCommands.Reply.Accepted) {
                // 先本地记一份「在跑」：那边下一次报状态之前这一行不会空着，
                // 用户也就不会以为点了没用而再点一次
                commandStatus = RelayCommands.Status(
                    running = true,
                    currentFeed = result.feed,
                    startedAt = System.currentTimeMillis(),
                    last = commandStatus?.last,
                )
                polling = true
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("电脑端采集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SectionHeader(
                    title = "这台电脑",
                    caption = "手机不自己抓知乎：它只读 PC 上 harvest 写好的静态 RSS。" +
                        "登录、采集、起服务都在电脑端，这一页管的是那条链的这一头。",
                )
            }
            item {
                StatusBlock(
                    address = relayBaseUrl,
                    sourceCount = relaySourceCount,
                    lastSyncLabel = RelayStatus.lastSyncLabel(relayLastSyncAt, now),
                    commandLine = RelayStatus.pairingLabel(relayCommandToken),
                )
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "这台电脑采到的内容",
                    caption = if (feedRows.isEmpty()) {
                        if (relayBaseUrl.isBlank()) {
                            "还没配过地址 —— 配一次、同步一下，它采回来的每条源都会列在这里"
                        } else {
                            "已配过地址，但这条地址还没带进过订阅：先点下面的同步"
                        }
                    } else {
                        // 说清楚那个上限：不然电脑端 `harvest list` 报 39 条、这里报 30 条，
                        // 看上去就像手机又「少拿了」
                        "手机每条源最多留 ${RssParser.MAX_ITEMS} 条，这里共 $totalItems 条。" +
                            "点一条只看它的文章"
                    },
                )
            }
            if (feedRows.isEmpty()) {
                item {
                    HintText(
                        "这一节列的是「那条源在手机上存下了几条」。一条都没有通常是" +
                            "两边之一：那台 PC 上没跑 harvest run，或者兴趣页里「已订阅源」这条通道关着。",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                if (totalItems == 0) {
                    item {
                        HintText(
                            "${feedRows.size} 条源全都一条没有：清单是读到了，内容没拿回来。" +
                                "要么那边这轮没写出新 feed，要么「已订阅源」通道关着 —— " +
                                "下面测一次就知道是哪一头。",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (feedRows.any { it.itemCount == 0 }) {
                    // 「0 条」有两种完全不同的意思：那边就没采到，或者这条刚搬进来还没刷。
                    // 这句话不写出来的话，用户会把前者当成后者，然后去删那条源
                    item {
                        HintText(
                            "写着「手机上还没有它的内容」的那几条，多半是电脑端本来就没采到" +
                                "（`harvest list` 里它的条目数也是 0）—— 对症的命令在下面" +
                                "「源在列表里，但内容不对」那一节。",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                feedRows.forEach { row ->
                    item {
                        ActionRow(
                            title = row.name,
                            subtitle = RelayStatus.rowLabel(row, now),
                        ) { onOpenSource(row.sourceId) }
                    }
                }
            }

            item {
                ActionRow(
                    title = "测一下手机能不能连上这台电脑",
                    subtitle = if (relayBaseUrl.isBlank()) {
                        "配过地址之后才能测"
                    } else {
                        "读一次它的清单，再抽查一条 feed：两步分开报，" +
                            "「清单在、内容不在」那种半截状态只有这样才能看出来"
                    },
                ) {
                    if (!testing && relayBaseUrl.isNotBlank()) {
                        testing = true
                        scope.launch {
                            // 兜一层：探测路上任何没被捕获的异常都不该让这一行永远转圈
                            testResult = runCatching { onSelfTest() }.getOrElse {
                                RelaySelfTest(false, RelayIssue.UNKNOWN, it.message ?: "测不了")
                            }
                            testing = false
                        }
                    }
                }
            }
            if (testing) {
                item {
                    HintText(
                        "正在读它的清单、抽查一条 feed…",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (!testing) {
                testResult?.let { test ->
                    item {
                        InfoText(
                            text = RelayStatus.selfTestLabel(test),
                            emphasis = true,
                            error = !test.manifestReached,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    // 连不上的话下一步只在那台机器上：把对症那一节直接接在结论后面，
                    // 和同步失败对话框里那行内嵌提示是同一个理由 —— 此刻人要的是手边下一行命令
                    test.issue?.let { issue ->
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                PcStepsBody(listOf(RelayGuidance.headSection(issue)))
                            }
                        }
                    }
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(title = "订阅这份清单", caption = null)
            }
            item {
                ActionRow(
                    title = "从 relay 同步订阅",
                    subtitle = if (relayBaseUrl.isBlank()) {
                        "填一次 PC 的局域网地址；那边加了账号后再点一次就补上新条目"
                    } else {
                        "当前 $relayBaseUrl · 那边加了账号或这边换了 IP，再点一次就行"
                    },
                ) { relayDialog = true }
            }
            item {
                ActionRow(
                    title = "扫一扫订阅二维码",
                    subtitle = "对着一台电脑上 harvest 出的二维码扫；认得的地址会先弹确认再动手",
                ) { scanOpen = true }
            }
            // 开关只在配过地址之后出现：没配过就没有「自动同步」这回事，摆出来只会让人
            // 以为它控制着扫码那条流程。
            if (relayBaseUrl.isNotBlank()) {
                item {
                    ToggleRow(
                        title = "刷新时自动同步",
                        subtitle = "刷新顺带重拉那份清单，最多半小时一次。PC 上加了账号、" +
                            "换了 IP 都会自己跟上；亲手删掉过的源不会被偷偷加回来",
                        checked = relayAutoSync,
                        onCheckedChange = onSetRelayAutoSync,
                    )
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "让这台电脑再采一轮",
                    caption = RelayStatus.commandAvailabilityLabel(relayBaseUrl, relayCommandToken),
                )
            }
            if (paired) {
                item {
                    ActionRow(
                        title = "先看这一轮谁会跑",
                        subtitle = "只问不动手：电脑端会把每条源跑不跑、被什么挡住报回来",
                    ) { sendCommand(RelayCommands.ACTION_PLAN, null) }
                }
                item {
                    ActionRow(
                        title = "让它采一轮",
                        subtitle = "跑它配置里全部启用的源。限速、冷却、每日上限照旧在电脑端生效，" +
                            "所以刚采过的那几条这一轮还是会被跳过",
                    ) { sendCommand(RelayCommands.ACTION_RUN, null) }
                }
                if (feedRows.isNotEmpty()) {
                    item {
                        ActionRow(
                            title = "只采这一条源",
                            subtitle = "想让某一条现在就有新东西，不必等整轮",
                        ) { pickingFeed = true }
                    }
                }
                item {
                    ActionRow(
                        title = "重出它的订阅产物",
                        subtitle = "纯离线：拿电脑上已有的数据重写一遍清单和那些 feed 文件，不碰知乎。" +
                            "电脑端换过目录、改过地址之后用它",
                    ) { sendCommand(RelayCommands.ACTION_REGEN, null) }
                }
            }
            pending?.let { action ->
                item {
                    HintText(text = inFlightLabel(action), modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            if (pending == null) {
                reply?.let { result ->
                    item {
                        InfoText(
                            text = RelayStatus.commandReplyLabel(result),
                            emphasis = true,
                            error = result is RelayCommands.Reply.Transport ||
                                result is RelayCommands.Reply.Refused ||
                                result is RelayCommands.Reply.Unreadable,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            // 电脑端报来的进度单独一行：它和上面那条回包说的是两件事 ——
            // 回包是「我听懂了」，这一行是「现在跑到哪了 / 上一轮跑成什么样」
            RelayStatus.statusLabel(commandStatus, now)?.let { label ->
                item {
                    HintText(text = label, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "电脑端要做的事",
                    caption = "在 crawlbase 仓库根目录打开 PowerShell，照着敲。命令可直接复制",
                )
            }
            item {
                CopyCommandsRow(sections)
            }
            item {
                PcStepsBody(sections, modifier = Modifier.padding(horizontal = 16.dp))
            }

            item { SectionDivider() }
            item {
                SectionHeader(title = "这份清单管不到什么", caption = null)
            }
            item {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    HintText(
                        "配对了之后，「那边最近一次采集跑成什么样」手机问得到（就是上面那一节最后一行）；" +
                            "没配对还是问不到 —— 那是 crawlbase 里 state 目录下的状态文件，" +
                            "想问就在电脑上跑 `harvest list`，每条源的状态和条目数都在里面。" +
                            "还有一件手机永远替不了：登录态过期，只有电脑端重新登录一次才修得好。" +
                            "知乎那批目前只有摘要（两三百字），整篇要电脑端按条再请求详情，" +
                            "那是拿登录态多打知乎，值不值得由你定。",
                    )
                }
            }
        }
    }

    if (relayDialog) {
        RelayDialog(
            current = relayBaseUrl,
            onSync = onSyncRelay,
            onDone = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onDismiss = { relayDialog = false },
        )
    }

    if (pickingFeed) {
        AlertDialog(
            onDismissRequest = { pickingFeed = false },
            title = { Text("只采这一条源") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    HintText("这里列的是这台电脑带进来的订阅；点一条就让它只跑那一条。")
                    Spacer(modifier = Modifier.height(8.dp))
                    feedRows.forEach { row ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingFeed = false
                                    sendCommand(
                                        RelayCommands.ACTION_RUN,
                                        RelayCommands.feedIdOf(row.endpoint),
                                    )
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(text = row.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = RelayStatus.rowLabel(row, now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingFeed = false }) { Text("取消") }
            },
        )
    }

    if (scanOpen) {
        ScanScreen(
            onClose = { scanOpen = false },
            onScanned = { link ->
                scanOpen = false
                onScanned(link)
            },
        )
    }
}

/**
 * 手机这侧知道的几件事，摆成一行行不用点开的状态。
 *
 * 没配过地址时把「配过之后才有自动同步、才有命令口」这条因果说清楚，否则那几行凭空消失会让人
 * 以为是 App 出 bug。
 */
@Composable
private fun StatusBlock(address: String, sourceCount: Int, lastSyncLabel: String, commandLine: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        StatusLine("PC 地址", if (address.isBlank()) "还没配过" else address)
        StatusLine("来自它的订阅", if (address.isBlank()) "—" else "$sourceCount 条")
        StatusLine(
            "清单",
            if (address.isBlank()) "同步一次之后就在这里" else lastSyncLabel,
        )
        StatusLine("命令口", if (address.isBlank()) "配过地址、扫过配对码之后才有" else commandLine)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 一次性复制整页命令（只含命令行，不含中文说明），粘进 PowerShell 就能一路回车。 */
@Composable
private fun CopyCommandsRow(sections: List<GuideSection>) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(sections) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (copied) "已复制 ${sections.sumOf { it.lines.count { l -> l.command != null } }} 行命令"
            else "复制这一页的全部命令",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(RelayGuidance.commandsOf(sections)))
                copied = true
            },
        ) { Text(if (copied) "已复制" else "复制命令") }
    }
}

/**
 * crawlbase relay 同步。
 *
 * 和添加订阅源那条路一样：**同步期间不关窗、不禁用取消**。那是一次真网络请求，
 * 中途跑掉的话「刚才到底同没同步上」用户永远不知道，而这恰恰是唯一能看出来
 * 「PC 地址填错了」还是「那边服务没起」的时刻。
 *
 * 失败时窗口留着、错误显示在里面，并把对症的那一节电脑端命令直接嵌在错误下面 ——
 * 地址大概率就在输入框里差一个字符，而下一步动作只在电脑上。
 */
@Composable
private fun RelayDialog(
    current: String,
    onSync: suspend (String) -> RelaySyncResult,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    var syncing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var issue by remember { mutableStateOf<RelayIssue?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!syncing) onDismiss() },
        title = { Text("crawlbase relay") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HintText(
                    "填 PC 的 IP，例如 192.168.1.20（不带端口按 " +
                        "${RelayRules.DEFAULT_PORT} 算）。手机要去读它的 feeds.opml，" +
                        "也就是 crawlbase 那边 `harvest serve` 监听的地址 —— " +
                        "得让手机和 PC 在同一个网络里，服务也得监听局域网而不只是 127.0.0.1。",
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                        issue = null
                    },
                    singleLine = true,
                    isError = error != null,
                    enabled = !syncing,
                    label = { Text("PC 地址") },
                    placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (syncing) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HintText("正在读它的 feeds.opml…")
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // 报错当场的电脑端动作嵌在这里，不再多开一个窗：用户此刻要的
                    // 就是「下一步敲哪一行」
                    issue?.let { kind ->
                        Spacer(modifier = Modifier.height(10.dp))
                        PcStepsBody(listOf(RelayGuidance.headSection(kind)))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !syncing && text.isNotBlank(),
                onClick = {
                    syncing = true
                    error = null
                    issue = null
                    scope.launch {
                        // 兜一层：任何没被捕获的异常都不该让按钮永远卡在「同步中」
                        val result = runCatching { onSync(text) }
                            .getOrElse {
                                RelaySyncResult.Failed(it.message ?: "同步失败", RelayIssue.UNKNOWN)
                            }

                        when (result) {
                            is RelaySyncResult.Synced -> {
                                onDone(
                                    if (result.added > 0 || result.moved > 0) {
                                        "已同步 ${result.total} 条：新增 ${result.added} 个、" +
                                            "改址 ${result.moved} 个，下拉刷新开始拉取"
                                    } else {
                                        "已同步 ${result.base}：${result.total} 条都是最新的"
                                    },
                                )
                                onDismiss()
                            }

                            is RelaySyncResult.Failed -> {
                                error = result.reason
                                issue = result.issue
                            }
                        }
                        syncing = false
                    }
                },
            ) { Text("同步") }
        },
        dismissButton = {
            TextButton(enabled = !syncing, onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 「电脑端要做的事」的正文：一节一节摆开，命令用等宽 + 底色，长按或整页复制都能拿到。
 *
 * 内容全部来自 [RelayGuidance]（纯数据层），这里只排版 —— 命令的字面量得跟 crawlbase 的
 * CLI 对齐，那份对齐由单元测试守着，不该散进 Compose。
 */
@Composable
private fun PcStepsBody(
    sections: List<GuideSection>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        sections.forEachIndexed { index, section ->
            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            section.note?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            section.lines.forEach { line -> GuideLineRow(line) }
        }
    }
}

@Composable
private fun GuideLineRow(line: GuideLine) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        line.text?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        line.command?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 一条命令送出去到回包之间那一行字。
 *
 * 分命令写而不是统一「正在发送…」：plan 与 regen 是问一句就回，run 只是「已受理」，
 * 同一句话会让人以为那一按没生效，然后连点三下。
 */
private fun inFlightLabel(action: String): String = when (action) {
    RelayCommands.ACTION_PLAN -> "正在问它这一轮的安排…"
    RelayCommands.ACTION_REGEN -> "正在让它重出订阅产物…"
    else -> "正在通知它开始采一轮…"
}

/**
 * 轮询电脑端状态的间隔。
 *
 * 电脑端跑一轮可能是几分钟也可能是几十分钟（限速在它那边），所以这里不必追得紧：
 * 五秒一次是一个几百字节的 GET，一轮下来问几百次也远轻于它自己采一次。
 */
private const val COMMAND_POLL_MS = 5_000L

