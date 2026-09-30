package com.example.feedreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.feedreader.data.Article
import com.example.feedreader.data.ArticleSearch
import com.example.feedreader.data.DateParser
import com.example.feedreader.data.DeepLink
import com.example.feedreader.data.FeedFailure
import com.example.feedreader.data.FeedSources
import com.example.feedreader.data.Interests
import com.example.feedreader.data.RelayGuidance
import com.example.feedreader.data.RelayIssue
import com.example.feedreader.data.RelayStatus
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.SourceRules
import com.example.feedreader.recommend.RecommendStats
import com.example.feedreader.recommend.ScoredArticle
import com.example.feedreader.ui.AddSourceResult
import com.example.feedreader.ui.FeedUiState
import com.example.feedreader.ui.FeedViewModel
import com.example.feedreader.ui.InterestsScreen
import com.example.feedreader.ui.RecommendSettings
import com.example.feedreader.ui.ReaderScreen
import com.example.feedreader.ui.RelayScreen
import com.example.feedreader.ui.RelaySyncResult
import com.example.feedreader.ui.SettingsScreen
import com.example.feedreader.ui.clearWebViewCache
import kotlinx.coroutines.launch

private const val TAB_LIST = 0
private const val TAB_CARD = 1

/**
 * 抽屉打开的那一页。0 = 就在首页。
 *
 * 用 Int 而不是枚举：`rememberSaveable` 要把它写进 Bundle，转屏幕之后还认得回来 ——
 * 枚举能不能存取决于版本，没必要为这个赌一把。
 */
