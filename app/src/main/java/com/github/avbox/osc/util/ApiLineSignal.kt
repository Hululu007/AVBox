package com.github.avbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ApiLineSignal {

    private val _version = MutableStateFlow(0)

    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() {
        _version.update { it + 1 }
    }
}
