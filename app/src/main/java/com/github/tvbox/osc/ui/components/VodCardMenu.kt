package com.github.tvbox.osc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.page.jumpToSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

@Stable
class VodCardMenuState internal constructor(private val scope: CoroutineScope) {

    internal var menu by mutableStateOf<Pair<Movie.Video, Boolean>?>(null)

    fun show(video: Movie.Video) {
        scope.launch {
            val collected = withContext(Dispatchers.IO) {
                RoomDataManger.isVodCollect(video.sourceKey, video.id)
            }
            menu = video to collected
        }
    }
}

@Composable
fun rememberVodCardMenuState(): VodCardMenuState {
    val scope = rememberCoroutineScope()
    return remember(scope) { VodCardMenuState(scope) }
}

@Composable
fun VodCardMenu(state: VodCardMenuState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    state.menu?.let { (video, collected) ->
        // 文案既是显示值也是分发键:统一取资源,否则会出现"显示英文、分支键仍是中文"而整条菜单失效
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
                    collectLabel -> scope.launch(Dispatchers.IO) {
                        RoomDataManger.insertVodCollect(video.sourceKey, toVodInfo(video))
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
                    }
                    uncollectLabel -> scope.launch(Dispatchers.IO) {
                        RoomDataManger.deleteVodCollect(video.sourceKey, toVodInfo(video))
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
                    }
                    searchSimilarLabel -> context.jumpToSearch(video.name ?: "")
                }
            },
        )
    }
}

internal fun toVodInfo(video: Movie.Video): VodInfo {
    val info = VodInfo()
    info.id = video.id
    info.name = video.name
    info.pic = video.pic
    info.sourceKey = video.sourceKey
    return info
}