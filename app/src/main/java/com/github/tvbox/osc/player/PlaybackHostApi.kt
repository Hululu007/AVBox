package com.github.tvbox.osc.player

import android.net.Uri

interface PlaybackHostApi {

    fun setData(session: PlaybackSession)

    fun play(reset: Boolean)

    fun playNext(rmProgress: Boolean)

    fun playPrevious()

    fun selectQuality(position: Int): Boolean

    fun setPreviewMode(previewMode: Boolean)

    fun toggleControllerControls()

    fun onBackPressed(): Boolean

    fun setExitingPreview(exitingPreview: Boolean)

    fun setPlayTitle(show: Boolean)

    fun stopForSourceSwitch(tip: String)

    fun clearSourceSwitchTip()

    fun showCast()

    fun onLocalSubtitlePicked(uri: Uri)

    fun hostResume()

    fun hostPause()

    fun hostDestroy()

    fun resumeFromMediaSession()

    fun pauseFromMediaSession()

    fun stopFromMediaSession()

    fun seekFromMediaSession(position: Long)
}
