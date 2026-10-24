package com.github.tvbox.osc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class VodCardMenuState internal constructor(
    private val scope: CoroutineScope,
    private val lookupCollected: suspend (Movie.Video) -> Boolean,
    private val onCollectToggle: (Movie.Video, Boolean) -> Unit,
) {

    internal var menu by mutableStateOf<Pair<Movie.Video, Boolean>?>(null)

    fun show(video: Movie.Video) {
        scope.launch {
            menu = video to lookupCollected(video)
        }
    }

    internal fun collect(video: Movie.Video, toCollect: Boolean) {
        onCollectToggle(video, toCollect)
    }
}

@Composable
fun rememberVodCardMenuState(
    lookupCollected: suspend (Movie.Video) -> Boolean,
    onCollectToggle: (Movie.Video, Boolean) -> Unit,
): VodCardMenuState {
    val scope = rememberCoroutineScope()
    return remember(scope) { VodCardMenuState(scope, lookupCollected, onCollectToggle) }
}

@Composable
fun VodCardMenu(state: VodCardMenuState, onSearchSimilar: (String) -> Unit) {
    state.menu?.let { (video, collected) ->
        val collectLabel = stringResource(R.string.detail_collect)
        val uncollectLabel = stringResource(R.string.detail_uncollect)
        val searchSimilarLabel = stringResource(R.string.vodcard_search_similar)
        AVBoxOptionSheet(
            onDismissRequest = { state.menu = null },
            title = video.name,
            options = if (collected) {
                listOf(uncollectLabel, searchSimilarLabel)
            } else {
                listOf(collectLabel, searchSimilarLabel)
            },
            selected = null,
            onSelect = { option ->
                when (option) {
                    collectLabel -> state.collect(video, true)
                    uncollectLabel -> state.collect(video, false)
                    searchSimilarLabel -> onSearchSimilar(video.name ?: "")
                }
            },
        )
    }
}

internal fun toVodInfo(video: Movie.Video): VodInfo {
    return VodInfo(id = video.id, name = video.name, pic = video.pic, sourceKey = video.sourceKey)
}
