# relay 的电脑端操作提示：手机上看到的症状，动作全在另一台机器上

日期：2026-09-27（1.4.6 / versionCode 13 落地，**1.4.7 / versionCode 14 把落点从设置页搬进独立页**，
**1.4.8 / 15 在同一页加了一键连接自检**）
状态：**代码、单测、装机完成**；新页面的排版没人眼看过（见文末）

## 一句话

relay 这条链的故障**没有一种是手机上能修的** —— 服务没起、没跑采集、登录态过期、被风控停机，
全在 PC 上。所以每个症状都必须配一句「去电脑上敲哪一行」，这一片就是那层翻译。

## 联动实况（写代码之前先量的，2026-09-27 21:4x）

两侧同时探了一遍，界面该提示什么由这份实况决定：

| 检查 | 结果 |
| --- | --- |
| PC `serve` | 在听 `192.168.1.20:8099`，`feeds.opml` 与 `subscribe.html` 都 200 |
| 手机 prefs | `relay_base_url = http://192.168.1.20:8099`、`custom_sources` 21 条、`relay_last_sync_at` 已写 |
| 设备缓存 | 46 个文件 / 888 篇，其中 relay 那 21 源全在里面（`tools/body_coverage.py`） |
| 登录态 | `harvest check`：知乎 21 条 + 小红书示例 `logged_in=true`；**twitter 三条示例全是 `logged_in=false`**（配置里 `enabled=false`，所以不影响） |
| 采集时刻 | `harvest/state/*.json` 的 `last_run_at` 都在 18:30–18:39 —— 量到这一步才知道内容已经三小时没动过 |
| 唯一的坏源 | `zhihu-sample-bei-jiu`（知乎 · 示例寅）state 里 `items=0`，`out/` 那份 XML 里 `<item>` 数为 0 |

最后两行是这片提示的直接来源：**「源都在列表里，但内容不再变」和「某一条永远 0 条」在界面上
都是沉默的**，用户只能猜。而这两件事的解法分别是 `harvest run` 和 `harvest list` /
`harvest run --feed <id>`。

## 改了什么

### 一、判定层：`data/RelayGuidance.kt`（新增，纯逻辑）

`RelayIssue` 九种症状（首次接入 / 地址不像 / 解析不到 / 端口上没服务 / 超时 / 没返回清单 /
清单是空的 / 那个地址没文件 / 认不出），每种给：

- `headline(issue)` —— 一行话，接在报错后面；
- `headSection(issue)` —— 针对性的排查段（3–4 行，带命令）；
- `pageSections(focus)` —— **「电脑端采集」那一页的正文**：不带症状时就是整份清单，带症状时
  把对症那段摆到第一屏（FIRST_RUN 的对症段与「第一次接入」同名，按标题去重，不摆两遍）；
- `allSections()` —— 整份清单（首次接入 / 日常刷新 / 先确认服务还在 / 内容不对 / 登录态过期与停机）。

1.4.6 时这两个入口分别叫 `sectionsFor(issue)`（弹窗用）和 `allSections()`（设置页弹窗用）；
1.4.7 把弹窗换成独立页面之后合成一个 `pageSections(focus)`，因为一份清单只有一处需要它了。

两条从症状到 issue 的映射：

- `issueFromFetchMessage(message)` 只认 `FeedRepository.describe()` 真会产出的四种说法
  （`域名解析失败`、`连接超时`、`HTTP 404`、okhttp 原文 `Failed to connect to …`），
  **认不出返回 null** —— 宁可少提示，也不要对着一台好 PC 编出「去把服务起来」；
- `issueForRelayFailures(failures, relayBaseUrl)` 按**主机**认：21 条知乎源一起失败就是
  「那台机器不在线」这一件事（聚合在 `collapseFailures`），源名会随用户改名变，主机不会。

命令全部写成 `& .\.venv\Scripts\python.exe -m harvest …`，**不借助 shell 变量**：用户往往只复制
其中一行去敲，而那一行依赖上一行定义过的 `$PY`，等于埋一个「我照做了却报错」的坑。
子命令名有测试盯着（见下），因为写一条 harvest 没有的命令比不写更糟。

### 二、入口（1.4.6 摆了三处弹窗；1.4.7 收进独立一页）

