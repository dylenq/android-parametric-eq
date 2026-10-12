package com.dylenq.eq.ui

import androidx.compose.runtime.Immutable

/** One response curve sampled on a log-frequency grid (dB per point). */
@Immutable
class CurvePoints(
    val freqsHz: FloatArray,
    val idealDb: FloatArray,
    val achievedDb: FloatArray,
)

/** Everything the screen renders. Later plans extend this additively. */
@Immutable
data class EqUiState(
    val curve: CurvePoints? = null,
    val curveError: Boolean = false,
)
