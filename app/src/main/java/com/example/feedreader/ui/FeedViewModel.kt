package com.example.feedreader.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.feedreader.data.Article
import com.example.feedreader.data.CacheStats
import com.example.feedreader.data.FeedCache
import com.example.feedreader.data.FeedRepository
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.FetchOutcome
import com.example.feedreader.data.Interest
import com.example.feedreader.data.InterestStore
import com.example.feedreader.data.Interests
import com.example.feedreader.data.OpmlParser
import com.example.feedreader.data.ReadStateStore
import com.example.feedreader.data.ProbeResult
import com.example.feedreader.data.RelayAutoSync
import com.example.feedreader.data.RelayCommandClient
import com.example.feedreader.data.RelayCommands
import com.example.feedreader.data.RelayIssue
import com.example.feedreader.data.RelayRules
import com.example.feedreader.data.RelaySelfTest
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.SettingsStore
import com.example.feedreader.data.WebDarkMode
import com.example.feedreader.data.SourceRules
import com.example.feedreader.data.SourceStore
import com.example.feedreader.data.SearchApiKeys
import com.example.feedreader.data.SearchKeyProvider
import com.example.feedreader.data.recall.OkHttpGet
import com.example.feedreader.data.recall.OkHttpKeyed
import com.example.feedreader.data.recall.RecallChannels
import com.example.feedreader.data.recall.RecallService
import com.example.feedreader.recommend.RecommendEngine
import com.example.feedreader.recommend.ScoredArticle
import io.github.pt123123.semantic.BgeSmallZh
import io.github.pt123123.semantic.ModelState
import io.github.pt123123.semantic.SemanticEngine
import io.github.pt123123.semantic.SemanticStatus
import io.github.pt123123.semantic.TextEncoder
import io.github.pt123123.semantic.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 需要 Application 来拿 SharedPreferences 和缓存目录，所以是 AndroidViewModel。
 * 加 @JvmOverloads 是为了让默认工厂能直接找到 (Application) 这个构造器。
 */
class FeedViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: FeedRepository = FeedRepository(),
    private val cache: FeedCache = FeedCache.forApp(app),
) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    private val interestStore = InterestStore(app)

    /**
     * 订阅源的开关与自建清单。
     *
     * 从 [SettingsStore] 挪出来是这次「源换不掉」改造的核心 —— 源的开放集合
     * （内置 + 用户自建）和它们各自的开关必须住在一起，否则「关掉一个内置源」
     * 和「新加一个源」会走两条互不知情的路径。
     */
    private val sourceStore = SourceStore(app)

    /**
     * 已读记录。和 [cache] 一样落在 filesDir，只是键换成了「这篇读过没有」。
     *
     * 单独一个存储而不是塞进偏好文件：一次刷新六百多条卡片里点过的都要记，
     * 而 SharedPreferences 每次改一个键都要重写整份 XML。
     */
    private val readState = ReadStateStore.forApp(app)

    private val opmlParser = OpmlParser()

    /**
     * 端侧语义模型的持有者。
     *
     * 挂在 ViewModel 上而不是界面里：加载要读 24MB 模型再建图 + 预热，几百毫秒起步，
     * 界面重组或切页不能把它丢掉。
     *
     * 加载时机是「真正要用语义排序的时候」（首次刷新、下载完成、设置页自检），
     * 不在 init 里预加载 —— 模型没下载或用户关掉了语义排序时，不该白付这份代价。
     */
    private val semanticEngine = SemanticEngine(app)

    /** 文章句向量的磁盘缓存。和 [cache] 同构，只是键换成了「文章来自哪条通道」。 */
    private val vectorStore = VectorStore.forApp(app)

    /** 「填 key 才能用」的搜索服务的凭据存储。 */
    private val searchKeysStore = SearchApiKeys(app)

    /**
     * 召回用的 HTTP 客户端（普通 GET 通道 + 带 key 的搜索 API 共用同一个底层 client）。
     *
     * 用和订阅抓取同一套超时参数（8s 连接 / 18s 整调用），这样两边的失败手感一致 ——
     * `RecallService` 自己还有一层 12s 的单路超时兜在前面。
     * 单独一个实例而不是和 [repository] 共用，是因为两者打的是完全不同的主机，
     * 共用连接池省不下什么，反而把两个模块耦合在一起。
     */
    private val client = FeedRepository.defaultClient()
    private val http = OkHttpGet(client)
    private val keyedHttp = OkHttpKeyed(client)

    /**
     * 推荐链路的总装。
     *
     * 做成 `var` 是因为通道列表依赖两个可变的设置（自建 RSSHub 地址、哪些通道被关掉），
     * 用户一改就得换一套通道重来。相比给 `RecallService` 塞一个「通道提供者」回调
     * （那会逼着它的单测多绕一层），重建一次更干净 —— 引擎不持有任何跨轮次的状态，
     * `Ranker` 是每次调用现建的，重建等于免费。
     */
    private var recommendEngine = buildEngine()

    private val _semanticStatus = MutableStateFlow<SemanticStatus>(SemanticStatus.NotLoaded)
    val semanticStatus: StateFlow<SemanticStatus> = _semanticStatus.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Absent)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** 模型占用的磁盘字节：装好了就是完整大小，否则是半成品的进度。 */
    private val _modelBytes = MutableStateFlow(0L)
    val modelBytes: StateFlow<Long> = _modelBytes.asStateFlow()

    /** 模型文件的目标大小，给界面显示「共 23.0 MB」。 */
    val modelExpectedBytes: Long get() = BgeSmallZh.SIZE_BYTES

    /** 兴趣、召回通道、排序开关的一份快照。设置页只读它。 */
    private val _recommend = MutableStateFlow(readRecommendSettings())
    val recommend: StateFlow<RecommendSettings> = _recommend.asStateFlow()

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** 当前启用的订阅源。首页和抓取都以它为准。 */
    private val _enabledSources = MutableStateFlow(sourceStore.enabledSources())
    val enabledSources: StateFlow<List<FeedSource>> = _enabledSources.asStateFlow()

    /**
     * 内置 + 自建的全部源。设置页的源列表读它。
     *
     * 和 [enabledSources] 分开两条流：设置页要展示所有源（包括关掉的那些）才能给出开关，
     * 而首页只关心开着的那份。合成一条流会让首页在每次开关时白重组一遍。
     */
    private val _allSources = MutableStateFlow(sourceStore.allSources)
    val allSources: StateFlow<List<FeedSource>> = _allSources.asStateFlow()

    private val _useInAppBrowser = MutableStateFlow(settings.useInAppBrowser)
    val useInAppBrowser: StateFlow<Boolean> = _useInAppBrowser.asStateFlow()

    /** crawlbase relay 的基地址，给设置页回显。空串 = 还没同步过。 */
    private val _relayBaseUrl = MutableStateFlow(settings.relayBaseUrl)
    val relayBaseUrl: StateFlow<String> = _relayBaseUrl.asStateFlow()

    /** 已确认过的 relay 要不要在刷新时偷偷重拉一遍清单。 */
    private val _relayAutoSync = MutableStateFlow(settings.relayAutoSync)
    val relayAutoSync: StateFlow<Boolean> = _relayAutoSync.asStateFlow()

    /**
     * 命令口的配对 token。空串 = 这台电脑只出静态 RSS，没开 `--allow-commands`。
     *
     * 它是扫码时顺手存下来的（`myfeed://relay-sync?base=…&tok=…`），没有手填入口 ——
     * 让用户抄 16 个随机字符换不来安全性，只会换来一次「我抄错了」。
     */
    private val _relayCommandToken = MutableStateFlow(settings.relayCommandToken)
    val relayCommandToken: StateFlow<String> = _relayCommandToken.asStateFlow()

    /** 每次现取：地址与 token 都可能刚被扫码换掉，缓存一份会连到上一台电脑。 */
    private fun commandClient() = RelayCommandClient(
        base = settings.relayBaseUrl,
        token = settings.relayCommandToken,
        client = repository.client,
    )

    /**
     * 向那台电脑下一条采集命令。
     *
     * `run` 只回「已受理」，结果靠 [relayCommandStatus] 轮询；plan / regen 是同步的。
     * 这里不判「该不该显示成功」—— 那是 [RelayStatus] 的事，这边只搬运。
     */
    suspend fun sendRelayCommand(action: String, feed: String?): RelayCommands.Reply =
        commandClient().send(action, feed)

    suspend fun relayCommandStatus(): RelayCommands.Status? = commandClient().status()

    /**
     * 上一次去读那台 PC 的清单是什么时候，给「电脑端采集」页的状态行。
     *
     * 静默同步和手动同步都算。它是**展示用**的镜像：真正的节流读的是
     * `settings.relayLastAutoSyncAt`，那边失败也会记一笔，这里不动那个语义。
     */
    private val _relayLastSyncAt = MutableStateFlow(settings.relayLastAutoSyncAt)
    val relayLastSyncAt: StateFlow<Long> = _relayLastSyncAt.asStateFlow()

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    /**
     * 已读记录：`article.id -> 什么时候读的`。
     *
     * 暴露成 Map 而不是 `Set<String>`，是为了让「上次读到」这一信息留着 ——
     * 设置页要报条数、以后想按已读时间清都能用得上。界面判断只需 `id in readAt`。
     */
    private val _readAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val readAt: StateFlow<Map<String, Long>> = _readAt.asStateFlow()

    private val _retentionDays = MutableStateFlow(settings.cacheRetentionDays)
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _cacheMaxMb = MutableStateFlow(settings.cacheMaxMb)
    val cacheMaxMb: StateFlow<Int> = _cacheMaxMb.asStateFlow()

    private val _searchEngine = MutableStateFlow(SearchEngine.of(settings.searchEngineId))
    val searchEngine: StateFlow<SearchEngine> = _searchEngine.asStateFlow()

    /** 内置浏览器的网页暗色模式。 */
    private val _webDarkMode = MutableStateFlow(WebDarkMode.of(settings.webDarkModeId))
    val webDarkMode: StateFlow<WebDarkMode> = _webDarkMode.asStateFlow()

    /**
     * 上一轮召回拿到的候选池。
     *
     * 留着它是为了「改兴趣权重不重打网络」：权重只影响排序，重新召回一遍
     * （48 个请求）换不来新内容。见 [rerankNow]。
     */
    private var lastCandidates: List<Article> = emptyList()

    init {
        viewModelScope.launch {
            // 已读记录先铺，首屏才不会把读过的当新内容推在最前面
            _readAt.value = withContext(Dispatchers.IO) { readState.load() }
            // 每次进程启动一行。已读记录出问题（文件被清、格式判坏、90 天过期）时，
            // 这条是唯一能在本机外面看到的证据 —— 界面上「全都未读」和「记录被清空」长得一样。
            Log.i("FeedReadState", "已读记录 ${_readAt.value.size} 条")
            // 先按当前策略清一遍再加载：改了保留天数/上限之后，下次启动就生效
            val stats = withContext(Dispatchers.IO) {
                cache.prune(settings.cacheRetentionDays, settings.cacheMaxBytes())
                cache.stats()
            }
            _cacheStats.value = stats
            load()
        }
    }

    /**
     * 记下「这篇点开过」。判定标准就是点开，不是读完 —— 见 [ReadStateStore] 的取舍。
     *
     * 写盘丢给 IO 线程：一次点击不该等一次文件重写，而连点几条会由存储层的
     * 串行化兜住。内存里那份先更新，界面立刻压暗。
     */
    fun markRead(article: Article) {
        if (article.id.isBlank() || _readAt.value.containsKey(article.id)) return
        val now = System.currentTimeMillis()
        _readAt.value = _readAt.value + (article.id to now)
        viewModelScope.launch(Dispatchers.IO) { readState.save(_readAt.value, now) }
    }

    /**
     * 清空已读记录（设置页用）。
     *
     * 留着它是因为「点开即已读」会误伤：手滑划到一条就压暗 90 天，没有反悔的入口
     * 就只能等过期。清掉只是回到「全都新鲜」，内容本身一个字节都没动。
     */
    suspend fun clearReadState(): String {
        val cleared = _readAt.value.size
        _readAt.value = emptyMap()
        val ok = withContext(Dispatchers.IO) { readState.save(emptyMap()) }
        return if (cleared == 0) {
            "本来就没有已读记录"
        } else if (ok) {
            "已清掉 $cleared 条已读记录，列表全部回到未读"
        } else {
            "已读记录已在界面上清空，但写盘失败：重启后可能又变回已读"
        }
    }

    fun refresh() = load()

    fun setSourceEnabled(id: String, enabled: Boolean) {
        sourceStore.setEnabled(id, enabled)
        syncEnabledSources()
        load(restart = true)
    }

    /**
     * 添加一个自建源。
     *
     * 三步：**校验格式 → 真拉一次 → 落库**。中间那步是关键 —— 用户是从别处抄来一个
     * 地址，十有八九是错的（给的是首页地址而不是 feed 地址、漏了 `/rss` 后缀、
     * 复制时带上了空格）。不验就收，结果是他过两天才发现有源一直挂在失败横幅里，
     * 而那时早忘了自己加过。
     *
     * 探测到的频道标题会当默认名字：比让用户手打准，也比域名好看。
     *
     * suspend 而不是自己开协程：界面要显示「正在检测…」，也得拿到最终结果才决定关不关窗。
     */
    suspend fun addSource(url: String, name: String, category: String): AddSourceResult {
        SourceRules.addError(url, sourceStore.allSources)?.let {
            return AddSourceResult.Rejected(it)
        }

        val probe = repository.probe(url)
        if (probe is ProbeResult.Failed) {
            return AddSourceResult.Rejected("拿不到这个地址：${probe.message}")
        }

        val ok = probe as ProbeResult.Ok
        val source = sourceStore.addCustom(
            url = url,
            // 用户填了就用他填的；没填用探测到的频道标题；都没有再退回域名
            name = name.trim().ifEmpty { ok.title },
            category = category,
        )
        syncEnabledSources()
        load(restart = true)
        return AddSourceResult.Added(source = source, itemCount = ok.itemCount)
    }

    /**
     * 删掉一个自建源。内置源删不掉（[SourceStore.removeCustom] 会直接忽略）——
     * 那是我这一层就想守住的不变量，而不是靠界面上「不显示删除按钮」来守。
     */
    fun removeSource(id: String) {
        sourceStore.removeCustom(id)
        syncEnabledSources()
        load(restart = true)
    }

    /**
     * 从 OPML 文件导入订阅源。
     *
     * 读盘放在这里而不是界面层，是为了把「读文件 + 解析 + 落库」当成一件事 ——
     * 分散到两边的话，异常处理会在中间断开，用户看到的最多是一句「导入失败」。
     *
     * **不逐个探测**：文件里几十个源逐个 probe 就是几十次网络往返。真拉不动的源
     * 会在下次刷新时进失败横幅，和内置源共用同一套反馈（见 [SourceStore.importSources]）。
     */
    suspend fun importOpml(uri: Uri): OpmlImportResult = withContext(Dispatchers.IO) {
        val text = try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: throw IOException("打不开这个文件")
        } catch (error: Throwable) {
            return@withContext OpmlImportResult.Failed(error.message ?: "读取文件失败")
        }

        val parsed = try {
            opmlParser.parse(text)
        } catch (error: Throwable) {
            // 解析抛异常说明根本不是 XML，和「是 OPML 但没有源」要分开说 ——
            // 前者是选错了文件，后者是这个文件里确实没订阅。
            return@withContext OpmlImportResult.Failed("这个文件不是有效的 OPML")
        }
        if (parsed.isEmpty()) {
            return@withContext OpmlImportResult.Failed("文件里没有找到订阅源")
        }

        val added = sourceStore.importSources(parsed)
        if (added > 0) {
            syncEnabledSources()
            load(restart = true)
        }
        OpmlImportResult.Imported(parsed = parsed.size, added = added)
    }

    /**
     * 从 crawlbase relay 同步订阅 —— 直接拉 PC 上那台 harvest 服务的 `feeds.opml`。
     *
     * 和 [importOpml] 的区别只在来源是网络而不是本地文件，以及**按文件名对齐**
     * （见 [SourceStore.syncRelaySources]）：relay 的清单是会长大的（在 harvest 那边加一个
     * 账号就多一条），而 PC 的 IP 是会变的（家里 DHCP）。所以这里必须能反复点：
     * 第二次同步只补新增的、把搬了地址的原地改掉，用户不需要先删干净再导。
     *
     * **基地址只在拉成功之后才落库**：填错、服务没起、防火墙挡住的地址存进去没有意义，
     * 下次进设置页还是显示上次那个能用的。
     *
     * 界面那条路（扫码 / 深链确认框）走这里；刷新时的静默同步走 [autoSyncRelay]，
     * 两者共用 [syncRelay]。
     */
    suspend fun syncFromRelay(rawAddress: String, token: String = ""): RelaySyncResult {
        val previousBase = settings.relayBaseUrl
        return when (val result = syncRelay(rawAddress, auto = false)) {
            is RelaySyncResult.Synced -> {
                // 只在真连上之后才动钥匙：地址填错时把旧 token 留着，等于下次扫到对的地址
                // 也说不清用的是哪把钥匙
                if (token.isNotBlank()) {
                    settings.relayCommandToken = token
                    _relayCommandToken.value = token
                } else if (previousBase.isNotBlank() &&
                    RelayRules.authorityOf(previousBase) != RelayRules.authorityOf(result.base)
                ) {
                    // 手动改地址、没带 token —— 那已经是另一台电脑了，旧钥匙留在口袋里
                    // 只会把 token 发给不相干的机器。代价是重扫一次，而扫码本来就顺手把
                    // 新地址一起带进来了
                    settings.relayCommandToken = ""
                    _relayCommandToken.value = ""
                }
                result
            }

            is RelaySyncResult.Failed -> result
        }
    }

    /**
     * 「测一下手机能不能连上这台电脑」。
     *
     * 两步：**读清单**（复用 [syncRelay]），再**抽查一条 feed**（复用 [FeedRepository.probe]，
     * 和「添加订阅源」同一套判据）。只读清单不够 —— `feeds.opml` 和 `zhihu-xxx.xml` 是
     * harvest 分两步写出来的，只写过一半时清单读得到、内容拿不到，而用户问的恰恰是
     * 「我手机上到底有没有货」。
     *
     * 走 `auto = true` 那次同步的写法，是为了让这一击**不打扰用户**：不触发整轮刷新
     * （自检不该一点就转十分钟），也不把「我亲手删过的那几条」偷偷加回来。顺带把 PC 上
     * 新加的账号补进清单，报出来的条数才是那边的真实情况。
     *
     * 抽查哪一条：按 [SourceStore.allSources] 而不是启用的那份挑 —— 用户把 relay 的源全关掉
     * 时，链路通不通这件事照样要测得出来。
     */
    suspend fun runRelaySelfTest(): RelaySelfTest {
        val base = settings.relayBaseUrl
        if (base.isBlank()) {
            return RelaySelfTest(
                manifestReached = false,
                reason = "还没配过 PC 地址",
                issue = RelayIssue.FIRST_RUN,
            )
        }

        return when (val result = syncRelay(base, auto = true)) {
            is RelaySyncResult.Failed -> RelaySelfTest(
                manifestReached = false,
                issue = result.issue,
                reason = result.reason,
            )

            is RelaySyncResult.Synced -> {
                val authority = RelayRules.authorityOf(result.base)
                val sample = _allSources.value.firstOrNull {
                    it.custom && RelayRules.authorityOf(it.url) == authority
                }
                val probe = sample?.let { runCatching { repository.probe(it.url) }.getOrNull() }
                RelaySelfTest(
                    manifestReached = true,
                    manifestFeeds = result.total,
                    added = result.added,
                    moved = result.moved,
                    sampleName = sample?.name,
                    sampleItems = (probe as? ProbeResult.Ok)?.itemCount,
                    sampleError = (probe as? ProbeResult.Failed)?.message
                        ?: sample?.let { if (probe == null) "没测成" else null },
                )
            }
        }
    }

    private suspend fun syncRelay(rawAddress: String, auto: Boolean): RelaySyncResult =
        withContext(Dispatchers.IO) {
            val base = RelayRules.normalizeBase(rawAddress)
                ?: return@withContext RelaySyncResult.Failed(
                    "不像个地址。填 PC 的 IP 就行，例如 192.168.1.20" +
                        "（不带端口按 ${RelayRules.DEFAULT_PORT} 算）",
                    RelayIssue.BAD_ADDRESS,
                )

            val xml = try {
                http.text(RelayRules.opmlUrl(base), "text/x-opml, application/xml, text/xml, */*")
            } catch (error: Throwable) {
                return@withContext relayFailure(error)
            }

            val parsed = try {
                // baseUrl 交给解析器：harvest 的清单写相对 xmlUrl，绝对地址才进得了 looksLikeUrl。
                // 分类兜底用「电脑端采集」而不是「自建」—— 这批内容的出处是那台 PC，
                // 首页按分类筛内容时要筛得到它（见 [SourceRules.RELAY_CATEGORY]）
                opmlParser.parse(xml, baseUrl = base, fallbackCategory = SourceRules.RELAY_CATEGORY)
            } catch (error: Throwable) {
                return@withContext RelaySyncResult.Failed(
                    "这个地址没返回 OPML。端口要填 harvest 的 serve 端口，不是浏览器的调试端口",
                    RelayIssue.NOT_A_MANIFEST,
                )
            }

            val sources = RelayRules.rebaseSources(parsed, base)
            if (sources.isEmpty()) {
                return@withContext RelaySyncResult.Failed(
                    "这份 OPML 里没有可用的订阅源",
                    RelayIssue.EMPTY_MANIFEST,
                )
            }

            settings.relayBaseUrl = base
            _relayBaseUrl.value = base

            // auto = 静默同步：要尊重「用户亲手删过」那份名单，也不在此处再触发一轮 load
            // （调用方就在 load 里，重新 load 等于自己套自己）
            val counts = sourceStore.syncRelaySources(sources, respectRemoved = auto)
            if (counts.changed > 0) {
                syncEnabledSources()
                if (!auto) load(restart = true)
            }
            // 只有真读到并落地才算「上次读到」；失败不动这个数（界面上那句话见 RelayStatus）
            _relayLastSyncAt.value = System.currentTimeMillis()
            RelaySyncResult.Synced(
                base = base,
                total = sources.size,
                added = counts.added,
                moved = counts.moved,
            )
        }

    /** 上一次静默同步正在跑：同一轮里不该并发拉两份清单。 */
    private var relayAutoSyncing = false

    /**
     * 刷新时顺带把已确认过的那台 relay 清单再拉一遍。返回**源列表有没有被改动**。
     *
     * 静默的意思是：不弹确认框（用户已经为这个地址点过一次「确认」），拉不动也不出
     * 失败横幅 —— 那台 PC 不在线时，21 个源本身就会各自进横幅，不必再多一条。
     * 但**一定要在 logcat 留一行**：它改的是订阅列表，出问题时必须能回答
     * 「这几条源是谁加进来的、什么时候」。
     */
    private suspend fun autoSyncRelay(): Boolean {
        val base = settings.relayBaseUrl
        val now = System.currentTimeMillis()
        if (relayAutoSyncing ||
            !RelayAutoSync.shouldRun(settings.relayAutoSync, base, settings.relayLastAutoSyncAt, now)
        ) {
            return false
        }
        relayAutoSyncing = true
        // 先占住节流再打网络：失败也算一次尝试，否则 PC 关机时每次刷新都要等一次连接超时
        settings.relayLastAutoSyncAt = now
        return try {
            when (val result = syncRelay(base, auto = true)) {
                is RelaySyncResult.Synced -> {
                    Log.i(
                        "FeedRelay",
                        "自动同步 ${result.base}：清单 ${result.total} 条 · " +
                            "新增 ${result.added} · 换地址 ${result.moved}",
                    )
                    result.added + result.moved > 0
                }

                is RelaySyncResult.Failed -> {
                    // 静默同步不出横幅，但日志要能回答「是哪一类问题」——
                    // 界面上的电脑端提示是按主机匹配的，PC 醒着时这条就是唯一的线索
                    Log.i(
                        "FeedRelay",
                        "自动同步没成（${base}）：${result.reason} · ${result.issue}",
                    )
                    false
                }
            }
        } finally {
            relayAutoSyncing = false
        }
    }

    fun setRelayAutoSync(value: Boolean) {
        settings.relayAutoSync = value
        _relayAutoSync.value = value
    }

    fun setUseInAppBrowser(value: Boolean) {
        settings.useInAppBrowser = value
        _useInAppBrowser.value = value
    }

    fun setSearchEngine(engine: SearchEngine) {
        settings.searchEngineId = engine.id
        _searchEngine.value = engine
    }

    fun setWebDarkMode(mode: WebDarkMode) {
        settings.webDarkModeId = mode.id
        _webDarkMode.value = mode
    }

    // ------------------------------------------------------------------ 兴趣与召回

    /**
     * 从磁盘同步一次推荐相关设置。进设置页时调。
     *
     * 需要它是因为有两个东西会在别处变化：模型文件可能被清理工具删掉，
     * 向量缓存可能被系统清掉。进页面时探一次，比让用户看一份过期的状态好。
     */
    fun syncRecommendSettings() {
        reloadRecommendFromStore()
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { vectorStore.stats() }
            _recommend.update { it.copy(vectorStats = stats) }
        }
    }

    /**
     * 加一条兴趣。
     *
     * 返回一个带成败的结果而不是一句提示文案：界面要区分「加成功了」和「没加进去」——
     * 前者关窗、后者把原因留在输入框下面。只用字符串的话得靠前缀去猜，太脆。
     */
    fun addInterest(raw: String): AddInterestResult {
        val current = _recommend.value.interests
        Interests.addError(raw, current)?.let { return AddInterestResult.Rejected(it) }

        val keyword = Interests.sanitize(raw)
        persistInterests(current + Interest(keyword), needsRecall = true)
        return AddInterestResult.Added("已添加「$keyword」")
    }

    fun removeInterest(id: String) {
        persistInterests(
            _recommend.value.interests.filterNot { it.id == id },
            needsRecall = false,
        )
    }

    fun setInterestEnabled(id: String, enabled: Boolean) {
        persistInterests(
            _recommend.value.interests.map { if (it.id == id) it.copy(enabled = enabled) else it },
            // 重新启用要把这个词的查询补回来，关掉不用
            needsRecall = enabled,
        )
    }

    /** 改权重。传进来的值会被吸附到 [Interests.WEIGHT_STEPS] 上，界面上就是选档。 */
    fun setInterestWeight(id: String, weight: Float) {
        val snapped = Interests.snapWeight(weight)
        persistInterests(
            _recommend.value.interests.map { if (it.id == id) it.copy(weight = snapped) else it },
            needsRecall = false,
        )
    }

    /**
     * 兴趣的增删改都落这里：**存盘 + 立刻让首页跟上**。
     *
     * [needsRecall] 区分两种改动，这是有实际意义的：
     *  - **加词、或者重新启用一个词** → 需要重新召回。新词的查询还没发出去过，
     *    光重排现有候选是变不出新内容的。
     *  - **改权重、关掉一个词、删词** → 只重排就够了。候选池里已有的东西完全够用，
     *    为了一次拖动再打 48 个请求说不过去（见 [rerankNow]）。
     */
    private fun persistInterests(next: List<Interest>, needsRecall: Boolean) {
        interestStore.interests = next
        _recommend.update { it.copy(interests = next) }
        if (needsRecall) load(restart = true) else rerankNow()
    }

    /**
     * 开关一条召回通道。
     *
     * 这个必须**重新召回**：通道决定了候选从哪来，换个通道集合候选池就完全不同了。
     * 和改兴趣的处理方式不一样，别合并。
     */
    fun setRecallChannelEnabled(id: String, enabled: Boolean) {
        val disabled = interestStore.disabledRecallChannels.toMutableSet()
        if (enabled) disabled -= id else disabled += id
        interestStore.disabledRecallChannels = disabled

        recommendEngine = buildEngine()
        reloadRecommendFromStore()
        load(restart = true)
    }

    /** 自建 RSSHub 地址。空串表示用内置公共镜像（不是关掉这条通道）。 */
    fun setRssHubBaseUrl(url: String) {
        interestStore.rssHubBaseUrl = url
        recommendEngine = buildEngine()
        reloadRecommendFromStore()
        load(restart = true)
    }

    /**
     * 保存某个搜索服务的 API key。
     *
     * 和开关通道一样必须**重新召回**：key 变了候选池就变了。清掉 key 不会立刻把首页已有的
     * 那些结果删掉 —— 它们已经在缓存里，下一轮刷新才会按新配置重算。
     */
    fun setSearchKey(id: String, key: String) {
        searchKeysStore.setKey(id, key)
        recommendEngine = buildEngine()
        reloadRecommendFromStore()
        load(restart = true)
    }

    /** 关掉语义排序。编码器不卸载 —— 关掉只是这一轮不用它，留着重开时省一次加载。 */
    fun setSemanticEnabled(value: Boolean) {
        interestStore.semanticEnabled = value
        _recommend.update { it.copy(semanticEnabled = value) }
        load(restart = true)
    }

    /** 列表里是否显示「命中哪个兴趣」。纯展示开关，不触发任何重算。 */
    fun setShowRecommendReason(value: Boolean) {
        interestStore.showRecommendReason = value
        _recommend.update { it.copy(showReason = value) }
    }

    /** 清掉向量缓存。下次刷新要重新编码全量，属于「修不好就重来」的兜底手段。 */
    suspend fun clearVectorCache(): String {
        val result = withContext(Dispatchers.IO) { vectorStore.clear() }
        val stats = withContext(Dispatchers.IO) { vectorStore.stats() }
        _recommend.update { it.copy(vectorStats = stats) }
        return if (result.removed == 0) {
            "向量缓存本来就是空的"
        } else {
            "已清掉 ${result.removed} 个通道的向量缓存，释放 ${formatBytes(result.freedBytes)}；" +
                "下次刷新会重新编码。"
        }
    }

    private fun buildEngine(): RecommendEngine {
        val disabled = interestStore.disabledRecallChannels
        val channels = RecallChannels.builtIn(
            rssHubBaseUrl = interestStore.rssHubBaseUrl,
            searchKeys = searchKeysStore.toMap(),
            keyedHttp = keyedHttp,
        ).filter { it.id !in disabled }
        return RecommendEngine(recall = RecallService(channels, http), vectors = vectorStore)
    }

    private fun readRecommendSettings(): RecommendSettings {
        val disabled = interestStore.disabledRecallChannels
        val keys = searchKeysStore
        return RecommendSettings(
            interests = interestStore.interests,
            channels = RecallChannels.settings(interestStore.rssHubBaseUrl)
                .map { RecallChannelSetting(it.id, it.name, it.hint, it.id !in disabled) },
            searchKeys = SearchApiKeys.PROVIDERS.map { p ->
                SearchKeyProvider(p.id, p.name, p.siteUrl, key = keys[p.id])
            },
            rssHubBaseUrl = interestStore.rssHubBaseUrl,
            semanticEnabled = interestStore.semanticEnabled,
            showReason = interestStore.showRecommendReason,
        )
    }

    /** 重读设置但保住已经探到的向量缓存统计 —— 那个是查磁盘查来的，不该被重置。 */
    private fun reloadRecommendFromStore() {
        val fresh = readRecommendSettings()
        _recommend.update { fresh.copy(vectorStats = it.vectorStats) }
    }

    // ------------------------------------------------------------------ 语义模型

    private var modelJob: Job? = null
    private var encoderJob: Job? = null

    /** 从磁盘同步一次模型状态。进设置页时调；**不触发加载**。 */
    fun syncSemanticState() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { semanticEngine.isModelInstalled() }
            _modelBytes.value = withContext(Dispatchers.IO) {
                if (installed) BgeSmallZh.SIZE_BYTES else semanticEngine.partialBytes()
            }
            if (installed) {
                if (_modelState.value !is ModelState.Ready) {
                    _modelState.value = ModelState.Ready(BgeSmallZh.SIZE_BYTES)
                }
            } else if (_modelState.value !is ModelState.Downloading) {
                // 磁盘上确实没有模型，把上次失败留下的红字也清掉，回到「未下载」
                _modelState.value = ModelState.Absent
                if (semanticEngine.status is SemanticStatus.NotLoaded) {
                    _semanticStatus.value = SemanticStatus.NotLoaded
                }
            }
        }
    }

    /**
     * 下载模型，装好后立刻加载并预热。
     *
     * 下载和加载放在同一个任务里是有意的：用户点「下载」想要的最终结果是
     * 「语义推荐能用了」，而不是「文件躺在磁盘上」。中间任何一步失败都要报出来。
     */
    fun downloadModel() {
        if (modelJob?.isActive == true) return
        _modelState.value = ModelState.Downloading(
            source = semanticEngine.sources.firstOrNull()?.name.orEmpty(),
            received = 0L,
            total = BgeSmallZh.SIZE_BYTES,
        )
        modelJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    semanticEngine.installModel { state ->
                        _modelState.value = state
                        if (state is ModelState.Downloading) _modelBytes.value = state.received
                    }
                }
            } catch (error: Throwable) {
                _modelState.value = ModelState.Failed(error.message ?: "下载失败")
                return@launch
            }
            _modelBytes.value = BgeSmallZh.SIZE_BYTES
            loadEncoder()
        }
    }

    /**
     * 加载编码器并预热，结果写进 [semanticStatus]；成功后顺手让首页按语义重排一遍。
     * 下载完成后自动调一次；设置页的「自检」也调它。
     *
     * 与 [ensureEncoder] 的区别：**这条路允许重试**。刷新流程里的自动加载遇到
     * 失败就不再反复尝试（每次刷新白等一遍加载没什么意义），而用户明确点了自检，
     * 就该真的再试一次。
     */
    fun loadEncoder() {
        if (encoderJob?.isActive == true) return
        encoderJob = viewModelScope.launch {
            _semanticStatus.value = SemanticStatus.Loading
            // Default 而不是 IO：建图和预热是纯 CPU 计算，别去占网络那组线程
            withContext(Dispatchers.Default) { semanticEngine.getOrLoad() }
            _semanticStatus.value = semanticEngine.status
            load(restart = true)
        }
    }

    /** 删除模型与已加载的编码器。下载中的任务也会被取消。 */
    fun deleteModel() {
        modelJob?.cancel()
        encoderJob?.cancel()
        semanticEngine.removeModel()
        _modelState.value = ModelState.Absent
        _semanticStatus.value = SemanticStatus.NotLoaded
        _modelBytes.value = 0L
        // 立刻降级重排，否则列表会一直保持着「算了语义分」的那个顺序，
        // 而设置页已经写着「语义排序已关闭」，前后对不上
        rerankNow()
    }

    /**
     * 确保编码器可用。没装模型、用户关掉了语义排序、或上次加载失败时返回 `null`，
     * 排序自然降级成「词法 + 时间」。
     *
     * **会被刷新流程 await**，所以第一次带语义的刷新要多等一两秒（读 24MB + 建图 + 预热）。
     * 这是有意的取舍：先用词法排一遍、等模型好了再跳一次序，比多等一秒更难受。
     * 第二次之后 `SemanticEngine.getOrLoad` 直接返回缓存，不再有额外开销。
     */
    private suspend fun ensureEncoder(): TextEncoder? {
        if (!_recommend.value.semanticEnabled) return null
        // 失败不自动重试：native 库缺失、ABI 不匹配这类问题重试一百次也一样，
        // 而每次重试都要把整个加载流程白走一遍。想重试去设置页点自检。
        if (semanticEngine.status is SemanticStatus.Failed) return null

        if (semanticEngine.status is SemanticStatus.NotLoaded) {
            _semanticStatus.value = SemanticStatus.Loading
        }
        val encoder = withContext(Dispatchers.Default) { semanticEngine.getOrLoad() }
        _semanticStatus.value = semanticEngine.status
        return encoder
    }

    override fun onCleared() {
        semanticEngine.close()
        super.onCleared()
    }

    // ------------------------------------------------------------------ 缓存设置

    /** 改保留天数：立刻按新策略清理，不用等下次刷新。 */
    fun setCacheRetentionDays(days: Int) {
        settings.cacheRetentionDays = days
        _retentionDays.value = days
        viewModelScope.launch { pruneNow() }
    }

    /** 改容量上限：同上，立刻生效。 */
    fun setCacheMaxMb(mb: Int) {
        settings.cacheMaxMb = mb
        _cacheMaxMb.value = mb
        viewModelScope.launch { pruneNow() }
    }

    /** 按当前策略清理一遍，返回给设置页做提示。 */
    suspend fun pruneNow(): String {
        val result = withContext(Dispatchers.IO) {
            cache.prune(settings.cacheRetentionDays, settings.cacheMaxBytes())
        }
        _cacheStats.value = withContext(Dispatchers.IO) { cache.stats() }
        return if (result.removed == 0) {
            "没有需要清理的缓存"
        } else {
            "已清理 ${result.removed} 个源的缓存，释放 ${formatBytes(result.freedBytes)}"
        }
    }

    /** 清空全部缓存。返回提示文案。 */
    suspend fun clearCacheNow(): String {
        val result = withContext(Dispatchers.IO) { cache.clear() }
        _cacheStats.value = withContext(Dispatchers.IO) { cache.stats() }
        return if (result.removed == 0) {
            "缓存本来就是空的"
        } else {
            "已清空 ${result.removed} 个源的缓存，释放 ${formatBytes(result.freedBytes)}"
        }
    }

    // ------------------------------------------------------------------ 加载

    private fun syncEnabledSources() {
        // 两条流一起刷：源**集合**变了（加了/删了自建源）和**开关**变了都会走到这里，
        // 分开写迟早有一处漏掉，然后设置页和首页对不上
        _allSources.value = sourceStore.allSources
        _enabledSources.value = sourceStore.enabledSources()
    }

    private suspend fun restoreFromCache(sources: List<FeedSource>) {
        val enabled = sources.map { it.id }.toSet()
        val cached = withContext(Dispatchers.IO) { cache.readAll() }
            .filter { it.sourceId in enabled }
        if (cached.isEmpty()) return

        // distinctBy(id)：首页拿 article.id 当 LazyColumn 的 key，重复就崩
        val articles = cached.flatMap { it.articles }.distinctBy { it.id }.sortedByDescending { it.publishedAt }
        if (articles.isEmpty()) return

        _state.update {
            it.copy(
                // 缓存铺上就不再整屏转圈，改由顶部的下拉指示器表示「网络还在拉」
                isLoading = false,
                isRefreshing = true,
                // 缓存里的文章没有分数（分数是每轮现算的），先按时间序摆出来。
                // 等网络那一轮回来会整体换成排好序的那份。
                scored = articles.map { article -> article.unscored() },
                lastUpdated = cached.maxOfOrNull { feed -> feed.fetchedAt } ?: 0L,
                showingCache = true,
            )
        }
    }

    /** 「没有任何内容来源」的引导面板。清掉旧列表，也别让下拉指示器一直转。 */
    private fun showNoContentSource() {
        lastCandidates = emptyList()
        _state.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                scored = emptyList(),
                failures = emptyList(),
                error = null,
                noContentSource = true,
                showingCache = false,
                stats = null,
            )
        }
    }

    private var loadJob: Job? = null

    private fun load(restart: Boolean = false) {
        val current = _state.value
        if (restart) {
            // 改了设置就期望立刻看到变化，把还在跑的那次换掉，
            // 否则会卡在下面那个守卫上，改了跟没改一样
            loadJob?.cancel()
        } else if (current.isLoading || current.isRefreshing) {
            return
        }

        val sources = _enabledSources.value

        // 一条内容来源都没有：既没兴趣词、也没启用的订阅源。这不是错误，给引导面板。
        // 注意这里**不再**拿「启用的源为空」当异常 —— 只要有兴趣词，六路召回照样能填满首页。
        if (sources.isEmpty() && _recommend.value.activeCount == 0) {
            if (settings.relayBaseUrl.isNotBlank()) {
                // 但确认过 relay 地址时先别急着下结论：源清单本身可能就是那次同步该带回来的
                // （换机、清数据、或者装好之后一直没点过刷新）。悄悄拉一次，拉到了重跑一遍，
                // 拉不到（含被半小时节流挡掉）再回到引导面板 —— 不能让它停在一个转圈的中间态。
                loadJob = viewModelScope.launch {
                    if (autoSyncRelay()) load(restart = true) else showNoContentSource()
                }
                return
            }
            showNoContentSource()
            return
        }

        // 有内容就只转下拉指示器，空页面才整屏转圈
        val coldStart = !current.hasContent
        _state.update {
            it.copy(
                isLoading = coldStart,
                isRefreshing = !coldStart,
                error = null,
                noContentSource = false,
            )
        }

        loadJob = viewModelScope.launch {
            // 先给 relay 一次机会：PC 上加了账号、或者换了 IP，这一轮抓取就该用上新那份源。
            // 半小时最多一次（见 [RelayAutoSync.shouldRun]），且只在用户确认过地址之后才发生。
            var roundSources = sources
            if (autoSyncRelay()) roundSources = _enabledSources.value

            // 磁盘缓存先顶上：秒开，断网时也有东西看
            if (coldStart) restoreFromCache(roundSources)

            // 每次进这里都重读一次设置：兴趣可能在加载途中被改过（改了会走 cancel 重来，
            // 但通道开关只改引擎不改这次任务），读最新的那个版本最省心
            val snapshot = _recommend.value

            // 订阅源抓取。关掉「已订阅源」这条通道就不抓 —— 那正是这个开关的意义：
            // 把 RSS 降级成一个可选语料来源，省掉十几次请求。
            val fetched = if (snapshot.subscriptionEnabled && roundSources.isNotEmpty()) {
                repository.fetchAll(roundSources)
            } else {
                FetchOutcome(emptyList(), emptyList())
            }

            val encoder = ensureEncoder()

            val outcome = recommendEngine.recommend(
                interests = snapshot.interests,
                subscribed = fetched.articles,
                encoder = encoder,
                pool = semanticEngine.pool(),
            )
            lastCandidates = outcome.articles.map { it.article }

            // 只有订阅内容落盘。召回结果是搜出来的，下一轮自己会重新搜，
            // 缓存它们既占地方又会让「离线时看到一堆搜来的东西」显得莫名其妙。
            // （向量缓存是另一回事，那个由 RecommendEngine 自己管，键是文章所在通道。）
            val stats = withContext(Dispatchers.IO) {
                val enabled = sources.map { it.id }.toSet()
                fetched.articles
                    .filter { it.sourceId in enabled }
                    .groupBy { it.sourceId }
                    .forEach { (id, list) -> cache.write(id, list) }
                cache.prune(settings.cacheRetentionDays, settings.cacheMaxBytes())
                cache.stats()
            }
            _cacheStats.value = stats
            val vectorStats = withContext(Dispatchers.IO) { vectorStore.stats() }
            _recommend.update { it.copy(vectorStats = vectorStats) }

            _state.update { previous ->
                val failures = fetched.failures + outcome.failures
                val nothingLoaded = outcome.articles.isEmpty() && failures.isNotEmpty()

                previous.copy(
                    isLoading = false,
                    isRefreshing = false,
                    // 一条都没排出来时保留旧列表，别把用户正看着的内容清空
                    scored = if (outcome.articles.isEmpty()) previous.scored else outcome.articles,
                    failures = failures,
                    error = if (nothingLoaded && !previous.hasContent) {
                        failures.joinToString("\n") { "${it.source}：${it.message}" }
                    } else {
                        null
                    },
                    lastUpdated = if (outcome.articles.isNotEmpty()) {
                        System.currentTimeMillis()
                    } else {
                        previous.lastUpdated
                    },
                    // 一个来源都没新内容、列表还是缓存那份时，继续标成缓存
                    showingCache = outcome.articles.isEmpty() && previous.showingCache,
                    stats = outcome.stats,
                )
            }
        }
    }

    /**
     * 用**已有的候选池**重排，不打网络。改兴趣权重/开关、删模型之后走这条路。
     *
     * 候选池还没建立（首次进来自动加载、或刚被清空）时退回完整刷新 ——
     * 那种情况下确实没有东西可排。
     *
     * 正在跑完整刷新时直接让路：那次刷新结束时会用最新的兴趣重排一遍，
     * 现在插一脚只会让两次结果互相覆盖。
     */
    private fun rerankNow() {
        if (loadJob?.isActive == true) return

        val candidates = lastCandidates
        if (candidates.isEmpty()) {
            load(restart = true)
            return
        }

        loadJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }

            val encoder = ensureEncoder()
            val outcome = recommendEngine.rerank(
                candidates = candidates,
                interests = _recommend.value.interests,
                encoder = encoder,
                pool = semanticEngine.pool(),
            )

            _state.update {
                it.copy(
                    isRefreshing = false,
                    scored = outcome.articles,
                    stats = outcome.stats,
                )
            }
        }
    }
}

