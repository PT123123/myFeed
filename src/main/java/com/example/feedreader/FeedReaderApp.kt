package com.example.feedreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedReaderApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("all") }

    val sampleArticles = remember {
        listOf(
            Article("1", "Android 15 正式发布", "Google 发布了 Android 15 系统...", "2026-09-23", "Google"),
            Article("2", "Kotlin 2.0 新特性", "Kotlin 2.0 带来了许多令人兴奋的新特性...", "2026-09-22", "JetBrains"),
            Article("3", "Jetpack Compose 最佳实践", "本文介绍了 Jetpack Compose 开发中的最佳实践...", "2026-09-21", "Android Dev"),
            Article("4", "Material Design 3 设计指南", "Material Design 3 提供了现代化的设计语言...", "2026-09-20", "Google Design"),
            Article("5", "RSS 协议详解", "RSS 是一种通用的信息发布协议...", "2026-09-19", "RSS Foundation")
        )
    }

    val filteredArticles = remember(searchQuery, selectedSource) {
        sampleArticles.filter { article ->
            (searchQuery.isEmpty() || article.title.contains(searchQuery, ignoreCase = true) ||
                    article.excerpt.contains(searchQuery, ignoreCase = true)) &&
                    (selectedSource == "all" || article.source == selectedSource)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题栏
        TopAppBar(
            title = { Text("MyFeed") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索文章...") },
            singleLine = true
        )

        // 来源选择器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSource == "all",
                onClick = { selectedSource = "all" },
                label = { Text("全部") }
            )
            FilterChip(
                selected = selectedSource == "tech",
                onClick = { selectedSource = "tech" },
                label = { Text("科技") }
            )
            FilterChip(
                selected = selectedSource == "news",
                onClick = { selectedSource = "news" },
                label = { Text("新闻") }
            )
        }

        // Tab 切换
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("列表视图") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("卡片视图") }
            )
        }

        // 内容区域
        when (selectedTab) {
            0 -> ListView(articles = filteredArticles)
            1 -> CardView(articles = filteredArticles)
        }
    }
}

@Composable
fun ListView(articles: List<Article>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(articles) { article ->
            ListItemCard(article)
        }
    }
}

@Composable
fun CardView(articles: List<Article>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(articles.chunked(2)) { rowArticles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowArticles.forEach { article ->
                    CardItem(
                        article = article,
                        modifier = Modifier.weight(1f)
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
fun ListItemCard(article: Article) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = article.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = article.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = article.pubDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CardItem(article: Article, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.aspectRatio(0.75f),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = article.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column {
                Text(
                    text = article.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = article.pubDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class Article(
    val id: String,
    val title: String,
    val excerpt: String,
    val pubDate: String,
    val author: String,
    val source: String = "tech"
)
