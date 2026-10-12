package com.dylenq.eq.ui

import androidx.compose.runtime.Immutable
import com.dylenq.eq.core.EqMode

/** One response curve sampled on a log-frequency grid (dB per point). */
@Immutable
class CurvePoints(
    val freqsHz: FloatArray,
    val idealDb: FloatArray,
    val achievedDb: FloatArray,
)

/** Everything the screen renders. Immutable; the holder publishes a fresh copy per change. */
@Immutable
data class EqUiState(
    val curve: CurvePoints? = null,
    val curveError: Boolean = false,

    val mode: EqMode = EqMode.GRAPHIC,
    val graphicBandCount: Int = 10,
    val graphicGainsDb: List<Float> = List(10) { 0f },
    val graphicCentresHz: List<Float> = emptyList(),
    /** Enabled parametric bands (Phase 4 shows the placeholder text based on this). */
    val parametricEnabledCount: Int = 0,
    val preampDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    /** Single source: the runtime status flow (shared with the notification action). */
    val bypass: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,

    /** From the engine params of the latest curve computation. */
    val headroomAfterPreampDb: Float = 0f,
    val suggestedPreampDb: Float = 0f,

    /** The graphic slider being dragged (freezes the Y window), or null. */
    val draggingBand: Int? = null,
    /** The band shown in the ReadoutBar; cleared when the band count changes. */
    val lastTouchedBand: Int? = null,

    val serviceRunning: Boolean = false,
    val sessionCount: Int = 0,
    val globalMode: Boolean = false,
    val notice: String? = null,
) {
    /** True when at least one parametric band is enabled (the placeholder copy depends on it). */
    val hasEnabledParametricBands: Boolean
        get() = parametricEnabledCount > 0

    /** Gain of [lastTouchedBand], if any. */
    val lastTouchedGainDb: Float?
        get() = lastTouchedBand?.let { graphicGainsDb.getOrNull(it) }
}
