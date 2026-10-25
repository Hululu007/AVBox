package com.github.avbox.osc.player

import android.content.Context

interface PageHost {

    fun context(): Context

    fun isPageAlive(): Boolean

    fun runOnUi(action: Runnable)

    fun toast(text: CharSequence)

    fun launchLocalSubtitlePicker()

    fun requestNotificationPermission()

    fun showEpisodeSheet()

    fun onPlaybackLinesExhausted(): Boolean
}
