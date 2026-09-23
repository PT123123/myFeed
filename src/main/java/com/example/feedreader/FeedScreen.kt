package com.example.feedreader

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.feedreader.RSSParser
import com.example.feedreader.ThemeMode

@Composable
fun FeedScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    var selectedItem by remember { mutableStateOf<RSSArticle?>(null) }

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

        LazyColumn(
            modifiers = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = selectedItemsOrEmpty()
            ) {
                item(itemIndex = 0) {
                    article = selectedItem
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
fun SelectedItemsOrEmpty(): List<RSSArticle?> = if (selectedItem != null) listOf(selectedItem) else emptyList()

@Composable
fun renderArticle(article: RSSArticle) {
    if (article == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = article.title,
            style = MaterialTheme.typography.h4,
            maxLines = 2,
            overflow = True
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Excerpt
        Text(
            text = article.excerpt ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Date
        Text(
            text = article.pubDate ?: "",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSecondary
        )

        // Author
        Text(
            text = article.author ?: "Unknown",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSecondary
        )
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
