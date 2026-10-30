package com.dylenq.eq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Reports a double tap without consuming anything: the slider keeps its own press and drag handling
 * (a `detectTapGestures` parent loses the slider's drag, and never sees a tap the slider consumed),
 * so this watches the Initial pass only.
 */
internal fun Modifier.observeDoubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastUp = 0L
    var lastPos = Offset.Unspecified
    val slop = viewConfiguration.touchSlop
    val timeout = viewConfiguration.doubleTapTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var up: PointerInputChange? = null
        var moved = false
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if ((ch.position - down.position).getDistance() > slop) {
                moved = true
                break
            }
            if (ch.changedToUp()) {
                up = ch
                break
            }
        }
        if (moved || up == null) {
            lastPos = Offset.Unspecified
        } else if (lastPos != Offset.Unspecified &&
            up.uptimeMillis - lastUp <= timeout &&
            (up.position - lastPos).getDistance() <= slop * 4
        ) {
            lastPos = Offset.Unspecified
            onDoubleTap()
        } else {
            lastUp = up.uptimeMillis
            lastPos = up.position
        }
    }
}

/** Whether a slider's undo group is currently open (one group per control, closed when the gesture ends). */
internal class GestureGroup {
    var open = false
}

/** Keeps the latest lambda without restarting effects (the gesture group outlives recompositions). */
@Composable
internal fun <T> rememberUpdatedStateOf(v: T) = androidx.compose.runtime.rememberUpdatedState(v)

/** 48 dp touch slot around a 20 dp thumb. */
@Composable
internal fun SliderThumb() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

/** Tabular figures for value readouts. */
internal val Tnum = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
