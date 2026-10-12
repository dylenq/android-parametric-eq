package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure maths of the Parametric curve interaction (phase 05: L-02 snapping, L-03 pinch, L-04 hit
 * testing, L-08 free frequency). Plain functions over primitives and [Offset]/[Rect] so the JVM tests
 * need no Android. Every output is clamped to the core limits and finite (T-05-03). Deliberately does
 * not reuse the graphic slider snap, which clamps to +-12.
 */
object ParametricMath {
    // Mirrored from the core (pinned by the parity test of plan 01).
    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f
    const val MIN_Q = 0.1f
    const val MAX_Q = 18f
    const val MAX_GAIN_DB = 24f
    const val MAX_BANDS = 16

    const val HIT_RADIUS_DP = 24f
    const val PINCH_CENTROID_RADIUS_DP = 72f
    const val DOUBLE_TAP_MAX_DISTANCE_DP = 48f
    const val NODE_DOUBLE_TAP_EXCLUSION_DP = 28f

    /** Q = Q0 x (distance ratio) ^ exponent; 1.0 is the linear feel of L-03. */
    const val PINCH_EXPONENT = 1.0f

    /** Two candidates this much (px) from equidistant count as a tie. */
    const val TIE_PX = 4f

    /** Add band tries these in order (UI-SPEC "Band List"). */
    val FREE_FREQUENCIES = floatArrayOf(1000f, 250f, 4000f, 100f, 10000f, 60f, 2000f, 8000f)

    private const val ZERO_DETENT_DB = 0.25f
    private val LOG_MIN = log10(MIN_HZ.toDouble())
    private val LOG_SPAN = log10(MAX_HZ.toDouble()) - LOG_MIN
    private val THIRD_OCTAVE = 1.0 / 3.0

    /** Inverse of [AxisScale.xFraction] for a plot x, clamped to 20..20000 Hz. */
    fun hzAt(x: Float, plot: Rect): Float {
        if (!x.isFinite() || plot.width <= 0f) return MIN_HZ
        val t = ((x - plot.left) / plot.width).coerceIn(0f, 1f)
        return 10.0.pow(LOG_MIN + t * LOG_SPAN).toFloat().coerceIn(MIN_HZ, MAX_HZ)
    }

    /** Inverse of the float [AxisScale.yFraction] for a plot y, clamped to the core's +-24 dB. */
    fun dbAt(y: Float, plot: Rect, windowDb: Float): Float {
        if (!y.isFinite() || plot.height <= 0f) return 0f
        val db = (0.5f - (y - plot.top) / plot.height) * 2f * windowDb
        return db.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
    }

    /** Three significant digits (agrees with FreqFormat), inside 20..20000. */
    fun snapHz(hz: Float): Float {
        if (!hz.isFinite()) return MIN_HZ
        val c = hz.coerceIn(MIN_HZ, MAX_HZ)
        return BigDecimal(c.toDouble()).round(MathContext(3)).toFloat().coerceIn(MIN_HZ, MAX_HZ)
    }

    /** 0.1 dB steps with a +-0.25 dB detent on zero, inside +-24. */
    fun snapDb(db: Float): Float {
        if (!db.isFinite()) return 0f
        val c = db.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        if (abs(c) <= ZERO_DETENT_DB) return 0f
        return (c * 10f).roundToInt() / 10f
    }

    /** Node centre in plot pixels. Pass-filter nodes (no gain) sit on the 0 dB line. */
    fun nodePosition(freqHz: Float, gainDb: Float, hasGain: Boolean, plot: Rect, windowDb: Float): Offset {
        val x = plot.left + AxisScale.xFraction(freqHz) * plot.width
        val y = plot.top + AxisScale.yFraction(if (hasGain) gainDb else 0f, windowDb) * plot.height
        return Offset(x, y)
    }

    /** Indices within [radiusPx] of [touch], ascending. */
    fun candidatesWithin(nodesPx: List<Offset>, touch: Offset, radiusPx: Float): List<Int> =
        nodesPx.indices.filter { (nodesPx[it] - touch).getDistance() <= radiusPx }

    /**
     * Nearest centre within the radius. Candidates within [TIE_PX] of the best distance are a tie:
     * the selected one wins, otherwise the higher index (drawn on top).
     */
    fun pickNode(nodesPx: List<Offset>, selected: Int?, touch: Offset, radiusPx: Float): Int? {
        val inside = candidatesWithin(nodesPx, touch, radiusPx)
        if (inside.isEmpty()) return null
        val best = inside.minOf { (nodesPx[it] - touch).getDistance() }
        val tied = inside.filter { (nodesPx[it] - touch).getDistance() <= best + TIE_PX }
        if (selected != null && selected in tied) return selected
        return tied.max()
    }

    /**
     * Tap target. With a stack of two or more candidates and the selected one among them, the next
     * one in index order (wrapping); otherwise the [pickNode] winner.
     */
    fun tapCycleTarget(nodesPx: List<Offset>, selected: Int?, touch: Offset, radiusPx: Float): Int? {
        val stack = candidatesWithin(nodesPx, touch, radiusPx)
        if (stack.isEmpty()) return null
        if (stack.size == 1) return stack[0]
        if (selected != null && selected in stack) return stack[(stack.indexOf(selected) + 1) % stack.size]
        return pickNode(nodesPx, selected, touch, radiusPx)
    }

    /** New Q for a pinch: clamp(q0 x ratio^exponent, 0.1, 18) rounded to 0.01. */
    fun qAfterPinch(q0: Float, startDistance: Float, currentDistance: Float, exponent: Float = PINCH_EXPONENT): Float {
        if (!startDistance.isFinite() || !currentDistance.isFinite() || startDistance <= 0f) return q0
        val ratio = (currentDistance / startDistance).coerceAtLeast(0f)
        val q = q0 * ratio.toDouble().pow(exponent.toDouble()).toFloat()
        if (!q.isFinite()) return q0
        return ((q.coerceIn(MIN_Q, MAX_Q) * 100f).roundToInt() / 100f).coerceIn(MIN_Q, MAX_Q)
    }

    /** Nearest node to the pinch centroid within [radiusPx], else the selected one, else none. */
    fun pinchTarget(nodesPx: List<Offset>, centroid: Offset, selected: Int?, radiusPx: Float): Int? {
        val near = nodesPx.indices
            .filter { (nodesPx[it] - centroid).getDistance() <= radiusPx }
            .minByOrNull { (nodesPx[it] - centroid).getDistance() }
        return near ?: selected
    }

    /** First of [FREE_FREQUENCIES] with no enabled band within a third of an octave; else 1 kHz. */
    fun nextFreeFrequency(bands: List<Pair<Float, Boolean>>): Float {
        for (f in FREE_FREQUENCIES) {
            val taken = bands.any { (bf, enabled) ->
                enabled && bf > 0f && abs(log2(f / bf)) <= THIRD_OCTAVE
            }
            if (!taken) return f
        }
        return 1000f
    }

    /** A second tap close in time and space, and not on a node, adds a band. */
    fun isDoubleTapAdd(
        first: Offset,
        second: Offset,
        dtMillis: Long,
        timeoutMillis: Long,
        nodesPx: List<Offset>,
        density: Float,
    ): Boolean {
        if (dtMillis > timeoutMillis) return false
        if ((second - first).getDistance() > DOUBLE_TAP_MAX_DISTANCE_DP * density) return false
        return nodesPx.none { (it - second).getDistance() <= NODE_DOUBLE_TAP_EXCLUSION_DP * density }
    }
}
