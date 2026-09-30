# crawlbase × myfeed：硬源采集接入

日期：2026-09-25（当天先调研、后落地）
状态：**App 侧与 PC 侧自动化都已完成**；PC 只剩「人工登录一次 + 起服务」两步（见下）

## 一句话

`github.com/PT123123/crawlbase` 的 `harvest/` 子系统就是原本设想的那台 relay：
真浏览器（第 2 档）+ 持久登录态采集知乎/小红书/X → 吐标准 RSS + OPML + 一个静态
HTTP 服务。所以**不用自己写 relay**，App 侧只需要「填 PC 地址 → 从 URL 同步订阅」。

## 背景：为什么需要它

myfeed 的中文内容供给是老大难：好内容长在公众号 / 知乎 / 小红书，它们没有官方 RSS，
RSSHub 路由要么要实例侧配 cookie、要么已被限流（知乎热榜、B站热搜的公共镜像只剩 1 条）。

crawlbase 是五档抓取能力栈（1 SendInput 真 OS 输入 → 2 真浏览器 CDP → 3 patchright 伪装
→ 4 Playwright → 5 curl_cffi 协议级），统一入口 `bridge.py`，stdout 恒为一个 JSON，
退出码 0-5 语义固定（5 = 被拦截 → 升档）。`harvest/` 在它之上，把「平台适配 → 结构化条目
→ RSS」这条流水线包好了，并且**只允许走第 2 档**（配置层白名单），内置限速/每日配额/被拦熔断。

### 本机实测记录（2026-09-25，crawlbase venv）

知乎热榜 / 小红书这两类「拿不到的源」确认必须登录态：

```
第 5 档 fetch  https://www.zhihu.com/api/v3/feed/topstory/hot-lists/total?limit=10
  → 401（x-zse-96 签名 + 登录态）
第 4 档 grab   https://www.zhihu.com/hot（无头渲染）
  → 「安全验证 - 知乎」页，正文只有「请您登录后查看更多专业优质内容」
第 5 档 probe  https://www.xiaohongshu.com/explore
  → 200，suspicion.score=0 —— 协议层放行，但内容 API 要 x-s / x-s-common 签名
```

X（推特）2023 起全站强制登录，同上。这三家正是 harvest 已经写好的三个平台适配器。

后来为了「能不能连登录这一步都自动化」，用第 2 档 `profile_mode=clone`（继承日常 Edge 的
profile）实测过一次，结论是**不能**：

```
clone 启动         → 把日常 Edge profile 的 3.3 万个文件复制进 .state/tier2/profile-edge（实测约 1GB），
                     第一次起要几分钟（bridge 客户端 90s 超时报 TIMEOUT，浏览器其实起来了）
知乎首页           → 302 到 /signin；document.cookie 只有 d_c0（设备号），没有 z_c0
/api/v4/me         → 401 {"error":{"code":100,"name":"AuthenticationInvalidRequest"}}
```

也就是这台机器的 Edge 从没登过知乎，clone 继承不到不存在的登录态。所以人工那一次
`harvest login` 保留（顺带说：自动登录属于风控大忌，本来也不该做），但**登录之后的所有
配置动作已经不用人手了** —— 见下一节。这两份真实响应体固化成了 harvest 的测试夹具
（`tests/fixtures/zhihu_me_anon.json`）。

### 顺带两例：判死的源值得用第 5 档重测

`dedicated.wallstreetcn.com/rss.xml` 用第 5 档拉回 **200 + 完整 RSS XML（588KB）**，连无
指纹伪装的普通 `requests` 引擎都能过 —— [Models.kt](../app/src/main/java/com/example/feedreader/data/Models.kt)
里「中文财经源全军覆没」的结论对华尔街见闻不成立，当时多半是试错 URL 而非被反爬。
判死的源（BioPharma Dive、Medical Xpress 等 Cloudflare 403）值得批量重测。

Bing News RSS 依旧死：`format=rss` 实测 302 → cn.bing.com 首页 HTML。crawlbase 解决
「怎么抓」，不解决「去哪搜」，中文免 key 网页搜索的缺口不变。

## 架构

```
手机 App ──HTTP──> harvest serve（PC，默认 127.0.0.1:8099）
                       · out/<feed-id>.xml   每个订阅一份 RSS
                       · out/feeds.opml      订阅清单
                       · 采集走 bridge 第 2 档 persistent 登录态
                       · 限速/熔断在 harvest/state/<feed>.json，跨进程生效
```

App 侧**不加任何通道类型**：relay 吐的就是 RSS，走现有的解析器、磁盘缓存、失败横幅、
开关机制、`MAX_BYTES` 全套。

## App 侧已做的改动（2026-09-25）

| 文件 | 改动 |
| --- | --- |
| `data/RelayRules.kt` | 新增。地址规整 + OPML 里的 feed 地址**重基**到用户填的 PC 地址 |
| `data/SourceStore.kt` | 新增 `syncRelaySources`：按 feed 文件名对齐，改地址而不是堆副本 |
| `data/SettingsStore.kt` | 新增 `relayBaseUrl` |
| `ui/FeedViewModel.kt` | 新增 `syncFromRelay()`：拉 `feeds.opml` → 解析 → 重基 → 落库 |
| `ui/SettingsScreen.kt` | 「从 crawlbase relay 同步」入口 + 地址输入对话框 |

### 为什么要在 App 侧改写 xmlUrl

harvest 导出 OPML 时基地址写死 `http://127.0.0.1:8099`（它只知道自己本机端口）。手机上的
127.0.0.1 是手机自己，照原样导入就是一堆死链，而且报错信息是「域名解析失败」这种指不到
问题上的话。

修在 App 侧不修 harvest：**只有 App 知道自己这侧能连通的地址**。同一份 `out/` 还要给 PC
本机浏览器用（那个 127.0.0.1 是对的），OPML 里写 127.0.0.1 不是 bug，写死任何别的东西才是。

### 为什么同步按文件名对齐而不是按整条地址

`importSources` 按地址判重，而 relay 的地址里带着 PC 的 IP —— 家里 DHCP 一换地址，再同步
一次就是「新增 N 条 + 老 N 条永远失败」。`<feed-id>.xml` 在 harvest 那边就是 feed 的 id，
换 IP 不变，所以拿它当身份：同名就只改地址（保留用户那份名字），新增才加条目。

### 跨仓库契约已钉住

`RssParserTest` 里那条 `吃得下 crawlbase harvest 产出的 RSS` 用的是 harvest 渲染器
（`harvest/formats/rss.py`）真实跑出来的字节。要点是 `<atom:link rel="self">`：它长得像
link，指的却是这份 feed 自己的地址 —— 一旦被当成条目链接，整个源每条文章都会指向同一个
XML 文件，界面上完全看不出来是解析错了。

