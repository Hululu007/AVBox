package com.github.avbox.osc.ui

internal enum class DetailPlaybackEntry {
    PlayCapsule,
    Episode,
    Line,
    Cast,
    ExitFullscreen,
    SwitchRollback,
    MusicReturn,
}

internal data class DetailPlaybackPlan(
    val startPlayback: Boolean,
    val enterFullScreen: Boolean,
    val stopPlayback: Boolean,
)

internal object DetailPlaybackPolicy {

    fun plan(entry: DetailPlaybackEntry, resumable: Boolean = false): DetailPlaybackPlan = when (entry) {
        DetailPlaybackEntry.PlayCapsule,
        DetailPlaybackEntry.Episode -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = true, stopPlayback = false)

        DetailPlaybackEntry.SwitchRollback,
        DetailPlaybackEntry.MusicReturn -> DetailPlaybackPlan(startPlayback = resumable, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.Line,
        DetailPlaybackEntry.Cast -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.ExitFullscreen -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = true)
    }
}
