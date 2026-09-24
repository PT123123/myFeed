package com.example.feedreader.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.CacheStats
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.Interest
import com.example.feedreader.data.Interests
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.SearchKeyProvider
import com.example.feedreader.data.SettingsStore
import com.example.feedreader.data.SourceRules
import io.github.pt123123.semantic.ModelState
import io.github.pt123123.semantic.SemanticStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: List<FeedSource>,
    enabledIds: Set<String>,
    useInAppBrowser: Boolean,
    version: String,
    cacheStats: CacheStats,
    retentionDays: Int,
    cacheMaxMb: Int,
    searchEngine: SearchEngine,
    recommend: RecommendSettings,
    semanticStatus: SemanticStatus,
    modelState: ModelState,
    modelBytes: Long,
    modelExpectedBytes: Long,
    onToggleSource: (String, Boolean) -> Unit,
    onAddSource: suspend (url: String, name: String, category: String) -> AddSourceResult,
    onRemoveSource: (String) -> Unit,
    onImportOpml: suspend (Uri) -> OpmlImportResult,
    onToggleInAppBrowser: (Boolean) -> Unit,
    onSetRetentionDays: (Int) -> Unit,
    onSetCacheMaxMb: (Int) -> Unit,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onAddInterest: (String) -> AddInterestResult,
    onRemoveInterest: (String) -> Unit,
    onToggleInterest: (String, Boolean) -> Unit,
    onSetInterestWeight: (String, Float) -> Unit,
    onToggleRecallChannel: (String, Boolean) -> Unit,
    onSetSearchKey: (String, String) -> Unit,
    onSetRssHubUrl: (String) -> Unit,
    onToggleSemanticEnabled: (Boolean) -> Unit,
    onToggleShowReason: (Boolean) -> Unit,
    onSyncRecommend: () -> Unit,
    onSyncSemantic: () -> Unit,
    onDownloadModel: () -> Unit,
    onLoadEncoder: () -> Unit,
    onDeleteModel: () -> Unit,
    onPruneCache: suspend () -> String,
    onClearFeedCache: suspend () -> String,
    onClearVectorCache: suspend () -> String,
    onClearWebCache: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var retentionDialog by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var engineDialog by remember { mutableStateOf(false) }
    var deleteModelDialog by remember { mutableStateOf(false) }
    var addInterestDialog by remember { mutableStateOf(false) }
    var rssHubDialog by remember { mutableStateOf(false) }
    var addSourceDialog by remember { mutableStateOf(false) }
    var deleteSource by remember { mutableStateOf<FeedSource?>(null) }

    /**
     * OPML 文件选择。
     *
     * mime 过滤传通配而不是 text/xml：OPML 是个冷门后缀，各家系统给它的 MIME 五花八门
     * （text/x-opml、application/xml、干脆 application/octet-stream），按 MIME 过滤会让
     * 用户在文件选择器里**看不到自己的文件** —— 那种「文件明明在却选不了」的问题，
     * 比多显示几个无关文件烦人得多。
     *
     * 用 SAF 而不是申请存储权限：不需要任何权限，也不用管分区存储那套适配。
     */
    val opmlPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = onImportOpml(uri)
            snackbar.showSnackbar(
                when (result) {
                    is OpmlImportResult.Imported -> when {
                        result.added > 0 ->
                            "从文件里的 ${result.parsed} 个源中新增了 ${result.added} 个，" +
                                "下拉刷新开始拉取"
                        result.parsed > 0 -> "文件里的 ${result.parsed} 个源都已经订阅过了"
                        else -> "没有可导入的源"
                    }
                    is OpmlImportResult.Failed -> result.reason
                },
            )
        }
    }

    // 进设置页时探一次磁盘：模型可能在别的地方被删掉了（清理工具、重装）；
    // 推荐设置同理 —— 换设备恢复备份之后，内存里的快照可能已经不是磁盘上那份了
    LaunchedEffect(Unit) {
        onSyncSemantic()
        onSyncRecommend()
    }

    // 源列表分两段呈现：自己加的排最前面（那才是用户真正在意的），内置的在后。
    // 分段而不是混着排，是因为这两类的可操作范围根本不同 —— 自建的能删，内置的只能关。
    val customSources = remember(sources) { sources.filter { it.custom } }
    val builtIn = remember(sources) { sources.filterNot { it.custom } }
    val enabledCount = remember(sources, enabledIds) { sources.count { it.id in enabledIds } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("兴趣与设置") },
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
                    title = "我添加的源",
                    caption = if (customSources.isEmpty()) {
                        "还没有。可以粘一个订阅地址，或者从别的阅读器导出 OPML 导进来"
                    } else {
                        "${customSources.size} 个 · 这是你自己的清单，内置列表随版本更新也不会动它"
                    },
                )
            }
            item {
                ActionRow(
                    title = "添加订阅源",
                    subtitle = "粘一个 RSS / Atom 地址；会先连一次确认真的能拉到内容",
                ) { addSourceDialog = true }
            }
            item {
                ActionRow(
                    title = "从 OPML 导入",
                    subtitle = "从别的阅读器整体搬过来；已经订阅过的会自动跳过",
                ) { opmlPicker.launch(arrayOf("*/*")) }
            }
            items(customSources, key = { it.id }) { source ->
                SourceRow(
                    source = source,
                    checked = source.id in enabledIds,
                    onCheckedChange = { onToggleSource(source.id, it) },
                    onDelete = { deleteSource = source },
                )
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "内置源",
                    caption = "已启用 ${builtIn.count { it.id in enabledIds }} / ${builtIn.size}" +
                        " · 标了「默认关闭」的是靠流量和软文变现的媒体号",
                )
            }
            items(builtIn, key = { it.id }) { source ->
                ToggleRow(
                    title = source.name,
                    subtitle = if (source.defaultEnabled) {
                        "${source.category} · ${source.url}"
                    } else {
                        "默认关闭 · ${source.category} · 选题不差，但稿费来自厂商、流量来自标题"
                    },
                    checked = source.id in enabledIds,
                    onCheckedChange = { onToggleSource(source.id, it) },
                )
            }
            if (enabledCount == 0) {
                item {
                    Text(
                        text = "全部关掉了。这不是问题 —— 只要下面有兴趣词，首页照样有内容，" +
                            "订阅源只是其中一个语料来源。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "兴趣",
                    caption = "首页就是这些词的推荐流。词越具体，召回越准",
                )
            }
            item {
                ActionRow(
                    title = "添加兴趣",
                    subtitle = if (recommend.overflowCount > 0) {
                        "已有 ${recommend.activeCount} 个，但一轮只发得出权重最高的 " +
                            "${recommend.queryCount} 个查询，多出来的这几个排不上"
                    } else {
                        "最多 ${Interests.MAX_COUNT} 个；一轮刷新会用上权重最高的 " +
                            "${Interests.EFFECTIVE_COUNT} 个"
                    },
                    icon = Icons.Filled.Add,
                ) { addInterestDialog = true }
            }
            items(recommend.interests, key = { it.id }) { interest ->
                InterestRow(
                    interest = interest,
                    onToggle = { onToggleInterest(interest.id, it) },
                    onCycleWeight = { onSetInterestWeight(interest.id, nextWeight(interest.weight)) },
                    onRemove = { onRemoveInterest(interest.id) },
                )
            }
            if (recommend.interests.isEmpty()) {
                item {
                    Text(
                        text = "一个兴趣都没有 —— 首页会退化成「已订阅源的时间序」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "召回通道",
                    caption = "决定候选从哪来。单路失败不影响其他路",
                )
            }
            items(recommend.channels, key = { it.id }) { channel ->
                ToggleRow(
                    title = channel.name,
                    subtitle = channel.hint,
                    checked = channel.enabled,
                    onCheckedChange = { onToggleRecallChannel(channel.id, it) },
                )
            }
            item {
                ChoiceRow(
                    title = "自建 RSSHub 实例",
                    subtitle = "公共镜像只对少数关键词路由可用，填自建地址更稳",
                    value = recommend.rssHubBaseUrl.ifEmpty { "公共镜像" },
                    onClick = { rssHubDialog = true },
                )
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "AI / 全网搜索（填 key 启用）",
                    caption = "Perplexity / 秘塔 / Tavily / Brave：填了 key 才会去搜，结果直接进首页流。" +
                        "这些服务多数在国外，需要能直连的网络（如 VPN）；秘塔国内可达。",
                )
            }
            items(recommend.searchKeys, key = { it.id }) { provider ->
                SearchKeyRow(
                    provider = provider,
                    onCommit = { onSetSearchKey(provider.id, it) },
                )
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "语义推荐模型",
                    caption = "让推荐理解同义表述，不只是关键词匹配",
                )
            }
            item {
                ToggleRow(
                    title = "启用语义排序",
                    subtitle = "关掉后退回「关键词 + 时间」排序；已下载的模型不会被删掉",
                    checked = recommend.semanticEnabled,
                    onCheckedChange = onToggleSemanticEnabled,
                )
            }
            item {
                SemanticModelBlock(
                    status = semanticStatus,
                    model = modelState,
                    bytes = modelBytes,
                    expectedBytes = modelExpectedBytes,
                    onDownload = onDownloadModel,
                    onLoad = onLoadEncoder,
                    onDelete = { deleteModelDialog = true },
                )
            }

            item { SectionDivider() }
            item { SectionHeader(title = "阅读", caption = null) }
            item {
                ToggleRow(
                    title = "用内置浏览器打开文章",
                    subtitle = "关掉则跳转到系统浏览器",
                    checked = useInAppBrowser,
                    onCheckedChange = onToggleInAppBrowser,
                )
            }
            item {
                ToggleRow(
                    title = "显示推荐理由",
                    subtitle = "在标题下面标出这篇命中了你哪个兴趣词",
                    checked = recommend.showReason,
                    onCheckedChange = onToggleShowReason,
                )
            }
            item {
                ActionRow(
                    title = "清除浏览器缓存",
                    subtitle = "清掉内置浏览器缓存的网页与 Cookie",
                ) {
                    onClearWebCache()
                    scope.launch { snackbar.showSnackbar("已清除浏览器缓存") }
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "搜索",
                    caption = "本地先在已加载的文章里找；找不到时可用这个引擎搜网页",
                )
            }
            item {
                ChoiceRow(
                    title = "网页搜索引擎",
                    subtitle = "搜索结果页在内置浏览器里打开",
                    value = searchEngine.label,
                    onClick = { engineDialog = true },
                )
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "缓存",
                    caption = "当前占用 ${formatBytes(cacheStats.bytes)} · ${cacheStats.entries} 个源",
                )
            }
            item {
                ChoiceRow(
                    title = "保留时长",
                    subtitle = "超过这个时间的缓存会自动清掉",
                    value = SettingsStore.retentionLabel(retentionDays),
                    onClick = { retentionDialog = true },
                )
            }
            item {
                ChoiceRow(
                    title = "容量上限",
                    subtitle = "超出后从最旧的缓存开始删",
                    value = SettingsStore.sizeLabel(cacheMaxMb),
                    onClick = { sizeDialog = true },
                )
            }
            item {
                ActionRow(
                    title = "立即清理",
                    subtitle = "按上面的策略清一遍，不影响当前显示的列表",
                ) {
                    scope.launch { snackbar.showSnackbar(onPruneCache()) }
                }
            }
            item {
                ActionRow(
                    title = "清空订阅缓存",
                    subtitle = "删掉全部已下载的订阅内容，下拉刷新会重新拉",
                ) {
                    scope.launch { snackbar.showSnackbar(onClearFeedCache()) }
                }
            }
            item {
                ActionRow(
                    title = "清除向量缓存",
                    subtitle = "已缓存 ${recommend.vectorStats.vectors} 篇的句向量" +
                        "（${formatBytes(recommend.vectorStats.bytes)}）；清掉后下次刷新要重新编码",
                ) {
                    scope.launch { snackbar.showSnackbar(onClearVectorCache()) }
                }
            }

            item { SectionDivider() }
            item { SectionHeader(title = "关于", caption = null) }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "MyFeed $version",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "端侧语义推荐：兴趣词 → 多路召回 → 本地向量排序。" +
                            "语义模型按需下载，不随安装包分发。内容缓存到本机，断网也能看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (retentionDialog) {
        ChoiceDialog(
            title = "缓存保留时长",
            options = SettingsStore.RETENTION_CHOICES,
            selected = retentionDays,
            label = SettingsStore::retentionLabel,
            onPick = {
                retentionDialog = false
                onSetRetentionDays(it)
            },
            onDismiss = { retentionDialog = false },
        )
    }

    if (sizeDialog) {
        ChoiceDialog(
            title = "缓存容量上限",
            options = SettingsStore.SIZE_CHOICES_MB,
            selected = cacheMaxMb,
            label = SettingsStore::sizeLabel,
            onPick = {
                sizeDialog = false
                onSetCacheMaxMb(it)
            },
            onDismiss = { sizeDialog = false },
        )
    }

    if (engineDialog) {
        ChoiceDialog(
            title = "网页搜索引擎",
            options = SearchEngine.values().toList(),
            selected = searchEngine,
            label = { it.label },
            onPick = {
                engineDialog = false
                onSetSearchEngine(it)
            },
            onDismiss = { engineDialog = false },
        )
    }

    if (deleteModelDialog) {
        // 24MB 重下一次要几十秒，误删的代价不小，所以加一道确认
        AlertDialog(
            onDismissRequest = { deleteModelDialog = false },
            title = { Text("删除语义模型？") },
            text = {
                Text(
                    "会删掉已下载的 ${formatBytes(modelBytes)} 模型文件，" +
                        "语义排序立刻退化成关键词匹配。下次要用需要重新下载。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteModelDialog = false
                        onDeleteModel()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteModelDialog = false }) { Text("取消") }
            },
        )
    }

    if (addSourceDialog) {
        AddSourceDialog(
            onAdd = onAddSource,
            onAdded = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onDismiss = { addSourceDialog = false },
        )
    }

    deleteSource?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSource = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("只是从订阅列表里移除，不影响你在别处对它的订阅。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSource = null
                        onRemoveSource(target.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSource = null }) { Text("取消") }
            },
        )
    }

    if (addInterestDialog) {
        AddInterestDialog(
            onAdd = onAddInterest,
            onAdded = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onDismiss = { addInterestDialog = false },
        )
    }

    if (rssHubDialog) {
        RssHubDialog(
            current = recommend.rssHubBaseUrl,
            onConfirm = {
                rssHubDialog = false
                onSetRssHubUrl(it)
            },
            onDismiss = { rssHubDialog = false },
        )
    }
}

