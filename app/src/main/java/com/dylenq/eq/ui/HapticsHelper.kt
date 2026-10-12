package com.dylenq.eq.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Haptic mapping of UI-SPEC "Graphic Mode: Sliders" over [LocalHapticFeedback], which honours the
 * system touch-feedback setting. Every constant used exists in Compose 1.10; only
 * `SegmentFrequentTick` needs the API 34 fallback to `TextHandleMove`.
 */
@Stable
class Haptics(private val feedback: HapticFeedback) {
    fun dragStart() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    fun tick() = feedback.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackType.SegmentFrequentTick else HapticFeedbackType.TextHandleMove,
    )

    fun zeroDetent() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    fun dragEnd() = feedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    /** Frequency crossing a plot grid line (UI-SPEC Motion and Haptics). */
    fun gridTick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    fun reset() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** Haptics for a change from [old] to [new]: whole-dB ticks, or the zero detent. */
    fun onChange(old: Float, new: Float) {
        if (SliderMath.enteredZero(old, new)) {
            zeroDetent()
        } else if (SliderMath.wholeDbCrossings(old, new) > 0) {
            tick()
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val f = LocalHapticFeedback.current
    return remember(f) { Haptics(f) }
}

/** The plot's vertical grid frequencies (20, 50, 100 ... 20k): a drag crossing one ticks. */
object FreqGridCrossing {
    val GRID_HZ = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

    /** True when moving from [oldHz] to [newHz] passes a grid frequency. */
    fun crossed(oldHz: Float, newHz: Float): Boolean = GRID_HZ.any { (oldHz < it) != (newHz < it) }
}

/** Allows one tick per [minIntervalMs] across every tick source; [clock] is injectable for tests. */
class TickLimiter(private val minIntervalMs: Long, private val clock: () -> Long) {
    private var last = Long.MIN_VALUE

    fun tryTick(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < minIntervalMs) return false
        last = now
        return true
    }
}
