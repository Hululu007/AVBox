package com.github.avbox.osc.player.core

class TrackInfoBean {
    @JvmField
    var trackId: Int = 0

    @JvmField
    var renderId: Int = 0

    @JvmField
    var trackGroupId: Int = 0

    @JvmField
    var name: String? = null

    @JvmField
    var language: String? = null

    @JvmField
    var groupIndex: Int = 0

    @JvmField
    var index: Int = 0

    @JvmField
    var selected: Boolean = false

    @JvmField
    var bitmapSubtitle: Boolean = false

    @JvmField
    var type: Int = 0

    @JvmField
    var formatKey: String? = null
}
