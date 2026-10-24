package com.github.tvbox.osc.player.usecase

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONException

object PlayerSwitchUseCase {

    @JvmStatic
    fun switchPlayer(): Boolean = true

    @JvmStatic
    fun encodeUrl(url: String?): String {
        return try {
            java.net.URLEncoder.encode(url, "UTF-8")
        } catch (e: Exception) {
            url ?: ""
        }
    }

    @JvmStatic
    fun firstUrlByArray(url: String?): String {
        var out = url ?: ""
        try {
            val urlArray = JSONArray(out)
            for (i in 0 until urlArray.length()) {
                val item = urlArray.getString(i)
                if (item.contains("http")) {
                    out = item
                    break
                }
            }
        } catch (e: JSONException) {
            LOG.d("PlayerSwitchUseCase", "url is not a json array, keep raw")
        }
        return out
    }

    @JvmStatic
    fun stopOther() {
        requireNotNull(App.getInstance()) { "app instance not ready" }.setDashData(null)
    }
}
