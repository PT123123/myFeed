package com.example.feedreader.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.Interest
import com.example.feedreader.data.Interests
import com.example.feedreader.data.SearchKeyProvider
import io.github.pt123123.semantic.ModelState
import io.github.pt123123.semantic.SemanticStatus
import kotlinx.coroutines.launch

/**
 * 「兴趣」页：这一轮推荐流由什么决定。
 *
 * 从设置页拆出来是因为这四节（兴趣词、召回通道、搜索 key、语义模型）回答的是同一个问题
 * ——「首页为什么是这些内容」，而剩下的设置回答的是「App 怎么用起来顺手」。混在一页里，
 * 调一次兴趣词要滚过十几行缓存和阅读开关。
 *
 * 只排版：兴趣词的校验在 [Interests]，权重档位在那边，判定一条都不在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterestsScreen(
    recommend: RecommendSettings,
    semanticStatus: SemanticStatus,
    modelState: ModelState,
    modelBytes: Long,
    modelExpectedBytes: Long,
    onAddInterest: (String) -> AddInterestResult,
    onRemoveInterest: (String) -> Unit,
    onToggleInterest: (String, Boolean) -> Unit,
    onSetInterestWeight: (String, Float) -> Unit,
    onToggleRecallChannel: (String, Boolean) -> Unit,
    onSetSearchKey: (String, String) -> Unit,
    onSetRssHubUrl: (String) -> Unit,
    onToggleSemanticEnabled: (Boolean) -> Unit,
    onSyncRecommend: () -> Unit,
    onSyncSemantic: () -> Unit,
    onDownloadModel: () -> Unit,
    onLoadEncoder: () -> Unit,
    onDeleteModel: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var addInterestDialog by remember { mutableStateOf(false) }
    var rssHubDialog by remember { mutableStateOf(false) }
    var deleteModelDialog by remember { mutableStateOf(false) }

    // 进这一页时探一次磁盘：换设备恢复备份之后，内存里那份快照可能已经不是磁盘上那份了；
    // 模型也可能被清理工具删掉
    LaunchedEffect(Unit) {
        onSyncRecommend()
        onSyncSemantic()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("兴趣") },
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
            items(recommend.interests, key = { "interest-" + it.id }) { interest ->
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
                        text = "一个兴趣都没有 —— 首页会退化成「已订阅源的时间序」。" +
                            "不想订源也没关系，去下面「召回通道」里留着几路，兴趣词就是唯一的语料入口。",
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
            items(recommend.channels, key = { "channel-" + it.id }) { channel ->
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
            items(recommend.searchKeys, key = { "apikey-" + it.id }) { provider ->
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
        }
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
