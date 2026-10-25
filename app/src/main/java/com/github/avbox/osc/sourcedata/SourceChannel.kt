package com.github.avbox.osc.sourcedata

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

open class SourceChannel<T> {

    private val shared = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: Flow<T> = shared

    open fun postValue(value: T) {
        shared.tryEmit(value)
    }

    open fun setValue(value: T) {
        shared.tryEmit(value)
    }
}
