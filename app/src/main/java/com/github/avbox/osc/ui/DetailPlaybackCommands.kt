package com.github.avbox.osc.ui

sealed interface PlaybackCommand {

    data object StopForContentSwitch : PlaybackCommand

    data class StopForSourceSwitch(val tip: String) : PlaybackCommand

    data object ClearSourceSwitchTip : PlaybackCommand

    data class SetEpisodeSheetOpen(val open: Boolean) : PlaybackCommand

    data class SelectQuality(val position: Int) : PlaybackCommand
}

data class DetailPlaybackFacts(
    val landscape: Boolean,
)

internal object DetailPlaybackCommands {

    fun exitFullScreenState(facts: DetailPlaybackFacts): Pair<Boolean, Boolean> = false to facts.landscape
}

internal object DetailFullScreenFrame {

    fun fullBox(rotating: Boolean, fullScreen: Boolean, landscapeNow: Boolean): Boolean =
        if (rotating) landscapeNow else fullScreen

    fun playerFullScreen(rotating: Boolean, fullScreen: Boolean, landscapeNow: Boolean): Boolean =
        if (rotating) !landscapeNow else fullScreen
}

internal data class DetailFullScreenSlideState(
    val fullScreen: Boolean = false,
    val entering: Boolean = false,
    val exiting: Boolean = false,
)

internal object DetailFullScreenSlide {

    fun enter(state: DetailFullScreenSlideState): DetailFullScreenSlideState? =
        if (state.fullScreen || state.entering) null
        else state.copy(entering = true, exiting = false)

    fun exit(state: DetailFullScreenSlideState): DetailFullScreenSlideState? =
        if (state.exiting || (!state.fullScreen && !state.entering)) null
        else state.copy(fullScreen = false, entering = false, exiting = true)

    fun cancelEntry(state: DetailFullScreenSlideState): DetailFullScreenSlideState? =
        if (state.exiting || !state.entering) null
        else state.copy(entering = false, exiting = true)

    fun entryFinished(state: DetailFullScreenSlideState): DetailFullScreenSlideState? =
        if (!state.entering) null
        else state.copy(entering = false, fullScreen = !state.exiting)

    fun exitFinished(state: DetailFullScreenSlideState): DetailFullScreenSlideState =
        state.copy(exiting = false)
}
