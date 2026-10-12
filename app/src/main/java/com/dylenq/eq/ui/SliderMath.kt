package com.dylenq.eq.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Pure maths for the graphic gain slider and its panel (UI-SPEC "Graphic Mode: Sliders"). */
object SliderMath {
    const val MIN_DB = -12f
    const val MAX_DB = 12f
    const val STEP_DB = 0.5f
    const val DETENT_DB = 0.25f

    /** Number of discrete stops between the ends, as `progressBarRangeInfo` counts them: 24 / 0.5 - 1. */
    const val STEPS = ((MAX_DB - MIN_DB) / STEP_DB).toInt() - 1

    /** Smallest column pitch when the row scrolls. */
    const val MIN_PITCH_DP = 32f

    /** Smallest pitch at which up to [FIT_MAX_COUNT] bands are squeezed to fit instead of scrolling. */
    const val FIT_MIN_PITCH_DP = 28f
    const val FIT_MAX_COUNT = 10
    const val LARGE_FONT_PITCH_DP = 48f
    const val LARGE_FONT_SCALE = 1.3f

    /** Track fraction (0 bottom, 1 top) to a snapped gain; within the detent of 0 it is exactly 0. */
    fun gainFromFraction(fraction: Float): Float = snap(MIN_DB + fraction.coerceIn(0f, 1f) * (MAX_DB - MIN_DB))

    /** Snaps to [STEP_DB], clamps to the slider range, and returns exactly 0 within (inclusive) [DETENT_DB] of 0. */
    fun snap(db: Float): Float {
        if (abs(db) <= DETENT_DB) return 0f
        val s = (db / STEP_DB).roundToInt() * STEP_DB
        return s.coerceIn(MIN_DB, MAX_DB).let { if (it == 0f) 0f else it }
    }

    /** Thumb fraction for a stored gain; a gain beyond the range pins at an end. */
    fun fractionFromGain(db: Float): Float = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)

    /** How many whole-dB boundaries lie between [old] and [new] (haptic ticks). */
    fun wholeDbCrossings(old: Float, new: Float): Int = abs(floor(new).toInt() - floor(old).toInt())

    /** True when the value has just arrived at exactly zero (detent haptic). */
    fun enteredZero(old: Float, new: Float): Boolean = new == 0f && old != 0f

    /**
     * Column pitch in dp for a row [widthDp] wide. At the large font scale it is at least 48. Otherwise
     * the even share when that is at least 32; up to 10 bands may use a share down to 28 so they fit
     * (the spec's "10 bands fit at 360 dp" cannot be met at 32 once the dB scale column is paid for);
     * anything narrower is 32 and scrolls.
     */
    fun columnPitch(widthDp: Float, bandCount: Int, fontScale: Float): Float {
        val even = widthDp / bandCount
        if (fontScale >= LARGE_FONT_SCALE) return maxOf(LARGE_FONT_PITCH_DP, even)
        if (even >= MIN_PITCH_DP) return even
        if (bandCount <= FIT_MAX_COUNT && even >= FIT_MIN_PITCH_DP) return even
        return MIN_PITCH_DP
    }

    fun fitsWithoutScroll(pitchDp: Float, bandCount: Int, widthDp: Float): Boolean =
        pitchDp * bandCount <= widthDp + 0.01f

    /** Per-slider gain labels appear only at a wide pitch. */
    fun showsGainLabel(pitchDp: Float): Boolean = pitchDp >= 44f
}
