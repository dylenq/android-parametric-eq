package com.dylenq.eq.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dylenq.eq.core.Band
import java.util.Locale
import kotlin.math.roundToInt

/** Pure text and placement of the drag/pinch readout (UI-SPEC "Readout while dragging or pinching"). */
object NodeReadout {
    const val OFFSET_DP = 56f

    /** `3  1.25 kHz  +3.5 dB  Q 1.00`; pass kinds omit the gain. Band number is index + 1. */
    fun text(index: Int, band: Band): String {
        val q = String.format(Locale.US, "%.2f", band.q)
        val f = FreqFormat.withUnit(band.freq)
        return if (band.kind.hasGain()) {
            "${index + 1}  $f  ${GainFormat.format(band.gainDb)}  Q $q"
        } else {
            "${index + 1}  $f  Q $q"
        }
    }

    /**
     * Top-left of a chip of [chipW] x [chipH] px: its centre [offsetPx] above the node centre, below it
     * when the chip would leave the plot at the top, and clamped inside the plot on every side.
     */
    fun position(node: Offset, chipW: Float, chipH: Float, plot: Rect, offsetPx: Float): Offset {
        var cy = node.y - offsetPx
        if (cy - chipH / 2f < plot.top) cy = node.y + offsetPx
        val maxX = maxOf(plot.left, plot.right - chipW)
        val maxY = maxOf(plot.top, plot.bottom - chipH)
        val x = (node.x - chipW / 2f).coerceIn(plot.left, maxX)
        val y = (cy - chipH / 2f).coerceIn(plot.top, maxY)
        return Offset(x, y)
    }
}

/**
 * The readout chip over the plot. Shown while [dragging] names a band (drag and pinch both set it) and
 * fades out 150 ms after release (instant under reduced motion). Hidden from TalkBack: the node's state
 * description announces the values.
 */
@Composable
fun NodeReadoutChip(
    bands: () -> List<Band>,
    dragging: () -> Int?,
    yWindow: () -> Float,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current.density
    val active = dragging()
    var shown by remember { mutableStateOf<Int?>(null) }
    if (active != null) shown = active
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active, reduced) {
        if (active != null) {
            if (reduced) alpha.snapTo(1f) else alpha.animateTo(1f, tween(100))
        } else {
            if (reduced) alpha.snapTo(0f) else alpha.animateTo(0f, tween(150))
            shown = null
        }
    }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var chip by remember { mutableStateOf(IntSize.Zero) }
    val index = shown ?: return
    val band = bands().getOrNull(index) ?: return

    Box(modifier.onSizeChanged { area = it }) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier
                .testTag("node-readout")
                .onSizeChanged { chip = it }
                .offset {
                    val b = bands().getOrNull(index) ?: band
                    val plot = PlotGeometry.plotRect(area.width.toFloat(), area.height.toFloat(), density)
                    val node = ParametricMath.nodePosition(b.freq, b.gainDb, b.kind.hasGain(), plot, yWindow())
                    val p = NodeReadout.position(node, chip.width.toFloat(), chip.height.toFloat(), plot, NodeReadout.OFFSET_DP * density)
                    IntOffset(p.x.roundToInt(), p.y.roundToInt())
                }
                .graphicsLayer { this.alpha = alpha.value }
                .clearAndSetSemantics {},
        ) {
            Text(
                NodeReadout.text(index, band),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum").capScale(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