## PC 侧（crawlbase 仓库）这一竖切改了什么

配置自动化做在了 harvest 里，App 侧一行没动。

| 文件 | 改动 |
| --- | --- |
| `harvest/discover.py` | 新增。`feed_id_for` / `person_to_feed` / `merge_feeds` / `discover` / `apply_to_config` 全是纯函数或只依赖注入的 bridge，离线可测 |
| `harvest/platforms/zhihu.py` | 新增 `whoami()`（`/api/v4/me`）与 `followees()`（`/api/v4/members/<token>/followees`）+ 两个纯解析函数；`fetch` 与它们共用同源的导航/取数骨架 |
| `harvest/cli.py` | 新增 `discover` 子命令；`--apply` 落盘后立即重出 RSS 占位文件与 OPML |
| `harvest/runner.py` | `feeds.opml` 只列 **enabled 且已有产物文件** 的 feed |
| `harvest/tests/` | 21 项新增离线测试（含整条 CLI 路径的信封与退出码），共 73 项，零网络 |

合并规则是幂等的：feed id 由 `平台 + target.id` 推导（`zhihu-<url_token>`），同一个人反复
discover 只会更新那一行，不会越跑越多份；用户自己改过的 `title` 保留，用户明确
`enabled: false` 过的订阅不再被擅自打开。写盘前先用真正的校验器 `load_config` 读一遍临时
文件，配置不合法就拒绝落盘。

`feeds.opml` 那条改动直接服务 App 导入：以前关掉的示例 feed 也会进 OPML，而它那份
`<id>.xml` 从来没人采过、根本不存在，导进来就是一条永久 404。

## PC 侧一次性操作（2026-09-27 已全部跑通，留此作复现清单）

crawlbase 在 `<crawlbase 目录>`，已 clone、`.venv` 就绪、与 origin 同步。

```powershell
cd <crawlbase 目录>
$PY = ".\.venv\Scripts\python.exe"

# 1) 知乎登录一次（弹真浏览器到登录页，你手动登，程序每 10 秒查一次 cookie）
& $PY -m harvest login --feed zhihu-example

# 2) 自动发现订阅：读「我是谁 + 我关注了谁」，直接写进 feeds.json，不用手抄 url_token
& $PY -m harvest discover --apply          # 去掉 --apply 只打印候选，不落盘
& $PY -m harvest list          # 配置对不对，这里能看出来

# 3) 验证登录态（只读 cookie，不访问内容页）
& $PY -m harvest check

# 4) 首轮采集（每个 feed 一次站内调用；知乎走 /api/v4/members/<token>/answers）
& $PY -m harvest run

# 5) 让手机能连上：监听局域网地址（不要 0.0.0.0，收窄到 PC 自己那个内网 IP）
& $PY -m harvest serve --host 192.168.x.x --port 8099
#    2026-09-27 之后也可以 `serve --host auto`：基址自动探本机局域网 IP，stderr 会打印
#    subscribe.html 的地址 —— 但 auto 实际绑的是 0.0.0.0，比上面那行宽，自己权衡。
```

然后在 App：兴趣与设置 → 我添加的源 → **从 crawlbase relay 同步** → 填 `192.168.x.x`
（不带端口按 8099 算）。那边加账号、这边换 IP，都是再点一次同一个按钮。
现在多一条更省事的路：手机浏览器开 `http://<PC>:8099/subscribe.html` 或直接扫码 —— 见文末
「2026-09-27 追加：扫码订阅」。

日常刷新用 Windows 任务计划每 4 小时跑一次 `& $PY -m harvest run` 即可（内部还有每日 4 次
上限兜底），`serve` 长驻。

## 已知代价与风险

- **登录态过期要重新登一次**（第 2 档 persistent 目录里补登）。
- **页面改版 sequence 要跟着改**。harvest 自己声明：三平台的线上解析路径**还没在登录态下
  实测过**。首轮 `run` 若报「没解析到任何回答」，人工开一次该主页核对结构，解析代码在
  `harvest/platforms/zhihu.py` 顶部（JS 与 Python 各一段）。
- **小红书风控狠**：relay 缓存 + 低频刷新是硬要求。当日被拦 ≥2 次会**停机**，必须人工
  `reset --feed <id>` 才会再跑 —— 这是故意的，`--force` 豁免不了。
- **暴露面**：`serve` 是只读静态目录，没有任何鉴权。只绑内网 IP、只在家里那套 Wi-Fi 用；
  公网/访客网络下别开。桥接的调试端口 harvest 自己只监听 127.0.0.1，不要改。
  遵守目标站点 ToS 与频率红线，`limits.step_delay_s`（3-8 秒随机停顿）保留。
- **`--host` 改动要过 Windows 防火墙**：入站规则需要管理员授权，属用户决定，不代做。

## 后续

1. **知乎热榜不在 harvest 里** —— 它的知乎适配器是「某人的回答流」，热榜要在
   `harvest/platforms/` 新写一个适配器（照 `zhihu.py` 的路子，第 2 档 sequence）。
   `FeedSources.DEFAULT` 里那个 `zhihuhot`（RSSHub 镜像，只剩 1 条）可以等这个替掉。
2. 用第 5 档批量重测 [Models.kt](../app/src/main/java/com/example/feedreader/data/Models.kt)
   判死的源，能救的补进 `FeedSources.DEFAULT`（记得 `DEFAULTS_VERSION` 加一）。
3. X 平台：本机匿名直连超时，依赖系统代理在线；要做就先 `harvest login --feed <id>` 验一把。
4. `discover` 目前只有知乎实现了钩子（`whoami` / `followees`）。小红书与 X 想要同样的
   「零手填」，就在各自适配器里补这两个方法，`discover.py` 一行不用改 —— 但先确认站内
   接口形状再写，别照抄知乎。
5. `login` 仍然按 feed 走（`--feed zhihu-example` 用的是仓库自带示例那条的 id）。要是想
   `login --platform zhihu` 一步到位，得给 `Runner.login` 加平台入口 —— 现在的示例 feed
   恰好能当这个入口，就没动。

---

## 2026-09-27 追加：扫码订阅（二维码 → go.html → `myfeed://`）

日期：2026-09-27
状态：**两侧代码与离线测试完成**；真机扫码还没验（要先有一次成功的 `harvest login` + `run` 才有就绪的源可扫）

### 多了什么

PC 侧（crawlbase `harvest/`）`run`/`regen` 之后 `out/` 里多三张**静态**手机页：

