package com.dylenq.eq.ui

import kotlin.math.log10

/**
 * Pure axis mapping for the curve plot (UI-SPEC "Curve View: Axes"). The x axis is log10 between
 * 20 Hz and 20 kHz (identical to the macOS app's `Axes::x`); the y axis is linear dB with 0 dB at
 * the vertical centre and one of two windows, 12 or 24 dB either side.
 */
object AxisScale {
    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f

    private val LOG_MIN = log10(MIN_HZ.toDouble())
    private val LOG_SPAN = log10(MAX_HZ.toDouble()) - LOG_MIN

    /** Window grows above this magnitude... */
    const val GROW_ABOVE_DB = 11.0f

    /** ...and shrinks back only below this one (2 dB hysteresis). */
    const val SHRINK_BELOW_DB = 9.0f

    /** 0 at 20 Hz, 1 at 20 kHz, clamped. */
    fun xFraction(hz: Float): Float {
        val t = (log10(hz.toDouble().coerceAtLeast(1e-3)) - LOG_MIN) / LOG_SPAN
        return t.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Rescale rule. [m] is the largest magnitude over the plotted data. While a graphic slider is
     * being dragged the window never changes (it is re-evaluated on drag end).
     */
    fun yWindow(current: Int, m: Float, dragging: Boolean): Int {
        if (dragging) return current
        return when {
            current == 12 && m > GROW_ABOVE_DB -> 24
            current == 24 && m < SHRINK_BELOW_DB -> 12
            else -> current
        }
    }

    fun gridLines(window: Int): List<Int> =
        if (window == 24) listOf(-24, -12, 0, 12, 24) else listOf(-12, -6, 0, 6, 12)

    /** Lines that get a dB label: in the 12 dB window on plots under 200 dp only -12, 0, 12. */
    fun dbLabels(window: Int, plotHeightDp: Float): List<Int> =
        if (window == 12 && plotHeightDp < 200f) listOf(-12, 0, 12) else gridLines(window)

    /** 0 is the top of the plot (+window), 1 the bottom (-window). */
    fun yFraction(db: Float, window: Int): Float = (0.5f - db / (2f * window)).coerceIn(0f, 1f)

    private val ALL_LABELS = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
    private val FEW_LABELS = listOf(100f, 1000f, 10000f)

    /** Ten labels, or three at font scale above 1.3. */
    fun frequencyLabels(fontScale: Float): List<Float> = if (fontScale > 1.3f) FEW_LABELS else ALL_LABELS
}
