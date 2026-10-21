package com.example.feedreader

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.DeepLink
import com.example.feedreader.ui.LlmFeedScreen

/** Product navigation is independent of the RSS list/card layout controls. */
@Composable
fun FeedReaderApp(
    deepLink: DeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    var section by rememberSaveable { mutableIntStateOf(1) }
    val pages = rememberSaveableStateHolder()
    val llmState = rememberSaveable { Bundle() }
    LaunchedEffect(deepLink) { if (deepLink != null) section = 0 }
    BackHandler(enabled = section == 1) { section = 0 }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section) {
            Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("订阅阅读") })
            Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("LLM Feed") })
        }
        Box(Modifier.weight(1f)) {
            if (section == 0) {
                pages.SaveableStateProvider("subscriptions") {
                    SubscriptionReaderApp(deepLink = deepLink, onDeepLinkHandled = onDeepLinkHandled)
                }
            }
            // Keep drafts and the WebView's IndexedDB transactions alive when switching tabs.
            LlmFeedScreen(llmState, if (section == 1) Modifier.fillMaxSize() else Modifier.size(0.dp), active = section == 1)
        }
    }
}