| 文件 | 作用 |
| --- | --- |
| `subscribe.html` | 二维码墙：整包同步一个码 + 每个**就绪**源一个码 + 采集状态/健康度表 |
| `go.html?f=<id>` | 扫码后的桥接页；`f=__relay__` 或不传 `f` = 整包 relay 同步 |
| `reader.html` | 不装 App 也能看：RSS 条目列成网页 |

App 侧接两条深链：

```
myfeed://relay-sync?base=http%3A%2F%2F192.168.1.20%3A8099     → viewModel.syncFromRelay(base)
myfeed://subscribe?url=http%3A%2F%2F…%2Fzhihu-x.xml&title=…   → viewModel.addSource(url, title, "自建")
```

| 文件 | 改动 |
| --- | --- |
| `data/DeepLink.kt` | 新增。纯字符串解析（JDK `URLDecoder`），进单测；解析失败一律 `null` 不抛 |
| `MainActivity.kt` | `singleTask` + `onNewIntent` 取深链；一次性的东西放 activity 的 `mutableStateOf`，不进 ViewModel |
| `AndroidManifest.xml` | 加 `VIEW`/`DEFAULT`/`BROWSABLE` + `scheme="myfeed"`；`launchMode="singleTask"` |
| `FeedReaderApp.kt` | 深链确认对话框 → 执行 → 「知道了」；渲染位置在 `opened?.let{…}` **之前**，所以在任何页面上都能盖住 |

### 为什么二维码里编的是 http 地址

系统相机和各家浏览器对自定义协议的处理不一致，有的直接拒收 `myfeed://`。所以码里放
`http://<pc>:8099/go.html?f=<id>`，页面再给一个按钮跳深链 —— Chrome 要求自定义协议跳转
**必须有用户手势**，这也顺带满足了「用户确实想打开 App」。

### 为什么要确认对话框，不直接同步

深链的来源是**另一台机器生成并广播**的二维码。落库要写网络、加订阅，所以先看一眼要干什么、
点一下才动。同理，网络操作跑完后不自动关闭，结果只在界面上说清楚。

### OPML 契约变了：harvest 现在写**相对** `xmlUrl`

原来 harvest 导出 OPML 时基址写死 `http://127.0.0.1:8099`（见上文那节）。现在改成
`xmlUrl="zhihu-x.xml"`（相对，不带任何主机），因为：

- relay 的 `base_url` 随本机 DHCP 变，把它写死进 OPML 才是真 bug；
- 本节的整包二维码走 `relay-sync`，App 仍然按用户填/扫到的基址重基（`RelayRules.rebaseUrl`
  的相对写法分支），行为与手动填地址完全一致；
- PC 本机浏览器导入同一份 OPML 也对 —— 相对地址按 OPML 自身 URL 解析。

`base_url` 仍然驱动 RSS 的 `atom:link`、`reader.html` 的链接和二维码内容，只是不再进 OPML。

### 就绪才出码

`enabled` 为 true 且 `out/` 里真有对应 XML 文件的 feed 才有二维码；未就绪的只进健康度表。
理由：那个 `<id>.xml` 从来没人采过、根本不存在，扫进去是个 404，比扫不到更糟 —— 与
`feeds.opml` 只列就绪 feed 是同一条判断（`ready`）。

### 验证情况（诚实）

- App 侧：`DeepLinkTest` 10 项 + 原有 `RelayRulesTest` 全过，`assembleDebug` 成功，
  合并后的 manifest 里有 `myfeed` scheme。
- PC 侧：`pytest harvest/tests -q` 89 项离线（含三张页的字节断言与 relative-OPML 断言）。
- **没做**：真机装 APK + 相机扫码。这一步要等你在本机跑通 `login`/`run`（见上一节待办）。
- `serve --host auto` 会真绑 `0.0.0.0`，第一次要过 Windows 防火墙入站规则（管理员授权，
  由你决定）；只在家里那套 Wi-Fi 用。

---

## 2026-09-27 追加二：应用内扫一扫 + 首次真机联调

日期：2026-09-27。版本：`versionCode 4 / versionName 1.3`。

### 为什么要加应用内扫码

上面那套「系统相机扫 → `go.html` → `myfeed://`」依赖相机 App 认自定义协议，各家表现不一，
而且用户明确要求「App 里能看到一个扫一扫」。所以补一条**不经过系统浏览器**的通路：设置页 →
「我添加的源」→ **扫一扫订阅二维码**，直接开相机识别二维码里的文本。两条路殊途同归——
识别出的文本喂回同一个 `DeepLink` 确认框，落库逻辑完全复用。

### 分工：识别在 UI，判定在纯函数

- `data/ScanRules.kt`（新增）：扫码文本 → `DeepLink` 或一个带中文原因的 `Rejected`。
  认识四种码：`myfeed://…`（原样给 `DeepLink.parse`）、`go.html?f=<id>`（`__relay__`/缺省
  = relay 同步，带 id = 单订阅 `<base>/<id>.xml`）、`feeds.opml`（relay 同步）、普通 RSS 地址
  （单订阅）。纯字符串逻辑，无 Android 依赖，`ScanRulesTest` 13 项钉死。
- `ui/ScanScreen.kt`（新增）：CameraX + ML Kit **bundled**（`com.google.mlkit:barcode-scanning`，
  模型打进 APK，不依赖 Google Play 服务——小米这类没 GMS 的机器也能扫）。只开 `FORMAT_QR_CODE`。
  识别成功一次立刻置 `handled` 停分析器，避免同帧多次命中；拒掉的码（回环地址、畸形 id）不退出，
  把原因显示在原界面让用户接着扫。**没有单测**：它只做「镜头 → 一段文本」，判定全在上面那层。

### 安全边界

`f=<id>` 会被拼进订阅地址，而二维码来自**另一台机器**，所以 id 只接受 harvest 真会产出的字符集
（字母数字与 `-_ .`），挡掉 `../`、`/`、`?`、`#`、空格、引号和超长值；畸形 id 直接 `Rejected`
而不是悄悄拼个坏地址。回环地址（`127.0.0.1` 等）在 App 侧拦下并说明「手机上打开等于连手机自己，
PC 改用 serve --host auto」—— 不拦的话用户只会看到一个指不到问题上的「连不上」。

### 首次真机联调（本机实测）

crawlbase 侧这次真的跑通了，纠正上文「PC 待办」里的假设：

