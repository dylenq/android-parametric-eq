package com.dylenq.eq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind

/** Pass filters (low/high pass) have no gain: their node sits on the 0 dB line. */
internal fun FilterKind.hasGain(): Boolean = this != FilterKind.LOW_PASS && this != FilterKind.HIGH_PASS

/** Remembered first tap of a possible double-tap on empty plot (read inside the loop, never a key). */
private class TapMemory {
    var valid = false
    var timeMs = 0L
    var pos = Offset.Zero
}

/** Everything the gesture machine reads or calls; lambdas so it never needs a changing pointerInput key. */
private class MachineInputs(
    val bands: () -> List<Band>,
    val selected: () -> Int?,
    val yWindow: () -> Float,
    val onSelect: (Int) -> Unit,
    val onDragStart: (Int) -> Unit,
    val onNodeChange: (index: Int, freqHz: Float, gainDb: Float?) -> Unit,
    val onDragEnd: () -> Unit,
    val onAddBand: (freqHz: Float, gainDb: Float) -> Unit,
    val onQChange: (index: Int, q: Float) -> Unit,
    val onLongPress: (Int) -> Unit,
    val onPinchCompleted: () -> Unit,
    val onDeselect: () -> Unit,
    val haptics: State<Haptics>,
    val tap: TapMemory,
)

private enum class PendingKind { UP, SLOP, SECOND, GONE }

private class PendingResult(val kind: PendingKind, val event: PointerEvent?, val change: PointerInputChange?)

/** Waits for the first pointer to lift, pass the touch slop, or for a second pointer to go down. */
private suspend fun AwaitPointerEventScope.awaitPending(
    down: PointerInputChange,
    slop: Float,
    consume: Boolean,
): PendingResult {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Main)
        if (ev.changes.count { it.pressed } >= 2) {
            if (consume) ev.changes.forEach { it.consume() }
            return PendingResult(PendingKind.SECOND, ev, null)
        }
        val c = ev.changes.firstOrNull { it.id == down.id } ?: return PendingResult(PendingKind.GONE, ev, null)
        if (!c.pressed) {
            if (consume) c.consume()
            return PendingResult(PendingKind.UP, ev, c)
        }
        if ((c.position - down.position).getDistance() > slop) {
            if (consume) c.consume()
            return PendingResult(PendingKind.SLOP, ev, c)
        }
        if (consume) c.consume()
    }
}

/** Consumes every event until no pointer is pressed (after a long-press or a finished pinch). */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Main)
        ev.changes.forEach { it.consume() }
        if (ev.changes.none { it.pressed }) return
    }
}

/** True once the holder's undo group of this physical gesture has been opened. */
private class GestureGate {
    var open = false
}

private suspend fun AwaitPointerEventScope.runGesture(m: MachineInputs) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val plot = PlotGeometry.plotRect(size.width.toFloat(), size.height.toFloat(), density)
    val w0 = m.yWindow()
    val list = m.bands()
    val nodes = list.map { ParametricMath.nodePosition(it.freq, it.gainDb, it.kind.hasGain(), plot, w0) }
    val radius = ParametricMath.HIT_RADIUS_DP * density
    val selAtDown = m.selected()
    val hit = ParametricMath.pickNode(nodes, selAtDown, down.position, radius)
    val slop = viewConfiguration.touchSlop
    val gate = GestureGate()
    try {
        val outcome: PendingResult
        if (hit == null) {
            outcome = awaitPending(down, slop, consume = false)
        } else {
            down.consume()
            val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPending(down, slop, consume = true) }
            if (r == null) {
                // Held in place: detection only (the menu arrives in plan 06); no edit, finger ignored until up.
                m.tap.valid = false
                m.onSelect(hit)
                m.onLongPress(hit)
                consumeUntilUp()
                return
            }
            outcome = r
        }
        when (outcome.kind) {
            PendingKind.GONE -> return
            PendingKind.UP -> {
                val up = outcome.change!!
                if (hit != null) {
                    m.tap.valid = false
                    ParametricMath.tapCycleTarget(nodes, selAtDown, down.position, radius)?.let(m.onSelect)
                } else {
                    m.onDeselect()
                    val t = m.tap
                    val isDouble = t.valid && ParametricMath.isDoubleTapAdd(
                        t.pos, up.position, up.uptimeMillis - t.timeMs,
                        viewConfiguration.doubleTapTimeoutMillis, nodes, density,
                    )
                    if (isDouble) {
                        t.valid = false
                        val freq = ParametricMath.snapHz(ParametricMath.hzAt(up.position.x, plot))
                        val gain = ParametricMath.snapDb(ParametricMath.dbAt(up.position.y, plot, w0))
                        if (list.size >= ParametricMath.MAX_BANDS) m.haptics.value.reject() else m.haptics.value.confirm()
                        m.onAddBand(freq, gain)
                    } else {
                        t.valid = true
                        t.timeMs = up.uptimeMillis
                        t.pos = up.position
                    }
                }
            }
            PendingKind.SLOP -> {
                if (hit == null) return // an empty-space swipe is never consumed: the page scrolls
                m.tap.valid = false
                drag(m, gate, down, outcome.change!!, hit, list[hit], nodes[hit], plot)
            }
            PendingKind.SECOND -> {
                m.tap.valid = false
                consumeUntilUp()
            }
        }
    } finally {
        if (gate.open) m.onDragEnd()
    }
}

