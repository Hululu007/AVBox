package com.github.tvbox.osc.api

import android.widget.Toast
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ConfigRepository
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.data.CurrentSubscription
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig

import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.Probe
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AppBootstrap {

    sealed interface Boot {
        data object Loading : Boot
        data class Ready(val epoch: Long) : Boot
        data class Error(val msg: String) : Boot
    }

    private val _state = MutableStateFlow<Boot>(Boot.Loading)
    val state: StateFlow<Boot> = _state

    private val subscriptionCidFlow: MutableStateFlow<String> by lazy {
        MutableStateFlow(CurrentSubscription.cid())
    }

    val subscriptionCid: StateFlow<String> get() = subscriptionCidFlow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootGeneration = BootGeneration()
    private val initMutex = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        val generation = bootGeneration.next()
        scope.launch {
            Probe.trace("boot:bootstrapRepair") { FileUtils.repairBogusNativeLibs() }
            Probe.trace("boot:bootstrapBootGuard") { BootGuard.disableBootLoopingSource() }
            Probe.trace("boot:bootstrapServer") { ControlManager.get().startServer() }
            startInit(forceFresh = false, offline = false, generation = generation)
        }
    }

    fun retry() {
        ApiConfig.get().cancelPendingLoads()
        _state.value = Boot.Loading
        startInit(forceFresh = true, offline = false)
    }

    fun continueOffline() {
        _state.value = Boot.Loading
        startInit(forceFresh = false, offline = true)
    }

    fun onApiUrlChanged() {
        val generation = bootGeneration.next()
        ApiConfig.get().cancelPendingLoads()
        _state.value = Boot.Loading
        LOG.debugI("echo-switch: request")
        scope.launch {
            val startedAt = System.currentTimeMillis()
            ApiConfig.get().invalidateVodConfig()
            LOG.debugI("echo-switch: invalidate dt=" + (System.currentTimeMillis() - startedAt) + "ms")
            SearchSourcesState.refresh()
            subscriptionCidFlow.value = CurrentSubscription.cid()
            startInit(forceFresh = true, offline = false, generation = generation)
        }
    }

    fun switchVodSubscription(url: String): Boolean {
        val followLive = ApiConfig.isLiveFollowVod()
        if (ConfigRepository.switchVodSource(url)) {
            ApiConfig.get().invalidateLiveConfig()
            return followLive
        }
        onApiUrlChanged()
        return followLive
    }

    private fun startInit(forceFresh: Boolean, offline: Boolean, generation: Long = bootGeneration.next()) {
        scope.launch {
            initMutex.withLock {
                if (!offline && !bootGeneration.isLatest(generation)) {
                    return@withLock
                }
                var dataInitOk = offline
                var jarInitOk = offline
                if (!dataInitOk) {
                    val err = awaitLoadConfig(forceFresh)
                    if (err != null) {
                        if (err == "-1") {
                            dataInitOk = true
                            jarInitOk = true
                        } else {
                            if (bootGeneration.isLatest(generation)) {
                                _state.value = Boot.Error(err)
                            }
                            return@withLock
                        }
                    } else {
                        if (!bootGeneration.isLatest(generation)) return@withLock
                        dataInitOk = true
                        if (ApiConfig.get().getSpider().isNullOrEmpty()) jarInitOk = true
                    }
                }
                if (dataInitOk && !jarInitOk) {
                    val err = awaitLoadJar()
                    jarInitOk = true
                    if (!err.isNullOrEmpty() && bootGeneration.isLatest(generation)) toast(err)
                }
                if (dataInitOk && jarInitOk && bootGeneration.isLatest(generation)) {
                    ApiConfig.get().warmSearchSpiders()
                    SearchSourcesState.refresh()
                    _state.value = Boot.Ready(generation)
                    if (!offline && !forceFresh) scheduleConfigRefresh(generation)
                }
            }
        }
    }

    private const val CONFIG_CACHE_TTL_MS = 12 * 60 * 60 * 1000L

    private const val CONFIG_REFRESH_DELAY_MS = 4_000L

    private fun scheduleConfigRefresh(generation: Long) {
        scope.launch {
            delay(CONFIG_REFRESH_DELAY_MS)
            if (!bootGeneration.isLatest(generation)) return@launch
            ApiConfig.get().refreshConfigIfChanged { newBody, oldBody ->
                applyRefreshedConfig(newBody, oldBody, generation)
            }
        }
    }

    private fun applyRefreshedConfig(newBody: String, oldBody: String, generation: Long) {
        scope.launch {
            initMutex.withLock {
                if (!bootGeneration.isLatest(generation)) return@withLock
                if (PlaybackService.peek()?.attachedPage() != null) {
                    LOG.debugI("echo-config-refresh: deferred")
                    return@withLock
                }
                if (oldBody.isEmpty()) {
                    LOG.debugI("echo-config-refresh: no baseline")
                    return@withLock
                }
                val apiUrl = KV.get(HawkConfig.API_URL, "")
                try {
                    ApiConfig.get().applyRefreshedConfig(apiUrl, newBody)
                } catch (th: Throwable) {
                    LOG.e("AppBootstrap", th)
                    rollbackConfig(apiUrl, oldBody)
                    return@withLock
                }
                if (!ApiConfig.get().getSpider().isNullOrEmpty()) {
                    val jarErr = try {
                        awaitLoadJar()
                    } catch (th: Throwable) {
                        LOG.e("AppBootstrap", th)
                        "jar load failed"
                    }
                    if (jarErr != null) {
                        rollbackConfig(apiUrl, oldBody)
                        return@withLock
                    }
                }
                ApiConfig.get().warmSearchSpiders()
                SearchSourcesState.refresh()
                LOG.debugI("echo-config-refresh: applied")
                _state.value = Boot.Ready(bootGeneration.next())
            }
        }
    }

    private fun rollbackConfig(apiUrl: String, oldBody: String) {
        if (oldBody.isEmpty()) return
        try {
            ApiConfig.get().restoreConfigCache(apiUrl, oldBody)
            ApiConfig.get().applyRefreshedConfig(apiUrl, oldBody)
            LOG.debugI("echo-config-refresh: rollback")
        } catch (th: Throwable) {
            LOG.e("AppBootstrap", th)
        }
    }

    private fun useCachedConfig(): Boolean {
        val apiUrl = KV.get(HawkConfig.API_URL, "")
        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) return false
        val app = App.getInstance() ?: return false
        val cache = File(app.filesDir, requireNotNull(MD5.encode(apiUrl)) { "md5 of api url failed" })
        return cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CONFIG_CACHE_TTL_MS
    }

    private suspend fun awaitLoadConfig(forceFresh: Boolean): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadConfig(!forceFresh && useCachedConfig(), object : ApiConfig.LoadConfigCallback {
            override fun success() {
                if (cont.isActive) cont.resume(null)
            }

            override fun error(msg: String?) {
                if (cont.isActive) cont.resume(msg ?: "-1")
            }

            override fun notice(msg: String?) {
                toast(msg)
            }
        }, null)
    }

    private suspend fun awaitLoadJar(): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadJar(false, ApiConfig.get().getSpider(), object : ApiConfig.LoadConfigCallback {
            override fun success() {
                if (cont.isActive) cont.resume(null)
            }

            override fun error(msg: String?) {
                if (cont.isActive) cont.resume(msg ?: "")
            }

            override fun notice(msg: String?) {
                toast(msg)
            }
        })
    }

    private fun toast(msg: String?) {
        if (msg.isNullOrEmpty()) return
        val context = com.github.tvbox.osc.base.App.getInstance() ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
