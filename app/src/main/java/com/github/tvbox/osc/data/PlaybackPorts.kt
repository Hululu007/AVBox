package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

interface PlaybackDataPorts {

    fun currentVod(): VodInfo?

    fun isLiveMode(): Boolean

    fun discardStartedContentOf(owners: List<String>)

    fun onProgressKeysCleared(keys: List<String>)
}

object PlaybackPorts {

    @Volatile
    var ports: PlaybackDataPorts? = null
}
