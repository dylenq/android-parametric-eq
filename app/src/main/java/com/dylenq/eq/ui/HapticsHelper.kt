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