| 位置 | 1.4.6 | 1.4.7（现在） |
| --- | --- | --- |
| 抽屉 | 「兴趣与设置」里的一行 → 设置页弹窗 | **独立一页「电脑端采集」**：状态 + 同步 + 扫码 + 自动同步开关 + 整份清单，页内「复制命令」 |
| 同步对话框失败时 | 错误下面嵌对症那一节 | 不变（那一刻要的就是「下一行敲什么」，不该再跳页） |
| 首页失败横幅 | 「点这里看要做哪几行」→ 弹窗 | 「点这里去『电脑端采集』页」→ 带症状 `focus` 进那一页，对症节摆第一屏 |
| 扫码 / 深链的确认框 | 失败结果后面接 `电脑端：…` | 不变 |

撤掉的是**弹窗**（`ui/PcStepsDialog.kt`，正文 `PcStepsBody` 搬进 `ui/RelayScreen.kt` 变成页面
私有组件），不是那份清单 —— 清单本身一字没动，判定层还在 `data/RelayGuidance.kt`。
抽屉里为什么点不进弹窗见 [navigation.md](navigation.md)。

顺带：`RelaySyncResult.Failed` 多了 `issue` 字段（原因写给用户看，类型写给代码用，以后改措辞
不会把提示改坏），`FeedFailure` 多了 `host`（横幅只能靠它认出那批失败是不是 relay），
`logcat` 的静默同步失败那行带上 issue 名（PC 关机时界面上没有横幅可看，日志是唯一线索）。

## 为什么不做成「自动修」

因为动作的**代价在电脑端且属于用户**：`harvest run` 是拿登录态打知乎，逐条取全文要几十次请求，
`reset --feed` 是人工确认没被风控之后才该做的事，防火墙入站规则要管理员授权。App 能做的到此
为止 —— 把该敲的那一行摆到他面前，敲不敲由他决定。全文那条尤其要写清楚（见
[offline-full-text.md](offline-full-text.md) 文末的实测：订阅里知乎正文最长 268 字）。

## 验证情况

- `:app:testDebugUnitTest` **381 项 0 失败**（1.4.7 再数一遍：这片 1.4.6 落的是
  `RelayGuidanceTest` 14 + `FeedRepositoryTest` 的「每条失败都带上聚合用的主机」1；
  搬到独立页时改成 `pageSections` 语义并补了 `RelayStatusTest` 5，`RelayGuidanceTest` 现在 16）。
  `RelayGuidanceTest` 里两条是特意拿来当对照的：
  - **命令白名单**：把 harvest `cli.py` 里 `add_parser` 的九个真子命令抄在测试里，清单里出现
    任何别的名字就红 —— 对面仓库改了名，这边文案不会自己跟上，靠这条发现；
  - **首次接入的顺序**：断言命令序列恰好是 `login → discover → run → serve`，因为 discover
    读登录态、run 产清单、serve 吐 run 的产物，顺序错了手机必然连不上。
  另外钉了「认不出的原因不给提示」和「主机/端口不匹配不开口」，防的是横幅对着一台好 PC 乱指。
- `:app:assembleDebug` 成功，APK versionCode **14** / versionName **1.4.7** 已 `adb install -r`
  进平板，冷启动清过 logcat 之后 crash buffer 里没有任何 `feedreader` 条目。
- **装机不是靠肉眼看的**：1.4.7 把 `classes*.dex` 拆出来按字符串查 —— `电脑端采集` 在
  `classes4.dex`（抽屉那一项）和 `classes6.dex`（页面标题），`上次读到清单`、
  `这份清单管不到什么`、`复制这一页的全部命令`、`刷新时自动同步` 都在 `classes6.dex`；
  **`兴趣与设置` 在整个包里已经查不到**（撤掉的正是这个说法）。设备上 `relay_base_url` =
  `http://192.168.1.20:8099`、21 条 `zhihu-*.xml` 源、`relay_last_sync_at` 升级后都还在原位 ——
  也就是说那一页的三行状态在真机上会显示「地址在 / 21 条 / 上次读到清单：几分钟前」。
- **仍没人眼看过**（要你回来点一下）：新页的排版 —— 状态三行的对齐、清单里命令块在窄屏上会不会
  溢出、「复制这一页的全部命令」按下去的反馈、抽屉里四件事（全部文章 / 兴趣 / 电脑端采集 / 设置）
  点进去的层次，以及从首页横幅跳过去时对症那节有没有真的排在第一屏。
  同步失败时嵌在错误下面那一节照旧是弹窗内嵌，位置没动。真机相机扫码那条路照旧没验。
