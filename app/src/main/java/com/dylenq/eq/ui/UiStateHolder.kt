package com.dylenq.eq.ui

import android.media.AudioManager
import android.media.AudioTrack
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EqException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plain state holder (no ViewModel library). Owns the UI state flow and recomputes the response
 * curve off the main thread whenever [refreshCurve] ticks (ticks conflate).
 */
class UiStateHolder(val runtime: EqRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _ui = MutableStateFlow(EqUiState())
    val ui: StateFlow<EqUiState> = _ui.asStateFlow()

    private val sampleRate: UInt =
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).toUInt()
    private val curveTick = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (isActive) {
                curveTick.receive()
                computeCurve()
            }
        }
        refreshCurve()
    }

    /** Asks for a curve recompute; cheap and safe from any thread. */
    fun refreshCurve() {
        curveTick.trySend(Unit)
    }

    private fun computeCurve() {
        val c = runtime.controller
        try {
            val d = c.responseCurve(CURVE_POINTS.toUInt(), sampleRate, c.snapshot().frameDurationMs)
            val pts = CurvePoints(d.freqsHz.toFloatArray(), d.idealDb.toFloatArray(), d.achievedDb.toFloatArray())
            _ui.update { it.copy(curve = pts, curveError = false) }
        } catch (e: EqException) {
            _ui.update { it.copy(curveError = true) }
        }
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        const val CURVE_POINTS = 256
    }
}
