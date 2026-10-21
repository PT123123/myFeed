# 局域网发现：手机不再需要预先知道电脑的 IP

日期：2026-10-21。状态：**代码、单测、电脑侧真跑过；手机侧的 NSD 还没在真机上按过一次**（`adb devices` 是空的）。
随 **1.5.4 / versionCode 21** 出了 release 包并进局域网仓库（`com.example.feedreader_21.apk`，sha256 与构建产物逐字节相同），**未装机**。

## 一句话

两台电脑服务（harvest 的 `serve`、阅流电脑端的 FastAPI）各自用 mDNS/DNS-SD 在局域网里广播一个类型名，
手机用系统 `NsdManager` 搜这两个类型，搜到就列出「机器名 + 能直接连的基地址」，点一下填进地址框。
手填和扫码那两条退路一字未动。

## 契约：两个类型名，跨三个仓库

| 角色 | 服务类型 | 默认端口 | 广播方 | 搜索方 |
| --- | --- | --- | --- | --- |
| `relay` 电脑端采集 | `_yueliu-relay._tcp.local.` | 8099 | crawlbase `harvest/mdns.py`（`serve` 起来时） | 「电脑端采集」那一页 |
| `desktop` 阅流电脑端 | `_yueliu-desktop._tcp.local.` | 8000 | `LLM feed/apps/server/app/services/mdns.py`（lifespan） | LLM Feed 连接面板 |

TXT 只有三条：`txtvers=1`、`role`、`v`（版本号）。端口和机器名不进 TXT —— SRV 记录带端口，
实例名本身就是主机名，重复一份就有两份会不一致的东西。

钉这条契约的是三个测试，任何一边改了字面量都会红：

- `my feed/app/src/test/.../LanRulesTest.kt`（8 项）—— 手机侧两个类型名字面量、角色映射、FQDN 写法差异；
- `crawlbase/harvest/tests/test_mdns.py`（14 项）—— 电脑侧 `SERVICE_TYPE`/`ROLE`/TXT 全等；
- `LLM feed/apps/server/tests/test_mdns.py`（14 项）—— 同上，另一类服务。

## 为什么走系统 NSD，而不是自己发 5353 组播

安卓的多播成员关系由系统统一管。第三方 socket 去收 5353 在部分机型上被内核直接丢掉，而那种失败
落到界面上只有「搜不到」四个字，查不到根因。走 `NsdManager` 则由系统负责加入组播组，
代价是只有一份多播锁要自己拿（见下）。

## 手机侧的一节拍：先收公告，收完再逐个解析

`LanDiscovery.search()` 分两段，中间隔着固定的 `BROADCAST_MS = 5000`：

1. 每一类服务注册一个 `DiscoveryListener`，只往集合里记「谁在应答」，**不就地解析**；
2. 停掉浏览，再逐个 `resolveService`，单个上限 `RESOLVE_MS = 3000`，最多解析 `MAX_RESOLVE = 8` 个。

分两段的原因很具体：`NsdManager` 在浏览还挂着的时候发起解析，在一些机型上以 11
（INVALID_LISTENER_OPERATION）失败，而报错时机正好落在用户唯一能看到结果的那一行字上。
所以广播窗口是固定的，不是「一直搜到有人点停止」。

三个不那么显然的写法：

- **一个监听器对象只能注册一次**，所以每类服务各配一个 `Collector`，同一个实例注册两次会抛异常；
- **类型名由 `Collector` 自己带着**，不从回调里的 `NsdServiceInfo.serviceType` 读：minSdk 21 只能用
  旧重载，旧重载的 `onServiceFound` 只有一个入参，那个字段在机型上可能是空的，而空类型会让
  `LanRules.roleOf` 返回 null，这条公告就凭空消失了；
- **`unresolved` 单独计数**：「应了答却叫不开」和「一台都没搜到」是两件事，混成一句「没找到」，
  用户就会去重试第三条路。

地址的规整复用 `RelayRules.normalizeBase`，没有另拼一遍字符串 —— 填 IP 那条路和搜索这条路必须
得到同一个形态（补 8099 的规则、IPv6 方括号、剪掉路径），否则同一台电脑两种方式配出来的地址不一样，
「订阅源换不掉」那类老毛病会换个形式回来。回环、`0.0.0.0`、`169.254.*`、`fe80*` 一律不进列表：
前者只在那台电脑上打得开，后者跨设备要点运气。

权限只加了 `CHANGE_WIFI_MULTICAST_STATE`（普通权限，不弹框），用来在搜索那 5 秒里持有
`createMulticastLock`。拿不到锁不让搜索失败（网线、模拟器就没有 WiFi 接口），`try/finally` 必 release。

## 电脑侧

### harvest（crawlbase 仓库）

