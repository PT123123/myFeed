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
    onToggleSource: (String, Boolean) -> Unit,
    onToggleInAppBrowser: (Boolean) -> Unit,
    onSetRetentionDays: (Int) -> Unit,
    onSetCacheMaxMb: (Int) -> Unit,
    onSetSearchEngine: (SearchEngine) -> Unit,
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