/** 下一档权重，到顶绕回最低档。当前值不在档位上时（老数据）从最低档重新开始。 */
private fun nextWeight(current: Float): Float {
    val steps = Interests.WEIGHT_STEPS
    val index = steps.indexOf(Interests.snapWeight(current))
    return steps[(index + 1) % steps.size]
}

/**
 * 一条兴趣：关键词、权重、启用开关、删除。
 *
 * 权重做成「点标签换下一档」而不是拉滑块或弹对话框：只有四档，循环点击最省事，
 * 也不用为每一行维护一个对话框的状态。
 */
@Composable
private fun InterestRow(
    interest: Interest,
    onToggle: (Boolean) -> Unit,
    onCycleWeight: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onCycleWeight)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = interest.keyword,
                style = MaterialTheme.typography.bodyLarge,
                color = if (interest.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("权重 ")
                    append(Interests.weightLabel(interest.weight))
                    append("（")
                    append(trimWeight(interest.weight))
                    append("）· 点这里换档")
                    if (!interest.enabled) append(" · 已关闭")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Switch(checked = interest.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除「${interest.keyword}」",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 一行自建源：开关 + 删除。
 *
 * 和内置源的 [ToggleRow] 分开写，是因为多一个删除按钮 —— 用同一个组件加个可选回调
 * 也能做，但那个回调永远只会对自建源非空，等于把一个恒真的判断塞进通用组件里。
 */
@Composable
private fun SourceRow(
    source: FeedSource,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = source.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${source.category} · ${source.url}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除「${source.name}」",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 添加订阅源。
 *
 * 名字和分类都允许留空：用户通常只想粘一个地址。名字能从 feed 的频道标题里探出来
 * （比让他手打准），分类留空就归到「自建」—— 逼他填满三个框才能加一个源，
 * 是把我的实现细节变成他的负担。
 *
 * **检测期间不关窗、也禁用取消**：探测是一次真网络请求，中途关掉的话那个源到底加没加
 * 进去用户永远不知道。宁可让他多等两秒看清楚结果。
 */
@Composable
private fun AddSourceDialog(
    onAdd: suspend (url: String, name: String, category: String) -> AddSourceResult,
    onAdded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("添加订阅源") },
        text = {
            Column {
                HintText(
                    "填的是 feed 地址，不是网站首页 —— 常见的是 https://某站/feed 或 /rss。" +
                        "拿不准就去网站页脚找那个橙色的 RSS 图标。",
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    enabled = !checking,
                    label = { Text("订阅地址") },
                    placeholder = { Text("https://example.com/feed") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !checking,
                    label = { Text("名字（留空则用频道标题）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    singleLine = true,
                    enabled = !checking,
                    label = { Text("分类（留空则为「${SourceRules.FALLBACK_CATEGORY}」）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (checking) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HintText("正在连一次，确认这个地址真能拉到内容…")
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !checking && url.isNotBlank(),
                onClick = {
                    checking = true
                    error = null
                    scope.launch {
                        // 兜一层：onAdd 里任何没被捕获的异常都不该让按钮永远卡在「检测中」
                        val result = runCatching { onAdd(url, name, category) }
                            .getOrElse { AddSourceResult.Rejected(it.message ?: "添加失败") }

                        when (result) {
                            is AddSourceResult.Added -> {
                                onAdded(
                                    "已添加「${result.source.name}」，拉到 ${result.itemCount} 条内容",
                                )
                                onDismiss()
                            }

                            is AddSourceResult.Rejected -> error = result.reason
                        }
                        checking = false
                    }
                },
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(enabled = !checking, onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 2.0 显示成 2，0.5 保持 0.5 —— 权重是档位，不该带着没意义的小数尾巴。 */
private fun trimWeight(weight: Float): String =
    if (weight == weight.toInt().toFloat()) weight.toInt().toString() else weight.toString()

/**
 * 添加兴趣。
 *
 * **校验失败不关窗**：「这个词已经在列表里了」如果只用 snackbar 闪一下，
 * 用户会以为是自己没点中按钮。把原因留在输入框下面，一眼就知道该改什么。
 */
@Composable
private fun AddInterestDialog(
    onAdd: (String) -> AddInterestResult,
    onAdded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        when (val result = onAdd(text)) {
            is AddInterestResult.Added -> {
                onAdded(result.message)
                onDismiss()
            }

            is AddInterestResult.Rejected -> error = result.reason
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加兴趣") },
        text = {
            Column {
                HintText("写得具体一点：「向量数据库」比「技术」有用得多。")
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text("例如：向量数据库") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 自建 RSSHub 基地址。留空 = 用内置公共镜像，所以「清空」是个合法且有意义的操作。 */
@Composable
private fun RssHubDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自建 RSSHub 实例") },
        text = {
            Column {
                HintText(
                    "填 https:// 开头的基地址，例如 https://rsshub.example.com。" +
                        "留空则用内置公共镜像。",
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("https://rsshub.example.com") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionHeader(title: String, caption: String?) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // 整行可点，Switch 自己不接点击，避免触发两次
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行可点的设置项，右侧显示当前选中的值。 */
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 一行「填 key」：名字 + 密码框 + 「获取 key」链接 + 保存。
 *
 * key 不在每次按键时存盘 —— 那样每个字符都会触发一次整轮召回重建，
 * 一个 40 位的 key 敲完就是 40 次重刷。所以本地暂存、点「保存」才提交。
 * 「获取 key」直接拉起浏览器跳到对应官网的开 key 页，省得用户自己找。
 */
@Composable
private fun SearchKeyRow(
    provider: SearchKeyProvider,
    onCommit: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by remember(provider.id) { mutableStateOf(provider.key) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.siteUrl)))
                    } catch (_: Exception) {
                        // 没有浏览器 / 链接坏掉：静默失败，链接本来就是个便利入口
                    }
                },
            ) { Text("获取 key") }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("API Key（留空则不开通）") },
            placeholder = { Text("粘贴你的 key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HintText("填了 key 才生效；通道开关在上方「召回通道」里")
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { onCommit(text.trim()) }) { Text("保存") }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 语义模型的状态与操作。
 *
 * 「下载」和「加载」分成两条独立的轴展示，因为它们的失败原因完全不同：
 * 下载失败是网络/镜像问题（换镜像、重试有意义），加载失败是 native 库缺失、
 * 内存不足、ABI 不匹配这类问题（重试没用）。混成一句话用户就没法判断该做什么。
 */
@Composable
private fun SemanticModelBlock(
    status: SemanticStatus,
    model: ModelState,
    bytes: Long,
    expectedBytes: Long,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        when {
            model is ModelState.Downloading -> DownloadProgress(model, expectedBytes)

            status is SemanticStatus.Loading -> InfoText("正在加载并预热…", emphasis = true)

            status is SemanticStatus.Ready -> {
                InfoText("已就绪 · ${status.dimension} 维向量", emphasis = true)
                HintText(
                    "建图 ${status.loadMs} ms · 预热 ${status.warmupMs} ms · " +
                        "并发 ${status.workers} 路 · 占用 ${formatBytes(bytes)}",
                )
                HintText(
                    "实测单条 %.1f ms（自检 %d 条）。每篇文章只编码一次并落盘，之后刷新只读缓存。"
                        .format(status.msPerItem, status.benchmarkSamples),
                )
                Row {
                    TextButton(onClick = onLoad) { Text("重新自检") }
                    TextButton(onClick = onDelete) { Text("删除模型") }
                }
            }

            status is SemanticStatus.Failed -> {
                InfoText("加载失败", emphasis = true, error = true)
                HintText(status.reason)
                Row {
                    TextButton(onClick = onLoad) { Text("重试") }
                    TextButton(onClick = onDelete) { Text("删除模型") }
                }
            }

            model is ModelState.Failed -> {
                InfoText("下载失败", emphasis = true, error = true)
                HintText(model.message)
                Row {
                    TextButton(onClick = onDownload) { Text("重试") }
                    TextButton(onClick = onDelete) { Text("删除模型") }
                }
            }

            expectedBytes > 0L && bytes >= expectedBytes -> {
                InfoText("模型已下载（${formatBytes(bytes)}），尚未加载", emphasis = true)
                Row {
                    TextButton(onClick = onLoad) { Text("加载并自检") }
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }

            else -> {
                HintText(
                    "未下载。装好后排序能理解同义表述（「大模型」也能命中只写 LLM 的文章）；" +
                        "不装则退化成关键词 + 时间排序，功能不受影响。",
                )
                if (bytes > 0L) HintText("上次下到 ${formatBytes(bytes)}，可以接着下")
                TextButton(onClick = onDownload) {
                    val action = if (bytes > 0L) "继续下载" else "下载模型"
                    Text("$action（${formatBytes(expectedBytes)}）")
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(model: ModelState.Downloading, expectedBytes: Long) {
    val total = if (model.total > 0L) model.total else expectedBytes
    val fraction = if (total > 0L) (model.received.toFloat() / total).coerceIn(0f, 1f) else 0f

    InfoText("正在下载 ${formatBytes(model.received)} / ${formatBytes(total)}", emphasis = true)
    Spacer(modifier = Modifier.height(6.dp))
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(4.dp))
    HintText(if (model.source.isBlank()) "准备中…" else "来源：${model.source}，失败会自动换下一个镜像")
}

@Composable
private fun InfoText(text: String, emphasis: Boolean = false, error: Boolean = false) {
    Text(
        text = text,
        style = if (emphasis) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.bodySmall
        },
        fontWeight = if (emphasis) FontWeight.Medium else null,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}