/** Node drag from the slop crossing until the finger lifts; relative to the grab offset. */
private suspend fun AwaitPointerEventScope.drag(
    m: MachineInputs,
    gate: GestureGate,
    down: PointerInputChange,
    first: PointerInputChange,
    index: Int,
    band: Band,
    nodeAtDown: Offset,
    plot: Rect,
) {
    gate.open = true
    m.onDragStart(index)
    m.haptics.value.dragStart()
    var lastGain = band.gainDb
    var change = first
    while (true) {
        val delta = change.position - down.position
        val target = nodeAtDown + delta
        val freq = ParametricMath.snapHz(ParametricMath.hzAt(target.x, plot))
        val gain = if (band.kind.hasGain()) ParametricMath.snapDb(ParametricMath.dbAt(target.y, plot, m.yWindow())) else null
        if (gain != null) {
            if (gain == 0f && lastGain != 0f) m.haptics.value.zeroDetent()
            lastGain = gain
        }
        m.onNodeChange(index, freq, gain)
        change.consume()
        val ev = awaitPointerEvent(PointerEventPass.Main)
        if (ev.changes.count { it.pressed } >= 2) {
            ev.changes.forEach { it.consume() }
            consumeUntilUp()
            return
        }
        val c = ev.changes.firstOrNull { it.id == down.id } ?: return
        if (!c.pressed) {
            c.consume()
            return
        }
        change = c
    }
}

/**
 * The Parametric plot overlay (phase 05): draws one node per band on the real curve and runs ONE
 * hand-written gesture machine (select, cycle, deselect, drag, double-tap add, long-press). Inputs are
 * lambdas so a drag redraws without recomposing. The one [pointerInput] key is constant: a changing key
 * would cancel a live gesture and orphan its begin_edit (L-05). Only gestures that start on a node are
 * consumed; empty-space swipes pass through so the page scrolls.
 */
@Composable
fun CurveInteractionLayer(
    bands: () -> List<Band>,
    selected: () -> Int?,
    yWindow: () -> Float,
    onSelect: (Int) -> Unit,
    onDragStart: (Int) -> Unit,
    onNodeChange: (index: Int, freqHz: Float, gainDb: Float?) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onAddBand: (freqHz: Float, gainDb: Float) -> Unit = { _, _ -> },
    onQChange: (index: Int, q: Float) -> Unit = { _, _ -> },
    onLongPress: (Int) -> Unit = {},
    onPinchCompleted: () -> Unit = {},
    onDeselect: () -> Unit = {},
) {
    val bandsState = rememberUpdatedState(bands)
    val selectedState = rememberUpdatedState(selected)
    val windowState = rememberUpdatedState(yWindow)
    val hapticsState = rememberUpdatedState(rememberHaptics())
    val onSelectState = rememberUpdatedState(onSelect)
    val onDragStartState = rememberUpdatedState(onDragStart)
    val onNodeChangeState = rememberUpdatedState(onNodeChange)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    val onAddBandState = rememberUpdatedState(onAddBand)
    val onQChangeState = rememberUpdatedState(onQChange)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onPinchCompletedState = rememberUpdatedState(onPinchCompleted)
    val onDeselectState = rememberUpdatedState(onDeselect)
    val machine = remember {
        MachineInputs(
            bands = { bandsState.value() },
            selected = { selectedState.value() },
            yWindow = { windowState.value() },
            onSelect = { onSelectState.value(it) },
            onDragStart = { onDragStartState.value(it) },
            onNodeChange = { i, f, g -> onNodeChangeState.value(i, f, g) },
            onDragEnd = { onDragEndState.value() },
            onAddBand = { f, g -> onAddBandState.value(f, g) },
            onQChange = { i, q -> onQChangeState.value(i, q) },
            onLongPress = { onLongPressState.value(it) },
            onPinchCompleted = { onPinchCompletedState.value() },
            onDeselect = { onDeselectState.value() },
            haptics = hapticsState,
            tap = TapMemory(),
        )
    }

    val cs = MaterialTheme.colorScheme
    val fill = cs.primary
    val ring = cs.surfaceContainerLowest

    Canvas(
        modifier
            .testTag("curve-interaction")
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture { runGesture(machine) }
            },
    ) {
        val plot = PlotGeometry.plotRect(size.width, size.height, density)
        val w = windowState.value()
        val list = bandsState.value()
        val sel = selectedState.value()
        list.forEachIndexed { i, b ->
            val c = ParametricMath.nodePosition(b.freq, b.gainDb, b.kind.hasGain(), plot, w)
            val r = 8.dp.toPx()
            drawCircle(ring, r + 2.dp.toPx(), c)
            drawCircle(fill, r, c)
            if (i == sel) drawCircle(ring, r + 4.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}
