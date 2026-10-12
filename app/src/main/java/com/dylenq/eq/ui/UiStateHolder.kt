package com.dylenq.eq.ui

import android.media.AudioManager
import android.media.AudioTrack
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EqException
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.InternalException
import com.dylenq.eq.core.Snapshot
import com.dylenq.eq.service.ServiceStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plain state holder (no ViewModel library). It never touches the core directly: every write goes
 * through [com.dylenq.eq.EqController]. The core has no change listener, so after each own edit the
 * holder re-reads the snapshot; bypass, sessions, notice and serviceRunning follow the runtime
 * status flow. The curve (and headroom) is recomputed on a conflated background worker.
 */
class UiStateHolder(val runtime: EqRuntime) {
    private val controller = runtime.controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val sampleRate: UInt =
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).toUInt()
    private val curveTick = Channel<Unit>(Channel.CONFLATED)

    private val _ui = MutableStateFlow(
        fromSnapshot(EqUiState(), controller.snapshot(), runtime.status.value),
    )
    val ui: StateFlow<EqUiState> = _ui.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-shot error events ("Couldn't apply that change."); the UI shows a snackbar. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Number of curve computations performed (tests check conflation). */
    internal val curveComputations = AtomicInteger()

    private var dragOpen = false
    private var preampDragOpen = false

    init {
        scope.launch {
            while (isActive) {
                curveTick.receive()
                computeCurve()
            }
        }
        scope.launch {
            runtime.status.collect { st ->
                synchronized(lock) { republishLocked(st) }
                refreshCurve()
            }
        }
        refreshCurve()
    }

    /** Asks for a curve recompute; cheap and safe from any thread. */
    fun refreshCurve() {
        curveTick.trySend(Unit)
    }

    // ---- actions -------------------------------------------------------------------------

    fun onBandDragStart(index: Int) = guarded {
        if (!dragOpen) {
            controller.beginEdit()
            dragOpen = true
        }
        _ui.update { it.copy(draggingBand = index, lastTouchedBand = index) }
    }

    /** Clamps to the core limits; snapping to 0.5 dB and the zero detent belong to the slider. */
    fun onBandDragChange(index: Int, db: Float) = guarded {
        val v = db.coerceIn(-GRAPHIC_LIMIT_DB, GRAPHIC_LIMIT_DB)
        controller.edit { it.setGraphicGain(index.toUInt(), v) }
        _ui.update { s ->
            if (index in s.graphicGainsDb.indices) {
                s.copy(graphicGainsDb = s.graphicGainsDb.toMutableList().also { it[index] = v }, lastTouchedBand = index)
            } else {
                s
            }
        }
        refreshCurve()
    }

    fun onBandDragEnd() {
        try {
            synchronized(lock) {
                if (dragOpen) {
                    dragOpen = false
                    controller.endEdit()
                }
            }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            fail()
        } finally {
            _ui.update { it.copy(draggingBand = null) }
            republish()
            refreshCurve()
        }
    }

    fun resetBand(index: Int) = guarded {
        controller.edit { it.setGraphicGain(index.toUInt(), 0f) }
        _ui.update { it.copy(lastTouchedBand = index) }
        republishLocked()
    }

    fun setBandCount(count: Int) = guarded {
        controller.edit { it.setGraphicBandCount(count.toUInt()) }
        _ui.update { it.copy(lastTouchedBand = null) }
        republishLocked()
    }

    fun setMode(mode: EqMode) = guarded {
        controller.edit { it.setMode(mode) }
        republishLocked()
    }

    fun setBypass(on: Boolean) = guarded {
        controller.setBypass(on)
    }

    fun setPreamp(db: Float) = guarded {
        val v = db.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
        controller.edit { it.setPreamp(v) }
        _ui.update { it.copy(preampDb = v) }
        refreshCurve()
    }

    fun preampDragStart() = guarded {
        if (!preampDragOpen) {
            controller.beginEdit()
            preampDragOpen = true
        }
    }

    fun preampDragEnd() {
        try {
            synchronized(lock) {
                if (preampDragOpen) {
                    preampDragOpen = false
                    controller.endEdit()
                }
            }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            fail()
        } finally {
            republish()
            refreshCurve()
        }
    }

    fun resetPreamp() = setPreamp(0f)

    fun applySuggestedPreamp() = guarded {
        val s = _ui.value.suggestedPreampDb.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
        controller.edit { it.setPreamp(s) }
        republishLocked()
    }

    fun setLimiter(on: Boolean) = guarded {
        controller.edit { it.setLimiterEnabled(on) }
        republishLocked()
    }

    /** Zeroes every graphic gain of the current band count and the preamp, as one undo step. */
    fun resetAll() = guarded {
        val s = _ui.value
        if (s.graphicGainsDb.all { it == 0f } && s.preampDb == 0f) return@guarded
        controller.beginEdit()
        try {
            controller.edit { c ->
                for (i in s.graphicGainsDb.indices) c.setGraphicGain(i.toUInt(), 0f)
                c.setPreamp(0f)
            }
        } finally {
            controller.endEdit()
        }
        _ui.update { it.copy(lastTouchedBand = null) }
        republishLocked()
    }

    fun undo() = guarded {
        controller.edit { it.undo() }
        republishLocked()
    }

    fun redo() = guarded {
        controller.edit { it.redo() }
        republishLocked()
    }

    fun dismissNotice() {
        runtime.dismissNotice()
    }

    fun close() {
        scope.cancel()
    }

    // ---- internals -----------------------------------------------------------------------

    /** Runs a core write under the lock; a core error becomes one error event plus a re-read. */
    private inline fun guarded(block: () -> Unit) {
        try {
            synchronized(lock) { block() }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            fail()
        }
    }

    private fun isCoreFailure(e: Exception) =
        e is EqException || e is IllegalArgumentException || e is ArithmeticException || e is InternalException

    private fun fail() {
        _errors.tryEmit("Couldn't apply that change.")
        republish()
        refreshCurve()
    }

    private fun republish() {
        synchronized(lock) { republishLocked() }
    }

    /** Re-reads the core snapshot (callers hold [lock]); status fields come from the runtime. */
    private fun republishLocked(st: ServiceStatus = runtime.status.value) {
        val snap = controller.snapshot()
        _ui.update { fromSnapshot(it, snap, st) }
        refreshCurve()
    }

    private fun computeCurve() {
        curveComputations.incrementAndGet()
        try {
            val snap = controller.snapshot()
            val d = controller.responseCurve(CURVE_POINTS.toUInt(), sampleRate, snap.frameDurationMs)
            val ep = controller.engineParams(sampleRate, snap.frameDurationMs)
            val pts = CurvePoints(d.freqsHz.toFloatArray(), d.idealDb.toFloatArray(), d.achievedDb.toFloatArray())
            _ui.update {
                it.copy(
                    curve = pts,
                    curveError = false,
                    headroomAfterPreampDb = ep.headroomAfterPreampDb,
                    suggestedPreampDb = ep.suggestedPreampDb,
                )
            }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            _ui.update { it.copy(curveError = true) }
        }
    }

    companion object {
        const val CURVE_POINTS = 256
        const val PREAMP_MIN_DB = -24f
        const val PREAMP_MAX_DB = 6f
        private const val GRAPHIC_LIMIT_DB = 24f

        private fun fromSnapshot(base: EqUiState, snap: Snapshot, st: ServiceStatus): EqUiState {
            val countChanged = base.graphicBandCount != snap.graphicBandCount.toInt()
            return base.copy(
                mode = snap.mode,
                graphicBandCount = snap.graphicBandCount.toInt(),
                graphicGainsDb = snap.graphicGainsDb.toList(),
                graphicCentresHz = snap.graphicCentresHz.toList(),
                parametricEnabledCount = snap.bands.count { it.enabled },
                preampDb = snap.preampDb,
                limiterEnabled = snap.limiterEnabled,
                canUndo = snap.canUndo,
                canRedo = snap.canRedo,
                bypass = st.bypass,
                serviceRunning = st.serviceRunning,
                sessionCount = st.sessionCount,
                globalMode = st.globalMode,
                notice = st.notice,
                lastTouchedBand = if (countChanged) null else base.lastTouchedBand,
            )
        }
    }
}
