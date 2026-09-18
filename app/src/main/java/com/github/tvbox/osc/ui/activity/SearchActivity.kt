@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.page.ManageActionIcon
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SearchSettings
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.UA
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SearchActivity : BaseActivity() {

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SearchScreen()
            }
        }
    }
}

class SearchViewModel : ViewModel() {

    enum class ResultState { Pending, Done }

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
        val arrivedAt: Int = Int.MAX_VALUE,
    )

    val results = MutableStateFlow<List<SourceResult>>(emptyList())
    val running = MutableStateFlow(false)
    val searchedTitle = MutableStateFlow("")
    val exactMatch = MutableStateFlow(false)
    val sitesEmpty = MutableStateFlow(false)

    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    val suggest = MutableStateFlow<List<String>>(emptyList())

    private var suggestSeq = 0

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val scope = viewModelScope

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val SEARCH_TIMEOUT_MS = 30_000L

        private const val DOUBAN_HOT_URL =
            "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range="

        private const val HOT_SEARCH_LIMIT = 20

        private const val SUGGEST_URL = "https://suggest.video.iqiyi.com/?if=mobile&key="

        private const val SUGGEST_LIMIT = 20

        @Volatile
        var checkedSources: HashMap<String, String>? = null
            private set

        @Volatile
        private var checkedSourcesApiUrl: String? = null

        @JvmStatic
        fun clearCheckedSources() {
            checkedSources = null
            checkedSourcesApiUrl = null
        }

        @JvmStatic
        fun loadCheckedSources() {
            val selection = SearchSettings.currentSelection()
            if (selection != null) {
                checkedSources = HashMap<String, String>().apply { selection.forEach { put(it, "1") } }
                checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
                return
            }
            val all = SearchHelper.getSources()
            if (all.isEmpty()) {
                checkedSources = null
                checkedSourcesApiUrl = null
                return
            }
            checkedSources = all
            checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
        }

        @JvmStatic
        fun isCheckedSourcesStale(): Boolean {
            if (checkedSources == null) return true
            if (checkedSourcesApiUrl != KV.get(HawkConfig.API_URL, "")) return true
            return SearchHelper.isSelectionStale(checkedSources)
        }
    }

    init {
        org.greenrobot.eventbus.EventBus.getDefault().register(this)
        fetchHotSearch()
    }

    override fun onCleared() {
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
        try {
            OkGo.getInstance().cancelTag("suggest")
        } catch (_: Throwable) {
        }
    }

    private fun fetchHotSearch() {
        scope.launch(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            val cached = KV.get(HawkConfig.HOME_HOT, "")
            if (KV.get(HawkConfig.HOME_HOT_DAY, "") == today && cached.isNotEmpty()) {
                hotSearch.value = parseHotTitles(cached)
                return@launch
            }
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            OkGo.get<String>(DOUBAN_HOT_URL + year + "," + year)
                .headers("User-Agent", UA.randomOne())
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        val body = response.body().orEmpty()
                        if (body.isNotEmpty()) {
                            KV.put(HawkConfig.HOME_HOT, body)
                            KV.put(HawkConfig.HOME_HOT_DAY, today)
                        }
                        hotSearch.value = parseHotTitles(body)
                    }

                    override fun convertResponse(response: okhttp3.Response): String =
                        response.body.string()

                    override fun onError(response: com.lzy.okgo.model.Response<String>) {
                        super.onError(response)
                        hotSearch.value = parseHotTitles(KV.get(HawkConfig.HOME_HOT, ""))
                    }
                })
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
        val seq = ++suggestSeq
        OkGo.get<String>(SUGGEST_URL + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
            .tag("suggest")
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                    if (seq != suggestSeq) return
                    suggest.value = parseSuggest(response.body().orEmpty())
                }

                override fun convertResponse(response: okhttp3.Response): String =
                    response.body.string()

                override fun onError(response: com.lzy.okgo.model.Response<String>) {
                    super.onError(response)
                }
            })
    }

    fun clearSuggest() {
        suggestSeq++
        suggest.value = emptyList()
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
        searchedTitle.value = t
        exactMatch.value = SearchSettings.isExactMatchEnabled()
        HistoryHelper.setSearchHistory(t)
        clearSuggest()
        try {
            JsLoader.stopAll()
        } catch (_: Throwable) {
        }
        try {
            com.lzy.okgo.OkGo.getInstance().cancelTag("search")
        } catch (_: Throwable) {
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        val home = ApiConfig.get().getHomeSourceBean()
        val checked = checkedSources
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
        arriveSeq = 0
        results.value = sources.map { SourceResult(it.key, it.name.orEmpty(), ResultState.Pending, emptyList()) }
        sitesEmpty.value = sources.isEmpty()
        if (sources.isEmpty()) {
            running.value = false
            return
        }
        running.value = true
        scope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            if (myToken != token) return@async
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            pendingSources[bean.key] = done
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    withContext(Dispatchers.IO) {
                                        searchCaller.getSearch(bean.key, t, tokenStr)
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSources.remove(bean.key, done)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (myToken == token) running.value = false
        }
    }

    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    fun onSearchResultEvent(event: com.github.tvbox.osc.event.RefreshEvent) {
        if (event.type != com.github.tvbox.osc.event.RefreshEvent.TYPE_SEARCH_RESULT) return
        val data = event.obj as? AbsXml ?: return
        val myToken = token
        if (data.searchToken != myToken.toString()) return
        val sourceKey = data.sourceKey ?: return
        if (results.value.none { it.sourceKey == sourceKey }) return
        pendingSources.remove(sourceKey)?.complete(Unit)
        val videos = data.movie?.videoList.orEmpty()
            .filter { !exactMatch.value || SearchSettings.isExactMatch(it.name, searchedTitle.value) }
            .sortedByDescending { it.name?.trim() == searchedTitle.value }
        updateResult(sourceKey, videos)
    }

    private fun updateResult(sourceKey: String, videos: List<Movie.Video>) {
        results.value = results.value.map {
            if (it.sourceKey == sourceKey) {
                SourceResult(sourceKey, it.sourceName, ResultState.Done, videos, ++arriveSeq)
            } else {
                it
            }
        }
    }

    private val searchCaller = SourceViewModel()
}

