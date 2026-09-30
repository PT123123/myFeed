# 内置浏览器的网页暗色模式（含微博专项）

日期：2026-09-27
状态：**代码与自动化验证完成；真机渲染未测**（需要用户在设备上过一眼，见末尾）

## 一句话

内置浏览器原来完全没有网页暗色：`targetSdk = 35` 下 WebView 那套 `forceDark` 是 no-op，
所以系统夜间模式里 UI 是黑的、网页还是白的。这次补上两层注入（早期反相 + 智能判定），
外加一张按站点的**配色覆盖表**，第一条给微博。

## 先钉住的前提：需要登录态的站点不在这里解决

结论来自上一轮对 `github.com/PT123123/a-justbrowse` 的调查，走的是「A 方向」：

- WebView 的 cookie 存在**本应用自己的沙箱**里（`CookieManager` 是进程内的，不是设备级的），
  读不到 JustBrowse / Edge 的登录态。跨应用复用 cookie 只能靠对方主动导出。
- 就算导出来，知乎的接口除了 cookie 还要 `x-zse-96` 签名（[crawlbase-relay.md](crawlbase-relay.md)
  里那组实测：第 5 档协议级 401、第 4 档无头渲染拿到「安全验证」页），换个 HTTP 客户端搬过去照样被拦。

所以：**知乎、小红书、X 这类站点的正文用顶栏的「在浏览器中打开」外跳**，把登录态留给
系统里那个真浏览器；内置浏览器只负责不需要登录就能读的东西。这条边界是这次改造的前提，
别指望在应用内把登录 wall 变没。

## 微博为什么必须单独写

在 `m.weibo.cn/status/<mid>`（statusLite 页）上实测拿到的事实：

| 事实 | 怎么量的 | 后果 |
| --- | --- | --- |
| 微博**有**自带暗色，但 cards.css 48 条 + base.css 32 条 `:root[data-theme=dark]` 规则**全部**包在 `@media (prefers-color-scheme:dark)` 里（大括号配平数出来的，outside=0） | 直接拉 CDN 上的 CSS 原文解析 | 设备不在夜间模式时它这套完全不触发。媒体查询 JS 和 CSS 都改不动，只能自己写配色 |
| statusLite 自己的 CSS 里 `data-theme` 出现 0 次 | 同上 | 详情页这块基本没有站点暗色可依 |
| 固定定位元素三个：`.lite-topbar.lite-page-top`（吸顶栏）、`.video-player.mwb-layer`、`.lite-page-editor`（底部输入框） | `getComputedStyle().position` 扫全文档 | `html{filter:invert(1)}` 会让 `html` 成为 fixed 的包含块 —— 通用反相会把这三块变成跟着页面滚走。所以微博走配色覆盖，**不碰 filter** |
| 页面亮底：卡片 `#fff`/边 `#e6e6e6`、吸顶栏 `#fafafa`/字 `#262626`、正文 `#333`、次要与时间 `#939393`、链接 `#3c6e9e`、图片占位 `#e6e6e6`、关注按钮 `#ff8200`、根底 `#f2f2f2`、25/25 采样点亮度均值 0.99 | 视口 5×5 网格采样 + 逐选择器 computed style | 通用判定这套页**会**判成白页并反相（反相本身不错，但撞上上一条）；覆盖配色直接照这些值映射 |
| `weibo.com/<uid>/<mid>`（RSS 里给的就是这个形态）在手机 UA 下 302 到 `m.weibo.cn/status/<mid>` | 跟随重定向时 visitor 网关的回跳参数里带着目标地址 | 宿主表里 `weibo.com` 和 `m.weibo.cn` 都得列，落到哪套 UI 都是同一份配色 |
| 匿名协议级请求四个 URL 全被 `visitor.passport.weibo.cn` 网关挡回 | 第 5 档 fetch | 想抄微博的 DOM 只能拿真浏览器量，别照着 curl 的结果写规则 |

专项 CSS 里出现的每个类名都必须在实测集合里，这条由
`DarkPageScriptTest.专项样式里的类名都要有实测出处` 钉着 —— 以后加类名得先去真页面上量到，
不能照别的站点的命名习惯猜。

## 实现

```
ui/web/SiteDarkStyles.kt   数据：通用反相 CSS + 阈值 + 站点专项表（微博是第一条）
ui/web/DarkPageScript.kt   纯字符串：把表拼成注入脚本（不碰 Android 类型，JVM 单测可跑）
ui/web/DarkPageInjector.kt 接线：document-start 注册 + 导航回调里补注入
ui/ReaderScreen.kt         开关算成布尔、挂到 WebView 生命周期上
data/SettingsStore.kt      WebDarkMode 三态（跟随系统 / 总是 / 不启用），默认跟随系统
```

