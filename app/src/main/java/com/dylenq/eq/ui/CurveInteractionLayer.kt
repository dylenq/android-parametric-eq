package com.dylenq.eq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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

/**
 * The Parametric plot overlay (phase 05): draws one node per band on the real curve and turns
 * single-finger drags on a node into frequency and gain changes. Inputs are lambdas so a drag
 * redraws without recomposing. The one [pointerInput] key is constant: a changing key would cancel a
 * live gesture and orphan its begin_edit (L-05). Only gestures that start on a node are consumed.
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
) {
    val bandsState = rememberUpdatedState(bands)
    val selectedState = rememberUpdatedState(selected)
    val windowState = rememberUpdatedState(yWindow)
    val selectState = rememberUpdatedState(onSelect)
    val startState = rememberUpdatedState(onDragStart)
    val changeState = rememberUpdatedState(onNodeChange)
    val endState = rememberUpdatedState(onDragEnd)

    val cs = MaterialTheme.colorScheme
    val fill = cs.primary
    val ring = cs.surfaceContainerLowest

    Canvas(
        modifier
            .testTag("curve-interaction")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val plot = PlotGeometry.plotRect(size.width.toFloat(), size.height.toFloat(), density)
                    val list = bandsState.value()
                    val w0 = windowState.value()
                    val nodes = list.map { ParametricMath.nodePosition(it.freq, it.gainDb, it.kind.hasGain(), plot, w0) }
                    val hit = ParametricMath.pickNode(
                        nodes, selectedState.value(), down.position, ParametricMath.HIT_RADIUS_DP * density,
                    ) ?: return@awaitEachGesture
                    val band = list[hit]
                    down.consume()
                    selectState.value(hit)
                    val nodeAtDown = nodes[hit]
                    val slop = 8.dp.toPx()
                    var started = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - down.position
                            if (!started) {
                                if (delta.getDistance() <= slop) continue
                                started = true
                                startState.value(hit)
                            }
                            // Relative drag: the finger keeps its grab offset from the node centre.
                            val target: Offset = nodeAtDown + delta
                            val w = windowState.value()
                            val freq = ParametricMath.snapHz(ParametricMath.hzAt(target.x, plot))
                            val gain = if (band.kind.hasGain()) {
                                ParametricMath.snapDb(ParametricMath.dbAt(target.y, plot, w))
                            } else {
                                null
                            }
                            changeState.value(hit, freq, gain)
                            change.consume()
                        }
                    } finally {
                        if (started) endState.value()
                    }
                }
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
