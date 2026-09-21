package com.github.tvbox.osc.ui.page

import android.widget.Toast
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HawkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.greenrobot.eventbus.EventBus
import kotlin.coroutines.resume

object AppBootstrap {

    sealed interface Boot {
        data object Loading : Boot
        data object Ready : Boot
        data class Error(val msg: String) : Boot
    }

    private val _state = MutableStateFlow<Boot>(Boot.Loading)
    val state: StateFlow<Boot> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var dataInitOk = false
    private var jarInitOk = false

    fun start() {
        if (started) return
        started = true
        // 必须在装载任何爬虫 jar 之前:①清掉私有目录里"假的原生库";②若同一源反复把应用崩掉,
        // 停用它的启动指针,否则用户连换源都进不去(详见 FileUtils/BootGuard 注释)。
        // 同步执行(几毫秒):异步会让首轮 jar 装载先跑起来,两道自检都白做。
        FileUtils.repairBogusNativeLibs()
        BootGuard.disableBootLoopingSource()
        ControlManager.get().startServer()
        startInit()
    }

    fun retry() {
        dataInitOk = false
        jarInitOk = false
        _state.value = Boot.Loading
        startInit()
    }

    fun continueOffline() {
        dataInitOk = true
        jarInitOk = true
        _state.value = Boot.Loading
        startInit()
    }

    fun onApiUrlChanged() {
        ApiConfig.get().invalidateVodConfig()
        SearchViewModel.clearCheckedSources()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE))
        retry()
    }

    private fun startInit() {
        scope.launch {
            if (!dataInitOk) {
                val err = awaitLoadConfig()
                if (err != null) {
                    if (err == "-1") {
                        dataInitOk = true
                        jarInitOk = true
                    } else {
                        _state.value = Boot.Error(err)
                        return@launch
                    }
                } else {
                    dataInitOk = true
                    if (ApiConfig.get().getSpider().isEmpty()) jarInitOk = true
                }
            }
            if (dataInitOk && !jarInitOk) {
                val err = awaitLoadJar()
                jarInitOk = true
                if (err != null) toast(err + " jar load err")
            }
            if (dataInitOk && jarInitOk) {
                ApiConfig.get().warmSearchSpiders()
                _state.value = Boot.Ready
            }
        }
    }

    private suspend fun awaitLoadConfig(): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadConfig(false, object : ApiConfig.LoadConfigCallback {
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
        val context = com.github.tvbox.osc.base.App.getInstance()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
