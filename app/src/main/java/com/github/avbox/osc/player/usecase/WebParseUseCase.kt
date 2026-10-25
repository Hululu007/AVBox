package com.github.avbox.osc.player.usecase

import android.os.Build
import android.webkit.WebView
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.VideoParseRuler
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class WebParseUseCase {

    fun getWebPlayUrlIfNeeded(webPlayUrl: String?): String? {
        if (webPlayUrl != null && !webPlayUrl.contains("127.0.0.1:9978") && webPlayUrl.contains(".m3u8")) {
            try {
                val urlEncode = URLEncoder.encode(webPlayUrl, "UTF-8")
                AppLog.i("echo-BOM-------")
                return ControlManager.get().getAddress(true) + "proxy?go=bom&url=" + urlEncode
            } catch (e: UnsupportedEncodingException) {
                AppLog.e("WebParseUseCase", e)
            }
        }
        return webPlayUrl
    }

    fun evaluateScript(sourceBean: SourceBean?, url: String?, webView: WebView?) {
        if (sourceBean == null || url == null) return
        var clickSelector = sourceBean.clickSelector?.trim { it <= ' ' } ?: ""
        clickSelector = if (clickSelector.isEmpty()) VideoParseRuler.hostScript(url) else clickSelector
        if (clickSelector.isNotEmpty()) {
            val selector: String
            if (clickSelector.contains(";") && !clickSelector.endsWith(";")) {
                val parts = RegexUtils.getPattern(";").split(clickSelector, 2)
                if (!url.contains(parts[0])) {
                    return
                }
                selector = parts[1].trim { it <= ' ' }
            } else {
                selector = clickSelector.trim { it <= ' ' }
            }
            val js = selector
            AppLog.i("echo-javascript:$js")
            if (webView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    webView.evaluateJavascript(js, null)
                } else {
                    webView.loadUrl("javascript:$js")
                }
            }
        }
    }
}