/** 添加兴趣的结果。成功带一句提示，失败带原因 —— 两者界面上的处理方式完全不同。 */
sealed interface AddInterestResult {

    data class Added(val message: String) : AddInterestResult

    data class Rejected(val reason: String) : AddInterestResult
}

/**
 * 添加订阅源的结果。
 *
 * 失败和 [AddInterestResult] 一样要分开处理（关窗 vs 把原因留在输入框下面），
 * 成功多带两条信息：落库后的源（id 由地址派生，界面不该自己算）和探测到的条目数 ——
 * 给用户一个「这源确实活着」的直观证据，比一句「添加成功」有说服力。
 */
sealed interface AddSourceResult {

    data class Added(val source: FeedSource, val itemCount: Int) : AddSourceResult

    data class Rejected(val reason: String) : AddSourceResult
}

/**
 * OPML 导入的结果。
 *
 * [parsed] 和 [added] 分开报是有用的：文件里 40 个源只新增了 3 个，说明大部分
 * 你早就订过了 —— 这比笼统一句「导入成功」让用户清楚得多。
 */
sealed interface OpmlImportResult {

    data class Imported(val parsed: Int, val added: Int) : OpmlImportResult

    data class Failed(val reason: String) : OpmlImportResult
}

/**
 * 从 crawlbase relay 同步的结果。见 [FeedViewModel.syncFromRelay]。
 *
 * [moved] 单独报出来，是因为它对应的正是「PC 换了 IP」这件事 —— 用户不必先手动删掉
 * 老地址那几条，界面告诉他「搬了 N 条」他才知道刚才发生了什么。
 */