三层，同一份脚本：

1. **document-start**（`androidx.webkit` 的 `WebViewCompat.addDocumentStartJavaScript`）——
   只有它在任何网页内容渲染之前执行，白底闪一下只能在这里治。
2. **onPageStarted** 再执行一遍：文档刚换新、`window.__mfDark` 是空的，正好重建；
   没有 document-start 能力的老 WebView 靠这一步兜，代价是会闪一下白。
3. **onPageFinished** 跑带判定的 `smart(true)`：通用路径在这一步撤掉「页面本来就是暗」的误伤；
   站点专项路径重新确认样式在位（SPA 站内换路由不重建文档，只有回调补得上）。

脚本自己按 `location.hostname` 决定走专项还是走反相，因为 document-start 阶段 Kotlin
侧还不知道这次导航最终落在哪个域（站内跳转会换 host）。附带两个护栏：
非 `http(s)` 协议直接不注入（`ReaderScreen` 里那份 `data:` 本地摘要页自带配色），
以及只在主框架注入（iframe 里再反相一次会叠成白块）。

开关只在 WebView 创建时生效，改设置要重开阅读页 —— 设置页和阅读页是两个目的地，
改设置时阅读页必然已经销毁，所以不存在「读到一半切开关」这条路径。

## 已知代价

- 通用反相在**深色但采不到背景色**的页面（整屏视频、背景图铺底的站）上会误判。
  方向是保守的：有效采样点不足就当作本来就是暗，宁可漏反相也绝不把黑页反成白页。
- 老 WebView 没有 document-start 能力 → 首屏会闪白，只能这样。
- 专项表目前只有微博一条，而且只在 statusLite 这一种页面上量过；微博的时间线页、
  个人主页、搜索页（`.card9`、`woo-*` 那批类名）还没实测，所以没写规则。
- **没动 UA**。`ReaderScreen.configureForReading()` 沿用系统默认 UA，里面带着 `; wv)`
  标记；部分站点会按这个标记把 WebView 流量单独收紧。JustBrowse 是显式把它抹掉的
  （`browserUserAgent()` 里 replace 掉 `; wv)` 和 `Version/4.0`）。这属于另一竖切。
- `WebDarkMode.FOLLOW_SYSTEM` 里的「跟随」跟的是设备夜间模式，不是应用内主题 ——
  应用主题目前也是跟随系统，两者暂时同源。

## 验证做到哪一步

| 层次 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | 通过 |
| `:app:assembleDebug` | 通过（新依赖 `androidx.webkit:webkit:1.12.1` 正常进包） |
| `:app:testDebugUnitTest` | 263 项全过（0 失败 / 0 跳过），其中本次新增 13 项：`DarkPageScriptTest` 10 项 + `SettingsLabelsTest` 新增的 3 项 `WebDarkMode` 用例 |
| `node --check` 真实生成的脚本 | 通过。这条是**必须的**：转义写坏时 `evaluateJavascript` 只会静默不生效，日志里一行都不留 |
| DOM 桩行为验证（把 dump 出来的脚本喂给 node，9 条断言） | 全过：微博走专项、普通站走反相、`weibo.com.evil.test` **不**命中、子域命中、`data:` 不注入、iframe 不注入、专项里没有 `filter`、暗页被 `smart(true)` 撤掉反相、白页在 `smart(false)` 时保持反相 |
| 真机渲染 | **没测。** 这台机器上没法跑 WebView，需要手动过一眼 |

真机上要看的是这四件事：

1. 设置 → 阅读 → 网页暗色模式，三档各开一次。
2. 一篇普通博客（该整页变黑，图片视频不串色）。
3. 一篇微博（该是深灰卡片 + 吸顶栏仍然吸顶 + 底部输入框仍然吸底）。
4. 首屏不该白一下（亮色设备 + 「总是暗色」下最容易看出来）。

## 后续

1. 站点表继续加：B 站、公众号文章、少数派那类中文站。加之前先按上面那张表的办法量。
2. 阅读页顶栏加一个「这页别变暗」的一次性开关（判定误伤时的逃生口）。
3. UA 里 `; wv)` 要不要抹掉，跟着「需要登录态的站点是否允许在应用内读」一起决定。
4. 知乎热榜之类要登录的源仍然走 relay（见 [crawlbase-relay.md](crawlbase-relay.md)），
   本次改动与它无关。

