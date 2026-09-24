package com.example.feedreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.CacheStats
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.FeedSources
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.SettingsStore
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
    semanticStatus: SemanticStatus,
    modelState: ModelState,
    modelBytes: Long,
    modelExpectedBytes: Long,
    onToggleSource: (String, Boolean) -> Unit,
    onToggleInAppBrowser: (Boolean) -> Unit,
    onSetRetentionDays: (Int) -> Unit,
    onSetCacheMaxMb: (Int) -> Unit,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onSyncSemantic: () -> Unit,
    onDownloadModel: () -> Unit,
    onLoadEncoder: () -> Unit,
    onDeleteModel: () -> Unit,
    onPruneCache: suspend () -> String,
    onClearFeedCache: suspend () -> String,
    onClearWebCache: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var retentionDialog by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var engineDialog by remember { mutableStateOf(false) }
    var deleteModelDialog by remember { mutableStateOf(false) }

    // 进设置页时探一次磁盘：模型可能在别的地方被删掉了（清理工具、重装）
    LaunchedEffect(Unit) { onSyncSemantic() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("订阅源与设置") },
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
                    title = "订阅源",
                    caption = "已启用 ${enabledIds.size} / ${sources.size}",
                )
            }
            items(sources, key = { it.id }) { source ->
                ToggleRow(
                    title = source.name,
                    subtitle = "${source.category} · ${source.url}",
                    checked = source.id in enabledIds,
                    onCheckedChange = { onToggleSource(source.id, it) },
                )
            }
            if (enabledIds.isEmpty()) {
                item {
                    Text(
                        text = "全部关掉了——首页会没有内容可显示。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { SectionDivider() }
            item {
                SectionHeader(
                    title = "语义推荐模型",
                    caption = "让推荐理解同义表述，不只是关键词匹配",
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
                        text = "内置 ${FeedSources.DEFAULT.size} 个订阅源，下拉刷新，" +
                            "内容会缓存到本机，断网也能看。",
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
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