@Composable
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val results by vm.results.collectAsState()
    val running by vm.running.collectAsState()
    val hotSearch by vm.hotSearch.collectAsState()
    val suggest by vm.suggest.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(KV.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())) }
    val searchedTitle by vm.searchedTitle.collectAsState()
    val exactMatch by vm.exactMatch.collectAsState()
    val sitesEmpty by vm.sitesEmpty.collectAsState()
    val vodMenu = rememberVodCardMenuState()
    var resultLayout by remember { mutableStateOf(SearchSettings.resultLayout()) }

    LaunchedEffect(Unit) {
        if (SearchViewModel.isCheckedSourcesStale()) {
            SearchViewModel.loadCheckedSources()
        }
        val initTitle = activity?.intent?.getStringExtra("title")
        if (!initTitle.isNullOrEmpty()) {
            query = initTitle
            vm.search(initTitle)
        }
    }

    LaunchedEffect(query) {
        val t = query.trim()
        if (t.isEmpty()) {
            vm.clearSuggest()
        } else {
            delay(300)
            vm.fetchSuggest(t)
        }
    }

    val bootState by com.github.tvbox.osc.ui.page.AppBootstrap.state.collectAsState()
    LaunchedEffect(bootState) {
        if (bootState is com.github.tvbox.osc.ui.page.AppBootstrap.Boot.Ready && SearchViewModel.isCheckedSourcesStale()) {
            SearchViewModel.loadCheckedSources()
        }
    }

    val resultListState = rememberLazyListState()

    LaunchedEffect(resultLayout) {
        selectedSource = null
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        query = t
        hideIme(activity)
        selectedSource = null
        vm.search(t)
        history = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList())
    }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { submit(query) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        navigationIcon = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .clickable { activity?.finish() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        actions = {
            LayoutSwitchAction(
                selected = resultLayout,
                onSelect = {
                    resultLayout = it
                    SearchSettings.setResultLayout(it)
                },
            )
        },
    ) { topPad, _ ->
        if (results.isEmpty() && !running && sitesEmpty) {
            SearchEmptyBox(topPad = topPad, text = "未选择搜索站点，请到首页「搜索设置」里勾选")
        } else if (results.isEmpty() && !running) {
            SearchIdleContent(
                history = history,
                hotSearch = hotSearch,
                suggest = suggest,
                topPad = topPad,
                onSearch = { submit(it) },
                onClearHistory = {
                    HistoryHelper.clearSearchHistory()
                    history = ArrayList()
                },
                onRemoveHistory = { word ->
                    HistoryHelper.removeSearchHistory(word)
                    history = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList())
                },
            )
        } else {
            SearchResultsContent(
                results = results,
                running = running,
                selectedSource = selectedSource,
                onSelectSource = { selectedSource = it },
                resultLayout = resultLayout,
                listState = resultListState,
                searchedTitle = searchedTitle,
                exactMatch = exactMatch,
                topPad = topPad,
                onCardClick = { context.openVodCardOrDetail(it) },
                onCardLongClick = { vodMenu.show(it) },
            )
        }
    }

    VodCardMenu(vodMenu)
}

