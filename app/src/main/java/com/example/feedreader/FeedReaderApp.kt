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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.feedreader.data.Article
import com.example.feedreader.data.DateParser
import com.example.feedreader.data.FeedFailure
import com.example.feedreader.data.FeedSources
import com.example.feedreader.data.Interests
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.recommend.RecommendStats
import com.example.feedreader.recommend.ScoredArticle
import com.example.feedreader.ui.FeedUiState
import com.example.feedreader.ui.FeedViewModel
import com.example.feedreader.ui.RecommendSettings
import com.example.feedreader.ui.ReaderScreen
import com.example.feedreader.ui.SettingsScreen
import com.example.feedreader.ui.clearWebViewCache
import kotlinx.coroutines.launch

private const val TAB_LIST = 0
private const val TAB_CARD = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedReaderApp(viewModel: FeedViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val sources by viewModel.enabledSources.collectAsState()
    val allSources by viewModel.allSources.collectAsState()
    val useInAppBrowser by viewModel.useInAppBrowser.collectAsState()
    val cacheStats by viewModel.cacheStats.collectAsState()
    val retentionDays by viewModel.retentionDays.collectAsState()
    val cacheMaxMb by viewModel.cacheMaxMb.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val semanticStatus by viewModel.semanticStatus.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val modelBytes by viewModel.modelBytes.collectAsState()
    val recommend by viewModel.recommend.collectAsState()
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(FeedSources.ALL) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_LIST) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Article?>(null) }

    // 分类 chips 改成从「列表里实际出现的来源」派生。
    // 原来是按启用的订阅源算的，换成兴趣流之后彻底对不上了 —— CSDN、V2EX、
    // Hacker News 这些来源根本不在订阅源列表里，用户永远筛不到它们。
    // 顺序取分数序（`.distinct()` 保留首次出现顺序），最相关的来源自然排在最前面。
    val categories = remember(state.scored) {
        FeedSources.categoriesOf(state.scored.map { it.article })
    }
    // 选中的分类对应的来源被关掉后，自动落回「全部」
    val activeCategory = if (category in categories) category else FeedSources.ALL

    // 过滤后仍保留 ScoredArticle —— 推荐理由要跟着条目一起走，
    // 只留 Article 的话列表就再也说不出「为什么推这条」了
    val visible = remember(state.scored, query, activeCategory) {
        val keyword = query.trim()
        state.scored.filter { scored ->
            val article = scored.article
            (activeCategory == FeedSources.ALL || article.category == activeCategory) &&
                (keyword.isEmpty() ||
                    article.title.contains(keyword, ignoreCase = true) ||
                    article.excerpt.contains(keyword, ignoreCase = true) ||
                    article.sourceName.contains(keyword, ignoreCase = true))
        }
    }

    val openArticle: (Article) -> Unit = { article ->
        if (useInAppBrowser) opened = article else openExternal(context, article.link)
    }

    // 网页搜索走同一条路：内置浏览器开着就在站内看结果，否则交给系统浏览器
    val openWebSearch: (String) -> Unit = { raw ->
        val keyword = raw.trim()
        if (keyword.isNotEmpty()) openArticle(searchArticle(searchEngine, keyword))
    }

    opened?.let { article ->
        ReaderScreen(
            article = article,
            onOpenExternal = { openExternal(context, it) },
            onBack = { opened = null },
        )
        return
    }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(
            // 设置页要展示**全部**源（含关掉的、含自建的）才能给出开关
            sources = allSources,
            enabledIds = sources.map { it.id }.toSet(),
            useInAppBrowser = useInAppBrowser,
            version = BuildConfig.VERSION_NAME,
            cacheStats = cacheStats,
            retentionDays = retentionDays,
            cacheMaxMb = cacheMaxMb,
            searchEngine = searchEngine,
            recommend = recommend,
            semanticStatus = semanticStatus,
            modelState = modelState,
            modelBytes = modelBytes,
            modelExpectedBytes = viewModel.modelExpectedBytes,
            onToggleSource = viewModel::setSourceEnabled,
            onAddSource = viewModel::addSource,
            onRemoveSource = viewModel::removeSource,
            onImportOpml = viewModel::importOpml,
            onToggleInAppBrowser = viewModel::setUseInAppBrowser,
            onSetRetentionDays = viewModel::setCacheRetentionDays,
            onSetCacheMaxMb = viewModel::setCacheMaxMb,
            onSetSearchEngine = viewModel::setSearchEngine,
            onAddInterest = viewModel::addInterest,
            onRemoveInterest = viewModel::removeInterest,
            onToggleInterest = viewModel::setInterestEnabled,
            onSetInterestWeight = viewModel::setInterestWeight,
            onToggleRecallChannel = viewModel::setRecallChannelEnabled,
            onSetRssHubUrl = viewModel::setRssHubBaseUrl,
            onToggleSemanticEnabled = viewModel::setSemanticEnabled,
            onToggleShowReason = viewModel::setShowRecommendReason,
            onSyncRecommend = viewModel::syncRecommendSettings,
            onSyncSemantic = viewModel::syncSemanticState,
            onDownloadModel = viewModel::downloadModel,
            onLoadEncoder = viewModel::loadEncoder,
            onDeleteModel = viewModel::deleteModel,
            onPruneCache = { viewModel.pruneNow() },
            onClearFeedCache = { viewModel.clearCacheNow() },
            onClearVectorCache = { viewModel.clearVectorCache() },
            onClearWebCache = { clearWebViewCache(context) },
            onBack = { settingsOpen = false },
        )
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
                    selected = activeCategory == FeedSources.ALL,
                    onClick = {
                        category = FeedSources.ALL
                        closeDrawer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                if (categories.size > 1) {
                    Text(
                        text = "分类",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
                    )
                    categories.filter { it != FeedSources.ALL }.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item) },
                            selected = activeCategory == item,
                            onClick = {
                                category = item
                                closeDrawer()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("兴趣与设置") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        settingsOpen = true
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

                CategoryRow(current = activeCategory, categories = categories, onSelect = { category = it })

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
                    FailureBanner(state.failures)
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
                                NoContentPane(onOpenSettings = { settingsOpen = true })
                            } else if (visible.isEmpty()) {
                                EmptyPane(query = query.trim(), category = activeCategory)
                            } else if (tab == TAB_CARD) {
                                CardView(
                                    scored = visible,
                                    showReason = recommend.showReason,
                                    onOpen = openArticle,
                                )
                            } else {
                                ListView(
                                    scored = visible,
                                    showReason = recommend.showReason,
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

/** 一行 chips 选分类。选项来自当前启用的源，关掉源后对应分类会消失。 */
@Composable
private fun CategoryRow(
    current: String,
    categories: List<String>,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { item ->
            FilterChip(
                selected = current == item,
                onClick = { onSelect(item) },
                label = { Text(item) },
            )
        }
    }
}

@Composable
private fun FailureBanner(failures: List<FeedFailure>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = buildString {
                append(failures.size)
                append(" 个源拉取失败：")
                append(failures.joinToString("、") { "${it.source}（${it.message}）" })
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(10.dp),
        )
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
private fun NoContentPane(onOpenSettings: () -> Unit) {
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
        Button(onClick = onOpenSettings) { Text("去加兴趣") }
    }
}

@Composable
private fun EmptyPane(query: String, category: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (query.isEmpty()) "「$category」下暂无内容" else "没有匹配「$query」的文章",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (query.isEmpty()) {
                "下拉即可刷新"
            } else {
                "可以点上方那行去搜网页，或者直接把它加成兴趣词"
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
    onOpen: (Article) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(scored, key = { it.article.id }) { item ->
            ListItemCard(scored = item, showReason = showReason, onOpen = onOpen)
        }
    }
}

@Composable
private fun CardView(
    scored: List<ScoredArticle>,
    showReason: Boolean,
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showReason) ReasonLabel(scored)
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
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showReason) ReasonLabel(scored)
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

private fun openExternal(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