- 这次没量到的一件事，说清楚：横幅的电脑端提示要求**这批失败的主机正好等于配过的 relay 地址**。
  PC 换了 IP 而手机上还没改地址时，认不出的是「地址过期」而不是「服务没起」，那时给的是
  `UNREACHABLE` / `DIFFERENT_NETWORK` 那一节 —— 内容正好也是「去电脑上重新起 serve 拿新地址」，
  所以不会指错方向，但界面上不会说「你手机里存的地址是老的」。


## 1.4.8：这一页第一次摆出「内容」，命令文案一处都没新增

被否第二次：独立页有了、整份清单也在，可它**看上去仍然不像会拿到东西的地方** —— 一屏都是开关
和命令，采回来的东西没有一处能看见。这次加的是那一节内容本身（每条源一行：`37 条 · 最新 5 小时前`，
判定在 `RelayStatus.feedRows` / `rowLabel` / `totalItems`）和一键连接自检；链路细节与实测在
[navigation.md](navigation.md)、[crawlbase-relay.md](crawlbase-relay.md) 追加十。

这片提示层被牵到三处，都值得单独记：

- **自检的失败分支不写自己的命令**。它拿 `FeedRepository.describe()` 那句原因走
  `RelayGuidance.issueFromFetchMessage(...)`，然后内嵌 `PcStepsBody(listOf(headSection(issue)))`
  —— 症状→「去电脑上敲哪一行」的翻译仍然只有 `RelayGuidance` 一份，Compose 里一句命令都没出现。
  这也是 `RelayGuidanceTest` 那条命令白名单在 1.4.8 仍然只盯着 `allSections()` 的原因：自检没有
  给它添新面孔。
- **三种「0 条」各归一处**。「一条都没采到」和「这边就没跑 run」以前在界面上是同一句沉默，现在
  分开：整节为空 → 说两种可能（PC 没跑 run / 兴趣页里「已订阅源」通道关着）；全为 0 但有行 →
  说「清单读到了、内容没拿回来」，并把「下面测一次就知道是哪一头」交给自检；**只有部分行为 0**
  才是 `harvest list` 里它本身也是 0，那句话直接指向下面已有的「源在列表里，但内容不对」那一节
  （文字指向，没做锚点跳转）。第三句最要紧：不写出来，用户会把「那边本来没采到」读成「刚搬进来
  还没刷」，然后去删那条源。
- **30 条上限写成声明，不写成报错**。`RssParser.MAX_ITEMS` 每条源只留 30 条，于是电脑端
  `harvest list` 报 39、这一页报 30 —— 长得完全像「手机又少拿了」。它写在节标题的 caption 里
  （「手机每条源最多留 30 条，这里共 N 条」），不进 issue、不进 error 配色：它永远不表示坏。

自检刻意**不触发整轮刷新**（`syncRelay(base, auto = true)`：不把用户亲手删过的条目偷偷加回来），
所以刚搬进来的那几条会在结论里明说「新增 2、换址 1，下拉刷新才有内容」。这是这一步唯一与
追加八那句「代价在电脑端且属于用户」相干的取舍：自检只读，`harvest run` 那种会打知乎的仍然只以
命令形式出现。

### 验证情况（1.4.8）

- `:app:testDebugUnitTest` **388 项 0 失败**（1.4.7 是 381）。新增判定都落在 `RelayStatusTest`
  （5 → 10：每条源一行且空源也要在、没配地址时不摆一排零、副标题三种形状、自检三种结论、
  通了但没得抽查）和 `SourceRulesTest`（15 → 17：`categoryFor` 该改的那一条与三条不该动的）。
  `RelayGuidanceTest` 16 项一字未改 —— 这一版没往提示层加新命令，这条没动本身就是检查点。
- `:app:assembleDebug` + `adb install -r`：versionCode **15** / **1.4.8**，冷启动清过 logcat，
  crash buffer 里 `feedreader` 条目数 0。
- 装机不是靠肉眼看的（拆 `classes*.dex`）：`这台电脑采到的内容`、`手机上还没有它的内容`、
  `多半是电脑端本来就没采到`、`测一下手机能不能连上这台电脑`、`手机每条源最多留`、
  `正在读它的清单`、`订阅这份清单` 全在包内。
