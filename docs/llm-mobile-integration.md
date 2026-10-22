# 阅流 1.5.3：独立 LLM Feed

> 状态（2026-10-22）：**「独立 tab」那部分已被 1.5.5 推翻**，内容与已读改回原生那一条流，
> WebView 只留生产侧。下文保留作为当时的取舍记录，最新归属见文末追加与
> [native-desktop-sync.md](native-desktop-sync.md)。

1.5.2 只把电脑端文章快照混入原生兴趣流，缺少独立入口和 LLM 管理页；文章也可能被 RSS 兴趣匹配过滤。本版将产品入口分为「订阅阅读」与「LLM Feed」，首次进入默认显示 LLM 页。订阅阅读仅在实际打开该 tab 时初始化，LLM 阅读不再启动 RSS 召回或端侧模型。

LLM 页直接打包完整 React 界面源码（`llm-ui/`），支持 Feed、类型筛选、发现/主题、收藏、文章和评论、生成任务、模型选择、提示词、角色、真实来源、用量、预算及数据保留设置。页面和数据分别保存在 APK 与应用自己的 IndexedDB 中，电脑不用额外运行前端开发服务器。

沿用 LLMFeed 的同步游标与 outbox。阅读、收藏和评论先在本机提交，联网后按幂等操作同步；电脑关闭时可继续阅读缓存。旧原生电脑内容缓存及已读状态在本机导入一次，已存在的 LLM 内容和反馈不会被覆盖。旧独立 Personal AI Feed Android 应用的沙箱无法直接读取，需要先导出 JSON，再从本版的备份入口合并导入。

Android 的网络接口仅向本地应用来源提供，限于用户已保存电脑地址的 `/api/v1/*` 与 `/health`，禁止跳到别的主机、端口、协议或携带 URL 凭据。外部链接由系统浏览器处理，不向外部网页开放接口。模型密钥继续由电脑端保管；服务端现有鉴权不会被手机自动关闭。

桥的动作为 `http`、`connection`、`legacy`、`export`，以及 2026-10-21 加的 `discovery`：网页发不了 mDNS 组播，所以由原生用系统 `NsdManager` 搜一轮再把 `{endpoints, unresolved, note}` 递回去，连接面板据此列出「点一行填进地址框」。它只是读局域网公告，不碰任何主机凭据，且只在 `isMobileHost()` 时出现入口；原生自己上 30 秒保险，早于前端那条通道 35 秒的超时。细节与实测见 `lan-discovery.md`。

切换 tab 保留 LLM WebView，避免丢失正在编辑的设置和评论。订阅侧清理网页缓存会保留 LLM 应用来源的离线数据库和待同步队列。JSON 备份导入、导出使用 Android 系统文件选择器。

构建：Gradle 自动安装 `llm-ui/pnpm-lock.yaml` 锁定依赖并打包界面。发布：`just publish` 先扫描可发布源码和解压后的 APK，再加入 lanpub。可用 `tools/check-release-privacy.py --env-file <本机配置>` 额外比对实际密钥；检查不会打印密钥值。

验证使用独立的 Mock 数据库，已通过手机尺寸的内容同步、生成任务、模型设置保存、收藏页和提示词页面操作。测试界面与数据库不进入 APK。没有连接的 Android 设备，不能将这些检查写成真机装机或手机到电脑的网络验收。

---

## 2026-10-22 追加：这条流改回原生那一条（1.5.5 / versionCode 22）

上面那份「产品入口分为订阅阅读与 LLM Feed 两个 tab」的设计作废。同一篇帖子在手机上有两份已读
（原生 `read_state.bin` 与 WebView 的 IndexedDB），读过的东西回到订阅列表还是未读——用户要的是
「有机结合而不是做个页面单独分离出来」，所以归属权改过来了：

- **内容与已读只有一份，在原生这边。** 新增 `data/DesktopFeed.kt`（解析与并表，纯函数）、
  `data/DesktopSyncClient.kt`、`data/DesktopOutbox.kt`、`data/DesktopSync.kt`（编排），
  节拍照 `llm-ui/src/services/sync.ts`：先 push 后 pull、410 只换全量快照且绝不动 outbox、
  裁决分 `applied`/`conflict`/其它三类。电脑端帖子走一条不抓 RSS 的虚拟源（`paf`，分类「AI 内容」），
  因此自动继承订阅开关、分类 chips、缓存保留策略与同一个已读 id；`remotePostId != null` 绕过兴趣门槛。
- **WebView 只留生产侧**，入口从并列 tab 降为抽屉里一行「AI 内容生产」，顶部用原生 `TopAppBar`
  （顺带修掉 1.5.4 那版 tab 行压进状态栏、点不着的 bug）。
- **电脑地址与访问令牌改由原生偏好文件保管**，注入 `window.__YUELIU_HOST__`；桥的 `connection` 动作
  现在带 `token` 回写。改动前全仓没有任何令牌输入 UI，`apiToken` 在 llm-ui 只读不写——这是补上的真实缺口。
- **桥的注入多一个 `manage: true`**：手机壳里 `/`、`/discover`、`/saved`、`/post/:id` 一律渲染生成页，
  导航不再列阅读入口，右栏阅读侧信息隐藏；电脑浏览器直开仍是完整应用。
- 桥的动作集合不变（`http`、`connection`、`legacy`、`discovery`、`export`），网络准入规则不变。

契约细节、真回包量出来的五个问题（微秒当毫秒累加、89% 的 `published_at` 为空、三种时区写法、
已读时间戳不能塞哨兵、`read_progress=0` 的那一行）与验证边界见 [native-desktop-sync.md](native-desktop-sync.md)。