- `harvest login` 成功，知乎 `z_c0` 到位（一次性人工步骤，之后全自动）。
- `harvest discover --apply` 从「我是谁 + 我关注了谁」自动写入 **21 条**订阅（`self=exampleone` + 20 关注）。
- 首轮 `harvest run` 全 21 条报 `TypeError: not enough arguments for format string` —— 这是
  crawlbase 的 bug（`zhihu.py` 的 `_FETCH_JS` 里 `%5B/%2C` 是 URL 编码字面量，用 `%` 格式化却漏了
  转义），与风控无关，已修并加 2 项回归测试；修后重采 **20/21 成功、共 477 条**。
- `serve --host 192.168.1.20 --port 8099`，平板（同 WiFi，`curl`/`nc` 可用）实测 TCP 通、
  `feeds.opml` 200。订阅页 `subscribe.html` 出 **22 个二维码**（21 源 + 1 整包）。

### 仍未闭环（诚实）

- **相机实拍扫码没验**：我能装 APK、发 `myfeed://` 深链、用 `run-as` 读落库，但没法替你把镜头
  对准另一个屏幕。`ScanScreen` 的真机识别需要你亲扫一次。
- **上一轮 relay 同步疑似没落库**：你点了确认后 `myfeed_settings.xml` 里既无 `relay_base_url`
  也无自定义源。OPML 内容本身是干净的相对地址，App 侧 `syncFromRelay` 逻辑单测也全过，
  所以问题更可能在「深链 query 被 shell 改写」或「确认框的同步请求被打断」，**尚未定位**。
  用新加的应用内扫一扫点「整包」那个码，是复现并排查这条的最快路径。

---

## 2026-09-27 追加三：真扫之后暴露的两个缺陷（1.3.1）

你扫了整包码，回来两句话：「显示这份 OPML 里没有可用订阅源」+「有闪退」。两条都根因明确，
且都**不是**扫码引入的。

### 一、相对 xmlUrl 在解析器里就被丢光

`OpmlParser.parse` 的准入是 `SourceRules.looksLikeUrl(url)` —— 只认 `http(s)://`。harvest 的
清单写的是 `xmlUrl="zhihu-exampleone.xml"`（相对），于是 21 条在 `parse()` 内部全部被当坏数据
丢掉，`syncFromRelay` 里那句 `rebaseSources` 拿到的是空表，最终 `sources.isEmpty()` 直接
`Failed`。

这条同时**解释掉**了上面「仍未闭环」的第二条疑问：`settings.relayBaseUrl = base` 写在空表
判断**之后**，所以同步一失败基地址就不会落库，`myfeed_settings.xml` 才会既无 `relay_base_url`
又无自定义源 —— 和深链、shell 改写都无关，那条链路在更早的一步就断了。

修法：`parse()` 加 `baseUrl: String? = null`，非空时每条地址先过 `RelayRules.rebaseUrl` 再校验；
`syncFromRelay` 传 `base`，文件导入路径不传（保持原有的严格行为：本地文件里的相对地址确实
不是可订阅的地址）。

这颗钉子原先没有：harvest 把 `xmlUrl` 从绝对改成相对时只有 PC 侧的测试跟着改，App 侧静默
降级。现在补的是**跨仓库契约测试** `RelayContractTest`，夹具 `/relay/feeds.opml` 是当天
`serve` 实际吐出的字节（md5 `3a2e554c…`，与 `harvest/out/feeds.opml` 以及线上 `curl` 回来的
3837 字节一致），断言 21 条全部落地、大小写不被动（`zhihu-Upper_Case_Gains.xml`）、换 IP 后
按文件名仍是同一批 feed。里面留了一条 control：同一份夹具**不传** baseUrl 时必须剩 0 ——
那正是故障现场，也挡住以后有人把 `baseUrl` 当冗余参数删掉。

### 二、设置页 LazyColumn 重复 key 闪退

`logcat` 两次抓到同一句：`Key "perplexity" was already used`。根因是 `SettingsScreen` 一个
LazyColumn 里五个 `items(...)` 全用裸 `it.id` 当 key，而 `perplexity` 既是召回通道 id 又是
搜索 key provider id。同类撞车不止这一处：兴趣的 id 直接是**归一化后的小写关键词**
（`Interests.normalizeId`），加一个兴趣叫 `csdn` / `v2ex` / `arxiv` 就会和同名通道撞上。

修法：五个 key 各加一段命名空间（`custom-` / `builtin-` / `interest-` / `channel-` / `apikey-`）。
这是接四家搜索 API 那版带进来的老问题，与扫码无关。旧包里 `apikey-` 字面量出现 0 次、新包
13 次，说明设备上真正跑过的确实是没命名空间的 key 逻辑。

### 验证情况

- `:app:testDebugUnitTest` **294 项 0 失败**（新增 8：OpmlParser 3 + RelayContract 5），
  `:app:assembleDebug` 成功；APK 升到 versionCode 5 / versionName 1.3.1 并 `adb install -r`
  进平板（`dumpsys package` 确认 `versionName=1.3.1`，数据保留）。
- relay 服务仍在 `192.168.1.20:8099`，`feeds.opml` 实测 200 / 3837 字节。
- **要你动手的两件事**：① 打开设置页、滑过「召回通道」和「AI / 全网搜索」两节，看还闪不闪；
  ② 重扫一次整包码。扫完我直接用 `run-as` 读 `myfeed_settings.xml` 和 `files/feed_cache`，
  落没落库不靠界面文案判断。
- 上面「294 项」是修完扫码两处时的数；同一天接着又落了两片（全文离线读、失败横幅聚合），
  现在这条线是 **319 项 0 失败**，见下一节。

---

## 2026-09-27 追加四：源进来之后暴露的两处「看得见但读不动」

21 条源能同步进来之后，问题就从「连不上」变成了「内容在手机上却读不到」。

### 一、知乎条目点进去是登录墙（详见 [offline-full-text.md](offline-full-text.md)）

harvest 把整条回答写在 `content:encoded` 里，而 `RssParser` 只留 280 字的 `excerpt` ——
全文在解析这一步就被扔了，于是点开只能去加载原页，而知乎 / 小红书 / X 的原页在应用内
WebView 里是登录墙（cookie 在应用自己的沙箱里，拿不到别处的登录态，这条是当初的实测结论）。

改了四处：`Article.body` 存全文（上限 6000 字，是内存问题不是排版问题）、
`TextCleaner.body()` 压纯文本但**保留分段**、`RssParser` 填这个字段、
`ReaderRouting.readOffline()` 决定「本地全文页」还是「加载原页」。栏上留了「看原页」，
全文为空时绝不离线渲染（对照测试在 `ReaderRoutingTest`）。

### 二、relay 那台 PC 一关机就是 21 行失败