## 1.4.9：白脸的那一页根本不是「网页」—— 它是我们自己渲染的

新一轮反馈是「点开文章后的阅读页白，我要黑暗模式」。量的结果和上一次那份暗色方案说的不是同一页：
他点的是**知乎那批登录墙源**，`ReaderRouting.readOffline` 判成离线，于是这一屏走的是
`OfflinePage.html(...)` + `loadDataWithBaseURL` —— 也就是上面「三层同一份脚本」那条链**刻意跳过**
的那一支（非 `http(s)` 不注入）。注入器再准也管不到它，而它当时是这样问夜里的：

```
@media (prefers-color-scheme: dark)   ← 交给 WebView 自己判定
```

问题就在这句。`prefers-color-scheme` 问的是 **WebView 的夜间判定**，Android 13 起的 algorithmic
darkening 又把它绑到 **App 声明的主题资源**上；而仓库里当时只有 `values/themes.xml` 一份
`Theme.Material.Light.NoActionBar`。两套判定因此不同源：Compose 那边 `isSystemInDarkTheme()` 说
「夜里，界面该暗」，WebView 那边拿着 Light 主题答「亮」。**界面是暗的，这一页白着脸** ——
正是他看到的那个形状。

改法是把这一页的配色**自己写死**，不留媒体查询：

- `OfflinePage.html(article, dark)` 收调用方算好的那个布尔（就是驱动注入器的同一个 `darkPages`，
  三档开关只有一个来源）。暗色那份是 `#121212` / `#dcdcdc` / `#93989f`，亮色那份照旧白底。
  **同一个底色常量 `DARK_BACKGROUND` 也喂给 `WebView.setBackgroundColor`**
  （`ReaderScreen.configureForReading`），否则加载期间 WebView 自己那块底会在暗界面中间白一条。
- 补 `res/values-night/themes.xml`：夜里那份主题 parent 换成 `android:Theme.Material.NoActionBar`，
  `windowBackground` / `colorBackground` 都是 `#FF121212`（首帧和抽屉滑动时露出来的那块），
  并且 `android:forceDarkAllowed=false` —— 我们自己就是暗的，别让 ROM 的强制反相在已经暗的页面上
  再盖一层。白天的 `values/themes.xml` 一个字没动。
- 状态栏那行跟着主题走：`isAppearanceLightStatusBars = !darkTheme`。这句话说的是**图标**按亮底画，
  夜里再设 true 就是把深色图标压在深色状态栏上，看不见。

新增的 `OfflinePageTest` 6 项钉的是这片以前测不到的东西（配色原来写在 Compose 文件的 private
函数里）：暗色那份把颜色写死而**不出现媒体查询**、亮色那份仍是白底、摘要长度正文在文末留
「整篇在原页」、尖括号只当文本、什么都没有时说一句空话而不是白屏、正文空但有摘要时读摘要。

### 验证（1.4.9，全部是包内证据，没点过屏幕）

- `aapt2 dump resources` 那份 1.4.9 APK：`style/Theme.FeedReader` 在默认配置 `() size=0
  parent=0x01030241`（Light），`(night) size=2` 与 `(night-v29) size=3` 的 parent 是
  `0x0103022e`（Material 无标题栏），两项颜色 `0x01010031` / `0x01010054` 都是 `#ff121212`，
  `night-v29` 多的那一项是 `0x0101058c=false` —— 就是 `forceDarkAllowed`（这个属性 API 29 才有，
  所以 aapt2 按 minSdk 把它自动切成单独一份 `-v29` 配置）。
- `grep -ac prefers-color-scheme classes*.dex` → **全 0**。这个词在仓库里只剩注释和 KDoc，
  也就是说：**装机的代码里没有任何一处再去问 WebView「现在是不是夜里」**，夜间一律由应用自己决定。
  `#121212` ×2、`#dcdcdc` ×1、`background: ` ×1 在 `classes4.dex`（离线页那份 CSS 与底色常量）。
- 436 项单测 0 失败，`adb install -r` 进平板冷启动 crash buffer 空。

没验到的还是**眼睛的活**：夜里点开一篇知乎回答（该是深灰底 + 浅灰字）、亮色设备下同一篇（该是白底，
不是反相得来的）、抽屉滑动时不再闪白、状态栏图标在夜里看得清。这台机器上跑不了 WebView 渲染，
上面那张表里「真机渲染 没测」这一条对这一页同样成立。
