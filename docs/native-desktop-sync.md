# 电脑端内容与已读收归原生那一条流（1.5.5 / versionCode 22）

1.5.3、1.5.4 把 LLM Feed 做成并列的一个 tab，读的东西和已读的记录各存两份：原生 `read_state.bin` 一份，
WebView 里 IndexedDB 一份；同一个帖子在 tab 里读过了，回到订阅列表它还是未读。用户的要求是
「有机结合而不是做个页面单独分离出来」，这一版把归属权改过来：**内容和已读只有一份，在原生这边**；
WebView 只留生产侧（生成任务、提示词、角色、来源、用量），入口从并列 tab 降级为抽屉里的一行。

## 谁负责什么

| | 原生（Kotlin） | WebView（llm-ui） |
| --- | --- | --- |
| 阅读列表、分类 chips、已读 | 唯一真相 | 手机壳里不再提供这几个入口 |
| 生成任务、提示词、角色、来源、用量、连接 | 不实现 | 唯一真相 |
| 电脑地址 + 访问令牌 | 存在原生偏好文件，注入给网页 | 只读写注入的那份，`connection` 动作回写原生 |
| 同步游标 / outbox | 原生自己一套（`paf_desktop.bin`、`paf_outbox.bin`） | 网页自己一套（IndexedDB `paf`），两边互不读 |

令牌这一格是补的真实缺口：改动前全仓库没有任何输入令牌的 UI，`settings.apiToken` 在 llm-ui 里只读不写；
服务端一旦开鉴权，手机端就没有第二条路可走。现在原生设置页有「电脑地址与令牌」，WebView 那个连接面板
也有令牌框（`type="password"`，且**不回显**旧令牌）。

## 原生同步引擎

四个新文件，分层照 `llm-ui/src/services/sync.ts` 来，因为服务端协议就是按那份写的：

- `data/DesktopFeed.kt` —— 纯函数：解析、映射、并表、错误码。**不含网络**，所以拿真回包字节就能在 JVM 单测里打。
- `data/DesktopSyncClient.kt` —— 三条路由的网络壳（`/sync/snapshot`、`/sync/changes`、`/sync/operations`）。
- `data/DesktopOutbox.kt` —— 「读过但还没上报」的磁盘队列，`op_id` 幂等键持久化。
- `data/DesktopSync.kt` —— 编排：先 push 后 pull、分页、410 回落全量、退避。

三条节拍是从电脑端抄过来且不能改的：

1. **先 push 后 pull**。反过来的话刚拉回来的服务端阅读状态会被自己攒着那条「读过」顶掉。
2. **410 只换全量快照，绝不动 outbox**。游标过期是「记录太旧」，不是「本地操作作废」；清队列等于把用户读过这件事悄悄丢掉。
3. **裁决分三类**：`applied`/`conflict` 出队（后者服务端已有更高明的判断，重试也不会赢），其它错误码标失败留档，
   没回裁决的原地不动等下次。

`run()` 永不抛出：电脑端关机是常态，订阅刷新不能跟着红。失败就回缓存那一份，退避 5 秒起翻倍、封顶 5 分钟，
原生设置页那个「现在同步」用 `force=true` 打断退避。

内容进原生列表走一条**不抓 RSS 的虚拟源**（`FeedSources.DESKTOP_ID = "paf"`，分类「AI 内容」），
而不是另开一个「AI 区」：这样它自动继承订阅开关、分类筛选、缓存与保留策略、以及同一个已读 id。
文章 id 是 `paf:<帖子 id>` —— RSS 那边的 id 也带源前缀，两条流撞同一个裸 id 的唯一后果是已读串到别的内容上。

兴趣门槛对电脑端帖子放行：`remotePostId != null` 直接进 `visible`，不看 `matchedInterests`。
1.5.2 那次翻车就是电脑端文章被 RSS 兴趣匹配滤掉，用户看到的是「生成了但手机上没有」。

## 真回包教我的五件事

`DesktopFeedContractTest` 的夹具出自 2026-10-22 本机 `apps/server`（v0.6.0）`/sync/snapshot` 与
`/sync/changes` 的实际回包：**字段名、嵌套形状、条数、`kind`、`demo`、`read_progress` 和那三种混着用
的时间字符串都是真回包原样**，帖子 id / 标题 / 正文 / 来源地址 / 主题换成了合成值 —— 仓库是 PUBLIC，
阅读记录本身不进公开历史。对着真形状跑，第一天就出五个问题：

1. **微秒被当毫秒累加**。`SimpleDateFormat` 的 `SSS` 是数字字段不是三位小数，`.986983` 读成 986983 毫秒
   —— 实测快照 345 条帖子里 **216 条带 6 位小数**。已修 `DateParser`：小数秒先截到 3 位再解析。
