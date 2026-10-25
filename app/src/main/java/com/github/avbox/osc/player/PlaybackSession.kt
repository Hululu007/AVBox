package com.github.avbox.osc.player

import com.github.avbox.osc.bean.VodInfo

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

    fun vodKey(): String = sourceKey + "|" + vod.id + "|" + vod.playFlag + "|"

    fun playbackKey(): String =
        vodKey() + vod.playIndex + "|" + vod.reverseSort + "|" + episodeName()

    private fun episodeName(): String =
        vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)?.name.orEmpty()
}
