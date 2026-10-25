package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo

interface PlaybackDataPorts {

    fun currentVod(): VodInfo?

    fun isLiveMode(): Boolean

    fun hasAttachedPage(): Boolean

    fun discardStartedContentOf(owners: List<String>)

    fun onProgressKeysCleared(keys: List<String>)
}

object PlaybackPorts {

    @Volatile
    var ports: PlaybackDataPorts? = null
}
