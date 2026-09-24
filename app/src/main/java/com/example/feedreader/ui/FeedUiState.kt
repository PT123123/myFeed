package com.example.feedreader.ui

import com.example.feedreader.data.Article
import com.example.feedreader.data.FeedFailure

/** 首页的全部可变状态。 */
data class FeedUiState(
    /** 首次加载（页面还没有任何内容）时转圈。 */
    val isLoading: Boolean = false,
    /** 已有内容时的刷新，走下拉指示器，不盖住列表。 */
    val isRefreshing: Boolean = false,
    val articles: List<Article> = emptyList(),
    /** 部分源失败：列表照常显示，顶部挂一条提示。 */
    val failures: List<FeedFailure> = emptyList(),
    /** 全部源都失败且无内容可显示时，才进整页错误态。 */
    val error: String? = null,
    val lastUpdated: Long = 0L,
) {
    val hasContent: Boolean get() = articles.isNotEmpty()
}
