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
import io.github.pt123123.semantic.BgeSmallZh
import io.github.pt123123.semantic.ModelState
import io.github.pt123123.semantic.SemanticEngine
import io.github.pt123123.semantic.SemanticStatus
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

    /**
     * 端侧语义模型的持有者。
     *
     * 挂在 ViewModel 上而不是界面里：加载要读 24MB 模型再建图 + 预热，几百毫秒起步，
     * 界面重组或切页不能把它丢掉。
     *
     * **刻意不在 init 里预加载**：模型没下载、或用户根本不在乎语义推荐时，
     * 不该白白付出这份代价。加载时机是「下载完成之后」或「用户在设置页点自检」。
     */
    private val semanticEngine = SemanticEngine(app)

    private val _semanticStatus = MutableStateFlow<SemanticStatus>(SemanticStatus.NotLoaded)
    val semanticStatus: StateFlow<SemanticStatus> = _semanticStatus.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Absent)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** 模型占用的磁盘字节：装好了就是完整大小，否则是半成品的进度。 */
    private val _modelBytes = MutableStateFlow(0L)
    val modelBytes: StateFlow<Long> = _modelBytes.asStateFlow()

    /** 模型文件的目标大小，给界面显示「共 23.0 MB」。 */
    val modelExpectedBytes: Long get() = BgeSmallZh.SIZE_BYTES

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

    // ------------------------------------------------------------------ 语义模型

    private var modelJob: Job? = null
    private var encoderJob: Job? = null

    /** 从磁盘同步一次模型状态。进设置页时调；**不触发加载**。 */
    fun syncSemanticState() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { semanticEngine.isModelInstalled() }
            _modelBytes.value = withContext(Dispatchers.IO) {
                if (installed) BgeSmallZh.SIZE_BYTES else semanticEngine.partialBytes()
            }
            if (installed) {
                if (_modelState.value !is ModelState.Ready) {
                    _modelState.value = ModelState.Ready(BgeSmallZh.SIZE_BYTES)
                }
            } else if (_modelState.value !is ModelState.Downloading) {
                // 磁盘上确实没有模型，把上次失败留下的红字也清掉，回到「未下载」
                _modelState.value = ModelState.Absent
                if (semanticEngine.status is SemanticStatus.NotLoaded) {
                    _semanticStatus.value = SemanticStatus.NotLoaded
                }
            }
        }
    }

    /**
     * 下载模型，装好后立刻加载并预热。
     *
     * 下载和加载放在同一个任务里是有意的：用户点「下载」想要的最终结果是
     * 「语义推荐能用了」，而不是「文件躺在磁盘上」。中间任何一步失败都要报出来。
     */
    fun downloadModel() {
        if (modelJob?.isActive == true) return
        _modelState.value = ModelState.Downloading(
            source = semanticEngine.sources.firstOrNull()?.name.orEmpty(),
            received = 0L,
            total = BgeSmallZh.SIZE_BYTES,
        )
        modelJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    semanticEngine.installModel { state ->
                        _modelState.value = state
                        if (state is ModelState.Downloading) _modelBytes.value = state.received
                    }
                }
            } catch (error: Throwable) {
                _modelState.value = ModelState.Failed(error.message ?: "下载失败")
                return@launch
            }
            _modelBytes.value = BgeSmallZh.SIZE_BYTES
            loadEncoder()
        }
    }

    /**
     * 加载编码器并预热，结果写进 [semanticStatus]。
     * 下载完成后自动调一次；设置页的「自检」也调它。
     */
    fun loadEncoder() {
        if (encoderJob?.isActive == true) return
        encoderJob = viewModelScope.launch {
            _semanticStatus.value = SemanticStatus.Loading
            // Default 而不是 IO：建图和预热是纯 CPU 计算，别去占网络那组线程
            withContext(Dispatchers.Default) { semanticEngine.getOrLoad() }
            _semanticStatus.value = semanticEngine.status
        }
    }

    /** 删除模型与已加载的编码器。下载中的任务也会被取消。 */
    fun deleteModel() {
        modelJob?.cancel()
        encoderJob?.cancel()
        semanticEngine.removeModel()
        _modelState.value = ModelState.Absent
        _semanticStatus.value = SemanticStatus.NotLoaded
        _modelBytes.value = 0L
    }

    override fun onCleared() {
        semanticEngine.close()
        super.onCleared()
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
