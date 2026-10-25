package com.github.avbox.osc.player.thirdparty

import android.app.Activity
import android.content.Context

import com.github.avbox.osc.R
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.LanguageManager

object PlayerCatalog {

    private var playersExistInfo: HashMap<Int, Boolean>? = null

    @JvmStatic
    fun name(playType: Int): String {
        return when (playType) {
            13 -> str(R.string.player_nearby_tvbox)
            else -> str(R.string.player_exo)
        }
    }

    @JvmStatic
    fun invalidateCache() {
        playersExistInfo = null
    }

    private fun existsMap(): HashMap<Int, Boolean> {
        val cached = playersExistInfo
        if (cached != null) return cached
        val playersExist = HashMap<Int, Boolean>()
        playersExist[2] = true
        playersExist[13] = RemoteTVBox.avalible != null
        playersExistInfo = playersExist
        return playersExist
    }

    @JvmStatic
    fun exists(playType: Int): Boolean {
        val playersExistInfo = existsMap()
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.getValue(playType)
        } else {
            return false
        }
    }

    @JvmStatic
    fun all(): ArrayList<Int> {
        val playersExistInfo = existsMap()
        val existPlayers = ArrayList<Int>()
        for (playerType in playersExistInfo.keys) {
            if (playersExistInfo.getValue(playerType)) {
                existPlayers.add(playerType)
            }
        }
        return existPlayers
    }

    private fun str(resId: Int): String {
        val app: Context? = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId)
    }
}

object ExternalPlayerLauncher {

    @JvmStatic
    fun launch(
        playerType: Int,
        activity: Activity,
        url: String,
        title: String,
        subtitle: String,
        headers: HashMap<String, String>?,
        progress: Long = 0L,
    ): Boolean {
        var callResult = false
        when (playerType) {
            13 -> {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers)
            }
        }
        return callResult
    }
}