`FeedRepository.fetchAll` 原本一个源一行，21 条同源（一台 PC）的源失败时把「那台机器不在
线」埋进刷屏。改成按 **主机 + 失败原因** 收成一行：`知乎 · 源1 等 21 个源（192.168.1.20:8099）· 连接超时`。
只按原因合并不行 —— 知乎本机和 openai 都超时是两件不相干的事，混成一条就不知道该去开哪一头。

### 验证情况

- `:app:testDebugUnitTest` **319 项 0 失败**（这两片新增 25：TextCleaner 5 / RssParser 3 /
  FeedCache 2 / ReaderRouting 8 / FeedRepository 5 / 重复 key 那两条 2），
  `:app:assembleDebug` 成功，APK versionCode 6→7 装到平板。
- **真机数据核验**：冷启动后应用自己拉了一轮，用 `run-as` + base64（`exec-out` 会把字节按
  行结束符改写，必须先 base64）取回 `files/feed_cache` 逐字段解出来 —— 25 个文件**全是 v2**
  （v1 的 10 个确实被删掉了）、447 篇里 215 篇正文显著长于摘要、正文字符合计 474,147
  （常驻约 0.9 MB）、分段 5,389 段、最长一条 6,001 字（撞上上限 + 省略号）。
- **仍然没人眼看过**：全文页的排版、窄屏上「看原页」按钮会不会把标题挤没，以及重扫整包码
  是否真把 21 条源落库 —— 这三件都要等你回来。




---

## 2026-09-27 追加五：21 条源进来之后，「信息流」缺的是已读记录

源同步成功之后暴露出来的下一层问题不是采集，是消费：一次刷新六百多条卡片，读过的和没读过的
长得一样，下拉刷新还会原样再来一遍。全仓 `grep` 过一遍，`已读|unread|isRead` 命中 0 处 ——
这功能从一开始就没有。

单独记一片，细节和取舍在 `docs/read-state.md`。这里只写和 relay 这条链相关的两点：

- 已读记的键是 `Article.id`（源 id + `guid → link → title`），所以**换 relay 地址、重新同步
  都不会把已读洗掉** —— 源的身份本来就设计成跟着文件名走（见 `RelayRules.endpointOf`）。
- 那台 PC 关机时首页会一直铺着同一批缓存条目，这正是「读过还在最前面」最难看的场景；
  失败横幅收成一行（追加四）解决的是刷屏，这条解决的是重复。

### 验证情况（追加）

- `:app:testDebugUnitTest` **332 项 0 失败**（新增 `ReadStateStoreTest` 13 项），
  APK 升到 versionCode 9 / versionName 1.4.2 并 `adb install -r` 进平板。
- 设备侧用「本机按同一格式造 `read_state.bin` → 推给应用读」做了字节级对账：真 id 3 条 →
  `logcat` 报 `已读记录 3 条`；换成 11 字节垃圾 → 报 0 条且文件被应用自己删掉（自愈分支实测走到）。
  造出来的文件收尾已删除，平板回到真实状态。
- **界面仍然没人看过**：标题压暗、「只看未读」chip、筛空时的新空态文案，三处都要你手动确认。

---

## 2026-09-27 追加六：全文的可搜性（细节见 `docs/offline-full-text.md` 追加）

追加四存下来的 `body` 当时只有阅读页在用，首页搜索仍然只看标题/摘要/来源 ——
21 个 relay 源一次刷新 468 篇里 271 篇带长正文（57.9%），中间提到的词一律搜不到。
1.4.3 把匹配规则抽成 `ArticleSearch`（标题→摘要→来源→正文，单字不进正文），
正文命中时卡片上标「正文里提到」。单测 332 → **343 项 0 失败**，APK versionCode 10 / versionName 1.4.3
已装平板，冷启动无崩溃。装完之后重跑了一遍已读探针（`tools/read_state_probe.py`），
那份 181 字节、3 条真实 id 的 `read_state.bin` **留在设备上没删** —— 这样不用点屏幕
就能核对「读过的该压暗」，怎么清掉见 `docs/read-state.md` 末尾。

> **更正（追加七写的时候查出来的）**：这一段里「21 个 relay 源一次刷新 468 篇」是错的。
> 468 篇全部来自内置源 —— 设备 prefs 里当时**没有** `custom_sources`，那 21 条从来没落过库
> （确认框是在解析器还把所有相对 `xmlUrl` 丢掉的那版上点的，同步进去 0 条）。
> 真正第一次落库是 1.4.4 的自动同步，见下面追加七。

---

## 2026-09-27 追加七：确认过一次之后，relay 清单该自己跟上（1.4.4）

### 为什么补这一步

扫码 / 深链那条流程是**一次性**的：点一次「确认」，把当时那份 `feeds.opml` 搬进来，之后
harvest 那边加一个账号、PC 换一个 IP，手机侧都毫无反应，得回设置页再点一次同步。
而用户对这条链的要求本来就不是这样 —— 「有多少就实时放多少」。PC 采得再勤，
只要手机不主动去拉，信息流就停在扫码那一刻。

顺带把一件真事钉住：这次做完才查出**那 21 条源从来没进过设备**（见上面追加六的更正）。
也就是说这条链在真机上一直只走到「解析成功」，没有走到「清单落库」。
少了自动同步这一步，它就永远只是演示。

### 多了什么

| 位置 | 内容 |
| --- | --- |
| `data/RelayAutoSync.kt`（新） | 两条**纯决策**：`shouldRun`（开关 / 有没有配过地址 / 距上次尝试够不够半小时）、`skipRemoved`（按 feed 文件名滤掉用户亲手删过的条目）。网络不放这儿，否则为了测一个布尔值得 mock SharedPreferences |
| `SettingsStore` | `relayAutoSync`（`relay_auto_sync`，默认**开**）、`relayLastAutoSyncAt`（`relay_last_sync_at`） |
| `SourceStore` | `relayRemovedEndpoints`（`relay_removed_endpoints`，上限 200）；`removeCustom` 删的是 relay 条目时顺手记文件名；`syncRelaySources(incoming, respectRemoved)` |
| `FeedViewModel` | `syncFromRelay` 与静默同步合并成 `syncRelay(rawAddress, auto)`；`autoSyncRelay()` 挂在 `load()` 那一轮的最前面，源变了本轮抓取就用新那份（`roundSources`）；列表为空且配过地址时先偷偷拉一次，拉不到再回落引导面板（新 `showNoContentSource()`） |
| 设置页 | 「刷新时自动同步 relay」开关，**只在配过地址之后出现** —— 没配过就没有自动同步这回事，摆出来只会让人以为它管着扫码 |

### 三条刻意的取舍

1. **节流记「尝试」不记「成功」**。PC 关机时如果不占住这半小时，每次刷新都要撞一次连接超时
   （8 秒），手感损失比「同步晚半小时」大得多。时刻在打网络**之前**就写。
