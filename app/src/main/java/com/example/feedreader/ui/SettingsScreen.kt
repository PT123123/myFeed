package com.example.feedreader.ui

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.CacheStats
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.SettingsStore
import com.example.feedreader.data.SourceRules
import com.example.feedreader.data.WebDarkMode
import kotlinx.coroutines.launch

/**
 * 「设置」页：这台设备怎么用起来顺手，以及本机数据。
 *
 * 兴趣/召回/搜索 key/语义模型搬到了 [InterestsScreen]（那一组回答「首页为什么是这些内容」），
 * crawlbase relay 和「电脑端要做的事」搬到了 [RelayScreen]（那条链的另一半在另一台机器上）。
 * 这里剩下的都是**只影响这台设备**的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: List<FeedSource>,
    enabledIds: Set<String>,
    useInAppBrowser: Boolean,
    webDarkMode: WebDarkMode,
    version: String,
    cacheStats: CacheStats,
    readCount: Int,
    retentionDays: Int,
    cacheMaxMb: Int,
    searchEngine: SearchEngine,
    recommend: RecommendSettings,
    onToggleSource: (String, Boolean) -> Unit,
    onAddSource: suspend (url: String, name: String, category: String) -> AddSourceResult,
    onRemoveSource: (String) -> Unit,
    onImportOpml: suspend (Uri) -> OpmlImportResult,
    onToggleInAppBrowser: (Boolean) -> Unit,
    onSetWebDarkMode: (WebDarkMode) -> Unit,
    onSetRetentionDays: (Int) -> Unit,
    onSetCacheMaxMb: (Int) -> Unit,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onToggleShowReason: (Boolean) -> Unit,
    onSyncRecommend: () -> Unit,
    onPruneCache: suspend () -> String,
    onClearFeedCache: suspend () -> String,
    onClearVectorCache: suspend () -> String,
    onClearReadState: suspend () -> String,
    onClearWebCache: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var retentionDialog by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var engineDialog by remember { mutableStateOf(false) }
    var darkDialog by remember { mutableStateOf(false) }
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

    // 清向量缓存那一行要报「缓存了多少篇」，那个数是磁盘上的，换机/恢复备份之后
    // 内存里那份快照可能已经不是当前这一份了
    LaunchedEffect(Unit) { onSyncRecommend() }

    // 源列表分两段呈现：自己加的排最前面（那才是用户真正在意的），内置的在后。
    // 分段而不是混着排，是因为这两类的可操作范围根本不同 —— 自建的能删，内置的只能关。
    val customSources = remember(sources) { sources.filter { it.custom } }
    val builtIn = remember(sources) { sources.filterNot { it.custom } }
    val enabledCount = remember(sources, enabledIds) { sources.count { it.id in enabledIds } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                        "${customSources.size} 个 · 这是你自己的清单，内置列表随版本更新也不会动它。" +
                            "从 crawlbase 那台 PC 同步来的订阅也在这里，那台机器上的开关在「电脑端采集」页"
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
            items(customSources, key = { "custom-" + it.id }) { source ->
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
            items(builtIn, key = { "builtin-" + it.id }) { source ->
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
                        text = "全部关掉了。这不是问题 —— 只要有兴趣词，首页照样有内容，" +
                            "订阅源只是其中一个语料来源。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
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
                ChoiceRow(
                    title = "网页暗色模式",
                    subtitle = "内置浏览器读文章时的配色，命中已知站点按站点样式刷，其余整页反相",
                    value = webDarkMode.label,
                    onClick = { darkDialog = true },
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
            item {
                ActionRow(
                    title = "清空已读记录",
                    subtitle = if (readCount == 0) {
                        "还没有已读记录：点开过的条目会压暗，90 天后自动过期"
                    } else {
                        "已读 $readCount 条；清掉后首页全部回到未读，内容本身不动"
                    },
                ) {
                    scope.launch { snackbar.showSnackbar(onClearReadState()) }
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

    if (darkDialog) {
        ChoiceDialog(
            title = "网页暗色模式",
            options = WebDarkMode.CHOICES,
            selected = webDarkMode,
            label = { it.label },
            onPick = {
                darkDialog = false
                onSetWebDarkMode(it)
            },
            onDismiss = { darkDialog = false },
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
