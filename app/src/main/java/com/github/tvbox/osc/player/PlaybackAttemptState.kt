package com.github.tvbox.osc.player

data class PlaybackAttemptState(
    val allowSwitchPlayer: Boolean = true,
    val hasAutoSwitchedPlayer: Boolean = false,
    val autoSwitchedPlayerType: Int = -1,
    val hasAutoSwitchedDecode: Boolean = false,
    val hasAutoSwitchedAudioTrack: Boolean = false,
    val autoSwitchedDecodeOld: String? = null,
    val autoSwitchedDecodeKey: String = "exo",
    val hasRetriedAfterStart: Boolean = false,
    val usedPreloadedResult: Boolean = false,
    val hasRetriedSameUrlOnBoot: Boolean = false,
    val hasRefreshedOnFormatError: Boolean = false,
    val refreshKeepsProgress: Boolean = false,
    val playbackStarted: Boolean = false,
    val playTimeoutBasePosition: Long = 0,
    val triedLineFlags: Set<String> = emptySet(),
    val lastRetryTime: Long = 0,
    val userPickedLine: Boolean = false,
    val allowAutoSwitchLine: Boolean = true,
    val switchIntent: SwitchIntent = SwitchIntent.NONE,
    val switchStopPending: Boolean = false,
    val pendingInheritKey: String? = null,
    val pendingInheritProgress: Long = 0,
    val switchingPlayback: Boolean = false,
    val castPrepareOnly: Boolean = false,
    val isoSession: Boolean = false,
    val hasRefreshedOnIsoHttpError: Boolean = false,
    val isoBandwidthSwitchTried: Boolean = false,
    val localProxySession: Boolean = false,
    val slowOpenExtended: Boolean = false,
    val lastHttpStatus: Int = 0,
    val castAborted: Boolean = false,
    val skipEndPending: Boolean = true,
) {

    enum class SwitchIntent { NONE, REUSE, REBUILD }

    data class Transition<T>(val state: PlaybackAttemptState, val value: T)

    fun beginSession(): PlaybackAttemptState = copy(
        playbackStarted = false,
        switchStopPending = false,
        autoSwitchedDecodeOld = null,
        castAborted = false,
        hasRefreshedOnFormatError = false,
        refreshKeepsProgress = false,
        isoBandwidthSwitchTried = false,
        localProxySession = false,
        slowOpenExtended = false,
        lastHttpStatus = 0,
    )

    fun beginNewPlay(): PlaybackAttemptState = copy(
        playbackStarted = false,
        playTimeoutBasePosition = 0,
        allowSwitchPlayer = true,
        hasAutoSwitchedPlayer = false,
        hasAutoSwitchedDecode = false,
        hasAutoSwitchedAudioTrack = false,
        hasRetriedAfterStart = false,
        hasRetriedSameUrlOnBoot = false,
        usedPreloadedResult = false,
        isoSession = false,
        hasRefreshedOnIsoHttpError = false,
        localProxySession = false,
        slowOpenExtended = false,
        lastHttpStatus = 0,
    )

    fun userSelfRescue(): PlaybackAttemptState = copy(
        hasAutoSwitchedPlayer = false,
        hasRetriedAfterStart = false,
        hasRetriedSameUrlOnBoot = false,
        hasRefreshedOnFormatError = false,
        hasRefreshedOnIsoHttpError = false,
        isoBandwidthSwitchTried = false,
    )

    fun stoppedForSourceSwitch(): PlaybackAttemptState = copy(
        playbackStarted = false,
        playTimeoutBasePosition = 0,
        switchIntent = SwitchIntent.NONE,
        switchStopPending = true,
    )

    fun resetAutoRetryLadder(): PlaybackAttemptState = copy(
        allowSwitchPlayer = true,
        hasAutoSwitchedPlayer = false,
        hasAutoSwitchedAudioTrack = false,
        hasRetriedSameUrlOnBoot = false,
        hasRefreshedOnFormatError = false,
        hasRefreshedOnIsoHttpError = false,
        triedLineFlags = emptySet(),
    )

    fun onLineSwitched(): PlaybackAttemptState = copy(
        allowSwitchPlayer = true,
        hasAutoSwitchedPlayer = false,
        hasRefreshedOnFormatError = false,
        hasRefreshedOnIsoHttpError = false,
        slowOpenExtended = false,
        switchIntent = if (switchIntent == SwitchIntent.REBUILD) SwitchIntent.REBUILD else SwitchIntent.REUSE,
    )

    fun consumeRefreshKeepsProgress(): Transition<Boolean> =
        Transition(copy(refreshKeepsProgress = false), refreshKeepsProgress)

    fun linesExhausted(): PlaybackAttemptState = copy(triedLineFlags = emptySet())

    fun consumeSkipEnd(): PlaybackAttemptState = copy(skipEndPending = false)

    fun withTriedLine(flag: String): PlaybackAttemptState = copy(triedLineFlags = triedLineFlags + flag)

    fun setReuseIntent(reuse: Boolean): PlaybackAttemptState {
        if (reuse) {
            return if (switchIntent == SwitchIntent.NONE) copy(switchIntent = SwitchIntent.REUSE) else this
        }
        return if (switchIntent == SwitchIntent.REUSE) copy(switchIntent = SwitchIntent.NONE) else this
    }

    fun setReleaseIntent(release: Boolean): PlaybackAttemptState {
        if (release) return copy(switchIntent = SwitchIntent.REBUILD)
        return if (switchIntent == SwitchIntent.REBUILD) copy(switchIntent = SwitchIntent.NONE) else this
    }

    fun consumeReuseIntent(): Transition<Boolean> =
        Transition(copy(switchIntent = SwitchIntent.NONE), switchIntent == SwitchIntent.REUSE)

    fun clearSessionFlags(): PlaybackAttemptState = copy(switchingPlayback = false)

    fun abortCastSession(): PlaybackAttemptState = copy(castPrepareOnly = false, castAborted = true)

    fun clearCastAbort(): Transition<Boolean> {
        if (!castAborted) return Transition(this, false)
        return Transition(copy(castAborted = false), true)
    }
}
