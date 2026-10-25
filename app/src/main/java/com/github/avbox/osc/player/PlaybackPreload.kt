package com.github.avbox.osc.player

import com.github.avbox.osc.sourcedata.SourceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.github.avbox.osc.player.core.PlayState

class PlaybackPreload(private val host: Host) {

    interface Host {
        fun view(): PlaybackViewBridge?

        fun sourceViewModel(): SourceViewModel?

        fun ensureFetch()

        fun onPreloadedResult(info: JSONObject?)
    }

    private var preloadCoordinator: PreloadCoordinator? = null
    private var collectJob: Job? = null
    private var readyJob: Job? = null

    fun init() {
        if (host.sourceViewModel() == null) host.ensureFetch()
        val vm = host.sourceViewModel()
        preloadCoordinator = PreloadCoordinator(vm) { showReadyTip() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (vm != null) {
            collectJob = scope.launch {
                vm.preloadResult.flow.collect { info -> preloadCoordinator?.handlePreloadResult(info) }
            }
        }
        readyJob = scope.launch {
            PreloadManagerHolder.completedUrl.collect { url -> if (url != null) showReadyTip() }
        }
    }

    private fun showReadyTip() {
        val view = host.view()
        if (view == null || !view.isPageAlive()) return
        view.runOnUi {
            val current = host.view()
            current?.showPreloadReadyTip()
        }
    }

    fun onPlayerState(playState: PlayState) {
        val coordinator = preloadCoordinator ?: return
        val view = host.view()
        if (view == null) return
        if (playState == PlayState.PLAYING || playState == PlayState.BUFFERED) {
            coordinator.scheduleEvaluate(view.buildPreloadSnapshot())
        } else if (playState == PlayState.BUFFERING) {
            coordinator.onMainPlayerBuffering()
        }
    }

    fun consumeResult(progressKey: String?): Boolean {
        val coordinator = preloadCoordinator ?: return false
        val preResult = coordinator.consumeResult(progressKey)
        if (preResult != null) {
            host.onPreloadedResult(preResult)
            return true
        }
        coordinator.dropPreloadData()
        return false
    }

    fun invalidate() {
        preloadCoordinator?.invalidate()
    }

    fun destroy() {
        collectJob?.cancel()
        collectJob = null
        readyJob?.cancel()
        readyJob = null
        preloadCoordinator?.destroy()
        preloadCoordinator = null
    }
}
