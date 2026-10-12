package com.dylenq.eq.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dylenq.eq.core.EqMode
import kotlin.math.abs

internal val PlotInsetLeft = 36.dp
internal val PlotInsetBottom = 20.dp
internal val PlotInsetTop = 8.dp
internal val PlotInsetRight = 8.dp

/** Largest magnitude of the plotted data: input to the Y-window rescale rule. */
internal fun plottedMagnitude(s: EqUiState): Float {
    val c = s.curve ?: return 0f
    var m = 0f
    for (i in c.achievedDb.indices) {
        m = maxOf(m, abs(c.achievedDb[i]), abs(c.idealDb[i]))
    }
    if (!s.bypass && CurveStats.isOver(s.headroomAfterPreampDb)) m = maxOf(m, abs(s.preampDb))
    return m
}

/**
 * The plot: background, grid, axis labels, fill, Target, Actual, clip level and band markers
 * (UI-SPEC "Curve View"). [state] and [yWindow] are read only in the draw phase, so slider drags
 * redraw without recomposing. Text is measured once per density/style in `remember`.
 *
 * [labelWindow] is the target Y window (12 or 24) whose grid and labels are drawn; [yWindow] is
 * its animated float value that positions everything.
 */
@Composable
fun CurveCanvas(
    state: () -> EqUiState,
    yWindow: () -> Float,
    labelWindow: Int,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val fontScale = LocalDensity.current.fontScale
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = cs.onSurfaceVariant,
        fontFeatureSettings = "tnum",
    ).capScale()
    val freqLabels: List<Pair<Float, TextLayoutResult>> = remember(measurer, labelStyle, fontScale) {
        AxisScale.frequencyLabels(fontScale).map { it to measurer.measure(FreqFormat.format(it), labelStyle) }
    }
    val dbLabels: Map<Int, TextLayoutResult> = remember(measurer, labelStyle) {
        listOf(-24, -12, -6, 0, 6, 12, 24).associateWith {
            measurer.measure(if (it > 0) "+$it" else "$it", labelStyle)
        }
    }

    val primary = cs.primary
    val error = cs.error
    val plotBg = cs.surfaceContainerLowest
    val gridColor = cs.outlineVariant
    val zeroColor = cs.outline
    val targetColor = cs.onSurfaceVariant
    val surface = cs.surface
    val bypassColor = cs.outline.copy(alpha = 0.45f)
    val fillColor = primary.copy(alpha = if (dark) 0.18f else 0.12f)
    val gridFreqs = AxisScale.frequencyLabels(1.0f)

    Spacer(
        modifier.drawWithCache {
            val l = PlotInsetLeft.toPx()
            val t = PlotInsetTop.toPx()
            val plot = Rect(l, t, size.width - PlotInsetRight.toPx(), size.height - PlotInsetBottom.toPx())
            val radius = CornerRadius(12.dp.toPx())
            val plotClip = Path().apply { addRoundRect(RoundRect(plot, radius)) }
            val actual = Path()
            val fill = Path()
            val target = Path()
            val seg = Path()
            val dashTarget = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            val dashClip = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            val hair = 1.dp.toPx()
            val plotHeightDp = plot.height / density
            // Labels that would touch a neighbour (10k against 20k at 360 dp) are dropped, not overlapped.
            val labelXs = AxisScale.frequencyLabelXs(
                freqLabels.map { it.first },
                freqLabels.map { it.second.size.width.toFloat() },
                plot.left, plot.right, 4.dp.toPx(),
            )

            onDrawBehind {
                val s = state()
                val w = yWindow()
                fun px(hz: Float) = plot.left + AxisScale.xFraction(hz) * plot.width
                fun py(db: Float) = plot.top + (0.5f - db.coerceIn(-w * 1.5f, w * 1.5f) / (2f * w)) * plot.height

                drawRoundRect(plotBg, plot.topLeft, plot.size, radius)
                clipPath(plotClip) {
                    for (f in gridFreqs) drawLine(gridColor, Offset(px(f), plot.top), Offset(px(f), plot.bottom), hair)
                    for (db in AxisScale.gridLines(labelWindow)) {
                        if (db != 0) drawLine(gridColor, Offset(plot.left, py(db.toFloat())), Offset(plot.right, py(db.toFloat())), hair)
                    }
                    drawLine(zeroColor, Offset(plot.left, py(0f)), Offset(plot.right, py(0f)), hair)

                    if (!s.curveError) {
                        drawCurves(s, ::px, ::py, plot, actual, fill, target, seg, dashTarget, dashClip) { kind ->
                            when (kind) {
                                Kind.PLOT_FILL -> fillColor
                                Kind.TARGET -> targetColor
                                Kind.ACTUAL -> primary
                                Kind.ERROR -> error
                                Kind.BYPASS -> bypassColor
                                Kind.RING -> surface
                            }
                        }
                    }
                }

                for (db in AxisScale.dbLabels(labelWindow, plotHeightDp)) {
                    val layout = dbLabels[db] ?: continue
                    drawText(
                        layout,
                        topLeft = Offset(plot.left - 4.dp.toPx() - layout.size.width, py(db.toFloat()) - layout.size.height / 2f),
                    )
                }
                for (i in freqLabels.indices) {
                    val x = labelXs[i] ?: continue
                    drawText(freqLabels[i].second, topLeft = Offset(x, plot.bottom + 4.dp.toPx()))
                }
            }
        },
    )
}