2. **自动同步永不删源**，只新增和改地址；用户删过的那条靠 tombstone 挡回来。
   手动同步则先把老账一笔勾销再执行 —— 手点一次「同步」的语义就是「我要现在这份清单」。
   tombstone 认文件名而不是整条地址或源 id，因为 id 是地址派生的，PC 换 IP 之后 id 会变，
   而用户删的是「知乎那一条」。
3. **静默失败不出横幅**，但 `logcat` 必留一行（tag `FeedRelay`）。PC 不在线时首页已经有
   21 行源各自失败的聚合横幅，再加一条只会吵；而这条链改的是订阅列表，出问题必须能回答
   「这几条是谁什么时候加进来的」。

### 验证情况（全程没点过一次屏幕）

设备侧先把 `relay_base_url` 用 `run-as` 预置进 prefs —— 这就是「用户确认过一次」的磁盘状态，
然后启动应用（`am start`）让自动同步自己跑：

- 冷启动日志 `自动同步 http://192.168.1.20:8099：清单 21 条 · 新增 21 · 换地址 0`；
  prefs 里 `custom_sources` 21 条、去重 21，`relay_last_sync_at` 已写。**21 条源第一次真机落库**。
- 同一轮抓取用上了新那份源：`files/feed_cache` 本轮写入 45 个文件 / 1 671 240 字节，
  拉全量统计 = 46 个缓存文件、**888 篇**（内置 468 + relay 420）、正文超 200 字的 584 篇。
- **节流对照**：立刻 force-stop 再起一次，`FeedRelay` 一行都不出（半小时内第二次启动没拉清单）。
- **重复同步不堆副本**：把 `relay_last_sync_at` 往回拨 40 分钟再起 —— `新增 0 · 换地址 0`，
  `custom_sources` 仍是 21 条、没有第二条同名副本。
- **tombstone 对照（正反都验）**：预置 `relay_removed_endpoints = zhihu-sample-formulator.xml` 并从清单里
  删掉那条 → 自动同步报 `清单 21 条 · 新增 0`，被删的那条**没有**偷偷回来；
  清掉 tombstone 再起 → `新增 1`，回到 21 条。设备最后停在这个正常状态。
- 全程无 `FATAL EXCEPTION`；`read_state.bin` 那 181 字节 / 3 条已读记录一路没被动过（`已读记录 3 条`）。
- 单测 343 → **355 项 0 失败**（新增 `RelayAutoSyncTest` 12 项，含一条拿 harvest 真实 21 条清单
  夹具跑的「删一条后自动同步回来二十条」）。APK versionCode 11 / versionName 1.4.4 已装平板。

### 顺手暴露的一件事：harvest 存的就只是摘要，全文得再要一次

上面那批缓存里，relay 的 420 篇有 312 篇正文超 200 字，但**单篇最长 268 字**；
内置源那边最长 6001 字（正好是 `FeedCache` 那个 6000 上限）。查了一下不是 App 的锅：
`harvest/formats/rss.py` 该给的 `content:encoded` 给了，只是 `platforms/zhihu.py` 往
`FeedItem` 里填的就是 `excerpt`（`content_text=excerpt`），而知乎那个
`/api/v4/members/<token>/answers` 列表接口本身只回摘要。所以 RSS 里两个字段一样长，
App 取「更长的那份」也取不到全文。
要补的是对面那个仓库：拿 `answer id` 逐条再请求一次详情（`include=content`）才有正文 ——
这是**带登录态打到知乎**的动作，量级是每次同步几十次请求，风控代价归 PC 侧决定，
我没有自己开这扇账。App 侧的管道已经就位：只要那边给出长正文，阅读页和搜索立刻就能用上，
见 `docs/offline-full-text.md`。

另外 `zhihu-sample-bei-jiu.xml`（知乎 · 示例寅）200 但 0 个 `<item>`，是 harvest 那边这个账号没采到内容；
App 侧行为正确：不写缓存文件、不出空源副本。

### 仍未闭环（诚实）

- 设置页那个新开关**没人眼看过**，只验了 prefs 层的默认值（键不存在 = 开）。
- 「手动同步清空 tombstone」这条设备路径要人点确认框，本轮没法不打扰用户地验；
  代码路径 + 单测覆盖了判定，真机只验了静默那一半。
- 每半小时一次的自动同步会在 PC 关机时白撞一次超时（日志留痕）。半小时这个数是拍的下限，
  真用起来嫌烦就往设置页那个开关一关了事，没有隐藏的第三态。

---

## 2026-09-27 追加八：界面上该说的是「去电脑上敲哪一行」（1.4.6）

上面七片都在修「手机侧能不能拿到」，这次修的是**拿到了却不知道该干什么**：relay 的故障
没有一种是手机上能修的（服务没起、没跑采集、登录态过期、被风控停机），而界面上报出来的
只有「连接超时」「这份 OPML 里没有可用的订阅源」这种症状。

细节、命令表和验证都在 [relay-pc-guidance.md](relay-pc-guidance.md)。这里只记三件和这条链
直接有关的事实：

- **两侧同时量过一次**（21:4x）：PC 的 serve 在听 `192.168.1.20:8099`，手机 prefs 里
  `relay_base_url` + 21 条 `custom_sources` 都在，设备缓存 46 文件 / 888 篇含全部 21 条 relay 源。
  这条链到这里已经是通的，问题转到了「内容不再变」和「某一条永远 0 条」这两件沉默的事上。
- **`harvest/state/*.json` 的 `last_run_at` 全在 18:30–18:39** —— 也就是量的时候那台 PC
  已经三小时没采过新一轮（限速 4 小时 / 每天 4 次）。这个数只有 PC 侧有，RSS 里只有一个
  `lastBuildDate`，所以界面给不出「已经多久没更新」，只能给出「想让它更新就去电脑上跑 run」。
- **`harvest check` 实测**：知乎 21 条 + 小红书示例 `logged_in=true`，twitter 三条示例
  `logged_in=false`（配置里 `enabled=false`，进不了 `feeds.opml`，所以手机侧不受影响）。
  登录态这一类手机完全看不见，只能作为常驻清单项摆在设置页那份清单里。

新增的 `zhihu-sample-bei-jiu`（示例寅）0 条也归到这里：`harvest list` 看得见、界面看不见，
所以清单里专门有一段「源在列表里，但内容不对」。


## 2026-09-27 追加九：这些提示的落点从设置页搬进独立一页（1.4.7）

