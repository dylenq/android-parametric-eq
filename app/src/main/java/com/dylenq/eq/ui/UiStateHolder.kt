package com.dylenq.eq.ui

import android.media.AudioManager
import android.media.AudioTrack
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqException
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.core.InternalException
import com.dylenq.eq.core.Snapshot
import com.dylenq.eq.service.ServiceStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.math.roundToInt
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
 *
 * Undo history is one linear stack across Graphic and Parametric (L-07): undo/redo can step through
 * an edit made in the other mode and nothing skips invisible steps. That is a documented v1
 * limitation (UNVERIFIED known-limitations entry, written by plan 12). Undo, redo and mode
 * switching are inert while a gesture is open.
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

    /** Rust core version for the Settings About row. */
    fun coreVersion(): String = controller.coreVersion()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-shot error events ("Couldn't apply that change."); the UI shows a snackbar. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-shot informational messages (for example the 16-band limit); the screen shows a snackbar. */
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val _editSerial = MutableStateFlow(0)

    /**
     * Counts user edits (every graphic/preamp/bass/treble/balance/mode/band-count/limiter/bypass change, reset, undo and redo).
     * The Reset all snackbar watches it so its Undo can never undo a later, unrelated edit.
     * A preset apply is an undo step and bumps it; preset save, save-as, rename and delete are not
     * undo steps and do not, so a visible "Applied" Undo snackbar survives them.
     */
    val editSerial: StateFlow<Int> = _editSerial.asStateFlow()

    private fun edited() {
        _editSerial.update { it + 1 }
    }

    /** Emits [text], stamping the current edit serial on it. Call under [lock], after [edited] for undoable ones. */
    private fun emit(text: String, undoable: Boolean = false) {
        val m = UiMessage(text, undoable)
        m.serial = _editSerial.value
        _messages.tryEmit(m)
    }

    /** Number of curve computations performed (tests check conflation). */
    internal val curveComputations = AtomicInteger()

    // One counter for every control that holds an undo group open (graphic slider, preamp,
    // bass, treble, balance, parametric gesture): begin_edit on 0->1, end_edit on 1->0, so two fingers on two controls still
    // make one step. Each source has its own flag so a double start or an unmatched end is harmless.
    // Guarded by [lock].
    private var gestureDepth = 0
    private enum class Source { GRAPHIC, PREAMP, PARAMETRIC, ADD, FIELD, BASS, TREBLE, BALANCE }
    private val openSources = java.util.EnumSet.noneOf(Source::class.java)

    /** Opens [source]'s share of the group; a second start from the same source is ignored. */
    private fun beginGesture(source: Source) {
        if (source in openSources) return
        if (gestureDepth == 0) controller.beginEdit()
        openSources.add(source)
        gestureDepth++
        _ui.update { it.copy(gestureOpen = true) }
    }

    /** Closes [source]'s share; an end without a start is ignored. */
    private fun endGesture(source: Source) {
        if (!openSources.remove(source) || gestureDepth == 0) return
        gestureDepth--
        if (gestureDepth == 0) {
            try {
                controller.endEdit()
            } finally {
                _ui.update { it.copy(gestureOpen = false) }
            }
        }
    }

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
        beginGesture(Source.GRAPHIC)
        _ui.update { it.copy(draggingBand = index, lastTouchedBand = index) }
    }

    /** Clamps to the core limits; snapping to 0.5 dB and the zero detent belong to the slider. */
    fun onBandDragChange(index: Int, db: Float) = guarded {
        edited()
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
                endGesture(Source.GRAPHIC)
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

    // ---- parametric ----------------------------------------------------------------------

    fun selectBand(index: Int?) = guarded {
        _ui.update { s ->
            // Out-of-range indices are ignored; null deselects.
            if (index == null || index in s.bands.indices) s.copy(selectedBand = index) else s
        }
        refreshCurve()
    }

    fun parametricGestureStart(index: Int) = guarded {
        beginGesture(Source.PARAMETRIC)
        _ui.update { it.copy(draggingBand = index, selectedBand = index) }
    }

    /** [gainDb] null keeps the band's gain (pass filters have none). Values arrive snapped and clamped. */
    fun parametricNodeChange(index: Int, freqHz: Float, gainDb: Float?) = guarded {
        val band = _ui.value.bands.getOrNull(index) ?: return@guarded
        edited()
        val next = band.copy(freq = freqHz, gainDb = gainDb ?: band.gainDb)
        controller.edit { it.setBand(index.toUInt(), next) }
        _ui.update { s ->
            if (index in s.bands.indices) {
                s.copy(bands = s.bands.toMutableList().also { it[index] = next })
            } else {
                s
            }
        }
        refreshCurve()
    }

    /** Pinch: sets only the Q of band [index]; frequency, gain, kind and enabled stay. Inside a gesture. */
    fun setBandQ(index: Int, q: Float) = guarded {
        if (!q.isFinite()) return@guarded
        val band = _ui.value.bands.getOrNull(index) ?: return@guarded
        edited()
        val next = band.copy(q = q.coerceIn(ParametricMath.MIN_Q, ParametricMath.MAX_Q))
        controller.edit { it.setBand(index.toUInt(), next) }
        _ui.update { s ->
            if (index in s.bands.indices) {
                s.copy(bands = s.bands.toMutableList().also { it[index] = next })
            } else {
                s
            }
        }
        refreshCurve()
    }

    /**
     * Double-tap add: a Peak, Q 1.0, enabled, at the (snapped) tapped frequency and gain, selected.
     * One undo step, composed with any open gesture through the shared counter. At 16 bands nothing is
     * added and [messages] gets the limit message.
     */
    fun addBandAt(freqHz: Float, gainDb: Float) = guarded {
        if (!freqHz.isFinite() || !gainDb.isFinite()) return@guarded
        if (controller.snapshot().bands.size >= ParametricMath.MAX_BANDS) {
            emit("All 16 bands are in use. Remove one to add another.")
            return@guarded
        }
        edited()
        var index = -1
        beginGesture(Source.ADD)
        try {
            controller.edit { c ->
                val i = c.addBand().toInt()
                index = i
                c.setBand(
                    i.toUInt(),
                    Band(FilterKind.PEAK, ParametricMath.snapHz(freqHz), ParametricMath.snapDb(gainDb), 1.0f, true),
                )
            }
        } finally {
            endGesture(Source.ADD)
        }
        republishLocked()
        if (index >= 0) _ui.update { s -> if (index in s.bands.indices) s.copy(selectedBand = index) else s }
    }

    fun parametricGestureEnd() {
        try {
            synchronized(lock) {
                endGesture(Source.PARAMETRIC)
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
        edited()
        controller.edit { it.setGraphicGain(index.toUInt(), 0f) }
        _ui.update { it.copy(lastTouchedBand = index) }
        republishLocked()
    }

    fun setBandCount(count: Int) = guarded {
        edited()
        controller.edit { it.setGraphicBandCount(count.toUInt()) }
        _ui.update { it.copy(lastTouchedBand = null) }
        republishLocked()
    }

    fun setMode(mode: EqMode) = guarded {
        if (gestureDepth > 0) return@guarded
        edited()
        controller.edit { it.setMode(mode) }
        republishLocked()
    }

    fun setBypass(on: Boolean) = guarded {
        edited()
        controller.setBypass(on)
    }

    fun setPreamp(db: Float) = guarded {
        edited()
        val v = db.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
        controller.edit { it.setPreamp(v) }
        _ui.update { it.copy(preampDb = v) }
        if (gestureDepth == 0) republishLocked() else refreshCurve()
    }

    fun preampDragStart() = guarded {
        beginGesture(Source.PREAMP)
    }

    fun preampDragEnd() {
        try {
            synchronized(lock) {
                endGesture(Source.PREAMP)
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

    // ---- tone and balance (each control has its own gesture source) ---------------------------

    fun setBass(db: Float) = guarded {
        if (!db.isFinite()) return@guarded
        edited()
        val v = db.coerceIn(SliderMath.MIN_DB, SliderMath.MAX_DB)
        controller.edit { it.setBass(v) }
        _ui.update { it.copy(bassDb = v) }
        if (gestureDepth == 0) republishLocked() else refreshCurve()
    }

    fun bassDragStart() = guarded { beginGesture(Source.BASS) }

    fun bassDragEnd() = endToneGesture(Source.BASS)

    /** At the default this is a no-op (no edit, no undo step); otherwise one undo step back to 0. */
    fun resetBass() {
        if (_ui.value.bassDb == 0f) return
        setBass(0f)
    }

    fun setTreble(db: Float) = guarded {
        if (!db.isFinite()) return@guarded
        edited()
        val v = db.coerceIn(SliderMath.MIN_DB, SliderMath.MAX_DB)
        controller.edit { it.setTreble(v) }
        _ui.update { it.copy(trebleDb = v) }
        if (gestureDepth == 0) republishLocked() else refreshCurve()
    }

    fun trebleDragStart() = guarded { beginGesture(Source.TREBLE) }

    fun trebleDragEnd() = endToneGesture(Source.TREBLE)

    fun resetTreble() {
        if (_ui.value.trebleDb == 0f) return
        setTreble(0f)
    }

    /** [percent] is whole percent -100..100 (negative = left); the core gets percent / 100. */
    fun setBalance(percent: Int) = guarded {
        edited()
        val v = BalanceMath.toCore(percent)
        controller.edit { it.setBalance(v) }
        _ui.update { it.copy(balance = v) }
        if (gestureDepth == 0) republishLocked()
    }

    fun balanceDragStart() = guarded { beginGesture(Source.BALANCE) }

    fun balanceDragEnd() = endToneGesture(Source.BALANCE)

    fun resetBalance() {
        if (_ui.value.balance == 0f) return
        setBalance(0)
    }

    private fun endToneGesture(source: Source) {
        try {
            synchronized(lock) {
                endGesture(source)
            }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            fail()
        } finally {
            republish()
            refreshCurve()
        }
    }

    fun applySuggestedPreamp() = guarded {
        edited()
        val s = _ui.value.suggestedPreampDb.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
        controller.edit { it.setPreamp(s) }
        republishLocked()
    }

    fun setLimiter(on: Boolean) = guarded {
        edited()
        controller.edit { it.setLimiterEnabled(on) }
        republishLocked()
    }

    // ---- parametric band actions (shared by the menu, the band list and the TalkBack actions) ----

    /**
     * Runs [block] as one undo step, composed with any open gesture through the shared counter.
     * Index parameters of the callers are validated against the current band list first (T-05-10).
     */
    private inline fun step(block: () -> Unit) {
        beginGesture(Source.ADD)
        try {
            block()
        } finally {
            endGesture(Source.ADD)
        }
    }

    private fun bandAt(index: Int): Band? = controller.snapshot().bands.getOrNull(index)

    private fun writeBand(index: Int, next: Band) {
        edited()
        step { controller.edit { it.setBand(index.toUInt(), next) } }
        republishLocked()
    }

    /** Changes the filter kind only; frequency, Q, stored gain and enabled stay. */
    fun setBandKind(index: Int, kind: FilterKind) = guarded {
        val band = bandAt(index) ?: return@guarded
        if (band.kind == kind) return@guarded
        writeBand(index, band.copy(kind = kind))
    }

    fun setBandEnabled(index: Int, on: Boolean) = guarded {
        val band = bandAt(index) ?: return@guarded
        if (band.enabled == on) return@guarded
        writeBand(index, band.copy(enabled = on))
    }

    /** Deletes band [index] ("Band N deleted", undoable). Selection follows the list. */
    fun removeBand(index: Int) = guarded {
        if (bandAt(index) == null) return@guarded
        edited()
        step { controller.edit { it.removeBand(index.toUInt()) } }
        _ui.update { s ->
            val sel = s.selectedBand
            s.copy(selectedBand = when {
                sel == null -> null
                sel == index -> null
                sel > index -> sel - 1
                else -> sel
            })
        }
        republishLocked()
        emit("Band ${index + 1} deleted", undoable = true)
    }

    /** Empties the band list in one undo step ("All bands removed", undoable). */
    fun removeAllBands() = guarded {
        val n = controller.snapshot().bands.size
        if (n == 0) return@guarded
        edited()
        step { controller.edit { c -> repeat(n) { c.removeBand(0u) } } }
        _ui.update { it.copy(selectedBand = null) }
        republishLocked()
        emit("All bands removed", undoable = true)
    }

    /** Band list "Add band": a Peak, Q 1.00, 0 dB at the first free frequency, selected. One undo step. */
    fun addBandFromList() = guarded {
        val bands = controller.snapshot().bands
        if (bands.size >= ParametricMath.MAX_BANDS) {
            emit("All 16 bands are in use. Remove one to add another.")
            return@guarded
        }
        val freq = ParametricMath.nextFreeFrequency(bands.map { it.freq to it.enabled })
        edited()
        var index = -1
        step {
            controller.edit { c ->
                val i = c.addBand().toInt()
                index = i
                c.setBand(i.toUInt(), Band(FilterKind.PEAK, freq, 0f, 1.0f, true))
            }
        }
        republishLocked()
        if (index >= 0) _ui.update { s -> if (index in s.bands.indices) s.copy(selectedBand = index) else s }
    }

    // ---- band list text fields (one undo step per focus session) ----------------------------

    /** First field of a row gained focus: selects the band and opens the session's undo group (idempotent). */
    fun bandFieldFocusGained(index: Int) = guarded {
        if (bandAt(index) == null) return@guarded
        beginGesture(Source.FIELD)
        _ui.update { s -> if (index in s.bands.indices) s.copy(selectedBand = index) else s }
    }

    /**
     * Sets any of frequency, gain and Q of band [index] (null keeps it). Values are clamped to the core
     * limits and ignored when not finite; gain is ignored for pass kinds. Inside a session it joins the
     * session's undo step, outside one it is a step of its own. An unchanged band writes nothing.
     */
    fun setBandFromField(index: Int, freqHz: Float? = null, gainDb: Float? = null, q: Float? = null) = guarded {
        val band = bandAt(index) ?: return@guarded
        if (freqHz?.isFinite() == false || gainDb?.isFinite() == false || q?.isFinite() == false) return@guarded
        var next = band
        if (freqHz != null) next = next.copy(freq = freqHz.coerceIn(ParametricMath.MIN_HZ, ParametricMath.MAX_HZ))
        if (gainDb != null && band.kind.hasGain()) {
            next = next.copy(gainDb = gainDb.coerceIn(-ParametricMath.MAX_GAIN_DB, ParametricMath.MAX_GAIN_DB))
        }
        if (q != null) next = next.copy(q = q.coerceIn(ParametricMath.MIN_Q, ParametricMath.MAX_Q))
        if (next == band) return@guarded
        writeBand(index, next)
    }

    /** The "Change sign of gain" button: flips +3.5 to -3.5 and back; pass kinds and exactly 0 dB do nothing. */
    fun flipBandGainSign(index: Int) {
        val band = controller.snapshot().bands.getOrNull(index) ?: return
        if (!band.kind.hasGain() || band.gainDb == 0f) return
        setBandFromField(index, gainDb = -band.gainDb)
    }

    /** The field session ended (no field of the row has focus any more): closes the undo step. Idempotent. */
    fun bandFieldFocusLost() {
        try {
            synchronized(lock) {
                endGesture(Source.FIELD)
            }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            fail()
        } finally {
            republish()
            refreshCurve()
        }
    }

    /** Moves band [index] by [steps] twelfths of an octave (negative = down). */
    fun nudgeBandFrequency(index: Int, steps: Int) = guarded {
        val band = bandAt(index) ?: return@guarded
        val hz = band.freq * 2.0.pow(steps / 12.0).toFloat()
        val next = ParametricMath.snapHz(hz)
        if (next == band.freq) return@guarded
        writeBand(index, band.copy(freq = next))
    }

    /** Moves the gain of band [index] by [deltaDb] (+-0.5 for the TalkBack actions). */
    fun nudgeBandGain(index: Int, deltaDb: Float) = guarded {
        val band = bandAt(index) ?: return@guarded
        if (!band.kind.hasGain() || !deltaDb.isFinite()) return@guarded
        val next = ParametricMath.snapDb(band.gainDb + deltaDb)
        if (next == band.gainDb) return@guarded
        writeBand(index, band.copy(gainDb = next))
    }

    /** Multiplies the Q of band [index] by [factor] (1.12 or 1/1.12), inside 0.1..18, rounded to 0.01. */
    fun scaleBandQ(index: Int, factor: Float) = guarded {
        val band = bandAt(index) ?: return@guarded
        if (!factor.isFinite() || factor <= 0f) return@guarded
        val q = (((band.q * factor).coerceIn(ParametricMath.MIN_Q, ParametricMath.MAX_Q)) * 100f).roundToInt() / 100f
        val next = q.coerceIn(ParametricMath.MIN_Q, ParametricMath.MAX_Q)
        if (next == band.q) return@guarded
        writeBand(index, band.copy(q = next))
    }

    /**
     * Reset all keeps its meaning (sliders/bands and preamp, UI-SPEC A2). When that has nothing to
     * reset but tone or balance is set, say where those are reset instead of staying silent.
     */
    private fun emitToneHintIfNeeded() {
        val c = controller.snapshot()
        if (c.bassDb != 0f || c.trebleDb != 0f || c.balance != 0f) {
            emit(TONE_RESET_HINT)
        }
    }

    /** Parametric: default seven bands and preamp 0 as one step. Graphic: zeroes the graphic gains and the preamp. */
    fun resetAll() = guarded {
        if (_ui.value.mode == EqMode.PARAMETRIC) {
            val before = controller.snapshot()
            controller.resetBands()
            val after = controller.snapshot()
            if (before.bands == after.bands && before.preampDb == after.preampDb) {
                emitToneHintIfNeeded()
                return@guarded
            }
            edited()
            _ui.update { it.copy(selectedBand = null) }
            republishLocked()
            emit("Bands and preamp reset", undoable = true)
            return@guarded
        }
        val s = _ui.value
        if (s.graphicGainsDb.all { it == 0f } && s.preampDb == 0f) {
            emitToneHintIfNeeded()
            return@guarded
        }
        edited()
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

    /**
     * Applies preset [key] as one undo step ("Applied <name>", with Undo only when the core recorded a step).
     * Inert while a gesture is open. A stale key shows "That preset no longer exists.".
     */
    fun applyPreset(key: String) = guarded {
        if (gestureDepth > 0) return@guarded
        val name = _ui.value.presets.firstOrNull { it.key == key }?.name ?: key
        edited()
        val recorded = try {
            controller.edit { it.applyPreset(key) }
        } catch (e: EqException.NotFound) {
            republishLocked()
            emit(PresetNames.GONE)
            return@guarded
        }
        _ui.update { it.copy(selectedBand = null, lastTouchedBand = null) }
        republishLocked()
        emit("Applied $name", undoable = recorded)
    }

    private fun existingPresetNames(): List<String> = controller.snapshot().presets.map { it.name }

    /** Maps a preset core error to UI copy, or null when it is not one of the preset errors. */
    private fun presetErrorText(e: Exception): String? = when (e) {
        is EqException.NameTaken -> PresetNames.NAME_TAKEN
        is EqException.InvalidName -> PresetNames.NAME_INVALID
        is EqException.NotFound -> PresetNames.GONE
        is EqException.ReadOnly -> PresetNames.READ_ONLY
        is EqException.TooManyPresets -> PresetNames.TOO_MANY
        else -> null
    }

    /**
     * Runs a preset persistence operation under the lock. Preset errors become [PresetResult.Rejected];
     * anything else falls back to the generic failure. Never calls edited(): these are not undo steps.
     */
    private inline fun presetOp(block: () -> PresetResult): PresetResult {
        try {
            return synchronized(lock) {
                try {
                    block()
                } catch (e: EqException) {
                    val text = presetErrorText(e) ?: throw e
                    republishLocked()
                    PresetResult.Rejected(text)
                }
            }
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            fail()
            return PresetResult.Rejected("Couldn't apply that change.")
        }
    }

    /** Saves the live sound as a new user preset named [name] and makes it active. Never overwrites. */
    fun savePresetAs(name: String): PresetResult = presetOp {
        PresetNames.check(name, existingPresetNames())?.let { return@presetOp PresetResult.Rejected(it) }
        val trimmed = name.trim()
        controller.edit { it.savePreset(trimmed) }
        republishLocked()
        emit("Saved $trimmed")
        PresetResult.Ok
    }

    /** Overwrites the active, modified user preset with the live sound. */
    fun savePreset(): PresetResult = presetOp {
        // Decide from the core's own state under the lock, not the last published UI copy.
        val s = controller.snapshot()
        val key = s.activePresetKey
        val name = s.activePresetName
        if (key == null || name == null) return@presetOp PresetResult.Rejected(PresetNames.NOTHING_TO_SAVE)
        if (!key.startsWith("user:")) return@presetOp PresetResult.Rejected(PresetNames.READ_ONLY)
        if (!s.presetModified) return@presetOp PresetResult.Rejected(PresetNames.NOTHING_TO_SAVE)
        controller.edit { it.savePreset(name) }
        republishLocked()
        emit("Saved $name")
        PresetResult.Ok
    }

    /** Renames a user preset; it stays active when it was. No message. */
    fun renamePreset(key: String, newName: String): PresetResult = presetOp {
        val own = controller.snapshot().presets.firstOrNull { it.key == key }?.name
        PresetNames.check(newName, existingPresetNames(), own)?.let { return@presetOp PresetResult.Rejected(it) }
        controller.edit { it.renamePreset(key, newName.trim()) }
        republishLocked()
        PresetResult.Ok
    }

    /** Deletes a user preset; the sound is unchanged. Emits "Deleted <name>". */
    fun deletePreset(key: String): PresetResult {
        val r = presetOp {
            // Resolve the display name from the core before deleting; never show the raw key.
            val name = controller.snapshot().presets.firstOrNull { it.key == key }?.name
                ?: key.substringAfter(':')
            controller.edit { it.deletePreset(key) }
            republishLocked()
            emit("Deleted $name")
            PresetResult.Ok
        }
        if (r is PresetResult.Rejected && r.message == PresetNames.GONE) synchronized(lock) { emit(PresetNames.GONE) }
        return r
    }

    fun undo() = guarded {
        if (gestureDepth > 0) return@guarded
        edited()
        controller.edit { it.undo() }
        republishLocked()
    }

    /**
     * Undo for a snackbar action: a no-op unless no edit, undo or redo has happened since [serial], the edit
     * serial stamped on the message that offered it (T-05-14).
     */
    fun undoIfUnchanged(serial: Int) = guarded {
        if (_editSerial.value != serial) return@guarded
        undo()
    }

    fun redo() = guarded {
        if (gestureDepth > 0) return@guarded
        edited()
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

    /** The selected enabled band's own response, or null; a failure here never blanks the main curve. */
    private fun selectedBandOverlay(snap: Snapshot): FloatArray? {
        val sel = _ui.value.selectedBand ?: return null
        if (snap.mode != EqMode.PARAMETRIC || snap.bands.getOrNull(sel)?.enabled != true) return null
        return try {
            controller.bandResponse(sel.toUInt(), CURVE_POINTS.toUInt(), sampleRate)
        } catch (e: Exception) {
            if (!isCoreFailure(e)) throw e
            null
        }
    }

    private fun computeCurve() {
        curveComputations.incrementAndGet()
        try {
            val snap = controller.snapshot()
            val d = controller.responseCurve(CURVE_POINTS.toUInt(), sampleRate, snap.frameDurationMs)
            val ep = controller.engineParams(sampleRate, snap.frameDurationMs)
            val overlay = selectedBandOverlay(snap)
            val pts = CurvePoints(d.freqsHz.toFloatArray(), d.idealDb.toFloatArray(), d.achievedDb.toFloatArray(), overlay)
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
                bands = snap.bands,
                preampDb = snap.preampDb,
                bassDb = snap.bassDb,
                trebleDb = snap.trebleDb,
                balance = snap.balance,
                limiterEnabled = snap.limiterEnabled,
                presets = snap.presets.toList(),
                activePresetKey = snap.activePresetKey,
                activePresetName = snap.activePresetName,
                presetModified = snap.presetModified,
                canUndo = snap.canUndo,
                canRedo = snap.canRedo,
                bypass = st.bypass,
                serviceRunning = st.serviceRunning,
                sessionCount = st.sessionCount,
                globalMode = st.globalMode,
                notice = st.notice,
                lastTouchedBand = if (countChanged) null else base.lastTouchedBand,
                selectedBand = base.selectedBand?.takeIf { snap.mode == EqMode.PARAMETRIC && it < snap.bands.size },
            )
        }
    }
}

/** Outcome of a preset save, rename or delete; [Rejected] carries the exact UI-SPEC copy for the dialog. */
sealed interface PresetResult {
    data object Ok : PresetResult
    data class Rejected(val message: String) : PresetResult
}

/** Shown by Reset all when only tone or balance is non-default. */
internal const val TONE_RESET_HINT = "Tone and balance reset from the Tone & balance card"
