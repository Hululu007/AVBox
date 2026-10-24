package com.github.tvbox.osc.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface UiChange {
    data object CollectLayoutChanged : UiChange
    data object HistoryChanged : UiChange
    data object PlaybackStarted : UiChange
}

object UiEvents {
    private val _changes = MutableSharedFlow<UiChange>(extraBufferCapacity = 16)

    val changes: SharedFlow<UiChange> = _changes.asSharedFlow()

    fun post(change: UiChange) {
        _changes.tryEmit(change)
    }
}
