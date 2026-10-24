@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.net.SearchSettings
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.SearchField
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.page.jumpToSearch
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import kotlinx.coroutines.delay

private const val SuggestDebounceMs = 120L

@Composable
private fun rememberSearchViewModel(): SearchViewModel {
    val activity = LocalContext.current as? android.app.Activity
    val initialTitle = activity?.intent?.getStringExtra("title").orEmpty()
    return viewModel(initializer = { SearchViewModel(initialTitle) })
}

@Composable
fun SearchScreen(vm: SearchViewModel = rememberSearchViewModel()) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val focusManager = LocalFocusManager.current
    val results by vm.results.collectAsState()
    val running by vm.running.collectAsState()
    val hotSearch by vm.hotSearch.collectAsState()
    val suggest by vm.suggest.collectAsState()
    var query by remember {
        mutableStateOf(activity?.intent?.getStringExtra("title").orEmpty())
    }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    val history by vm.searchHistory.collectAsState()
    val searchedTitle by vm.searchedTitle.collectAsState()
    val exactMatch by vm.exactMatch.collectAsState()
    val sitesEmpty by vm.sitesEmpty.collectAsState()
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )
    var resultLayout by remember { mutableStateOf(SearchSettings.resultLayout()) }

    LaunchedEffect(query) {
        val t = query.trim()
        if (t.isEmpty()) {
            vm.clearSuggest()
        } else {
            if (t.length > 1) delay(SuggestDebounceMs)
            vm.fetchSuggest(t)
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
    }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { submit(query) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                trailing = {
                    LayoutSwitchAction(
                        selected = resultLayout,
                        onSelect = {
                            resultLayout = it
                            SearchSettings.setResultLayout(it)
                        },
                    )
                },
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
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { topPad, _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            if (results.isEmpty() && !running && sitesEmpty) {
                SearchEmptyBox(topPad = topPad, text = stringResource(R.string.search_no_site))
            } else if (results.isEmpty() && !running) {
                SearchIdleContent(
                    history = history,
                    hotSearch = hotSearch,
                    suggest = suggest,
                    query = query,
                    topPad = topPad,
                    onSearch = { submit(it) },
                    onClearHistory = { vm.clearSearchHistory() },
                    onRemoveHistory = { word -> vm.removeSearchHistory(word) },
                )
            } else {
                SearchResultsContent(
                    results = results,
                    running = running,
                    selectedSource = selectedSource,
                    onSelectSource = {
                        focusManager.clearFocus()
                        selectedSource = it
                    },
                    resultLayout = resultLayout,
                    listState = resultListState,
                    searchedTitle = searchedTitle,
                    exactMatch = exactMatch,
                    topPad = topPad,
                    onCardClick = {
                        focusManager.clearFocus()
                        context.openVodCardOrDetail(it)
                    },
                    onCardLongClick = { vodMenu.show(it) },
                )
            }
        }
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
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
    val hits = SearchHits.sources(results)
    if (hits.isEmpty() && !running) {
        SearchEmptyBox(
            topPad = topPad,
            text = if (exactMatch) {
                stringResource(R.string.search_no_exact_result, searchedTitle)
            } else {
                stringResource(R.string.search_no_result, searchedTitle)
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
                hits = hits,
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
                done = hits,
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
