package com.example.feedreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.feedreader.data.CacheStats
import com.example.feedreader.data.FeedCache
import com.example.feedreader.data.FeedRepository
import com.example.feedreader.data.FeedSource
import com.example.feedreader.data.FeedSources
import com.example.feedreader.data.SearchEngine
import com.example.feedreader.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 需要 Application 来拿 SharedPreferences 和缓存目录，所以是 AndroidViewModel。
 * 加 @JvmOverloads 是为了让默认工厂能直接找到 (Application) 这个构造器。
 */
class FeedViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: FeedRepository = FeedRepository(),
    private val cache: FeedCache = FeedCache.forApp(app),
) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** 当前启用的源。首页的分类、设置页的开关都以它为准。 */
    private val _enabledSources = MutableStateFlow(FeedSources.DEFAULT.filter { it.id in settings.enabledSourceIds })
    val enabledSources: StateFlow<List<FeedSource>> = _enabledSources.asStateFlow()

    private val _useInAppBrowser = MutableStateFlow(settings.useInAppBrowser)
    val useInAppBrowser: StateFlow<Boolean> = _useInAppBrowser.asStateFlow()

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    private val _retentionDays = MutableStateFlow(settings.cacheRetentionDays)
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _cacheMaxMb = MutableStateFlow(settings.cacheMaxMb)
    val cacheMaxMb: StateFlow<Int> = _cacheMaxMb.asStateFlow()

    private val _searchEngine = MutableStateFlow(SearchEngine.of(settings.searchEngineId))
    val searchEngine: StateFlow<SearchEngine> = _searchEngine.asStateFlow()

    init {
        viewModelScope.launch {
            // 先按当前策略清一遍再加载：改了保留天数/上限之后，下次启动就生效
            val stats = withContext(Dispatchers.IO) {
                cache.prune(settings.cacheRetentionDays, settings.cacheMaxBytes())
                cache.stats()
            }
            _cacheStats.value = stats
            load()
        }
    }

    fun refresh() = load()

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val ids = settings.enabledSourceIds.toMutableSet()
        if (enabled) ids += id else ids -= id
        settings.enabledSourceIds = ids
        syncEnabledSources()
        load(restart = true)
    }

    fun setUseInAppBrowser(value: Boolean) {
        settings.useInAppBrowser = value
        _useInAppBrowser.value = value
    }

    fun setSearchEngine(engine: SearchEngine) {
        settings.searchEngineId = engine.id
        _searchEngine.value = engine
    }

    /** 改保留天数：立刻按新策略清理，不用等下次刷新。 */
    fun setCacheRetentionDays(days: Int) {
        settings.cacheRetentionDays = days
        _retentionDays.value = days
        viewModelScope.launch { pruneNow() }
    }

    /** 改容量上限：同上，立刻生效。 */
    fun setCacheMaxMb(mb: Int) {
        settings.cacheMaxMb = mb
        _cacheMaxMb.value = mb
        viewModelScope.launch { pruneNow() }
    }

    /** 按当前策略清理一遍，返回给设置页做提示。 */
    suspend fun pruneNow(): String {
        val result = withContext(Dispatchers.IO) {
            cache.prune(settings.cacheRetentionDays, settings.cacheMaxBytes())
        }
        _cacheStats.value = withContext(Dispatchers.IO) { cache.stats() }
        return if (result.removed == 0) {
            "没有需要清理的缓存"
        } else {
            "已清理 ${result.removed} 个源的缓存，释放 ${formatBytes(result.freedBytes)}"
        }
    }

    /** 清空全部缓存。返回提示文案。 */
    suspend fun clearCacheNow(): String {
        val result = withContext(Dispatchers.IO) { cache.clear() }
        _cacheStats.value = withContext(Dispatchers.IO) { cache.stats() }
        return if (result.removed == 0) {
            "缓存本来就是空的"
        } else {
            "已清空 ${result.removed} 个源的缓存，释放 ${formatBytes(result.freedBytes)}"
        }
    }

    private fun syncEnabledSources() {
        val ids = settings.enabledSourceIds
        _enabledSources.value = FeedSources.DEFAULT.filter { it.id in ids }
    }

    private suspend fun restoreFromCache(sources: List<FeedSource>) {
        val enabled = sources.map { it.id }.toSet()
        val cached = withContext(Dispatchers.IO) { cache.readAll() }
            .filter { it.sourceId in enabled }
        if (cached.isEmpty()) return

        val articles = cached.flatMap { it.articles }.sortedByDescending { it.publishedAt }
        if (articles.isEmpty()) return

        _state.update {
            it.copy(
                // 缓存铺上就不再整屏转圈，改由顶部的下拉指示器表示「网络还在拉」
                isLoading = false,
                isRefreshing = true,
                articles = articles,
                lastUpdated = cached.maxOfOrNull { feed -> feed.fetchedAt } ?: 0L,
                showingCache = true,
            )
        }
    }

    private var loadJob: Job? = null

    private fun load(restart: Boolean = false) {
        val current = _state.value
        if (restart) {
            // 改了启用的源就期望立刻看到变化，把还在跑的那次换掉，
            // 否则会卡在下面那个守卫上，改了跟没改一样
            loadJob?.cancel()
        } else if (current.isLoading || current.isRefreshing) {
            return
        }

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
                    showingCache = false,
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

        loadJob = viewModelScope.launch {
            // 磁盘缓存先顶上：秒开，断网时也有东西看
            if (coldStart) restoreFromCache(sources)

            val outcome = repository.fetchAll(sources)

            val stats = withContext(Dispatchers.IO) {
                val enabled = sources.map { it.id }.toSet()
                outcome.articles
                    .filter { it.sourceId in enabled }
                    .groupBy { it.sourceId }
                    .forEach { (id, list) -> cache.write(id, list) }
                cache.prune(settings.cacheRetentionDays, settings.cacheMaxBytes())
                cache.stats()
            }
            _cacheStats.value = stats

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
                    // 一个源都没新内容、列表还是缓存那份时，继续标成缓存
                    showingCache = outcome.articles.isEmpty() && previous.showingCache,
                )
            }
        }
    }
}

/** 给设置页显示缓存占用。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
