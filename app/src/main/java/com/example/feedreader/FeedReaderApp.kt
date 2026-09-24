package com.example.feedreader

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.feedreader.ui.FeedUiState
import com.example.feedreader.ui.FeedViewModel

private const val TAB_LIST = 0
private const val TAB_CARD = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedReaderApp(viewModel: FeedViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(FeedSources.ALL) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_LIST) }

    val visible = remember(state.articles, query, category) {
        val keyword = query.trim()
        state.articles.filter { article ->
            (category == FeedSources.ALL || article.category == category) &&
                (keyword.isEmpty() ||
                    article.title.contains(keyword, ignoreCase = true) ||
                    article.excerpt.contains(keyword, ignoreCase = true) ||
                    article.sourceName.contains(keyword, ignoreCase = true))
        }
    }

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
                actions = {
                    val busy = state.isLoading || state.isRefreshing
                    IconButton(onClick = viewModel::refresh, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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

            CategoryRow(current = category, onSelect = { category = it })

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
                        if (visible.isEmpty()) {
                            EmptyPane(query = query.trim(), category = category)
                        } else if (tab == TAB_CARD) {
                            CardView(articles = visible, onOpen = { openLink(context, it.link) })
                        } else {
                            ListView(articles = visible, onOpen = { openLink(context, it.link) })
                        }
                    }
                }
            }
        }
    }
}

/** 一行 chips 选分类。选项来自真实订阅源，不再写死。 */
@Composable
private fun CategoryRow(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FeedSources.CATEGORIES.forEach { item ->
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
        Text("正在加载订阅…", style = MaterialTheme.typography.bodyMedium)
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
            text = "下拉刷新，或点右上角重新拉取",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListView(articles: List<Article>, onOpen: (Article) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(articles, key = { it.id }) { article ->
            ListItemCard(article = article, onOpen = onOpen)
        }
    }
}

@Composable
private fun CardView(articles: List<Article>, onOpen: (Article) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(articles.chunked(2), key = { row -> row.first().id }) { rowArticles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowArticles.forEach { article ->
                    CardItem(
                        article = article,
                        onOpen = onOpen,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowArticles.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ListItemCard(article: Article, onOpen: (Article) -> Unit) {
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
    article: Article,
    onOpen: (Article) -> Unit,
    modifier: Modifier = Modifier,
) {
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

private fun Article.byline(): String =
    if (author.equals(sourceName, ignoreCase = true)) sourceName else "$sourceName · $author"

private fun FeedUiState.updatedLabel(): String =
    if (lastUpdated > 0L) "更新于 ${DateParser.relative(lastUpdated)}" else "尚未更新"

private fun openLink(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
