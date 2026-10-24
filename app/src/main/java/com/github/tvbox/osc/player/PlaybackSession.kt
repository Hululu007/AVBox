package com.github.tvbox.osc.player

import com.github.tvbox.osc.bean.VodInfo

class PlaybackSession(vod: VodInfo, sourceKey: String?, userPickedLine: Boolean) {

    private var vod: VodInfo = vod

    private val sourceKey: String = sourceKey ?: ""

    private val userPickedLine: Boolean = userPickedLine

    fun vod(): VodInfo = vod

    internal fun adoptVod(value: VodInfo) {
        vod = value
    }

    fun sourceKey(): String = sourceKey

    fun userPickedLine(): Boolean = userPickedLine

    fun playbackKey(): String = sourceKey + "|" + vod.id + "|" + vod.playFlag + "|" + vod.playIndex
}