@Composable
private fun SearchEmptyBox(topPad: Dp, text: String) {
    LoadStateBox(
        state = LoadState.Empty,
        emptyText = text,
        errorText = "",
        retryText = "",
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPad),
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchIdleContent(
    history: List<String>,
    hotSearch: List<String>,
    suggest: List<String>,
    topPad: Dp,
    onSearch: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(topPad - 20.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.cardContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionIconBadge(R.drawable.ic_search_history, "搜索历史")
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "搜索历史",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ManageActionIcon(
                    iconRes = R.drawable.ic_delete,
                    contentDescription = "清空搜索历史",
                    onClick = onClearHistory,
                )
            }
            if (history.isEmpty()) {
                Text(
                    text = "暂无搜索历史",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { word ->
                        HistoryChip(
                            word = word,
                            onClick = { onSearch(word) },
                            onLongClick = { onRemoveHistory(word) },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.cardContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val suggestTitle = if (suggest.isEmpty()) "热搜榜" else "搜索建议"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionIconBadge(R.drawable.ic_hot_search, suggestTitle)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = suggestTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (suggest.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggest.forEach { word ->
                        HistoryChip(word = word, onClick = { onSearch(word) }, onLongClick = {})
                    }
                }
            } else if (hotSearch.isEmpty()) {
                Text(
                    text = "暂无热搜数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    hotSearch.chunked(2).forEachIndexed { rowIdx, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            pair.forEachIndexed { colIdx, title ->
                                val rank = rowIdx * 2 + colIdx + 1
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSearch(title) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = rank.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (rank <= 3) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.width(20.dp),
                                    )
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 10.dp),
                                    )
                                }
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SearchResultsContent(
    results: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    resultLayout: SearchSettings.SearchLayout,
    listState: LazyListState,
    searchedTitle: String,
    exactMatch: Boolean,
    topPad: Dp,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val done = results.filter { it.videos.isNotEmpty() }
    if (done.isEmpty() && !running) {
        SearchEmptyBox(
            topPad = topPad,
            text = if (exactMatch) {
                "未找到与「$searchedTitle」完全一致的结果"
            } else {
                "「$searchedTitle」暂无搜索结果"
            },
        )
        return
    }
    val railState = rememberLazyListState()
    val railResultState = rememberLazyListState()
    AnimatedContent(
        targetState = resultLayout,
        transitionSpec = {
            val toVertical = targetState == SearchSettings.SearchLayout.Vertical
            (slideInHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                if (toVertical) full / 4 else -full / 4
            } + fadeIn(spring(stiffness = Spring.StiffnessMedium))).togetherWith(
                slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                    if (toVertical) -full / 4 else full / 4
                } + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            )
        },
        label = "searchResultLayout",
    ) { layout ->
        if (layout == SearchSettings.SearchLayout.Vertical) {
            RailResults(
                results = results,
                running = running,
                selectedSource = selectedSource,
                onSelectSource = onSelectSource,
                topPad = topPad,
                railState = railState,
                listState = railResultState,
                onCardClick = onCardClick,
                onCardLongClick = onCardLongClick,
            )
        } else {
            SearchListResults(
                done = done,
                running = running,
                selectedSource = selectedSource,
                onSelectSource = onSelectSource,
                listState = listState,
                topPad = topPad,
                onCardClick = onCardClick,
                onCardLongClick = onCardLongClick,
            )
        }
    }
}

@Composable
private fun SearchListResults(
    done: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    listState: LazyListState,
    topPad: Dp,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val context = LocalContext.current
    val shown = if (selectedSource == null) done else done.filter { it.sourceKey == selectedSource }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPad - 4.dp, bottom = 12.dp),
    ) {
        if (running || done.size > 1) {
            item(key = "search_leading") {
                Column {
                    if (running) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (done.size > 1) {
                        LazyRow(
                            modifier = Modifier.padding(top = if (running) 0.dp else 12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "filter_all") {
                                FilterChip(
                                    selected = selectedSource == null,
                                    onClick = { onSelectSource(null) },
                                    label = { Text("全部") },
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                            items(done, key = { "filter_${it.sourceKey}" }) { result ->
                                FilterChip(
                                    selected = selectedSource == result.sourceKey,
                                    onClick = {
                                        onSelectSource(
                                            if (selectedSource == result.sourceKey) null else result.sourceKey,
                                        )
                                    },
                                    label = { Text(result.sourceName) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(shown, key = { _, r -> r.sourceKey }) { index, result ->
            Column(modifier = Modifier.padding(top = if (index == 0) 12.dp else 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = result.sourceName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .clickable {
                                PartitionListActivity.startForSearch(context, result.videos, result.sourceName)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "全部",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(result.videos) { _, video ->
                        VodCard(
                            video = video,
                            onClick = { onCardClick(video) },
                            onLongClick = { onCardLongClick(video) },
                            modifier = Modifier.width(110.dp),
                        )
                    }
                }
            }
        }
    }
}