- **PC 侧的数是直接问服务本身的**（`urllib` 取 `feeds.opml` 再逐条取 XML 数 `<item>`）：
  21 条 / 477 个 `<item>`，超上限的正好 4×39 + 3×37，`zhihu-sample-bei-jiu.xml` 为 0。
  本地 `harvest/out/` 那 22 个 zhihu XML 最新 mtime 09-27 18:39，与追加八量的采集时刻对得上。
- **仍没人眼看过**：自检那条按钮真机没按过（要人手点一下才会跑），两步各自等价于已验过的路
  （同步 + 添加源探测），但「清单读到了、抽查失败」那一支要 PC 侧处于半写状态才造得出来，
  本机造不出；21 行内容列表在窄屏上的排版、命令块会不会溢出，也没人看过。

## 1.4.9：手机能反过来下命令之后，命令文案仍然只有 `RelayGuidance` 一处

这一版给「电脑端采集」那页加了四个能真打到 PC 的按钮（plan / run / 只采这一条 / regen），
按 1.4.6 立下的规矩，最容易破的就是「UI 里顺手写一句电脑端该怎么敲」。实际走向相反：新命令
**照旧只出现在 `RelayGuidance`**，而且新增了一处**去重**。

- `RelayGuidance` 多一节 `命令口`（`让手机能叫电脑现采（命令口）`）：开 `serve --host auto --port 8099
  --allow-commands`、`http://127.0.0.1:8099/pair` 看配对页、以及**反悔的那条**
  `Remove-Item harvest\state\command_token`。整份清单从五段变六段，位置在「日常刷新」之后 ——
  它不是排查，是一次性的接入动作，但也不属于「第一次接入」那条四步主线（不配也能读订阅）。
- `RelayStatus.commandAvailabilityLabel`（没配对时那行副标题）以前自己写着
  「在电脑上用 `serve --allow-commands` 起服务」，现在改成**指路**：「下面『让手机能叫电脑现采』那一节
  写着怎么开、怎么扫码」。命令字面量在一个仓库里出现两次，就一定有哪天只改一处 —— 这版把它收回到
  一处，`RelayGuidanceTest` 里加了一条 `contains(section.title.take(9))` 把那句指路和那节标题钉在一起，
  改标题会红。
- **所有「重新起 serve」的行都补了 `--allow-commands`**（`triage`、`SERVICE_DOWN`、`UNREACHABLE`、
  `DIFFERENT_NETWORK` 四处）。这不是排版洁癖：那些「重启一次试试」是用户真会抄的，漏了开关就等于
  一次排查换来一次倒退 —— 重启之后手机上那四个按钮全变回「没配对」，而那看起来像 App 坏了。
  `RelayGuidanceTest` 新增一条：凡是含 `-m harvest serve` 的行必须带这个开关。

界面新加的那些句子（四个动作的标题、在跑时的「已经让它开始了」、回包结论）走的是另一套分层：
判定与文案在 `RelayCommands` / `RelayStatus.commandReplyLabel` / `statusLabel`，
`RelayScreen` 只摆。命令口的回包是**电脑说的话**，不是提示层的翻译，所以不进 `RelayGuidance`。

### 验证情况（1.4.9）

- `:app:testDebugUnitTest` **436 项 0 失败**（1.4.8 是 388）。`RelayGuidanceTest` 16 → 18 项：
  段数 5 → 6、带症状时 6 → 7 节，加「命令口一节给出开关扫码与退路」和「每一行重启 serve 都带着命令口开关」。
  `RelayStatusTest` 里 `commandAvailabilityLabel` 的三条形状照旧逐字钉住，只改了没配对那一条。
- `:app:assembleDebug` + `adb install -r`：versionCode **16** / **1.4.9**，冷启动 crash buffer 空。
  包内字符串：`让手机能叫电脑现采`、`--allow-commands`、`command_token`、`127.0.0.1:8099/pair`、
  `这台电脑只出静态 RSS，命令口没开` 全在 `classes6.dex`。
- 真机偏好读出来是 `relay_base_url=http://192.168.1.20:8099` 而**没有** `relay_command_token`
  （`shared_prefs/myfeed_settings.xml`）—— 也就是配对还没做，这一节当前会显示成「命令口没开」那副样子。
- **仍没人眼看过**：六段清单在窄屏的排版、四个按钮按下去的转圈与结论行、选源对话框（21 行 +
  `verticalScroll`）。按约定不做屏幕自动化，要他手动看一眼。