private enum class Kind { PLOT_FILL, TARGET, ACTUAL, ERROR, BYPASS, RING }

private fun DrawScope.drawCurves(
    s: EqUiState,
    px: (Float) -> Float,
    py: (Float) -> Float,
    plot: Rect,
    actual: Path,
    fill: Path,
    target: Path,
    seg: Path,
    dashTarget: PathEffect,
    dashClip: PathEffect,
    color: (Kind) -> Color,
) {
    val bypass = s.bypass
    val curve = s.curve
    val zeroY = py(0f)
    if (curve == null) {
        // First frame: a flat curve on the 0 dB line.
        drawLine(color(if (bypass) Kind.BYPASS else Kind.ACTUAL), Offset(plot.left, zeroY), Offset(plot.right, zeroY), 2.5.dp.toPx(), StrokeCap.Round)
        return
    }
    val n = curve.freqsHz.size
    val xs = FloatArray(n) { px(curve.freqsHz[it]) }

    actual.reset()
    for (i in 0 until n) {
        val y = py(curve.achievedDb[i])
        if (i == 0) actual.moveTo(xs[i], y) else actual.lineTo(xs[i], y)
    }

    if (!bypass) {
        fill.reset()
        fill.moveTo(xs[0], zeroY)
        for (i in 0 until n) fill.lineTo(xs[i], py(curve.achievedDb[i]))
        fill.lineTo(xs[n - 1], zeroY)
        fill.close()
        drawPath(fill, color(Kind.PLOT_FILL))
    }

    var maxDiff = 0f
    for (i in 0 until n) maxDiff = maxOf(maxDiff, abs(curve.idealDb[i] - curve.achievedDb[i]))
    if (CurveStats.targetVisible(maxDiff)) {
        target.reset()
        for (i in 0 until n) {
            val y = py(curve.idealDb[i])
            if (i == 0) target.moveTo(xs[i], y) else target.lineTo(xs[i], y)
        }
        drawPath(target, color(if (bypass) Kind.BYPASS else Kind.TARGET), style = Stroke(1.5.dp.toPx(), pathEffect = dashTarget))
    }

    val over = !bypass && CurveStats.isOver(s.headroomAfterPreampDb)
    val lineWidth = if (bypass) 1.5.dp.toPx() else 2.5.dp.toPx()
    drawPath(
        actual, color(if (bypass) Kind.BYPASS else Kind.ACTUAL),
        style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    if (over) {
        val clipY = py(-s.preampDb)
        drawLine(color(Kind.ERROR), Offset(plot.left, clipY), Offset(plot.right, clipY), 1.dp.toPx(), pathEffect = dashClip)
        for (r in CurveStats.clipSegments(curve.achievedDb, s.preampDb)) {
            seg.reset()
            val from = maxOf(r.first - 1, 0)
            val to = minOf(r.last + 1, n - 1)
            for (i in from..to) {
                val y = py(curve.achievedDb[i])
                if (i == from) seg.moveTo(xs[i], y) else seg.lineTo(xs[i], y)
            }
            drawPath(seg, color(Kind.ERROR), style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // Band markers: Graphic mode only (Parametric is read-only in Phase 4).
    if (s.mode == EqMode.GRAPHIC && s.graphicCentresHz.size == s.graphicGainsDb.size) {
        val small = s.graphicBandCount >= 31
        val dot = color(if (bypass) Kind.BYPASS else Kind.ACTUAL)
        for (i in s.graphicCentresHz.indices) {
            val c = Offset(px(s.graphicCentresHz[i]), py(s.graphicGainsDb[i]))
            if (s.draggingBand == i) {
                drawLine(dot.copy(alpha = if (bypass) dot.alpha else 0.4f), Offset(c.x, plot.top), Offset(c.x, plot.bottom), 1.dp.toPx())
                drawCircle(color(Kind.RING), 7.dp.toPx(), c)
                drawCircle(dot, 6.dp.toPx(), c)
            } else {
                val r = (if (small) 2 else 4).dp.toPx()
                drawCircle(color(Kind.RING), r + 1.dp.toPx(), c)
                drawCircle(dot, r, c)
            }
        }
    }
}
