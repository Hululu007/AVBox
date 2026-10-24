package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.ui.components.toVodInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class PartitionListVM : ViewModel() {
    sealed interface State {
        data object Loading : State
        data object Empty : State
        data object Ready : State
    }

    data class UiState(
        val state: State = State.Loading,
        val videos: List<Movie.Video> = emptyList(),
        val nextPage: Int = FIRST_PAGE,
        val maxPage: Int = 0,
    ) {
        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    companion object {
        const val FIRST_PAGE = 1
    }

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    private val _title = MutableStateFlow("")
    val title = _title.asStateFlow()
    private val _level = MutableStateFlow(0)
    val level = _level.asStateFlow()

    var sort: MovieSort.SortData? = null
        private set

    private var sourceKey: String? = null
    private val scope = viewModelScope
    private val nav = PartitionNavStack()

    private val _actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionMessages: SharedFlow<String> = _actionMessages.asSharedFlow()

    private val actionViewModel = SourceViewModel()

    init {
        scope.launch {
            actionViewModel.actionResult.flow.collect { json ->
                val msg = json?.optString("msg").orEmpty()
                if (msg.isNotEmpty()) _actionMessages.tryEmit(msg)
                refresh()
            }
        }
    }

    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private var loader = Loader()

    private inner class Loader {
        private val svm = SourceViewModel()

        private val loaderScope = CoroutineScope(
            SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate
        )

        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        @Volatile
        var busy: Boolean = false
            private set

        init {
            loaderScope.launch {
                svm.listResult.flow.collect { abs ->
                    val current = pending
                    pending = null
                    busy = false
                    current?.invoke(LoaderResult(false, abs))
                }
            }
        }

        fun release() {
            pending?.invoke(LoaderResult(true, null))
            pending = null
            busy = false
            loaderScope.cancel()
        }

        fun request(page: Int, data: MovieSort.SortData, sourceKey: String?, onDone: (LoaderResult) -> Unit) {
            pending?.invoke(LoaderResult(true, null))
            pending = onDone
            busy = true
            svm.getList(sourceKey, data, page)
        }
    }

    override fun onCleared() {
        loader.release()
    }

    fun runAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

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

    fun refresh() {
        if (nav.isEmpty()) return
        reload()
    }

    fun open(sort: MovieSort.SortData, sourceKey: String?, title: String): Boolean {
        if (!nav.push(PartitionNavStack.Target(sort, sourceKey, title))) return false
        reload()
        return true
    }

    fun back(): Boolean {
        if (nav.pop() == null) return false
        reload()
        return true
    }

    fun isRoot(): Boolean = nav.isRoot()

    fun setStaticTitle(title: String) {
        if (nav.isEmpty()) _title.value = title
    }

    private fun reload() {
        val target = nav.current() ?: return
        sort = target.sort
        sourceKey = target.sourceKey ?: ApiConfig.get().getHomeSourceBean().key
        _title.value = target.title
        _level.value = _level.value + 1
        _ui.value = UiState(State.Loading)
        loader.release()
        loader = Loader()
        request(FIRST_PAGE)
    }

    fun applyFilter(selection: Map<String, String>) {
        if (nav.isEmpty()) return
        sort?.filterSelect = HashMap(selection)
        reload()
    }

    fun loadMore() {
        if (nav.isEmpty()) return
        val s = _ui.value
        if (s.state != State.Ready || !s.hasMore || loader.busy) return
        request(s.nextPage)
    }

    private fun request(page: Int) {
        val data = sort ?: return
        scope.launch {
            val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                loader.request(page, data, sourceKey) { r -> if (cont.isActive) cont.resume(r) }
            }
            if (!result.stale) applyResult(page, result.absXml)
        }
    }

    private fun applyResult(page: Int, abs: AbsXml?) {
        val videos = abs?.movie?.videoList ?: emptyList()
        val maxPage = abs?.movie?.pagecount ?: 0
        val cur = _ui.value
        _ui.value = when {
            videos.isEmpty() && page == FIRST_PAGE -> UiState(State.Empty, emptyList(), FIRST_PAGE, maxPage)
            else -> UiState(State.Ready, if (page == 0) videos else cur.videos + videos, page + 1, maxPage)
        }
    }
}
