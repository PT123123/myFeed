package com.example.feedreader

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

import com.example.feedreader.RSSParser
import com.example.feedreader.ThemeMode

@Composable
fun FeedScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onSourceChange: (String) -> Unit,
    onSearchQuery: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Twitter List, 1: Xiaohongshu Cards
    var searchQuery by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("general") } // general, twitter, xiaohongshu

    // Filter articles based on search and source
    val filteredArticles = if (searchQuery.isNotEmpty()) {
        RSSParser().parseRSS("https://example.com/rss") // Placeholder - would use actual source
            .filter { it.title?.contains(searchQuery) || it.excerpt?.contains(searchQuery) }
    } else {
        RSSParser().parseRSS("https://example.com/rss") // Placeholder
            .filter { it.pubDate != null }
    }

    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Header
        Text(
            text = "News Feed",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Theme indicator
        ThemeIndicator(themeMode)

        // Tab navigation
        TabBar(
            tabs = listOf(
                Tab("Twitter List", 0),
                Tab("Xiaohongshu Cards", 1)
            ),
            current = selectedTab,
            onTabClick = { idx -> selectedTab = idx }
        )

        // Articles section
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredArticles) {
                item(itemIndex = 0) {
                    article = filteredArticles[it.index]
                    renderArticle(article)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        ButtonGroup(onClick = { /* handle button clicks */ }) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                ItemButton(themeMode, "Refresh", "🔄")
                ItemButton(themeMode, "Settings", "⚙️")
            }
        }
    }
}

@Composable
fun ThemeIndicator(themeMode: ThemeMode) {
    Box(
        modifier = Modifier.padding(12.dp),
        fillMaxWidth = true
    ) {
        Text(
            text = when (themeMode) {
                ThemeMode.TWITTER -> "Twitter Style (List)"
                ThemeMode.XIAOHONGSHU -> "Xiaohongshu Style (Cards)"
            },
            style = MaterialTheme.typography.subtitleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TabBar(tabs: List<Tab>, current: Int, onTabClick: (Int) -> Unit) {
    Row(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        tabs.forEachIndexed { index, tab ->
            TabItem(
                label = tab.label,
                selected = if (index == current) true else false,
                onClick = { onTabClick(index) }
            )
            .padding(4.dp)
        )
    }
}

@Composable
fun TabItem(label: String, selected: Boolean, onClick: (Int) -> Unit) {
    Row(
        modifier = Modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = selected,
            onClick = onClick,
            modifier = Modifier.padding(2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ItemButton(themeMode: ThemeMode, label: String, icon: String) {
    Button(
        onClick = { /* Handle button click */ },
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.${icon}, contentDescription = label)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// Data class representing an RSS Article
data class RSSArticle(
    val title: String,
    val excerpt: String,
    val pubDate: String,
    val author: String,
    val url: String = ""
) {
}
