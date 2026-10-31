package com.dylenq.eq.ui

import androidx.compose.runtime.Immutable
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.PresetInfo

/** One response curve sampled on a log-frequency grid (dB per point). */
@Immutable
class CurvePoints(
    val freqsHz: FloatArray,
    val idealDb: FloatArray,
    val achievedDb: FloatArray,
    /** The selected enabled band's own response (dB) on the same grid, or null (Parametric only). */
    val selectedBandDb: FloatArray? = null,
)

/** A one-shot message for the screen's snackbar (for example the 16-band limit). */
data class UiMessage(val text: String, val undoable: Boolean = false)

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
    /** Every parametric band in index order (enabled or not). */
    val bands: List<Band> = emptyList(),
    /** The selected parametric band, or null; cleared on leaving Parametric, never out of range. */
    val selectedBand: Int? = null,
    /** True while any gesture (graphic slider, preamp or parametric) holds an undo group open. */
    val gestureOpen: Boolean = false,
    val preampDb: Float = 0f,
    /** Tone and balance mirror the core snapshot; balance is the core value -1..1. */
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val balance: Float = 0f,
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

    /** Built-ins first, then user presets in saved order. */
    val presets: List<PresetInfo> = emptyList(),
    val activePresetKey: String? = null,
    val activePresetName: String? = null,
    /** True when the active preset's curve no longer matches the live sound (the Edited marker). */
    val presetModified: Boolean = false,

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
