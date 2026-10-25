package com.github.tvbox.osc.api

import android.net.Uri
import android.os.Environment
import android.text.TextUtils

import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.io.LocalSourceTree
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.util.ApiLineSignal
import com.github.tvbox.osc.boot.BootGuard
import com.github.tvbox.osc.data.PlaybackPorts
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.PySourcePack

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Supplier

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ConfigLoader(private val owner: ApiConfig) {
    private val configLoadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val activeFetches = ConcurrentHashMap<String, okhttp3.Call>()

    suspend fun loadConfig(useCache: Boolean): String? = suspendCancellableCoroutine { cont ->
        configLoadExecutor.execute(Runnable {
            runLoadConfig(useCache, object : ApiConfig.LoadConfigCallback {
                override fun success() {
                    cont.resume(null)
                }

                override fun error(msg: String?) {
                    cont.resume(msg)
                }
            })
        })
    }

    fun cancelVodFetches() {
        cancelFetch(KIND_VOD)
        cancelFetch(KIND_REFRESH)
    }

    private fun cancelFetch(kind: String) {
        val call = activeFetches.remove(kind) ?: return
        LOG.debugI("echo-switch: cancel " + kind + " fetch")
        call.cancel()
    }

    private fun isStaleFetch(apiUrl: String): Boolean = apiUrl != KV.get(HawkConfig.API_URL, "")

    private inline fun <T> guarded(callback: ApiConfig.LoadConfigCallback, messageRes: Int, block: () -> T): T? {
        return try {
            block()
        } catch (th: Throwable) {
            LOG.e("ApiConfig", th)
            callback.error(ApiConfig.str(messageRes).ifEmpty { th.toString() })
            null
        }
    }

    private fun runLoadConfig(useCache: Boolean, callback: ApiConfig.LoadConfigCallback) {
        val apiUrl = guarded(callback, R.string.toast_config_parse_failed) { KV.get(HawkConfig.API_URL, "") } ?: return
        if (apiUrl.isEmpty()) {
            callback.error("-1")
            return
        }
        val cache = guarded(callback, R.string.toast_config_parse_failed) {
            File(requireNotNull(App.getInstance()) { "app instance not ready" }.getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl))
        } ?: return
        if (useCache && cache.exists() && isRemoteSource(apiUrl)) {
            try {
                val json = readConfigFile(cache)
                if (switchApiCollectionIfNeeded(apiUrl, json)) {
                    runLoadConfig(false, callback)
                    return
                }
                clearApiLinesIfUnmatched(apiUrl)
                owner.parseJson(apiUrl, json)
                callback.success()
                return
            } catch (th: Throwable) {
                LOG.e("ApiConfig", th)
            }
        }
        val resolved = guarded(callback, R.string.toast_config_parse_failed) {
            ConfigParser.configUrl(apiUrl, Supplier { ApiConfig.localFileBase() })
        } ?: return
        val configUrl = resolved.url
        val configKey = resolved.key

        fetchConfigAsync(apiUrl, configUrl, configKey, KIND_VOD, object : ConfigFetchCallback {
            override fun success(body: String) {
                if (isStaleFetch(apiUrl)) {
                    LOG.debugI("echo-switch: drop stale config fetch")
                    callback.error("stale")
                    return
                }
                try {
                    if (switchApiCollectionIfNeeded(apiUrl, body)) {
                        FileUtils.saveCache(cache, body)
                        runLoadConfig(false, callback)
                        return
                    }
                    clearApiLinesIfUnmatched(apiUrl)
                    owner.parseJson(apiUrl, body)
                    FileUtils.saveCache(cache, body)
                    callback.success()
                } catch (th: Throwable) {
                    LOG.e("ApiConfig", th)
                    callback.error(ApiConfig.str(R.string.toast_config_parse_failed))
                }
            }

            override fun error(error: String?) {
                if (isStaleFetch(apiUrl)) {
                    LOG.debugI("echo-switch: drop stale config fetch")
                    callback.error("stale")
                    return
                }
                if (isLocalSourceUnreadable(apiUrl)) {
                    callback.error(localSourceUnreadableMsg())
                    return
                }
                if (isLocalSourceMissing(apiUrl)) {
                    callback.error(localSourceMissingMsg())
                    return
                }
                if (cache.exists()) {
                    try {
                        val json = readConfigFile(cache)
                        if (switchApiCollectionIfNeeded(apiUrl, json)) {
                            runLoadConfig(false, callback)
                            return
                        }
                        clearApiLinesIfUnmatched(apiUrl)
                        owner.parseJson(apiUrl, json)
                        callback.success()
                        return
                    } catch (th: Throwable) {
                        LOG.e("ApiConfig", th)
                    }
                }
                callback.error(ApiConfig.str(R.string.toast_config_fetch_failed, error))
            }
        })
    }

    fun refreshIfChanged(onChanged: (String, String) -> Unit) {
        configLoadExecutor.execute(Runnable {
            try {
                val apiUrl = KV.get(HawkConfig.API_URL, "")
                if (!isRemoteSource(apiUrl)) {
                    LOG.debugI("echo-config-refresh: skip local")
                    return@Runnable
                }
                val app = App.getInstance() ?: return@Runnable
                val cache = File(app.getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl))
                val previous = if (cache.exists()) readConfigFile(cache) else ""
                val resolved = ConfigParser.configUrl(apiUrl, Supplier { ApiConfig.localFileBase() })
                fetchConfigAsync(apiUrl, resolved.url, resolved.key, KIND_REFRESH, object : ConfigFetchCallback {
                    override fun success(body: String) {
                        if (isStaleFetch(apiUrl)) {
                            LOG.debugI("echo-config-refresh: drop stale")
                            return
                        }
                        if (ConfigContentDiff.isSame(body, previous)) {
                            LOG.debugI("echo-config-refresh: unchanged")
                            return
                        }
                        LOG.debugI("echo-config-refresh: changed " + previous.length + "->" + body.length)
                        FileUtils.saveCache(cache, body)
                        onChanged(body, previous)
                    }

                    override fun error(error: String?) {
                        LOG.debugI("echo-config-refresh: failed " + error)
                    }
                })
            } catch (th: Throwable) {
                LOG.debugI("echo-config-refresh: error " + th.message)
            }
        })
    }

    fun refreshLiveIfChanged() {
        configLoadExecutor.execute(Runnable {
            try {
                val liveApiUrl = ApiConfig.getEffectiveLiveUrl()
                if (ApiConfig.isLiveFollowVod() || !isRemoteSource(liveApiUrl)) {
                    LOG.debugI("echo-live-refresh: skip follow-vod-or-local")
                    return@Runnable
                }
                val app = App.getInstance() ?: return@Runnable
                val cache = File(app.getFilesDir().getAbsolutePath() + "/" + MD5.encode(liveApiUrl))
                val previous = if (cache.exists()) readConfigFile(cache) else ""
                val resolved = ConfigParser.configUrl(liveApiUrl, Supplier { ApiConfig.localFileBase() })
                fetchConfigAsync(liveApiUrl, resolved.url, resolved.key, KIND_LIVE_REFRESH, object : ConfigFetchCallback {
                    override fun success(body: String) {
                        try {
                            if (liveApiUrl != KV.get(HawkConfig.LIVE_API_URL, "")) {
                                LOG.debugI("echo-live-refresh: drop stale")
                                return
                            }
                            if (switchLiveApiCollectionIfNeeded(liveApiUrl, body)) {
                                FileUtils.saveCache(cache, body)
                                return
                            }
                            if (ConfigContentDiff.isSame(body, previous)) {
                                LOG.debugI("echo-live-refresh: unchanged")
                                return
                            }
                            FileUtils.saveCache(cache, body)
                            if (PlaybackPorts.ports?.hasAttachedPage() == true) {
                                LOG.debugI("echo-live-refresh: deferred, page attached")
                                return
                            }
                            try {
                                owner.parseLiveConfigContent(liveApiUrl, body)
                            } catch (th: Throwable) {
                                LOG.e("ApiConfig", th)
                                LOG.debugI("echo-live-refresh: parse failed, restore old cache")
                                if (previous.isNotEmpty()) FileUtils.saveCache(cache, previous)
                                return
                            }
                            if (!owner.hasLiveConfigResult()) {
                                LOG.debugI("echo-live-refresh: parse empty, restore old cache")
                                if (previous.isNotEmpty()) FileUtils.saveCache(cache, previous)
                                return
                            }
                            LOG.debugI("echo-live-refresh: changed " + previous.length + "->" + body.length)
                            owner.loadedLiveConfigUrl = liveApiUrl
                        } catch (th: Throwable) {
                            LOG.e("ApiConfig", th)
                        }
                    }

                    override fun error(error: String?) {
                        LOG.debugI("echo-live-refresh: failed " + error)
                    }
                })
            } catch (th: Throwable) {
                LOG.debugI("echo-live-refresh: error " + th.message)
            }
        })
    }

    fun applyRefreshed(apiUrl: String, body: String) {
        clearApiLinesIfUnmatched(apiUrl)
        owner.parseJson(apiUrl, body)
    }

    fun restoreCache(apiUrl: String, body: String) {
        if (body.isEmpty()) return
        val app = App.getInstance() ?: return
        FileUtils.saveCache(File(app.getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl)), body)
    }

    suspend fun loadLiveConfig(useCache: Boolean): String? = suspendCancellableCoroutine { cont ->
        configLoadExecutor.execute(Runnable {
            runLoadLiveConfig(useCache, object : ApiConfig.LoadConfigCallback {
                override fun success() {
                    cont.resume(null)
                }

                override fun error(msg: String?) {
                    cont.resume(msg)
                }
            })
        })
    }

    private fun runLoadLiveConfig(useCache: Boolean, callback: ApiConfig.LoadConfigCallback) {
        val apiUrl = guarded(callback, R.string.toast_live_config_parse_failed) { ApiConfig.getEffectiveLiveUrl() } ?: return
        if (apiUrl.isEmpty()) {
            LOG.e("echo-live-config-fail url=empty")
            callback.error("-1")
            return
        }
        val liveApiUrl = apiUrl
        val resolvedLive = guarded(callback, R.string.toast_live_config_parse_failed) {
            ConfigParser.configUrl(liveApiUrl, Supplier { ApiConfig.localFileBase() })
        } ?: return
        val liveApiConfigUrl = resolvedLive.url
        val liveConfigKey = resolvedLive.key
        val live_cache = guarded(callback, R.string.toast_live_config_parse_failed) {
            File(requireNotNull(App.getInstance()) { "app instance not ready" }.getFilesDir().getAbsolutePath() + "/" + MD5.encode(liveApiUrl))
        } ?: return
        LOG.i("echo-load live config " + liveApiUrl)
        if (useCache && live_cache.exists() && isRemoteSource(liveApiUrl)) {
            try {
                val json = readConfigFile(live_cache)
                if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                    runLoadLiveConfig(false, callback)
                    return
                }
                clearLiveApiLinesIfUnmatched(liveApiUrl)
                owner.parseLiveConfigContent(liveApiUrl, json)
                if (owner.hasLiveConfigResult()) {
                    owner.loadedLiveConfigUrl = liveApiUrl
                    callback.success()
                    return
                }
            } catch (th: Throwable) {
                LOG.e("ApiConfig", th)
            }
        }
        fetchConfigAsync(liveApiUrl, liveApiConfigUrl, liveConfigKey, KIND_LIVE, object : ConfigFetchCallback {
            override fun success(body: String) {
                try {
                    if (switchLiveApiCollectionIfNeeded(liveApiUrl, body)) {
                        FileUtils.saveCache(live_cache, body)
                        runLoadLiveConfig(false, callback)
                        return
                    }
                    clearLiveApiLinesIfUnmatched(liveApiUrl)
                    owner.parseLiveConfigContent(liveApiUrl, body)
                    if (!owner.hasLiveConfigResult()) {
                        LOG.e("echo-live-config-empty url=$liveApiUrl")
                        callback.error(ApiConfig.str(R.string.toast_live_config_parse_failed))
                        return
                    }
                    owner.loadedLiveConfigUrl = liveApiUrl
                    FileUtils.saveCache(live_cache, body)
                    callback.success()
                } catch (th: Throwable) {
                    LOG.e("ApiConfig", th)
                    callback.error(ApiConfig.str(R.string.toast_live_config_parse_failed))
                }
            }

            override fun error(error: String?) {
                if (isLocalSourceUnreadable(liveApiUrl)) {
                    callback.error(localSourceUnreadableMsg())
                    return
                }
                if (isLocalSourceMissing(liveApiUrl)) {
                    callback.error(localSourceMissingMsg())
                    return
                }
                if (live_cache.exists()) {
                    try {
                        val json = readConfigFile(live_cache)
                        if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                            runLoadLiveConfig(false, callback)
                            return
                        }
                        clearLiveApiLinesIfUnmatched(liveApiUrl)
                        owner.parseLiveConfigContent(liveApiUrl, json)
                        if (owner.hasLiveConfigResult()) {
                            owner.loadedLiveConfigUrl = liveApiUrl
                            callback.success()
                            return
                        }
                    } catch (th: Throwable) {
                        LOG.e("ApiConfig", th)
                    }
                }
                LOG.e("echo-live-config-fail url=$liveApiUrl err=$error")
                callback.error(ApiConfig.str(R.string.toast_live_config_fetch_failed))
            }
        })
    }

    private fun localSourceUnreadableMsg(): String {
        return ApiConfig.str(R.string.toast_local_source_unreadable)
    }

    private fun localSourceMissingMsg(): String {
        return ApiConfig.str(R.string.toast_local_source_missing)
    }

    private fun isLocalSourceUnreadable(apiUrl: String): Boolean {
        val path = localSourcePath(apiUrl)
        if (path == null) return false
        val app = requireNotNull(App.getInstance()) { "app instance not ready" }
        if (LocalSourceTree.serves(app, path)) return false
        return !PermissionHelper.isStorageGranted(app) && !File(path).canRead()
    }

    private fun isLocalSourceMissing(apiUrl: String): Boolean {
        val path = localSourcePath(apiUrl)
        if (path == null) return false
        val app = requireNotNull(App.getInstance()) { "app instance not ready" }
        if (LocalSourceTree.serves(app, path)) return false
        return !File(path).exists()
    }

    private fun isRemoteSource(apiUrl: String?): Boolean {
        return apiUrl != null && (apiUrl.startsWith("http://") || apiUrl.startsWith("https://"))
    }

    private fun localSourcePath(apiUrl: String?): String? {
        if (apiUrl == null) return null
        var url = apiUrl
        val pk = url.indexOf(";pk;")
        if (pk >= 0) url = url.substring(0, pk)
        val query = url.indexOf('?')
        if (query >= 0) url = url.substring(0, query)
        if (url.startsWith("clan://localhost/")) {
            return Environment.getExternalStorageDirectory().getAbsolutePath() +
                    "/" + Uri.decode(url.substring("clan://localhost/".length))
        }
        if (url.startsWith("file://")) {
            return Uri.decode(url.substring("file://".length))
        }
        return null
    }

    private fun firstUsableApiLine(apiLines: ArrayList<String>): String {
        for (line in apiLines) {
            val url = HistoryHelper.getApiLineUrl(line)
            if (!TextUtils.isEmpty(url) && !BootGuard.isDisabledSource(url)) {
                return url
            }
        }
        return ""
    }

    private fun switchApiCollectionIfNeeded(apiUrl: String, jsonStr: String): Boolean {
        val apiLines = ConfigParser.parseApiCollection(jsonStr)
        if (apiLines.isEmpty()) {
            return false
        }
        val firstApi = firstUsableApiLine(apiLines)
        if (TextUtils.isEmpty(firstApi) || firstApi == apiUrl) {
            return false
        }
        KV.put(HawkConfig.API_LINE_LIST, apiLines)
        KV.put(HawkConfig.API_LINE_SOURCE, apiUrl)
        KV.put(HawkConfig.API_URL, firstApi)
        HistoryHelper.setApiHistory(apiUrl)
        owner.invalidateVodConfig()
        val liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "")
        if (TextUtils.isEmpty(liveApiUrl) || liveApiUrl == apiUrl) {
            KV.put(HawkConfig.LIVE_API_URL, firstApi)
            HistoryHelper.setLiveApiHistory(firstApi)
            HistoryHelper.clearLiveApiLineList()
        }
        ApiLineSignal.notifyChanged()
        return true
    }

    private fun switchLiveApiCollectionIfNeeded(apiUrl: String, jsonStr: String): Boolean {
        val apiLines = ConfigParser.parseApiCollection(jsonStr)
        if (apiLines.isEmpty()) {
            return false
        }
        val firstApi = firstUsableApiLine(apiLines)
        if (TextUtils.isEmpty(firstApi) || firstApi == apiUrl) {
            return false
        }
        KV.put(HawkConfig.LIVE_API_LINE_LIST, apiLines)
        KV.put(HawkConfig.LIVE_API_LINE_SOURCE, apiUrl)
        val followLive = ApiConfig.isLiveFollowVod()
        KV.put(HawkConfig.LIVE_API_URL, firstApi)
        if (followLive) {
            KV.put(HawkConfig.API_URL, firstApi)
            HistoryHelper.setApiHistory(firstApi)
        }
        HistoryHelper.setLiveApiHistory(apiUrl)
        owner.loadedLiveConfigUrl = ""
        owner.clearLiveConfigResult()
        owner.clearLiveHosts()
        ApiLineSignal.notifyChanged()
        return true
    }

    private fun clearLiveApiLinesIfUnmatched(apiUrl: String) {
        if (TextUtils.isEmpty(apiUrl)) return
        if (!HistoryHelper.isLiveApiLineUrl(apiUrl) && !HistoryHelper.isLiveApiLineSource(apiUrl)) {
            HistoryHelper.clearLiveApiLineList()
        }
    }

    private fun clearApiLinesIfUnmatched(apiUrl: String) {
        val apiLines: ArrayList<String> = KV.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
        if (apiLines.isEmpty()) {
            return
        }
        for (apiLine in apiLines) {
            if (apiUrl == HistoryHelper.getApiLineUrl(apiLine)) {
                return
            }
        }
        HistoryHelper.clearApiLineList()
    }

    private interface ConfigFetchCallback {
        fun success(body: String)

        fun error(error: String?)
    }

    private fun fetchConfigAsync(apiUrl: String, requestUrl: String, configKey: String?, kind: String, callback: ConfigFetchCallback) {
        configLoadExecutor.execute(Runnable {
            var result = ""
            var error: String? = ""
            var response: okhttp3.Response? = null
            var call: okhttp3.Call? = null
            try {
                val request = okhttp3.Request.Builder()
                        .url(requestUrl)
                        .build()
                var client: okhttp3.OkHttpClient? = OkGoHelper.getDefaultClient()
                if (client == null) client = com.github.catvod.net.OkHttp.client()
                val configCall = client.newCall(request)
                call = configCall
                activeFetches[kind] = configCall
                response = configCall.execute()
                if (!response.isSuccessful) {
                    error = "HTTP " + response.code
                } else if (response.body == null) {
                    error = "empty body"
                } else {
                    result = requireNotNull(ApiConfig.FindResult(response.body.string(), configKey)) { "find result failed" }
                    val packedPy = PySourcePack.packUrl(apiUrl, result)
                    if (packedPy != null) result = packedPy
                    if (apiUrl.startsWith("clan")) {
                        result = ConfigParser.clanContentFix(ConfigParser.clanToAddress(apiUrl, Supplier { ApiConfig.localFileBase() }), result)
                    }
                    result = ConfigParser.fixContentPath(apiUrl, result, Supplier { ApiConfig.localFileBase() })
                }
            } catch (th: Throwable) {
                error = th.message
                if (TextUtils.isEmpty(error)) error = th.toString()
            } finally {
                if (call != null && activeFetches[kind] === call) activeFetches.remove(kind)
                if (response != null) SpiderLoader.closeQuietly(response.body)
            }
            val finalResult = result
            val finalError = error
            if (TextUtils.isEmpty(finalError)) {
                callback.success(finalResult)
            } else {
                callback.error(finalError)
            }
        })
    }

    private fun readConfigFile(f: File): String {
        BufferedReader(InputStreamReader(FileInputStream(f), "UTF-8")).use { bReader ->
            val sb = StringBuilder()
            var s: String? = bReader.readLine()
            while (s != null) {
                sb.append(s + "\n")
                s = bReader.readLine()
            }
            return sb.toString()
        }
    }

    private companion object {
        private const val KIND_VOD = "vod"
        private const val KIND_REFRESH = "refresh"
        private const val KIND_LIVE = "live"
        private const val KIND_LIVE_REFRESH = "live_refresh"
    }
}
