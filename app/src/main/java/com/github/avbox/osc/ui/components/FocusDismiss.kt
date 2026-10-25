package com.github.avbox.osc.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration

private const val ImeFocusGraceMs = 400L

@Composable
internal fun ClearFocusOnImeDismiss(
    focusManager: FocusManager,
    fieldFocused: Boolean,
) {
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    var focusedSince by remember { mutableStateOf(0L) }
    LaunchedEffect(fieldFocused) {
        if (fieldFocused) focusedSince = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(imeVisible) {
        val justFocused = SystemClock.elapsedRealtime() - focusedSince < ImeFocusGraceMs
        if (imeWasVisible && !imeVisible && fieldFocused && !justFocused) {
            focusManager.clearFocus()
        }
        imeWasVisible = imeVisible
    }
}

@Composable
internal fun Modifier.clearFocusOnDrag(focusManager: FocusManager): Modifier {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    return pointerInput(focusManager, touchSlop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dragged = false
            var distance = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                distance += change.positionChange().getDistance()
                if (!dragged && distance > touchSlop) {
                    dragged = true
                    focusManager.clearFocus()
                }
            }
        }
    }
}
