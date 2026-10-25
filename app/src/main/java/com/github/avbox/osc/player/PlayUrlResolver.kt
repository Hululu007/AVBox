package com.github.avbox.osc.player

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.base.App
import com.github.avbox.osc.bean.ParseBean
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.AdBlocker
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.HeaderGuard
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.VideoParseRuler
import com.github.avbox.osc.util.net.Http
import com.github.avbox.osc.net.SuperParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PlayUrlResolver(private val host: Host) {

    interface Host {

        fun view(): PlaybackViewBridge?

        fun sourceBean(): SourceBean?

        fun webHeaderMap(): HashMap<String, String>?

        fun setWebHeaderMap(headers: HashMap<String, String>?)

        fun webUserAgent(): String?

        fun setWebUserAgent(userAgent: String?)

        fun playUrl(url: String, headers: HashMap<String, String>?)

        fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?)

        fun cancelPlayRequest()

        fun cancelM3u8Purify()
    }

    private val parseHandler: Handler = Handler(
        Looper.getMainLooper(),
        Handler.Callback { msg: Message ->
            if (msg.what == MSG_PARSE_TIMEOUT) {
                stopParse()
                host.view()?.showErrorWithRetry(str(R.string.player_error_sniff), false)
                return@Callback true
            }
            false
        }
    )

    fun nextGen(): Int = parseGeneration.incrementAndGet()

    fun resetGen() {
        parseGeneration.set(0)
    }

    fun currentGen(): Int = parseGeneration.get()

    fun cancelParseTimeout() {
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
    }

    fun hasFoundUrls(): Boolean = !loadFoundVideoUrls.isEmpty()

    fun consumeFoundUrl() {
        autoRetryFromLoadFoundVideoUrls()
    }

    private var webUrl: String? = null
    private var parseFlag: String? = null

    private val parseGeneration = AtomicInteger(0)

    private val requestScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    @Volatile
    private var jsonJob: Job? = null

    private var sysWebView: WebView? = null

    @Volatile
    private var webSniffGeneration: Int = -1

    private val loadedUrls: MutableMap<String, Boolean> = ConcurrentHashMap()

    @Volatile
    private var loadFoundVideoUrls: Queue<String> = ConcurrentLinkedQueue()

    @Volatile
    private var loadFoundVideoUrlsHeader: MutableMap<String, HashMap<String, String>> = ConcurrentHashMap()
    private val loadFoundCount = AtomicInteger(0)
    private var parseThreadPool: ExecutorService? = null

    fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String) {
        parseFlag = flag
        webUrl = url
        var parseBean: ParseBean? = null
        host.view()?.showParse(useParse)
        if (useParse) {
            parseBean = ApiConfig.get().defaultParseBean
        } else {
            if (playUrl.startsWith("json:")) {
                val bean = ParseBean()
                bean.type = 1
                bean.url = playUrl.substring(5)
                parseBean = bean
            } else if (playUrl.startsWith("parse:")) {
                val parseRedirect = playUrl.substring(6)
                for (pb in ApiConfig.get().parseBeanList) {
                    if (pb.name == parseRedirect) {
                        parseBean = pb
                        break
                    }
                }
            }
            if (parseBean == null) {
                val bean = ParseBean()
                bean.type = 0
                bean.url = playUrl
                parseBean = bean
            }
        }
        doParse(requireNotNull(parseBean) { "parse bean not resolved" })
    }

    @Throws(JSONException::class)
    fun jsonParse(input: String?, json: String): JSONObject? {
        val jsonPlayData = JSONObject(json)
        val playData = jsonPlayData.optJSONObject("data") ?: jsonPlayData
        var url = playData.optString("url", jsonPlayData.optString("url", ""))
        if (url.startsWith("//")) {
            url = "http:$url"
        }
        var parse = false
        if (url.startsWith("video://")) {
            url = url.substring(8)
            parse = true
        }
        url = DefaultConfig.checkReplaceProxy(url)
        if (!url.startsWith("http") && !url.startsWith("data:application")) {
            return null
        }
        parse = parse || playData.optInt("parse", jsonPlayData.optInt("parse", 0)) == 1
        val headers = JSONObject()
        val headerMap = PlaybackController.extractHeaders(jsonPlayData)
        val dataHeaderMap = PlaybackController.extractHeaders(playData)
        if (headerMap != null) PlaybackController.putHeaders(headers, headerMap)
        if (dataHeaderMap != null) PlaybackController.putHeaders(headers, dataHeaderMap)
        val ua = playData.optString("user-agent", jsonPlayData.optString("user-agent", ""))
        if (ua.trim { it <= ' ' }.isNotEmpty()) {
            headers.put("User-Agent", " $ua")
        }
        val referer = playData.optString("referer", jsonPlayData.optString("referer", ""))
        if (referer.trim { it <= ' ' }.isNotEmpty()) {
            headers.put("Referer", " $referer")
        }
        val taskResult = JSONObject()
        taskResult.put("header", headers)
        taskResult.put("url", url)
        taskResult.put("parse", if (parse) 1 else 0)
        return taskResult
    }

    fun stopParse() {
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
        stopLoadWebView(false)
        host.cancelPlayRequest()
        jsonJob?.cancel()
        host.cancelM3u8Purify()
        val pool = parseThreadPool
        if (pool != null) {
            try {
                pool.shutdown()
                parseThreadPool = null
            } catch (th: Throwable) {
                AppLog.e("PlayUrlResolver", th)
            }
        }
    }

    fun initParseLoadFound() {
        loadFoundCount.set(0)
        loadFoundVideoUrls = ConcurrentLinkedQueue()
        loadFoundVideoUrlsHeader = ConcurrentHashMap()
    }

    fun autoRetryFromLoadFoundVideoUrls() {
        val videoUrl = loadFoundVideoUrls.poll() ?: return
        val header = loadFoundVideoUrlsHeader[videoUrl]
        if (host.view() != null) host.playUrl(videoUrl, header)
    }

    fun isParseResultCurrent(gen: Int): Boolean = gen == parseGeneration.get()

    fun doParse(pb: ParseBean) {
        val gen = parseGeneration.incrementAndGet()
        stopParse()
        initParseLoadFound()
        if (pb.type == 4) {
            parseMix(pb, true, gen)
        } else if (pb.type == 0) {
            host.view()?.showTip(str(R.string.player_sniffing_url), true, false)
            parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
            parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS)
            if (pb.ext != null) {
                try {
                    val reqHeaders = HashMap<String, String>()
                    val jsonObject = JSONObject(pb.ext)
                    val headerMap = PlaybackController.extractHeaders(jsonObject)
                    if (headerMap != null) {
                        for (key in headerMap.keys) {
                            val value = headerMap[key] ?: continue
                            if (!HeaderGuard.isSendable(key, value)) {
                                AppLog.i("echo-ext-header-skip:$key")
                                continue
                            }
                            if (key.equals("user-agent", ignoreCase = true)) {
                                host.setWebUserAgent(value.trim { it <= ' ' })
                            } else {
                                reqHeaders[key] = value
                            }
                        }
                        if (reqHeaders.isNotEmpty()) host.setWebHeaderMap(reqHeaders)
                    }
                } catch (e: Throwable) {
                    AppLog.e("PlayUrlResolver", e)
                }
            }
            loadWebView(pb.url + webUrl)
        } else if (pb.type == 1) {
            host.view()?.showTip(str(R.string.player_resolving_url), true, false)
            val reqHeaders = LinkedHashMap<String, String>()
            try {
                val jsonObject = JSONObject(pb.ext)
                val headerMap = PlaybackController.extractHeaders(jsonObject)
                if (headerMap != null) {
                    for (key in headerMap.keys) {
                        val value = headerMap[key] ?: continue
                        if (!HeaderGuard.isSendable(key, value)) {
                            AppLog.i("echo-ext-header-skip:$key")
                            continue
                        }
                        reqHeaders[key] = value
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("PlayUrlResolver", e)
            }
            val view = host.view()
            val web = webUrl
            val requestUrl = pb.url + (if (view == null) web else view.encodeUrl(requireNotNull(web)))
            jsonJob = requestScope.launch {
                val json = try {
                    Http.get(requestUrl) {
                        for ((key, value) in reqHeaders) headers(key, value)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    errorWithRetry(str(R.string.player_parse_error), false)
                    return@launch
                }
                if (!isParseResultCurrent(gen)) return@launch
                try {
                    val rs = requireNotNull(withContext(Dispatchers.IO) { jsonParse(webUrl, json) })
                    val headers = PlaybackController.extractHeaders(rs)
                    if (rs.optInt("parse", 0) == 1) {
                        host.setWebHeaderMap(headers)
                        if (headers != null) {
                            host.setWebUserAgent(PlaybackController.headerValue(headers, "user-agent"))
                            host.webUserAgent()?.let { host.setWebUserAgent(it.trim { it <= ' ' }) }
                        }
                        loadWebView(DefaultConfig.checkReplaceProxy(rs.getString("url")))
                    } else {
                        if (host.view() != null) host.playUrl(gen, rs.getString("url"), headers)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.e("PlayUrlResolver", e)
                    errorWithRetry(str(R.string.player_parse_error), false)
                }
            }
        } else if (pb.type == 2) {
            host.view()?.showTip(str(R.string.player_resolving_url), true, false)
            val pool = Executors.newSingleThreadExecutor()
            parseThreadPool = pool
            val jxs = LinkedHashMap<String, String>()
            for (p in ApiConfig.get().parseBeanList) {
                if (p.type == 1) {
                    val name = p.name
                    val mixUrl = p.mixUrl()
                    if (name != null && mixUrl != null) jxs[name] = mixUrl
                }
            }
            pool.execute {
                if (!isParseResultCurrent(gen)) return@execute
                val rs = ApiConfig.get().jsonExt(requireNotNull(pb.url), jxs, requireNotNull(webUrl))
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                    val bridge = host.view()
                    if (isParseResultCurrent(gen) && bridge != null) {
                        bridge.runOnUi { bridge.showTip(str(R.string.player_parse_error), false, true) }
                    }
                } else {
                    val headers = PlaybackController.extractHeaders(rs)
                    val bridge = host.view()
                    if (rs.has("jxFrom") && bridge != null) {
                        val jxFrom = rs.optString("jxFrom")
                        bridge.runOnUi { bridge.toast(str(R.string.player_parse_from, jxFrom)) }
                    }
                    val parseWV = rs.optInt("parse", 0) == 1
                    if (parseWV) {
                        val wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        loadUrl(gen, wvUrl)
                    } else {
                        if (host.view() != null) host.playUrl(gen, rs.optString("url", ""), headers)
                    }
                }
            }
        } else if (pb.type == 3) {
            parseMix(pb, false, gen)
        }
    }

    private fun errorWithRetry(err: String, finish: Boolean) {
        host.view()?.showErrorWithRetry(err, finish)
    }

    private fun parseMix(pb: ParseBean, isSuper: Boolean, gen: Int) {
        host.view()?.showTip(str(R.string.player_resolving_url), true, false)
        val pool = Executors.newSingleThreadExecutor()
        parseThreadPool = pool
        val jxs = LinkedHashMap<String, HashMap<String, String>>()
        val jsonJxs = LinkedHashMap<String, String>()
        var extendName = ""
        for (p in ApiConfig.get().parseBeanList) {
            val data = HashMap<String, String>()
            p.url?.let { data["url"] = it }
            if (p.url == pb.url) {
                extendName = p.name ?: ""
            }
            data["type"] = p.type.toString()
            p.ext?.let { data["ext"] = it }
            val name = p.name
            if (name != null) {
                jxs[name] = data
                if (p.type == 1) {
                    p.mixUrl()?.let { jsonJxs[name] = it }
                }
            }
        }
        val finalExtendName = extendName
        val parseTargets = SuperParse.buildTargets(jxs, parseFlag + "123")
        pool.execute {
            if (!isParseResultCurrent(gen)) return@execute
            if (isSuper) {
                val rs = SuperParse.parse(jxs, parseFlag + "123", requireNotNull(webUrl), parseTargets)
                if (!rs.has("url") || rs.optString("url").isEmpty()) {
                    val bridge = host.view()
                    if (isParseResultCurrent(gen) && bridge != null) {
                        bridge.runOnUi { bridge.showTip(str(R.string.player_parse_error), false, true) }
                    }
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            host.setWebUserAgent(rs.optString("ua").trim { it <= ' ' })
                        }
                        val bridge = host.view()
                        if (bridge != null) {
                            bridge.runOnUi { bridge.showTip(str(R.string.player_super_parsing), true, false) }
                        }
                        val mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        val webBridge = host.view()
                        if (webBridge != null) {
                            webBridge.runOnUi {
                                if (!isParseResultCurrent(gen)) return@runOnUi
                                stopParse()
                                parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
                                parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS)
                                loadWebView(mixParseUrl)
                            }
                        }
                        pool.execute {
                            val res = SuperParse.doJsonJx(parseTargets.jsonJx, requireNotNull(webUrl))
                            rsJsonJX(gen, res, true)
                        }
                    } else {
                        rsJsonJX(gen, rs, false)
                    }
                }
            } else {
                val rs = ApiConfig.get().jsonExtMix(parseFlag + "111", requireNotNull(pb.url), finalExtendName, jxs, requireNotNull(webUrl))
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                    val bridge = host.view()
                    if (isParseResultCurrent(gen) && bridge != null) {
                        bridge.runOnUi { bridge.showTip(str(R.string.player_parse_error), false, true) }
                    }
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            host.setWebUserAgent(rs.optString("ua").trim { it <= ' ' })
                        }
                        val mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        val bridge = host.view()
                        if (bridge != null) {
                            bridge.runOnUi {
                                if (!isParseResultCurrent(gen)) return@runOnUi
                                stopParse()
                                bridge.showTip(str(R.string.player_sniffing_url), true, false)
                                parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
                                parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS)
                                loadWebView(mixParseUrl)
                            }
                        }
                    } else {
                        rsJsonJX(gen, rs, false)
                    }
                }
            }
        }
    }

    private fun rsJsonJX(gen: Int, rs: JSONObject?, isSuper: Boolean) {
        if (!isParseResultCurrent(gen)) return
        val result = rs ?: return
        if (isSuper) {
            if (!result.has("url")) return
            stopLoadWebView(false)
        }
        val headers = PlaybackController.extractHeaders(result)
        val bridge = host.view()
        if (result.has("jxFrom") && bridge != null) {
            val jxFrom = result.optString("jxFrom")
            bridge.runOnUi { bridge.toast(str(R.string.player_parse_from, jxFrom)) }
        }
        if (bridge != null) host.playUrl(gen, result.optString("url", ""), headers)
    }

    fun loadWebView(url: String?) {
        if (sysWebView == null) {
            initWebView()
        }
        loadUrl(url)
    }

    fun initWebView() {
        val bridge = host.view() ?: return
        sysWebView = bridge.newSniffWebView()
        val web = sysWebView ?: return
        configWebViewSys(web)
    }

    fun loadUrl(url: String?) {
        val bridge = host.view()
        if (bridge == null || !bridge.isPageAlive()) return
        webSniffGeneration = parseGeneration.get()
        bridge.runOnUi {
            sysWebView?.let { web ->
                val target = url ?: return@let
                web.stopLoading()
                host.webUserAgent()?.let { web.settings.userAgentString = it }
                val headerMap = host.webHeaderMap()
                if (headerMap != null) {
                    web.loadUrl(target, headerMap)
                } else {
                    web.loadUrl(target)
                }
            }
        }
    }

    private fun loadUrl(gen: Int, url: String) {
        val bridge = host.view()
        if (bridge == null || !bridge.isPageAlive()) return
        webSniffGeneration = gen
        bridge.runOnUi {
            if (!isParseResultCurrent(gen)) return@runOnUi
            loadUrl(url)
        }
    }

    private fun isSniffRequestOfCurrentRound(): Boolean {
        return webSniffGeneration >= 0 && webSniffGeneration == parseGeneration.get()
    }

    fun stopLoadWebView(destroy: Boolean) {
        val bridge = host.view() ?: return
        bridge.runOnUi {
            sysWebView?.let { web ->
                web.stopLoading()
                web.loadUrl("about:blank")
                if (destroy) {
                    web.clearCache(true)
                    web.removeAllViews()
                    web.destroy()
                    sysWebView = null
                    webSniffGeneration = -1
                }
            }
        }
    }

    fun checkVideoFormat(url: String): Boolean {
        return try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false
            }
            val source = host.sourceBean()
            if (source != null && source.type == 3) {
                val sp = ApiConfig.get().getCSP(source)
                if (sp != null && SpiderReaper.track(sp) { sp.manualVideoCheck() }) {
                    return SpiderReaper.track(sp) { sp.isVideoFormat(url) }
                }
            }
            VideoParseRuler.checkIsVideoForParse(requireNotNull(webUrl), url)
        } catch (e: Exception) {
            false
        }
    }

    private fun configWebViewSys(webView: WebView?) {
        if (webView == null) {
            return
        }
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.clearFocus()
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS
        val bridge = host.view()
        if (bridge == null || !bridge.isPageAlive()) return
        bridge.attachSniffWebView(webView)
        val settings = webView.settings
        settings.setNeedInitialFocus(false)
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.databaseEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptEnabled = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.mediaPlaybackRequiresUserGesture = false
        }
        settings.blockNetworkImage = true
        settings.useWideViewPort = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.setSupportZoom(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.defaultTextEncodingName = "utf-8"
        settings.userAgentString = webView.settings.userAgentString

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = false

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = true

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = true

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String,
                result: JsPromptResult,
            ): Boolean = true
        }
        val sysWebClient = SysWebClient()
        webView.webViewClient = sysWebClient
        webView.setBackgroundColor(Color.BLACK)
    }

    private inner class SysWebClient : WebViewClient() {

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(webView: WebView, sslErrorHandler: SslErrorHandler, sslError: SslError) {
            sslErrorHandler.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            AppLog.i("echo-onPageFinished url:$url")
            val bridge = host.view()
            if (url != "about:blank" && bridge != null) {
                bridge.evaluateScript(url, view)
            }
        }

        fun checkIsVideo(url0: String, headers: HashMap<String, String>): WebResourceResponse? {
            var url = url0
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return WebResourceResponse("image/x-icon", "UTF-8", null)
                }
                return null
            }

            if (!isSniffRequestOfCurrentRound()) {
                return null
            }
            val isFilter = VideoParseRuler.isFilter(requireNotNull(webUrl), url)
            if (isFilter) {
                AppLog.i("shouldInterceptLoadRequest filter:$url")
                return null
            }

            val ad: Boolean
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url)
                loadedUrls[url] = ad
            } else {
                ad = loadedUrls[url] == true
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url)
                    loadFoundVideoUrlsHeader[url] = headers
                    AppLog.i("echo-loadFoundVideoUrl:$url")
                    if (loadFoundCount.incrementAndGet() == 1) {
                        stopLoadWebView(false)
                        SuperParse.stopJsonJx()
                        val found = loadFoundVideoUrls.poll()
                        if (found == null) return null
                        parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
                        val cookie = CookieManager.getInstance().getCookie(found)
                        if (!TextUtils.isEmpty(cookie)) headers["Cookie"] = " $cookie"
                        if (host.view() != null) host.playUrl(found, headers)
                    }
                }
            }

            return if (ad || loadFoundCount.get() > 0) AdBlocker.createEmptyResource() else null
        }

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? = null

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            AppLog.i("echo-shouldInterceptRequest url:$url")
            val webHeaders = HashMap<String, String>()
            val hds = request.requestHeaders
            if (hds != null && hds.keys.size > 0) {
                for (k in hds.keys) {
                    if (k.equals("user-agent", ignoreCase = true)
                        || k.equals("referer", ignoreCase = true)
                        || k.equals("origin", ignoreCase = true)
                    ) {
                        webHeaders[k] = " " + hds[k]
                    }
                }
            }
            return checkIsVideo(url, webHeaders)
        }
    }

    companion object {
        private const val MSG_PARSE_TIMEOUT = 100
        private const val PARSE_TIMEOUT_MS = 20 * 1000L

        @JvmStatic
        private fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance() ?: return ""
            return LanguageManager.localized(app).getString(resId, *args)
        }
    }
}