追加八那份东西落地之后被否了一次：把「电脑端要做的事」排在设置页「我添加的源」下面第四行，
等于暗示它是订阅列表的附属开关，而它管的是**另一台机器**上的登录、采集节奏和服务生死。
同时抽屉里那组「分类」和首页 chips 是同一批词（都从 `state.scored` 派生），两处选一处、
只有一处会跟着内容变，是重复而不是冗余入口。

改成：

- 抽屉四件事 —— 全部文章 / 兴趣 / **电脑端采集** / 设置；「分类」分组撤掉，筛选只留首页 chips。
- 「兴趣」独立成页（兴趣词、召回通道、RSSHub、搜索 key、语义模型），设置页只剩这台设备自己的事。
- crawlbase relay 的同步入口、扫一扫、自动同步开关、整份电脑端清单，合并进「电脑端采集」一页；
  页顶三行状态（PC 地址 / 来自它的订阅 / 上次读到清单）由新的纯逻辑层 `data/RelayStatus.kt` 算。
- 首页失败横幅那一行改为**跳到那一页并带上症状**（`RelayGuidance.pageSections(focus)`），
  对症那段摆第一屏；同步对话框里失败当场的内嵌提示照旧保留，不把用户送出他正在改地址的那个框。

细节与验证见 [navigation.md](navigation.md)；提示层本身的理由还在
[relay-pc-guidance.md](relay-pc-guidance.md)（追加八那节里「摆在设置页那份清单里」的说法
从 1.4.7 起读作「摆在『电脑端采集』这一页」）。


## 2026-09-28 追加十：又一句「手机拿不到」，量出来还是通的（1.4.8）

反馈是「电脑端能正常访问那个地址，但手机端貌似拿不到」，附的是「那一页里全是设置，找不到从哪
里看采到的东西」。两侧再量一遍（09-27 23:2x，他那台平板 ↔ `192.168.1.20:8099`），
**链路这一层没有任何一处坏**：

| 检查 | 结果 |
| --- | --- |
| 设备侧网络 | `nc -z 192.168.1.20 8099` 通；`curl feeds.opml` → 200 / 24ms；`curl zhihu-sample-zhi-75-54.xml` → 200 / 58677B |
| 手机缓存 | 20 个 relay 源文件共 **420 条**知乎回答，写入时间就在量的前几分钟 |
| PC 侧 | **直接数服务吐出的 XML**（不读本地目录）：`feeds.opml` 21 条 → 合计 **477 个 `<item>`**；超 30 的正好是 4×39 + 3×37，其余 ≤30；`zhihu-sample-bei-jiu.xml` 为 0 |

这条链第三次被量成「通」，而用户的感受依然成立。差在**认不出来**：那 420 条的 `pubDate` 是回答
原文的时间（2021 / 2024 居多），首页按新鲜度排序，于是它们全沉在底部；唯一能把它们筛出来的分类
词叫「自建」，那个词回答的是「这条源怎么加进来的」，不是「内容从哪来」。界面那一面的改法
（每条源一行的内容节 / 点一行回首页只看它 / 一键连接自检 / 分类改名「电脑端采集」）记在
[navigation.md](navigation.md) 的 1.4.8 一节，这里只留和这条链直接有关的两个数。

- **477 与 420 那 57 条是手机侧的解析上限**：`RssParser.MAX_ITEMS` 每条源只留 30 条，
  `4×(39−30) + 3×(37−30) = 57`。它不是新故障，但它一定会被读成新故障 —— 电脑端 `harvest list`
  报 39、手机这一页报 30，看着就像链路又漏了。所以那句上限写在节标题底下当声明，**不是**写进
  报错文案：它永远不表示坏。
- **0 条那两行各有解释**：`zhihu-sample-bei-jiu` 在 PC 侧那份 XML 里就是 0 个 `<item>`（追加八已经把它
  归到「源在列表里，但内容不对」），这次它终于在手机界面上被点名了；其余行全为 0 只可能是
  「那台 PC 没跑 run」或「兴趣页把『已订阅源』通道关了」。三种意思不能共用一句「没有内容」。
- **PC 侧一个字没改**：serve、harvest、`feeds.opml` 里那条相对 `xmlUrl`（复核时第一条仍然是裸
  文件名 `zhihu-exampleone.xml`，那是 1.3.1 在手机解析器里补的）全部照旧；新增的自检跑的是手机
  上已有的两步（读清单 + 探测一条 feed），**不会往 PC 打任何请求**，也不会触发一轮采集。

装机复核（09-28 复跑，全程没点屏幕）：388 项单测 0 失败；versionCode 15 / 1.4.8 装进平板冷启动，
crash buffer 空。分类改名在真机上的形状是**不对称的** —— `shared_prefs` 里那 21 行仍写「自建」，
而 20 个 relay 缓存文件的分类字段已全部写成「电脑端采集」，这正是「读时归一、不起迁移」应有的
样子（`harvest/out/` 那份产物 22 个 zhihu XML、最新 mtime 09-27 18:39，也顺手对上追加八说的
采集时刻）。

没验到的：**自检那条按钮真机没按过**（它要人手点一下才会跑），「清单读到了但抽查失败」那一支要
PC 侧半写状态才造得出来，本机造不出；21 行内容列表在窄屏上的排版没人看过。

## 2026-09-28 追加十一：方向反过来了 —— 手机能让电脑现采一轮（1.4.9）

反馈原文有两句：「点开文章后的阅读页白」，以及「我需要它跟电脑端打配合。因为有时候电脑端可以开启
监听，手机端可以向它发起采集命令，来获取更多的数据」。第二句是**这条链第一次要往回走**：前十次追加
都在解决「手机怎么读到 PC 写好的文件」，这次是「手机怎么让 PC 现在去写」。

### 命令口的形状（契约在 crawlbase `harvest/command.py`，这里只留手机侧要认的）

| 端点 | 方法 | 手机侧认的返回 |
| --- | --- | --- |
| `/command/plan` | POST | 200 + `data.plan` 每条源一行（`feed` / `enabled` / `would_run` / `reason` / `last_status` / `known_items`） |
| `/command/run` | POST（可带 `{"feed":"<id>"}`） | **202** + `data.feed`，真结果靠轮询 |
| `/command/regen` | POST | 200 + `data.feeds` / `data.xml_files` / `data.qr_entries` / `data.base_url` |
| `/command/status` | GET | 200 + `data.running` / `feed` / `started_at` / `finished_at` / `outcome` / `error` |
| `/pair` | GET | 200 那份配对页（二维码 + 深链） |