2. **`published_at` 有 89% 是 null**（345 条里 306 条），所以「退回 `created_at`」不是兜底分支而是主路径。
   这一行没写对，症状是整列内容排到错误的时间上。
3. **服务端时间有三种写法**：`PostOut` 的校验器补了 `Z`，变更页 payload 带 `+00:00`，
   `last_read_at` 什么都不带（裸的 UTC 墙钟）。裸串不补 `Z` 就按本机时区解释 —— 本机 +08:00，
   每条读记录偏 8 小时，而已读是按较晚时刻合并的，症状是「电脑端读过的东西手机上还是未读」。
   `DesktopFeed.utcText()` 统一补。
4. **已读记录的时间戳不能塞哨兵**。`ReadStateStore.prune` 丢 `it > 0` 之外的键，所以「服务端没给时刻」时
   填 0 或 1 会在第一次写盘时被当孤儿键清掉 —— 等于这条已读永久丢失。现在 `readMarks(states, now)`
   拿本轮时间兜底。
5. **真数据里就有 `read_progress=0` 且 `last_read_at=null` 的状态行**（14 条里 1 条）。原来那个哨兵
   会让它被判成已读，把用户没看的东西压暗。

还有一个是门自己报的：`FeedSourcesTest` 那条「源地址都是 https」被虚拟源的空地址判失败。
门没错，是它没考虑不发请求的源 —— 改成只约束可抓取的源，并反过来断言虚拟源**不该**有地址。

## 状态栏遮挡（1.5.4 遗留的那个 bug）

「点上去都跑到状态栏去了，根本点不着」的根因：`targetSdk 35` 在 Android 15 上强制 edge-to-edge，
内容画到系统状态栏底下，而可点控件正好压在窗口最上面那一截 —— 用户点的是状态栏，不是按钮。

改法是可点区域一律避让，衬底照常铺满：

- 那个手写的 `TabRow`（122px 状态栏 vs 132px 高的 tab 行）整块删掉，生产侧顶部换成 Material3 `TopAppBar`，
  它默认就带 `windowInsets`。
- 抽屉头部原来写死 `top = 28.dp`：3x 设备上状态栏 122px ≈ 61dp，标题和头几行照样被盖。改成
  `windowInsetsPadding(WindowInsets.statusBars)` + 16.dp，不再靠猜一个数。
- 扫一扫页底部按钮列补 `windowInsetsPadding(WindowInsets.navigationBars)`。
- 阅读侧每个页面本来就是 `Scaffold + TopAppBar`，M3 自己处理，不需要手工 padding。

## 手机壳里的 llm-ui

`window.__YUELIU_HOST__` 多带一个 `manage: true`（由原生注入），llm-ui 据此：

- `/`、`/discover`、`/saved`、`/post/:id` 全部渲染生成页；
- 左侧栏与手机端底部导航不再列这几个阅读入口（点了只会撞回生成页）；
- 右栏「今日库存」「在追的主题」这两块阅读侧信息隐藏 —— 手机上的未读数在原生那边，网页这份是过期的；
- 网页直开（电脑浏览器）时以上全部不变，仍是完整应用。

WebView 常驻不销毁，否则正在编辑的提示词草稿和未提交的事务会丢。

## 验证到哪一步

- `:app:testDebugUnitTest` 全绿（473 颗）。新钉的：`DesktopFeedContractTest` 13 颗（真字节）、
  `DesktopOutboxTest` 6 颗（幂等、去重、老化上限、半截文件、退避翻倍）、`DateParserTest` 微秒那颗。
- `:app:compileDebugKotlin`、`tsc -b` 过。注意出包会连带跑 `installLlmUi` + `buildLlmUi`，
  TS 类型错误直接让 Gradle 失败。
- 契约字段名逐个比对过服务端 `apps/server/app/schemas_sync.py` 与 `schemas.PostOut`。
- **本机 `apps/server` 实测可读**：`/health` 返回 `auth_required=false`，`/sync/snapshot` 389KB、
  `latest_seq=632`、345 条帖子（292 条 demo）、14 条状态；`/sync/changes?since_seq=600` 32 条。
  上面那些「实测 N 条」全部是这几条命令数出来的。
- `llm-ui` 侧 198 颗 vitest 全绿（24 个文件），`tsc -b` 干净。
- 发布数字与产物指纹记在 `docs/build-verification-2026-10-22.json`（1.5.5 / versionCode 22）。
- **没做的**（这三条得等装机之后补，代码层结论不等于实测）：
  真机端到端（手机 → 电脑一轮 push+pull 的实际裁决、410 之后退回快照时队列有没有保住）、
  装机后抽屉首个可点控件的 bounds 与状态栏高度对照、manage 模式下 WebView 实际渲染成什么样。
  1.5.5 出包当晚装机被 MIUI 挡下：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，
  这是手机上「USB 安装」那个授权开关，得人在旁边点一次。