sealed interface RelaySyncResult {

    /**
     * @param base 真正生效的基地址（已经过规整，可能和用户填的不完全一样）
     * @param total OPML 里可用的订阅条数
     * @param added 新增条数
     * @param moved 只改了地址的条数
     */
    data class Synced(val base: String, val total: Int, val added: Int, val moved: Int) : RelaySyncResult

    /**
     * [reason] 是能直接展示给用户的中文说明；[issue] 是同一个失败的**类型**，
     * 界面拿它去要「电脑端该做什么」那份清单（见 [RelayGuidance.sectionsFor]）——
     * 原因写给用户看，类型写给代码用，字符串以后改措辞不会把提示改坏。
     */
    data class Failed(val reason: String, val issue: RelayIssue) : RelaySyncResult
}

/**
 * relay 是局域网里的明文服务，报错得说人话：这几类失败的原因和修法完全不同，
 * 而且修法**全在那台 PC 上**，所以每条都带上对应的 [RelayIssue]。
 */
private fun relayFailure(error: Throwable): RelaySyncResult.Failed = when (error) {
    is UnknownHostException -> RelaySyncResult.Failed(
        "找不到这台主机 —— 确认手机和 PC 连的是同一个网络",
        RelayIssue.DIFFERENT_NETWORK,
    )

    is ConnectException -> RelaySyncResult.Failed(
        "连不上 —— PC 上 harvest 的服务起来了吗？端口填对了吗？",
        RelayIssue.SERVICE_DOWN,
    )

    is SocketTimeoutException -> RelaySyncResult.Failed(
        "连接超时 —— PC 多半不在这个地址上",
        RelayIssue.UNREACHABLE,
    )

    is IOException -> when {
        error.message?.startsWith("HTTP") == true -> RelaySyncResult.Failed(
            "${error.message} —— 地址能连上，但那里没有 feeds.opml",
            RelayIssue.NO_FILE,
        )

        else -> RelaySyncResult.Failed(error.message ?: "网络错误", RelayIssue.UNKNOWN)
    }

    else -> RelaySyncResult.Failed(
        error.message ?: error.javaClass.simpleName,
        RelayIssue.UNKNOWN,
    )
}

/** 缓存铺出来的文章没有分数：给一个空壳，界面据此不显示推荐理由。 */
private fun Article.unscored(): ScoredArticle = ScoredArticle(
    article = this,
    score = 0f,
    semantic = 0f,
    lexical = 0f,
    recency = 0f,
    topInterest = "",
    matchedInterests = emptyList(),
)

/** 给设置页显示缓存占用。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
