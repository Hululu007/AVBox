package com.github.tvbox.osc.ui.page

import android.widget.Toast
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.MD5
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.greenrobot.eventbus.EventBus
import java.io.File
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
        startInit(forceFresh = false)
    }

    fun retry() {
        dataInitOk = false
        jarInitOk = false
        _state.value = Boot.Loading
        startInit(forceFresh = true)
    }

    fun continueOffline() {
        dataInitOk = true
        jarInitOk = true
        _state.value = Boot.Loading
        startInit(forceFresh = false)
    }

    fun onApiUrlChanged() {
        ApiConfig.get().invalidateVodConfig()
        SearchViewModel.clearCheckedSources()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE))
        retry()
    }

    /** forceFresh = 用户主动重载(换源/改地址/失败重试):必须走网络,否则"重选同一个源"会拿旧快照,看起来像没生效 */
    private fun startInit(forceFresh: Boolean) {
        scope.launch {
            if (!dataInitOk) {
                val err = awaitLoadConfig(forceFresh)
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

    /** 快照有效期:过期即走网络刷新并把新快照写回,避免"一次缓存永久冻结源更新" */
    private const val CONFIG_CACHE_TTL_MS = 12 * 60 * 60 * 1000L

    /** 只有远程源吃快照:本地/局域网配置的改动必须立即生效,不能被快照挡住 */
    private fun useCachedConfig(): Boolean {
        val apiUrl = KV.get(HawkConfig.API_URL, "")
        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) return false
        val app = App.getInstance() ?: return false
        val cache = File(app.filesDir, MD5.encode(apiUrl))
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
        val context = com.github.tvbox.osc.base.App.getInstance()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
