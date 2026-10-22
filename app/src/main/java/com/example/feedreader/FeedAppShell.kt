package com.example.feedreader

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.feedreader.data.DeepLink
import com.example.feedreader.ui.LlmFeedScreen

private const val SECTION_READER = 0
private const val SECTION_STUDIO = 1

/**
 * 阅读器和电脑端生产页的总壳。
 *
 * 「AI 内容」不再是这一层并列的一个 tab —— 内容和已读都进了原生那一条流（见
 * `docs/native-desktop-sync.md`），这一层只剩「平时读书」和「去电脑端生产内容」两件事，
 * 后者由首页抽屉进，进来就是一整页。
 *
 * WebView 一直挂在树里（不活跃时给 0dp）：它里面的草稿和 IndexedDB 事务经不起一次销毁重建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedReaderApp(
    deepLink: DeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    var section by rememberSaveable { mutableIntStateOf(SECTION_READER) }
    val pages = rememberSaveableStateHolder()
    val llmState = rememberSaveable { Bundle() }
    LaunchedEffect(deepLink) { if (deepLink != null) section = SECTION_READER }
    BackHandler(enabled = section == SECTION_STUDIO) { section = SECTION_READER }
    Column(Modifier.fillMaxSize()) {
        if (section == SECTION_STUDIO) {
            // TopAppBar 自己避让状态栏：targetSdk 35 在 Android 15 上是强制 edge-to-edge 的，
            // 手写那一排 tab 时没算这层 insets，结果就是「点上去都跑到状态栏里去了」。
            TopAppBar(
                title = { Text("AI 内容生产") },
                navigationIcon = {
                    IconButton(onClick = { section = SECTION_READER }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回阅读器")
                    }
                },
            )
        }
        Box(Modifier.weight(1f)) {
            if (section == SECTION_READER) {
                pages.SaveableStateProvider("subscriptions") {
                    SubscriptionReaderApp(
                        deepLink = deepLink,
                        onDeepLinkHandled = onDeepLinkHandled,
                        onOpenStudio = { section = SECTION_STUDIO },
                    )
                }
            }
            LlmFeedScreen(
                llmState,
                if (section == SECTION_STUDIO) Modifier.fillMaxSize() else Modifier.size(0.dp),
                active = section == SECTION_STUDIO,
            )
        }
    }
}