`serve` 起来时顺手广播，`--no-mdns` 关掉。横幅会把结果写成人话，包括没广播成功的原因：

```text
mDNS 已公告 <本机局域网 IP>:8099（<主机名>）
如果手机搜不到，放行 UDP 5353 入站：New-NetFirewallRule -DisplayName yueliu-mdns -Direction Inbound -Protocol UDP -LocalPort 5353 -Action Allow
```

缺 `zeroconf` 包、探不到局域网 IPv4、5353 被占或注册报错，都只留一句说明，**不会把 `serve` 带走**；
`--host 127.0.0.1` 时根本不公告（手机在另一个设备上，announce 了也没用）。

### 阅流电脑端（FastAPI）

`app/services/mdns.py` + `main.py` 的 lifespan，用 `asyncio.to_thread` 包住注册：同步 `zeroconf`
自己起线程收发包，Windows 的 Proactor 事件循环在 uvicorn 的 asyncio 循环上没法 `add_reader`，
所以不走 `AsyncZeroconf`；注册那一秒也不该卡住服务启动。

开关是 `PAF_MDNS`：`0/off/no/false` 关，`1/on/yes/true` 开，默认 auto = 监听地址不是回环就公告。
`just server-lan` 会同步 `PAF_HOST`/`PAF_PORT`。

这里修掉一个会静默出错的坑：`scripts/start-server.ps1` 的监听地址只存在于命令行上，而
`app.config` 读的是环境变量 —— 不把它们写进子进程的环境，广播出来的端口会是 `.env` 里那个，
而不是真正在听的那个。

## Windows 防火墙：这一条是「电脑上搜得到」证明不了「手机搜得到」的根源

量过的三件事（2026-10-21，本机 WLAN 网络类别为 Public）：

- 系统自带那条 `mDNS (UDP-In)` 规则**按程序放行，绑在 `svchost.exe` 上**，管不到 venv 里的 `python.exe`；
- 本机自己收发 mDNS 不过防火墙，所以在同一台电脑上 browse 成功跟手机能不能搜到毫无关系；
- 因此第一次要让手机搜到，需要一条 UDP 5353 入站规则（要管理员授权，所以只写成给用户点的一行，App 不自动跑）。

这句话同时写进了 harvest 的横幅、FastAPI 的启动日志、`.env.example`，以及手机上「一台都没搜到」
那一节的对症文案里。

## 界面

- 「电脑端采集」页的地址对话框，输入框下面多一节「搜索局域网里的电脑」：转圈 + 「约 5 秒」，
  命中一条一行（机器名 + 地址），点一下填进框里，**仍然要人按一次同步**。搜不到时把对症那一节
  （`RelayIssue.NOT_DISCOVERED`）直接嵌在这里，因为下一步动作全在那台电脑上。
- LLM Feed 的连接面板同一件事，但走 WebView 桥：网页发不了组播，所以桥里加了 `discovery` 动作
  （原生搜完把 `{endpoints, unresolved, note}` 递回去），只在 `isMobileHost()` 时显示按钮。
  原生自己上 30 秒保险，因为前端那条通道 35 秒报「电脑服务未响应」，那时分不清是搜索慢还是这台设备没有 NSD。

## 实测与未验

真跑过的（本机局域网 IPv4，写进这份仓库时按隐私门的要求抹掉具体值）：

```text
_yueliu-desktop._tcp.local. → <主机名>._yueliu-desktop._tcp.local.
  addresses=['<本机局域网 IP>'] port=8000 txt={'txtvers':'1','role':'desktop','v':'0.6.0'}
```

这条是 `just server-lan` 真起来之后、用独立进程 browse 出来的，不是启动日志里自己说的一句。
harvest 那一侧本轮同样用生产代码 `mdns.advertise()` 起过一次并被 browse 到（`_yueliu-relay`，8099）。

没跑过的，说清楚：

1. **手机上的 `NsdManager` 一次都没按过** —— 没插设备。安卓侧的逻辑全部落在纯数据层 `LanRules` 上有测试，
   `LanDiscovery` 的框架编排只能真机验；
2. **venv 的 `python.exe` 收到的 5353 入站**还没实际放行过：本机 browse 是自己发给自己，不过防火墙；
3. 手机搜到之后的排版没人眼看过。

## 门

| 门 | 数 |
| --- | --- |
| `:app:testDebugUnitTest` | 453 项 0 失败（新增 `LanRulesTest` 8） |
| harvest `pytest` | 125 项 0 失败（新增 `test_mdns.py` 14） |
| 电脑端 server `pytest` | 166 项 0 失败（新增 `test_mdns.py` 14） |
| `llm-ui` typecheck + `vitest` | 24 文件 197 项 0 失败 |