回包一律是 `harvest/1` 信封（`protocol` / `ok` / `action` / `data` / `meta`），失败时**不看 HTTP 码
也能拿到原因**：顶层 `error{code,message}`。手机侧只认这些 code：`NEEDS_TOKEN` / `BAD_FEED` /
`BUSY` / `BAD_ACTION`，其余（`RUNTIME_ERROR`、没 code 只有 message 的）当「电脑说的原话」摆出来。

四条设计约束都得记着，它们各自决定手机上的一句话：

- **默认关**。要 `serve --allow-commands` 才存在。开了就等于把「拿你的登录态去打目标站点」这个按钮
  交给同 WiFi 里能连上端口的设备，所以配对走 token：`Authorization: Bearer <token>`，比对用
  `secrets.compare_digest`，token 存在 `harvest/state/command_token`（**故意不在 `out/`** ——
  那个目录整个被 HTTP 暴露着，写进去等于 GET 一下就拿到）。`secrets.token_urlsafe(12)` → 16 字符，
  重启不变，所以旧配对不会因为他重起一次服务就失效。
- **`run` 是受理不是跑完**。一轮要开调试浏览器、几十秒起步，HTTP 长连接在手机上必然被掐。
  所以 202 之后手机每 5 秒轮 `/command/status`，看到 `running=false` 才收尾并刷列表。
- **一次只跑一个**。锁被占着回 409 `BUSY`，**不排队** —— 排队在手机上长得和「点了没反应」一模一样。
- **限速一条都不绕**。这里只是把 `harvest run` 换个人按下，`interval` / `daily_cap` / `cooling` /
  `needs_login` 照旧；`force` 不在这个口上暴露 —— 绕过限速是人的决定，不该由手机上一个按钮替他做。
  于是「让手机能叫它现采」永远换不来「采得比电脑自己排的更多」。

### `/pair` 那张二维码的编码，是两个仓库之间新的一次握手

配对页的深链是 `myfeed://relay-sync?base=<编码过的地址>&tok=<token>`。`base` **必须**百分号编码：
它是 URL 的 query 值，里面天然带 `://` 和 `:`，而手机那边的解析器（`DeepLink`）是按 `?` / `&` 切
query 的 —— 不编码就会被切一刀，只剩 `http://192.168.1.20`，端口掉了。**二维码那一层还要再编一次**
（qrserver 的 `data=` 参数本身是 URL 的一部分，所以是 `%253A` 那种双重编码）。这两处在页面上看着
都「像是对的」，错的表现在手机上：扫出来是个残缺地址，同步失败，而人只会以为手机坏了。

配对页**不印 token**：这个应用没有手填 token 的入口，印出来只会多一个能被人拍照的东西；页面上明说
开关叫什么、反悔要删哪个文件（`state/command_token` + 重启）。手机侧对 token 当不可信字符串处理：
只收 `token_urlsafe` 的字符集、夹到 64 长度，可疑码只丢配对那一半、地址照旧能用（`DeepLink.safeToken`）。

### 实测（09-28，全程没点屏幕）

在跑的那台 serve 是 `serve --host 192.168.1.20 --port 8099 --allow-commands`（PID 136332）。
**绑的是局域网网卡，所以 `127.0.0.1:8099` 连不上** —— 这不是缺陷，但它会让人以为服务死了：

| 探针 | 结果 |
| --- | --- |
| `POST /command/plan`（带 token） | **200 / 0.10s**，24 行（含两条 `enabled=false` 的样例源） |
| `POST /command/plan`（不带 token） | **401** |
| `GET /pair` | 200；深链 `href="myfeed://relay-sync?base=http%3A%2F%2F192.168.1.20%3A8099&amp;tok=…"`；二维码 `data=` 双重编码；页面三个 `<code>` 是地址、`--allow-commands`、`state/command_token`，**没有 token** |
| `GET /feeds.opml` | 200（静态那条路一个字没改） |

`202 + 轮询`、`400 BAD_FEED`、`404 BAD_ACTION`、`401` 这四支是在本机另一台试验服务
（`127.0.0.1:8199`）上量的，`run` 特意点的是**一条 `enabled=false` 的源** —— 它会被 `Safety` 直接挡掉，
不会拿他的知乎登录态去打站。手机侧那 4 个按钮真机**一次都没按过**：设备上 `relay_base_url` 在位，
但 `shared_prefs/myfeed_settings.xml` 里**没有 `relay_command_token`**，也就是他还没扫这张配对码。

### 这一版另一头：阅读页为什么白，以及为什么不在 WebView 里问夜间

点开文章那一页（知乎这类登录墙站点走的应用内离线全文）是**应用自己渲染的 HTML**，以前写的是
`@media (prefers-color-scheme: dark)` —— 也就是把「现在是不是夜里」交给 WebView 判定。Android 13
起的 algorithmic darkening 跟的是**App 声明的主题资源**，而仓库里只有 `values/themes.xml` 一份
Light 主题，于是系统夜里真机上 WebView 报的是亮，界面是暗的、这一页白着脸。改法两层一起：
`OfflinePage.html(article, dark)` 直接把配色写死（`#121212` / `#dcdcdc` / `#93989f`，不留媒体查询），
`res/values-night/themes.xml` 补上夜里那份主题（窗口底 `#FF121212`，并把 `forceDarkAllowed=false`
—— 我们自己就是暗的，别让 ROM 的强制反相再盖一层）。细节与实测在
[web-reader-dark-mode.md](web-reader-dark-mode.md)。

装机复核（09-28）：**436 项单测 0 失败**（1.4.8 是 388；这一版新增 `RelayCommandsTest` 14、
`MiniJsonTest` 8、`RelayCommandClientTest` 9、`OfflinePageTest` 6，`RelayStatusTest` 10 → 15、
`DeepLinkTest` 10 → 13、`RelayGuidanceTest` 16 → 18、`RelayContractTest` 5 → 6）；versionCode
**16** / **1.4.9** 装进平板冷启动，crash buffer 里 `feedreader` 条目数 0。包内（`classes6.dex`）确认落进：`让手机能叫电脑现采` ×2、`--allow-commands`、
`command_token`、`/pair`、`这台电脑只出静态 RSS，命令口没开`。资源侧 `aapt2 dump resources`：
`style/Theme.FeedReader` 在 `()` 仍是 Light 父主题，`(night)` 两项 `#ff121212`，
`(night-v29)` 三项（多的是 `0x0101058c=false`，即 `forceDarkAllowed`）。

没验到的：**手机上那四个命令按钮的观感与真跑**（要他先扫 `/pair`，再点一下才会真打到 PC）；
夜里阅读页的观感（按约定没做任何屏幕自动化，只跑编译 + 资源/dex 校验，要他亲眼看一眼）；
`/pair` 在只有 IPv6 或 hostname 访问时的地址形态。
