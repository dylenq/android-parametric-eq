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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
    val limiter: TickLimiter = TickLimiter(40) { android.os.SystemClock.uptimeMillis() },
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
                m.haptics.value.longPress()
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
                pinch(m, gate, outcome.event!!, plot)
            }
        }
    } finally {
        if (gate.open) {
            m.haptics.value.dragEnd()
            m.onDragEnd()
        }
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
    var lastFreq = band.freq
    var change = first
    while (true) {
        val delta = change.position - down.position
        val target = nodeAtDown + delta
        val freq = ParametricMath.snapHz(ParametricMath.hzAt(target.x, plot))
        val gain = if (band.kind.hasGain()) ParametricMath.snapDb(ParametricMath.dbAt(target.y, plot, m.yWindow())) else null
        val zero = gain != null && SliderMath.enteredZero(lastGain, gain)
        val gainTick = gain != null && SliderMath.wholeDbCrossings(lastGain, gain) > 0
        val gridTick = FreqGridCrossing.crossed(lastFreq, freq)
        if (zero) {
            m.haptics.value.zeroDetent()
        } else if ((gainTick || gridTick) && m.limiter.tryTick()) {
            if (gainTick) m.haptics.value.tick() else m.haptics.value.gridTick()
        }
        if (gain != null) lastGain = gain
        lastFreq = freq
        m.onNodeChange(index, freq, gain)
        change.consume()
        val ev = awaitPointerEvent(PointerEventPass.Main)
        if (ev.changes.count { it.pressed } >= 2) {
            // Drag turns into a pinch: same undo group, node frozen where it is.
            pinch(m, gate, ev, plot)
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
 * Two-finger pinch for Q from the first event with two pressed pointers until the pair breaks, then the
 * survivor is ignored until all fingers are up (a resumed drag would teleport the node). Every event is
 * consumed. The target band is resolved once; with none, the pinch is consumed but changes nothing.
 */
private suspend fun AwaitPointerEventScope.pinch(
    m: MachineInputs,
    gate: GestureGate,
    start: PointerEvent,
    plot: Rect,
) {
    start.changes.forEach { it.consume() }
    val pressed = start.changes.filter { it.pressed }
    val idA = pressed[0].id
    val idB = pressed[1].id
    val startDistance = (pressed[0].position - pressed[1].position).getDistance()
    val centroid = (pressed[0].position + pressed[1].position) / 2f
    val list = m.bands()
    val w = m.yWindow()
    val nodes = list.map { ParametricMath.nodePosition(it.freq, it.gainDb, it.kind.hasGain(), plot, w) }
    val target = ParametricMath.pinchTarget(
        nodes, centroid, m.selected(), ParametricMath.PINCH_CENTROID_RADIUS_DP * density,
    )?.takeIf { it in list.indices }
    var changed = false
    var allUp = false
    try {
        if (target != null) {
            m.onSelect(target)
            val q0 = list[target].q
            var lastQ = q0
            var limitFired = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                ev.changes.forEach { it.consume() }
                val a = ev.changes.firstOrNull { it.id == idA }
                val b = ev.changes.firstOrNull { it.id == idB }
                if (a == null || b == null || !a.pressed || !b.pressed) {
                    allUp = ev.changes.none { it.pressed }
                    break
                }
                val q = ParametricMath.qAfterPinch(q0, startDistance, (a.position - b.position).getDistance())
                if (q != lastQ) {
                    if (!gate.open) {
                        gate.open = true
                        m.onDragStart(target)
                        m.haptics.value.dragStart()
                    }
                    m.onQChange(target, q)
                    lastQ = q
                    changed = true
                    if (!limitFired && (q <= ParametricMath.MIN_Q || q >= ParametricMath.MAX_Q)) {
                        limitFired = true
                        m.haptics.value.reject()
                    }
                }
            }
        } else {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                ev.changes.forEach { it.consume() }
                val a = ev.changes.firstOrNull { it.id == idA }
                val b = ev.changes.firstOrNull { it.id == idB }
                if (a == null || b == null || !a.pressed || !b.pressed) {
                    allUp = ev.changes.none { it.pressed }
                    break
                }
            }
        }
    } finally {
        if (changed) m.onPinchCompleted()
    }
    if (!allUp) consumeUntilUp()
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
    dragging: () -> Int? = { null },
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
    val colors = NodeGlyphColors(cs.primary, cs.outline, cs.surfaceContainerLowest)
    val guideColor = cs.primary.copy(alpha = NodeGlyphs.GUIDE_ALPHA)
    val labelColor = cs.onSurface
    val labelDisabledColor = cs.onSurfaceVariant.copy(alpha = 0.6f)
    val haloColor = cs.surfaceContainerLowest
    val fontScale = LocalDensity.current.fontScale
    val labelsOn = NodeGlyphs.labelVisible(fontScale)
    val measurer = rememberTextMeasurer()
    val smallStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val largeStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val density = LocalDensity.current.density
    val labels = remember(measurer, smallStyle, largeStyle, density, labelColor, labelDisabledColor, haloColor) {
        NodeLabels(measurer, smallStyle, largeStyle, labelColor, labelDisabledColor, haloColor, 4f * density)
    }

    // 100 ms grow of the dragged node (instant under reduced motion); read inside the draw phase.
    val reduced = rememberReducedMotion()
    val draggingState = rememberUpdatedState(dragging)
    val grow = remember { Animatable(0f) }
    val isDragging = draggingState.value() != null
    LaunchedEffect(isDragging, reduced) {
        val target = if (isDragging) 1f else 0f
        if (reduced) grow.snapTo(target) else grow.animateTo(target, tween(100))
    }

    Canvas(
        modifier
            .testTag("curve-interaction")
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture { runGesture(machine) }
            },
    ) {
        val plot = PlotGeometry.plotRect(size.width, size.height, this.density)
        val w = windowState.value()
        val list = bandsState.value()
        val sel = selectedState.value()
        val drag = draggingState.value()?.takeIf { it in list.indices }
        val centres = list.map { ParametricMath.nodePosition(it.freq, it.gainDb, it.kind.hasGain(), plot, w) }

        if (drag != null) {
            val c = centres[drag]
            val hair = 1.dp.toPx()
            drawLine(guideColor, Offset(c.x, c.y), Offset(c.x, plot.bottom), hair)
            if (list[drag].kind.hasGain()) drawLine(guideColor, Offset(c.x, c.y), Offset(plot.left, c.y), hair)
        }

        // Index order with the selected node last (on top); a dragged node is drawn above the rest too.
        val order = list.indices.sortedBy { if (it == sel) 2 else if (it == drag) 1 else 0 }
        for (i in order) {
            val b = list[i]
            val spec = NodeGlyphs.spec(b.kind, NodeVisualState(i == sel, b.enabled, i == drag))
            drawNodeGlyph(centres[i], spec, colors, this.density, growth = if (i == drag) grow.value else 1f)
        }

        if (labelsOn) {
            for (i in order) {
                val b = list[i]
                val c = centres[i]
                val layout = labels.layout(i + 1, selected = i == sel, enabled = b.enabled)
                val halfNode = (if (b.kind.hasGain()) NodeGlyphs.CIRCLE_DP else NodeGlyphs.DIAMOND_DP) / 2f * this.density
                val below = NodeGlyphs.labelBelow(c.y / this.density, plot.top / this.density)
                val y = if (below) c.y + halfNode + NodeGlyphs.LABEL_GAP_DP * this.density
                else c.y - halfNode - NodeGlyphs.LABEL_GAP_DP * this.density - layout.first.size.height
                val x = c.x - layout.first.size.width / 2f
                drawText(layout.second, topLeft = Offset(x, y))
                drawText(layout.first, topLeft = Offset(x, y))
            }
        }
    }
}

/** Measured band-number labels: a text-halo layout under the fill layout, cached per (number, style). */
private class NodeLabels(
    private val measurer: TextMeasurer,
    private val small: TextStyle,
    private val large: TextStyle,
    private val color: Color,
    private val disabledColor: Color,
    private val haloColor: Color,
    private val haloStrokePx: Float,
) {
    private val cache = HashMap<Int, Pair<TextLayoutResult, TextLayoutResult>>()

    /** (fill, halo) layouts; the halo is drawn first. */
    fun layout(number: Int, selected: Boolean, enabled: Boolean): Pair<TextLayoutResult, TextLayoutResult> {
        val key = number * 4 + (if (selected) 2 else 0) + (if (enabled) 1 else 0)
        return cache.getOrPut(key) {
            val base = if (selected) large else small
            val fill = measurer.measure(number.toString(), base.copy(color = if (enabled) color else disabledColor))
            val halo = measurer.measure(
                number.toString(),
                base.copy(color = haloColor, drawStyle = Stroke(haloStrokePx, join = StrokeJoin.Round)),
            )
            fill to halo
        }
    }
}
