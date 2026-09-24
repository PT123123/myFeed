package com.example.feedreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.feedreader.data.FeedRepository
import com.example.feedreader.data.FeedSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedViewModel(
    private val repository: FeedRepository = FeedRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    private fun load() {
        val current = _state.value
        if (current.isLoading || current.isRefreshing) return

        // 有内容就只转下拉指示器，空页面才整屏转圈
        val coldStart = !current.hasContent
        _state.update {
            it.copy(isLoading = coldStart, isRefreshing = !coldStart, error = null)
        }

        viewModelScope.launch {
            val outcome = repository.fetchAll(FeedSources.DEFAULT)
            _state.update { previous ->
                val articles = outcome.articles.ifEmpty { previous.articles }
                val nothingLoaded = outcome.articles.isEmpty() && outcome.failures.isNotEmpty()

                previous.copy(
                    isLoading = false,
                    isRefreshing = false,
                    articles = articles,
                    failures = outcome.failures,
                    error = if (nothingLoaded && !previous.hasContent) {
                        outcome.failures.joinToString("\n") { "${it.source}：${it.message}" }
                    } else {
                        null
                    },
                    lastUpdated = if (outcome.articles.isNotEmpty()) {
                        System.currentTimeMillis()
                    } else {
                        previous.lastUpdated
                    },
                )
            }
        }
    }
}
