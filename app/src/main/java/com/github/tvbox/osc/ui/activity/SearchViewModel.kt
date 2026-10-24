package com.github.tvbox.osc.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.net.SearchSettings
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.UA
import com.github.tvbox.osc.api.SearchSourcesState
import com.github.tvbox.osc.util.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.ui.components.toVodInfo

class SearchViewModel(initialTitle: String? = null) : ViewModel() {

    enum class ResultState { Pending, Done }

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
        val arrivedAt: Int = Int.MAX_VALUE,
    )

    private val _results = MutableStateFlow<List<SourceResult>>(emptyList())
    val results = _results.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _searchedTitle = MutableStateFlow("")
    val searchedTitle = _searchedTitle.asStateFlow()
    private val _exactMatch = MutableStateFlow(false)
    val exactMatch = _exactMatch.asStateFlow()
    private val _sitesEmpty = MutableStateFlow(false)
    val sitesEmpty = _sitesEmpty.asStateFlow()

    private val _hotSearch = MutableStateFlow<List<String>>(emptyList())
    val hotSearch = _hotSearch.asStateFlow()

    private val _suggest = MutableStateFlow<List<String>>(emptyList())
    val suggest = _suggest.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(readSearchHistory())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    fun clearSearchHistory() {
        HistoryHelper.clearSearchHistory()
        _searchHistory.value = readSearchHistory()
    }

    fun removeSearchHistory(keyword: String) {
        HistoryHelper.removeSearchHistory(keyword)
        _searchHistory.value = readSearchHistory()
    }

    private fun readSearchHistory(): List<String> = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList())

    suspend fun isVideoCollected(video: Movie.Video): Boolean = withContext(Dispatchers.IO) {
        AppGraph.collectRepository.isVodCollect(video.sourceKey, video.id)
    }

    fun toggleVideoCollect(video: Movie.Video, toCollect: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (toCollect) {
                AppGraph.collectRepository.insertVodCollect(video.sourceKey, toVodInfo(video))
            } else {
                AppGraph.collectRepository.deleteVodCollect(video.sourceKey, toVodInfo(video))
            }
        }
    }

    private var suggestJob: Job? = null

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val scope = viewModelScope
    private val searchCaller = SourceViewModel()

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val SEARCH_TIMEOUT_MS = 30_000L

        private const val DOUBAN_HOT_URL =
            "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range="

        private const val HOT_SEARCH_LIMIT = 20

        private const val DOUBAN_REFERER = "https://movie.douban.com/"

        private const val SUGGEST_URL = "https://suggest.video.iqiyi.com/?if=mobile&key="

        private const val SUGGEST_LIMIT = 20

        private const val SUGGEST_TIMEOUT_MS = 2_000L
    }

    init {
        scope.launch {
            searchCaller.searchResult.flow.collect { onSearchResult(it) }
        }
        fetchHotSearch()
        SearchSourcesState.refresh()
        val initial = initialTitle?.trim().orEmpty()
        if (initial.isNotEmpty()) {
            search(initial)
        }
    }

    override fun onCleared() {
        try {
            searchCaller.cancelSearch()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel search requests failed")
        }
    }

    private fun fetchHotSearch() {
        scope.launch(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            val cached = KV.get(HawkConfig.HOME_HOT, "")
            if (KV.get(HawkConfig.HOME_HOT_DAY, "") == today && cached.isNotEmpty()) {
                val titles = parseHotTitles(cached)
                if (titles.isNotEmpty()) {
                    _hotSearch.value = titles
                    return@launch
                }
            }
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            try {
                val body = Http.get(DOUBAN_HOT_URL + year + "," + year) {
                    headers("User-Agent", UA.random())
                    headers("Referer", DOUBAN_REFERER)
                }
                val titles = parseHotTitles(body)
                if (titles.isNotEmpty()) {
                    KV.put(HawkConfig.HOME_HOT, body)
                    KV.put(HawkConfig.HOME_HOT_DAY, today)
                }
                _hotSearch.value = titles
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _hotSearch.value = parseHotTitles(KV.get(HawkConfig.HOME_HOT, ""))
            }
        }
    }

    private fun parseHotTitles(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), HOT_SEARCH_LIMIT))
            .mapNotNull { arr.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotEmpty() } }
    } catch (_: Throwable) {
        emptyList()
    }

    fun fetchSuggest(text: String) {
        suggestJob?.cancel()
        suggestJob = scope.launch {
            val body = withTimeoutOrNull(SUGGEST_TIMEOUT_MS) {
                try {
                    Http.getOnce(SUGGEST_URL + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            _suggest.value = withContext(Dispatchers.IO) { parseSuggest(body) }
        }
    }

    fun clearSuggest() {
        suggestJob?.cancel()
        _suggest.value = emptyList()
    }

    private fun parseSuggest(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), SUGGEST_LIMIT)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val title = o.optString("title")
            when {
                name.isNotEmpty() -> name
                title.isNotEmpty() -> title
                else -> null
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun search(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        val configured = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
        if (configured != semaphorePermits) {
            semaphorePermits = configured
            semaphore = Semaphore(configured)
        }
        token = SEARCH_SEQ.incrementAndGet()
        val myToken = token
        val tokenStr = myToken.toString()
        _searchedTitle.value = t
        _exactMatch.value = SearchSettings.isExactMatchEnabled()
        HistoryHelper.setSearchHistory(t)
        _searchHistory.value = readSearchHistory()
        clearSuggest()
        try {
            JsLoader.stopAll()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "JsLoader.stopAll failed, continue new search")
        }
        try {
            searchCaller.cancelSearch()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel previous search requests failed")
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        val home = ApiConfig.get().getHomeSourceBean()
        val picked = SearchSourcesState.state.value.pickedKeys
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && picked.contains(it.key) }
            .sortedBy { it.key != home.key }
        arriveSeq = 0
        _results.value = sources.map { SourceResult(it.key.orEmpty(), it.name.orEmpty(), ResultState.Pending, emptyList()) }
        _sitesEmpty.value = sources.isEmpty()
        if (sources.isEmpty()) {
            _running.value = false
            return
        }
        _running.value = true
        scope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            if (myToken != token) return@async
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            pendingSources[bean.key.orEmpty()] = done
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    searchCaller.getSearch(bean.key, t, tokenStr)
                                    done.await()
                                }
                            } finally {
                                pendingSources.remove(bean.key, done)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (myToken == token) _running.value = false
        }
    }

    private fun onSearchResult(data: AbsXml?) {
        val data = data ?: return
        val myToken = token
        if (data.searchToken != myToken.toString()) return
        val sourceKey = data.sourceKey ?: return
        if (_results.value.none { it.sourceKey == sourceKey }) return
        pendingSources.remove(sourceKey)?.complete(Unit)
        val videos = data.movie?.videoList.orEmpty()
            .filter { !_exactMatch.value || SearchSettings.isExactMatch(it.name, _searchedTitle.value) }
            .sortedByDescending { it.name?.trim() == _searchedTitle.value }
        updateResult(sourceKey, videos)
    }

    private fun updateResult(sourceKey: String, videos: List<Movie.Video>) {
        _results.value = _results.value.map {
            if (it.sourceKey == sourceKey) {
                SourceResult(sourceKey, it.sourceName, ResultState.Done, videos, ++arriveSeq)
            } else {
                it
            }
        }
    }
}
