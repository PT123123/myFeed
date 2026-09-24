package com.example.feedreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.feedreader.data.FeedRepository
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.FeedSources
import com.example.feedreader.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 需要 Application 来拿 SharedPreferences，所以是 AndroidViewModel。
 * 加 @JvmOverloads 是为了让默认工厂能直接找到 (Application) 这个构造器。
 */
class FeedViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: FeedRepository = FeedRepository(),
) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** 当前启用的源。首页的分类、设置页的开关都以它为准。 */
    private val _enabledSources = MutableStateFlow(FeedSources.DEFAULT.filter { it.id in settings.enabledSourceIds })
    val enabledSources: StateFlow<List<FeedSource>> = _enabledSources.asStateFlow()

    private val _useInAppBrowser = MutableStateFlow(settings.useInAppBrowser)
    val useInAppBrowser: StateFlow<Boolean> = _useInAppBrowser.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val ids = settings.enabledSourceIds.toMutableSet()
        if (enabled) ids += id else ids -= id
        settings.enabledSourceIds = ids
        syncEnabledSources()
        load()
    }

    fun setUseInAppBrowser(value: Boolean) {
        settings.useInAppBrowser = value
        _useInAppBrowser.value = value
    }

    private fun syncEnabledSources() {
        val ids = settings.enabledSourceIds
        _enabledSources.value = FeedSources.DEFAULT.filter { it.id in ids }
    }

    private fun load() {
        val current = _state.value
        if (current.isLoading || current.isRefreshing) return

        val sources = _enabledSources.value
        if (sources.isEmpty()) {
            _state.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    articles = emptyList(),
                    failures = emptyList(),
                    error = null,
                    noSourcesEnabled = true,
                )
            }
            return
        }

        // 有内容就只转下拉指示器，空页面才整屏转圈
        val coldStart = !current.hasContent
        _state.update {
            it.copy(
                isLoading = coldStart,
                isRefreshing = !coldStart,
                error = null,
                noSourcesEnabled = false,
            )
        }

        viewModelScope.launch {
            val outcome = repository.fetchAll(sources)
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