private const val PAGE_NONE = 0
private const val PAGE_INTERESTS = 1
private const val PAGE_RELAY = 2
private const val PAGE_SETTINGS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedReaderApp(
    viewModel: FeedViewModel = viewModel(),
    deepLink: DeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val sources by viewModel.enabledSources.collectAsState()
    val allSources by viewModel.allSources.collectAsState()
    val useInAppBrowser by viewModel.useInAppBrowser.collectAsState()
    val relayBaseUrl by viewModel.relayBaseUrl.collectAsState()
    val relayAutoSync by viewModel.relayAutoSync.collectAsState()
    val relayLastSyncAt by viewModel.relayLastSyncAt.collectAsState()
    val relayCommandToken by viewModel.relayCommandToken.collectAsState()
    val cacheStats by viewModel.cacheStats.collectAsState()
    val retentionDays by viewModel.retentionDays.collectAsState()
    val cacheMaxMb by viewModel.cacheMaxMb.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val webDarkMode by viewModel.webDarkMode.collectAsState()
    val semanticStatus by viewModel.semanticStatus.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val modelBytes by viewModel.modelBytes.collectAsState()
    val recommend by viewModel.recommend.collectAsState()
    val readAt by viewModel.readAt.collectAsState()
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(FeedSources.ALL) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_LIST) }
    var page by rememberSaveable { mutableIntStateOf(PAGE_NONE) }

    /**
     * 「只看这一条源」：从「电脑端采集」页点某条采到的内容进来时非空，存的是源的 id。
     *
     * 和 [category] 是同一个槽位的两种取值（都在筛「这一屏看什么」），所以互斥：
     * 它非空时分类筛选让位，点任何一个分类 chip 就把它清掉。之所以按源而不是按分类，
     * 是因为电脑端那台机器一个账号一条 feed（21 条知乎源同类同名），按「电脑端采集」
     * 筛只能看到一屏混在一起的人。
     */
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }

    /** 只看未读。转屏幕方向要留着，但不必写进偏好 —— 它是一次阅读会话的手感，不是设置。 */
    var hideRead by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Article?>(null) }
    // 应用内扫一扫认出来的链接，和外发深链走同一套确认框与执行逻辑
    var scanned by remember { mutableStateOf<DeepLink?>(null) }
    // 首页失败横幅点进「电脑端采集」页时带过去的症状：非空 = 对症那一节摆在全份清单前面
    var relayFocus by remember { mutableStateOf<RelayIssue?>(null) }

    // 分类 chips 改成从「列表里实际出现的来源」派生。
    // 原来是按启用的订阅源算的，换成兴趣流之后彻底对不上了 —— CSDN、V2EX、
    // Hacker News 这些来源根本不在订阅源列表里，用户永远筛不到它们。
    // 顺序取分数序（`.distinct()` 保留首次出现顺序），最相关的来源自然排在最前面。
    val categories = remember(state.scored) {
        FeedSources.categoriesOf(state.scored.map { it.article })
    }
    // 选中的分类对应的来源被关掉后，自动落回「全部」
    val activeCategory = if (category in categories) category else FeedSources.ALL
    // 按 id 认不回源（被删了、或换 IP 之后 id 变了）就当没筛：宁可显示全列表，
    // 也不要一个筛不出任何东西的隐形过滤器挂在顶上
    val filteredSource = sourceFilter?.let { id -> allSources.firstOrNull { it.id == id } }

    // 过滤后仍保留 ScoredArticle —— 推荐理由要跟着条目一起走，
    // 只留 Article 的话列表就再也说不出「为什么推这条」了
    //
    // 已读的判断也放在这里而不是排序里：Ranker 的分数是「为什么推这条」的依据，
    // 拿已读去扣分等于把可见性和推荐混成一团，事后想知道「这条为什么排前面」就查不清了。
    // 所以已读只做两件事 —— 压暗、按开关隐藏，一分都不改。
    val inCategory = remember(state.scored, activeCategory, filteredSource) {
        state.scored.filter {
            if (filteredSource != null) it.article.sourceId == filteredSource.id
            else activeCategory == FeedSources.ALL || it.article.category == activeCategory
        }
    }
    // 「本轮会话之前读过」的集合。只看未读时用它而不是整个 readAt：
    // 刚点的那条要留在屏幕上，否则从阅读页返回的瞬间它就被筛掉了，
    // 列表跟着往上跳一格 —— 症状像「我点开的一条内容凭空消失」。
    // 下次冷启动这条豁免就没了，那时它该藏就藏。
    val sessionStart = remember { System.currentTimeMillis() }
    val readBeforeSession = remember(readAt, sessionStart) {
        readAt.filterValues { it < sessionStart }.keys
    }
    val unreadCount = remember(inCategory, readBeforeSession) {
        inCategory.count { it.article.id !in readBeforeSession }
    }
    val visible = remember(inCategory, query, readAt, readBeforeSession, hideRead) {
        val keyword = query.trim()
        inCategory.filter { scored ->
            val article = scored.article
            (!hideRead || article.id !in readBeforeSession) &&
                ArticleSearch.matches(article, keyword)
        }
    }

    // 「这台电脑采到的内容」那一节的行数。数据取自**首页那份列表**而不是另开一次缓存
    // 读取：同一屏不能有两个真相来源 —— 首页翻不到的东西，这一页也不该报出条数来。
    val relayRows = remember(state.scored, allSources, relayBaseUrl) {
        RelayStatus.feedRows(relayBaseUrl, allSources, state.articles)
    }

    val openArticle: (Article) -> Unit = { article ->
        // 点开即已读，不管最后在哪读：内置浏览器、系统浏览器都算看过了
        viewModel.markRead(article)
        if (useInAppBrowser) opened = article else openExternal(context, article.link)
    }

    // 网页搜索走同一条路：内置浏览器开着就在站内看结果，否则交给系统浏览器
    val openWebSearch: (String) -> Unit = { raw ->
        val keyword = raw.trim()
        if (keyword.isNotEmpty()) openArticle(searchArticle(searchEngine, keyword))
    }

    // 深链放在 opened/settings 的早返回之前：用户可能正停在文章页或设置页扫码，
    // 确认框要能盖在当前界面上，而不是等他回到列表才看得见。
    // 应用内扫一扫和系统相机扫（go.html → myfeed://）走的是同一个框、同一段执行逻辑 ——
    // 区别只在这条链是谁递进来的。
    val pendingLink = deepLink ?: scanned
    pendingLink?.let { link ->
        DeepLinkDialog(
            link = link,
            onRun = {
                when (link) {
                    is DeepLink.RelaySync -> when (val result = viewModel.syncFromRelay(link.base, link.token)) {
                        // 配对这件事要说出来：扫的是 /pair 页时用户以为只是订阅，
                        // 其实那一扫同时把「能让它现采」这件事定下来了
                        is RelaySyncResult.Synced ->
                            "已连上 ${result.base}：新增 ${result.added} 条、" +
                                "换地址 ${result.moved} 条（清单共 ${result.total} 条）" +
                                if (link.token.isNotBlank()) "；已配对这个电脑的命令口" else ""

                        is RelaySyncResult.Failed ->
                            // 深链这条路上没有「再点一次同步」的入口，所以把电脑端那一步
                            // 直接接在报错后面 —— 只说连不上，用户就只能干看着这个框
                            "${result.reason}\n电脑端：${RelayGuidance.headline(result.issue)}"
                    }

                    is DeepLink.Subscribe -> when (val result = viewModel.addSource(
                        link.url, link.title, SourceRules.FALLBACK_CATEGORY,
                    )) {
                        is AddSourceResult.Added ->
                            "已订阅「${result.source.name}」，抓到 ${result.itemCount} 条"

                        is AddSourceResult.Rejected -> result.reason
                    }
                }
            },
            onDismiss = {
                scanned = null
                onDeepLinkHandled()
            },
        )
    }

    opened?.let { article ->
        ReaderScreen(
            article = article,
            darkMode = webDarkMode,
            onOpenExternal = { openExternal(context, it) },
            onBack = { opened = null },
        )
        return
    }

    if (page != PAGE_NONE) {
        BackHandler { page = PAGE_NONE }
        when (page) {
            PAGE_INTERESTS -> InterestsScreen(
                recommend = recommend,
                semanticStatus = semanticStatus,
                modelState = modelState,
                modelBytes = modelBytes,
                modelExpectedBytes = viewModel.modelExpectedBytes,
                onAddInterest = viewModel::addInterest,
                onRemoveInterest = viewModel::removeInterest,
                onToggleInterest = viewModel::setInterestEnabled,
                onSetInterestWeight = viewModel::setInterestWeight,
                onToggleRecallChannel = viewModel::setRecallChannelEnabled,
                onSetSearchKey = viewModel::setSearchKey,
                onSetRssHubUrl = viewModel::setRssHubBaseUrl,
                onToggleSemanticEnabled = viewModel::setSemanticEnabled,
                onSyncRecommend = viewModel::syncRecommendSettings,
                onSyncSemantic = viewModel::syncSemanticState,
                onDownloadModel = viewModel::downloadModel,
                onLoadEncoder = viewModel::loadEncoder,
                onDeleteModel = viewModel::deleteModel,
                onBack = { page = PAGE_NONE },
            )

            PAGE_RELAY -> RelayScreen(
                relayBaseUrl = relayBaseUrl,
                relayAutoSync = relayAutoSync,
                relaySourceCount = RelayStatus.endpointCount(relayBaseUrl, allSources),
                relayLastSyncAt = relayLastSyncAt,
                relayCommandToken = relayCommandToken,
                feedRows = relayRows,
                // 从失败横幅点进来时对症那一节排在前面；从抽屉进来没有「这次」，就没有对症
                focus = relayFocus,
                onSyncRelay = { viewModel.syncFromRelay(it) },
                onSelfTest = viewModel::runRelaySelfTest,
                onSendCommand = viewModel::sendRelayCommand,
                onCommandStatus = viewModel::relayCommandStatus,
                onSetRelayAutoSync = viewModel::setRelayAutoSync,
                onScanned = { scanned = it },
                onOpenSource = { id ->
                    // 回首页并只看这一条源：文章列表只有首页那一份实现，
                    // 在这一页里再摆一份卡片就等于以后改一处漏一处
                    sourceFilter = id
                    page = PAGE_NONE
                    relayFocus = null
                },
                // 电脑端报来「这一轮跑完了」：不重新去拉，这一页上一节那些条目数、
                // 以及它带进来的那些新文章都还是旧的，用户会以为命令没生效
                onRunFinished = viewModel::refresh,
                onBack = {
                    page = PAGE_NONE
                    relayFocus = null
                },
            )

            PAGE_SETTINGS -> SettingsScreen(
                // 设置页要展示全部源（含关掉的、含 relay 同步来的）才能给出开关
                sources = allSources,
                enabledIds = sources.map { it.id }.toSet(),
                useInAppBrowser = useInAppBrowser,
                webDarkMode = webDarkMode,
                version = BuildConfig.VERSION_NAME,
                cacheStats = cacheStats,
                readCount = readAt.size,
                retentionDays = retentionDays,
                cacheMaxMb = cacheMaxMb,
                searchEngine = searchEngine,
                recommend = recommend,
                onToggleSource = viewModel::setSourceEnabled,
                onAddSource = viewModel::addSource,
                onRemoveSource = viewModel::removeSource,
                onImportOpml = viewModel::importOpml,
                onToggleInAppBrowser = viewModel::setUseInAppBrowser,
                onSetRetentionDays = viewModel::setCacheRetentionDays,
                onSetCacheMaxMb = viewModel::setCacheMaxMb,
                onSetSearchEngine = viewModel::setSearchEngine,
                onSetWebDarkMode = viewModel::setWebDarkMode,
                onToggleShowReason = viewModel::setShowRecommendReason,
                onSyncRecommend = viewModel::syncRecommendSettings,
                onPruneCache = { viewModel.pruneNow() },
                onClearFeedCache = { viewModel.clearCacheNow() },
                onClearVectorCache = { viewModel.clearVectorCache() },
                onClearReadState = { viewModel.clearReadState() },
                onClearWebCache = { clearWebViewCache(context) },
                onBack = { page = PAGE_NONE },
            )
        }
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp)) {
                    Text("MyFeed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.updatedLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = recommendSummary(recommend, sources.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 「首页为什么长这样」的答案就在这两行：候选从哪来、这一轮编码了多少、
                    // 语义到底有没有跑起来。不显示这些，推荐出问题就只能靠猜。
                    state.stats?.let { stats ->
                        Text(
                            text = statsLabel(stats),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("全部文章") },
                    selected = activeCategory == FeedSources.ALL && filteredSource == null,
                    onClick = {
                        category = FeedSources.ALL
                        sourceFilter = null
                        closeDrawer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                // 分类不再在这里列一遍：首页搜索框下面那排 chips 就是同一批分类，
                // 抽屉再列一次只会让「中文代码开发」这种词出现两处、还不会同时更新。
                // 抽屉留给真正不同的页面。
                NavigationDrawerItem(
                    label = { Text("兴趣") },
                    selected = false,
                    onClick = {
                        page = PAGE_INTERESTS
                        closeDrawer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    label = { Text("电脑端采集") },
                    selected = false,
                    onClick = {
                        // 从抽屉进的就是「平时看一眼」，不带上次那次失败
                        relayFocus = null
                        page = PAGE_RELAY
                        closeDrawer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    label = { Text("设置") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        page = PAGE_SETTINGS
                        closeDrawer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("MyFeed", fontWeight = FontWeight.Bold)
                            Text(
                                text = state.updatedLabel(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "菜单")
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
            Column(modifier = Modifier.fillMaxSize().padding(insets)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("搜索标题、摘要或来源…") },
                    singleLine = true,
                )

                // 本地过滤是即时的；搜不到或想搜全网时，走这一行
                if (query.isNotBlank()) {
                    WebSearchRow(
                        label = "用${searchEngine.label}搜索「${query.trim()}」",
                        onClick = { openWebSearch(query) },
                    )

                    // 搜索和订阅之间最短的一条路：用户输了词、看到空结果，那一刻他最想
                    // 做的就是「让这个词以后能有内容」。校验不过（重复、太长）就不显示这一行。
                    val candidate = Interests.sanitize(query)
                    if (visible.isEmpty() && Interests.addError(candidate, recommend.interests) == null) {
                        WebSearchRow(
                            label = "把「$candidate」加成兴趣",
                            icon = Icons.Filled.Add,
                            onClick = {
                                viewModel.addInterest(candidate)
                                // 清掉搜索词，让刚加的兴趣流直接显示出来 —— 这比一句
                                // 一闪而过的提示更直接
                                query = ""
                            },
                        )
                    }
                }

                CategoryRow(
                    current = if (filteredSource != null) "" else activeCategory,
                    categories = categories,
                    unreadCount = unreadCount,
                    hideRead = hideRead,
                    sourceName = filteredSource?.name,
                    // 分类和「只看某条源」是互斥的两种筛法：点分类就是用户自己选了回到分类视角
                    onSelect = { category = it; sourceFilter = null },
                    onClearSource = { sourceFilter = null },
                    onToggleHideRead = { hideRead = !hideRead },
                )

                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == TAB_LIST,
                        onClick = { tab = TAB_LIST },
                        text = { Text("列表视图") },
                    )
                    Tab(
                        selected = tab == TAB_CARD,
                        onClick = { tab = TAB_CARD },
                        text = { Text("卡片视图") },
                    )
                }

                if (state.failures.isNotEmpty() && state.hasContent) {
                    FailureBanner(
                        failures = state.failures,
                        // 这批失败里有没有 relay 那台 PC：有就多一行「电脑端该做什么」
                        pcIssue = RelayGuidance.issueForRelayFailures(
                            state.failures,
                            relayBaseUrl,
                        ),
                        onOpenPcSteps = {
                            relayFocus = it
                            page = PAGE_RELAY
                        },
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.isLoading -> LoadingPane()

                        state.error != null -> ErrorPane(
                            message = state.error.orEmpty(),
                            onRetry = viewModel::refresh,
                        )

                        else -> PullToRefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (state.noContentSource) {
                                NoContentPane(onOpenInterests = { page = PAGE_INTERESTS })
                            } else if (visible.isEmpty()) {
                                EmptyPane(
                                    query = query.trim(),
                                    // 只看一条源时空态要说的是那条源的名字，
                                    // 说「全部」会让人以为筛选没生效
                                    category = filteredSource?.name ?: activeCategory,
                                    // 只有「筛掉了已读」才可能把一类筛空，这时候该说人话
                                    allRead = hideRead && inCategory.isNotEmpty(),
                                )
                            } else if (tab == TAB_CARD) {
                                CardView(
                                    scored = visible,
                                    showReason = recommend.showReason,
                                    readAt = readAt,
                                    keyword = query.trim(),
                                    onOpen = openArticle,
                                )
                            } else {
                                ListView(
                                    scored = visible,
                                    showReason = recommend.showReason,
                                    readAt = readAt,
                                    keyword = query.trim(),
                                    onOpen = openArticle,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 点一下就用当前引擎搜网页，结果页在内置浏览器里打开。 */
@Composable
private fun WebSearchRow(
    label: String,
    icon: ImageVector = Icons.Filled.Search,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一行 chips 选分类，末尾挂「只看未读」。
 *
 * 已读开关和分类是同一类东西（都在筛「这一屏要看什么」），所以放进同一行，
 * 不再为它单独开一条工具栏 —— 首页竖向空间本来就被搜索框、分类、视图切换占着。
 * 计数取当前分类下「本轮会话开始前未读」的条数：切到 V2EX 就只数 V2EX，
 * 否则这个数字对不上屏幕；刚点过的那几条还算未读，和筛选的行为保持一致。
 *
 * [sourceName] 非空 = 现在筛的是「只看这一条源」（从「电脑端采集」页点进来）。
 * 它排在最前面并且是唯一选中态，此时 `current` 传空串 —— 一个 chip 都没选中才是
 * 真的状态，让「全部」亮着等于同时说两句矛盾的话。点任意分类会把它清掉（互斥）。
 */
@Composable
private fun CategoryRow(
    current: String,
    categories: List<String>,
    unreadCount: Int,
    hideRead: Boolean,
    sourceName: String?,
    onSelect: (String) -> Unit,
    onClearSource: () -> Unit,
    onToggleHideRead: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sourceName?.let { name ->
            FilterChip(
                selected = true,
                onClick = onClearSource,
                label = { Text("只看 $name ✕") },
            )
        }
        categories.forEach { item ->
            FilterChip(
                selected = current == item,
                onClick = { onSelect(item) },
                label = { Text(item) },
            )
        }
        FilterChip(
            selected = hideRead,
            onClick = onToggleHideRead,
            label = { Text(if (unreadCount > 0) "只看未读 · $unreadCount" else "只看未读") },
        )
    }
}

/**
 * 拉取失败的聚合横幅。
 *
 * [pcIssue] 非空 = 这批失败里就有已配过的那台 relay：光说「21 个源连接超时」不告诉用户
 * 该去动哪一头，而那一步（起 serve / 跑一轮采集）只能在电脑上做。所以多一行把症状翻成动作。
 */
@Composable
private fun FailureBanner(
    failures: List<FeedFailure>,
    pcIssue: RelayIssue?,
    onOpenPcSteps: (RelayIssue) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column {
            Text(
                text = buildString {
                    append(failures.size)
                    append(" 类源拉取失败：")
                    append(failures.joinToString("、") { "${it.source}（${it.message}）" })
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(10.dp),
            )
            pcIssue?.let { issue ->
                Text(
                    text = "电脑端：${RelayGuidance.headline(issue)} · 点这里去「电脑端采集」页",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPcSteps(issue) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text("正在召回并排序…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("加载失败", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

/**
 * 「一条内容来源都没有」的空态。
 *
 * 触发条件是**兴趣词和订阅源同时为空**（见 `FeedUiState.noContentSource`）。
 * 「把订阅源全关掉」不再进这里 —— 那现在是合法配置，只要有兴趣词，
 * 六路召回照样能填满首页。
 */
@Composable
private fun NoContentPane(onOpenInterests: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有内容来源", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "首页是一条兴趣流：按你关注的词去微博、CSDN、V2EX、Hacker News " +
                "这些通道里找内容，再排序。先去加几个兴趣词试试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenInterests) { Text("去加兴趣") }
    }
}

@Composable
private fun EmptyPane(query: String, category: String, allRead: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                allRead && query.isEmpty() -> "「$category」都读过了"
                allRead -> "「$query」在这类里没有未读的"
                query.isEmpty() -> "「$category」下暂无内容"
                else -> "没有匹配「$query」的文章"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                // 这一屏不是没内容，是被「只看未读」筛空了 —— 说清楚怎么退回去，
                // 否则症状就是「我订阅的源呢」
                allRead -> "关掉分类行末尾的「只看未读」就能回看，或者下拉刷新拿新内容"
                query.isEmpty() -> "下拉即可刷新"
                else -> "可以点上方那行去搜网页，或者直接把它加成兴趣词"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListView(
    scored: List<ScoredArticle>,
    showReason: Boolean,
    readAt: Map<String, Long>,
    keyword: String,
    onOpen: (Article) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(scored, key = { it.article.id }) { item ->
            ListItemCard(
                scored = item,
                showReason = showReason,
                read = item.article.id in readAt,
                keyword = keyword,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun CardView(
    scored: List<ScoredArticle>,
    showReason: Boolean,
    readAt: Map<String, Long>,
    keyword: String,
    onOpen: (Article) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(scored.chunked(2), key = { row -> row.first().article.id }) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    CardItem(
                        scored = item,
                        showReason = showReason,
                        read = item.article.id in readAt,
                        keyword = keyword,
                        onOpen = onOpen,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ListItemCard(
    scored: ScoredArticle,
    showReason: Boolean,
    read: Boolean,
    keyword: String,
    onOpen: (Article) -> Unit,
) {
    val article = scored.article
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(article) },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = readTitleColor(read),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showReason) ReasonLabel(scored)
            BodyHitLabel(article, keyword)
            if (article.excerpt.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = article.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = article.byline(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = DateParser.display(article),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CardItem(
    scored: ScoredArticle,
    showReason: Boolean,
    read: Boolean,
    keyword: String,
    onOpen: (Article) -> Unit,
    modifier: Modifier = Modifier,
) {
    val article = scored.article
    Card(
        modifier = modifier
            .aspectRatio(0.75f)
            .clickable { onOpen(article) },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = readTitleColor(read),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showReason) ReasonLabel(scored)
                BodyHitLabel(article, keyword)
                if (article.excerpt.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = article.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = article.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = DateParser.display(article),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「为什么推这条」。
 *
 * 只在真的命中过某个兴趣词时显示。纯时间序兜底出来的条目（一个兴趣都没有、
 * 或者缓存铺出来的那批）没有理由，硬凑一句「因为你关注…」是骗人的。
 */
/**
 * 「正文里提到」—— 命中的是全文而不是标题/摘要时，解释一句这条为什么出现在结果里。
 *
 * 全文是 1.4 才存进缓存的，之前用户搜一个词只可能从标题命中；现在一篇六七千字的
 * 回答中间提到什么都能搜到，但不标出来的话结果看起来像「跟这个词没关系」。
 */
@Composable
private fun BodyHitLabel(article: Article, keyword: String) {
    val label = ArticleSearch.label(ArticleSearch.fieldHit(article, keyword)) ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ReasonLabel(scored: ScoredArticle) {
    if (scored.topInterest.isEmpty()) return
    Text(
        text = "因为你关注「${scored.topInterest}」",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 读过的标题压暗，不加徽章、不改卡片高度。
 *
 * 为什么只是压暗：已读判断是「点开过」，误点一下就会把一条真内容标成读过。
 * 压暗是提示，划过去还认得出来；整卡变灰或打勾反而是最容易让人以为「内容被删了」的做法。
 * 真想清屏就点分类行末尾那个「只看未读」。
 */
@Composable
private fun readTitleColor(read: Boolean): Color =
    if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

private fun Article.byline(): String =
    if (author.equals(sourceName, ignoreCase = true)) sourceName else "$sourceName · $author"

private fun FeedUiState.updatedLabel(): String = when {
    lastUpdated <= 0L -> "尚未更新"
    // 网络结果还没回来，列表是磁盘缓存铺的
    showingCache -> "缓存内容 · ${DateParser.relative(lastUpdated)}"
    else -> "更新于 ${DateParser.relative(lastUpdated)}"
}

/**
 * 抽屉里的「这套推荐现在什么状态」。
 *
 * 把「几条通道」和「语义开没开」写出来，是因为首页质量不对时这两项是首要嫌疑：
 * 通道关多了候选就少，语义没跑起来排序就只剩关键词。
 */
private fun recommendSummary(recommend: RecommendSettings, sourceCount: Int): String = buildString {
    append(if (recommend.activeCount == 0) "没有兴趣词" else "${recommend.activeCount} 个兴趣词")
    append(" · ")
    append("${recommend.channels.count { it.enabled }} 条召回通道")
    append(" · ")
    append("$sourceCount 个订阅源")
    append(" · ")
    append(if (recommend.semanticEnabled) "语义排序" else "仅关键词")
}

/** 一轮推荐的产出。用来回答「首页为什么长这样」。 */
private fun statsLabel(stats: RecommendStats): String = buildString {
    append("候选 ${stats.candidates}")
    if (stats.newlyEncoded > 0) append(" · 新编码 ${stats.newlyEncoded}")
    // 长期不减说明编码预算定小了：那些条目一直只有词法分，在兴趣流里天然吃亏
    if (stats.deferred > 0) append(" · 待编码 ${stats.deferred}")
    if (stats.queriesSkipped > 0) append(" · ${stats.queriesSkipped} 个兴趣词没排上查询")
}

/** 把一次网页搜索包装成 Article，好在内置浏览器里复用同一套阅读界面。 */
private fun searchArticle(engine: SearchEngine, keyword: String): Article = Article(
    id = "search:${engine.id}:$keyword",
    title = keyword,
    excerpt = "",
    link = engine.urlFor(keyword),
    author = "",
    sourceId = "search",
    sourceName = "${engine.label}搜索",
    category = "搜索",
    publishedAt = 0L,
)

/**
 * 扫码深链的确认框。
 *
 * 一定要人点一下才动手：二维码是**另一台机器**生成并广播的，让它静默改用户的订阅列表
 * 不是合理的默认。跑完也不自动关窗 —— 「新增 3 条」还是「连不上」，是这条链路里唯一
 * 能指出问题出在 PC、防火墙还是地址上的信息，跟 [RelayDialog] 同一个道理。
 */
@Composable
private fun DeepLinkDialog(
    link: DeepLink,
    onRun: suspend () -> String,
    onDismiss: () -> Unit,
) {
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val relay = link is DeepLink.RelaySync

    AlertDialog(
        onDismissRequest = { if (!running && message == null) onDismiss() },
        title = { Text(if (relay) "同步 crawlbase relay" else "订阅这个源") },
        text = {
            Column {
                Text(
                    text = when (link) {
                        is DeepLink.RelaySync ->
                            "PC 地址 ${link.base}\n会去读它的 feeds.opml，按订阅文件名对齐：" +
                                "已有的只改地址，新增才加条目。" +
                                // 配对是「这台电脑肯听手机的话」，得在点确认之前说清楚，
                                // 不能让它混在「订阅」那句话里悄悄发生
                                if (link.token.isNotBlank())
                                    "\n这个码还带着配对钥匙：扫过之后手机能让它现采一轮。"
                                else ""

                        is DeepLink.Subscribe ->
                            "${link.title.ifBlank { "未命名源" }}\n${link.url}\n" +
                                "添加前会先真拉一次，拉不到就不入库。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                message?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (message != null) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            } else {
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        scope.launch { message = onRun() }
                    },
                ) { Text(if (running) "进行中…" else if (relay) "同步" else "添加") }
            }
        },
        dismissButton = if (message == null && !running) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else {
            null
        },
    )
}

private fun openExternal(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
