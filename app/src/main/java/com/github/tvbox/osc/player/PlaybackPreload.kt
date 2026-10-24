package com.github.tvbox.osc.player

import com.github.tvbox.osc.sourcedata.SourceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.github.tvbox.osc.player.core.PlayState

class PlaybackPreload(private val host: Host) {

    interface Host {
        fun view(): PlaybackViewBridge?

        fun sourceViewModel(): SourceViewModel?

        fun ensureFetch()

        fun onPreloadedResult(info: JSONObject?)
    }

    private var preloadCoordinator: PreloadCoordinator? = null
    private var preloadReadyListener: PreloadManagerHolder.ReadyListener? = null
    private var collectJob: Job? = null

    fun init() {
        if (host.sourceViewModel() == null) host.ensureFetch()
        val vm = host.sourceViewModel()
        preloadCoordinator = PreloadCoordinator(vm)
        if (vm != null) {
            collectJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                vm.preloadResult.flow.collect { info -> preloadCoordinator?.handlePreloadResult(info) }
            }
        }
        val listener = PreloadManagerHolder.ReadyListener { _ ->
            val view = host.view()
            if (view == null || !view.isPageAlive()) return@ReadyListener
            view.runOnUi {
                val current = host.view()
                current?.showPreloadReadyTip()
            }
        }
        preloadReadyListener = listener
        PreloadManagerHolder.setReadyListener(listener)
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
        PreloadManagerHolder.clearReadyListener(preloadReadyListener)
        preloadReadyListener = null
        collectJob?.cancel()
        collectJob = null
        preloadCoordinator?.destroy()
        preloadCoordinator = null
    }
}
