package com.dylenq.eq.ui

import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.service.SettingsStore
import com.dylenq.eq.testsupport.FakeEffectFactory
import com.dylenq.eq.testsupport.RuntimeRule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Holder behaviour on a real EqCore through EqController, no Activity. */
class UiStateHolderTest {
    @get:Rule val rt = RuntimeRule()

    private val holders = mutableListOf<UiStateHolder>()

    private fun newHolder(runtime: EqRuntime = rt.runtime) = UiStateHolder(runtime).also { holders += it }
    private val ctrl get() = rt.runtime.controller

    @After fun closeHolders() { holders.forEach { it.close() } }

    private fun await(h: UiStateHolder, ms: Long = 5000, pred: (EqUiState) -> Boolean): EqUiState =
        runBlocking { withTimeout(ms) { h.ui.first(pred) } }

    // ---- runtime additions -------------------------------------------------------------

    @Test fun serviceRunningFollowsAttachAndDetach() {
        val r = rt.runtime
        assertFalse(r.status.value.serviceRunning)
        r.attach()
        assertTrue(r.status.value.serviceRunning)
        r.detach()
        assertFalse(r.status.value.serviceRunning)
    }

    @Test fun serviceRunningSurvivesWorkerStatusPublishes() {
        val r = rt.runtime
        r.attach()
        ctrl.edit { it.setGraphicGain(0u, 3f) }
        r.flush()
        r.inspect { }
        assertTrue(r.status.value.serviceRunning)
    }

    @Test fun dismissNoticeClearsAndStaysCleared() {
        val dir = File(rt.dir, "damaged").also { it.mkdirs() }
        File(dir, "eq-state.json").writeText("this is not json")
        val r = EqRuntime(FakeEffectFactory(), dir, SettingsStore(null))
        try {
            assertNotNull(r.status.value.notice)
            r.dismissNotice()
            assertNull(r.status.value.notice)
            r.controller.edit { it.setGraphicGain(1u, 2f) }
            r.flush()
            r.inspect { }
            assertNull(r.status.value.notice)
            r.dismissNotice() // no-op without a notice
            assertNull(r.status.value.notice)
        } finally {
            r.shutdown()
        }
    }

    // ---- state mirror ------------------------------------------------------------------

    @Test fun stateMirrorsTheCore() {
        ctrl.edit {
            it.setGraphicGain(2u, 4.5f)
            it.setPreamp(-3f)
            it.setLimiterEnabled(false)
            it.setMode(EqMode.PARAMETRIC)
        }
        rt.runtime.attach()
        val h = newHolder()
        val s = await(h) { it.curve != null && it.serviceRunning }
        assertEquals(EqMode.PARAMETRIC, s.mode)
        assertEquals(10, s.graphicBandCount)
        assertEquals(10, s.graphicCentresHz.size)
        assertEquals(4.5f, s.graphicGainsDb[2], 0f)
        assertEquals(-3f, s.preampDb, 0f)
        assertFalse(s.limiterEnabled)
        assertFalse(s.bypass)
        assertEquals(0, s.sessionCount)
        assertFalse(s.globalMode)
        assertNull(s.notice)
    }

    @Test fun curveCarriesHeadroomAndSuggestedPreamp() {
        ctrl.edit { it.setGraphicGain(3u, 12f) }
        val h = newHolder()
        val s = await(h) { it.curve != null && it.suggestedPreampDb < 0f }
        assertTrue("suggested ${s.suggestedPreampDb}", s.suggestedPreampDb <= -10f)
        assertTrue("headroom ${s.headroomAfterPreampDb}", s.headroomAfterPreampDb < -0.05f)
    }

    @Test fun bypassFollowsTheControllerFromOutside() {
        val h = newHolder()
        await(h) { it.curve != null }
        assertFalse(h.ui.value.bypass)
        ctrl.toggleBypass() // the notification path
        await(h, 1000) { it.bypass }
        ctrl.toggleBypass()
        await(h, 1000) { !it.bypass }
    }

    @Test fun aNewHolderOnABypassedStateReportsBypassOnItsVeryFirstEmission() {
        ctrl.setBypass(true)
        rt.runtime.flush()
        // Repeated: the old behaviour only failed while the new worker had not yet published.
        repeat(25) { i ->
            val r = EqRuntime(FakeEffectFactory(), rt.dir, SettingsStore(null))
            val h = UiStateHolder(r)
            try {
                assertTrue("cold start #$i: first emission must carry bypass", h.ui.value.bypass)
                assertTrue("cold start #$i: status carries bypass before the worker runs", r.status.value.bypass)
            } finally {
                h.close()
                r.shutdown()
            }
        }
    }

    // ---- drags and undo grouping --------------------------------------------------------

    @Test fun oneDragIsOneUndoStep() {
        val h = newHolder()
        h.onBandDragStart(4)
        assertEquals(4, h.ui.value.draggingBand)
        for (i in 1..20) h.onBandDragChange(4, i * 0.5f)
        assertEquals(10f, h.ui.value.graphicGainsDb[4], 0f) // optimistic value, before the curve arrives
        h.onBandDragEnd()
        assertNull(h.ui.value.draggingBand)
        assertEquals(4, h.ui.value.lastTouchedBand)
        assertEquals(10f, ctrl.snapshot().graphicGainsDb[4], 0f)
        assertTrue(h.ui.value.canUndo)
        h.undo()
        assertEquals(0f, ctrl.snapshot().graphicGainsDb[4], 0f)
        assertEquals(0f, h.ui.value.graphicGainsDb[4], 0f)
        assertFalse("a single undo must restore the pre-drag state", h.ui.value.canUndo)
        assertTrue(h.ui.value.canRedo)
        h.redo()
        assertEquals(10f, h.ui.value.graphicGainsDb[4], 0f)
    }

    @Test fun dragChangeClampsToCoreLimits() {
        val h = newHolder()
        h.onBandDragStart(0)
        h.onBandDragChange(0, 99f)
        h.onBandDragEnd()
        assertEquals(24f, ctrl.snapshot().graphicGainsDb[0], 0f)
    }

    @Test fun resetBandIsOneUndoStep() {
        ctrl.edit { it.setGraphicGain(5u, 6f) }
        val h = newHolder()
        h.resetBand(5)
        assertEquals(0f, h.ui.value.graphicGainsDb[5], 0f)
        h.undo()
        assertEquals(6f, h.ui.value.graphicGainsDb[5], 0f)
    }

    @Test fun bandCountChangeRepublishesTheResampledCore() {
        ctrl.edit { it.setGraphicGain(4u, 6f) }
        val h = newHolder()
        h.onBandDragStart(4)
        h.onBandDragEnd()
        assertEquals(4, h.ui.value.lastTouchedBand)
        h.setBandCount(31)
        val s = h.ui.value
        val snap = ctrl.snapshot()
        assertEquals(31, s.graphicBandCount)
        assertEquals(31, s.graphicGainsDb.size)
        assertEquals(31, s.graphicCentresHz.size)
        assertEquals(snap.graphicGainsDb.toList(), s.graphicGainsDb)
        assertEquals(snap.graphicCentresHz.toList(), s.graphicCentresHz)
        assertNull("readout resets with the band count", s.lastTouchedBand)
    }

    @Test fun resetAllIsOneUndoStep() {
        ctrl.edit { it.setGraphicGain(1u, 3f) }
        ctrl.edit { it.setGraphicGain(8u, -5f) }
        ctrl.edit { it.setPreamp(-4f) }
        val h = newHolder()
        h.resetAll()
        val s = h.ui.value
        assertTrue(s.graphicGainsDb.all { it == 0f })
        assertEquals(0f, s.preampDb, 0f)
        h.undo()
        assertEquals(3f, h.ui.value.graphicGainsDb[1], 0f)
        assertEquals(-5f, h.ui.value.graphicGainsDb[8], 0f)
        assertEquals(-4f, h.ui.value.preampDb, 0f)
    }

    @Test fun applySuggestedPreampIsOneUndoStep() {
        ctrl.edit { it.setGraphicGain(3u, 12f) }
        val h = newHolder()
        val suggested = await(h) { it.suggestedPreampDb < 0f }.suggestedPreampDb
        h.applySuggestedPreamp()
        assertEquals(suggested, h.ui.value.preampDb, 0f)
        h.undo()
        assertEquals(0f, h.ui.value.preampDb, 0f)
        assertEquals(12f, h.ui.value.graphicGainsDb[3], 0f)
    }

    @Test fun preampDragClampsAndIsOneUndoStep() {
        val h = newHolder()
        h.preampDragStart()
        for (db in listOf(-1f, -2.5f, -5f)) h.setPreamp(db)
        h.preampDragEnd()
        assertEquals(-5f, h.ui.value.preampDb, 0f)
        h.undo()
        assertEquals(0f, h.ui.value.preampDb, 0f)
        h.setPreamp(40f)
        assertEquals(6f, ctrl.snapshot().preampDb, 0f)
        h.setPreamp(-99f)
        assertEquals(-24f, ctrl.snapshot().preampDb, 0f)
        h.resetPreamp()
        assertEquals(0f, ctrl.snapshot().preampDb, 0f)
    }

    @Test fun limiterModeAndBypassActions() {
        val h = newHolder()
        h.setLimiter(false)
        assertFalse(h.ui.value.limiterEnabled)
        assertFalse(ctrl.snapshot().limiterEnabled)
        h.setLimiter(true)
        assertTrue(h.ui.value.limiterEnabled)
        h.setMode(EqMode.PARAMETRIC)
        assertEquals(EqMode.PARAMETRIC, h.ui.value.mode)
        h.setMode(EqMode.GRAPHIC)
        assertEquals(EqMode.GRAPHIC, ctrl.snapshot().mode)
        h.setBypass(true)
        await(h, 1000) { it.bypass }
        h.setBypass(false)
        await(h, 1000) { !it.bypass }
    }

    // ---- errors and conflation ----------------------------------------------------------

    @Test fun coreErrorBecomesOneEventAndTheStateIsReread() {
        val h = newHolder()
        val got = CopyOnWriteArrayList<String>()
        val ready = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { h.errors.onSubscription { ready.complete(Unit) }.collect { got.add(it) } }
        runBlocking { withTimeout(2000) { ready.await() } }

        h.onBandDragStart(2)
        h.onBandDragChange(2, 3f)
        h.onBandDragChange(2, Float.NaN) // the core rejects non-finite values
        val end = System.currentTimeMillis() + 2000
        while (got.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        h.onBandDragEnd()
        scope.cancel()

        assertEquals(listOf("Couldn't apply that change."), got.toList())
        assertEquals(3f, ctrl.snapshot().graphicGainsDb[2], 0f)
        assertEquals(3f, h.ui.value.graphicGainsDb[2], 0f)
        assertNull(h.ui.value.draggingBand)
        // the holder is still usable and the edit group was closed
        h.undo()
        assertEquals(0f, h.ui.value.graphicGainsDb[2], 0f)
    }

    @Test fun rapidDragChangesConflateTheCurveWork() {
        val h = newHolder()
        await(h) { it.curve != null }
        val before = h.curveComputations.get()
        h.onBandDragStart(6)
        for (i in 1..100) h.onBandDragChange(6, (i % 24).toFloat())
        h.onBandDragEnd()
        val finalGain = ctrl.snapshot().graphicGainsDb[6]
        // The last published curve matches the final core state.
        val rate = android.media.AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC).toUInt()
        val expected = ctrl.responseCurve(256u, rate, ctrl.snapshot().frameDurationMs)
        val s = await(h) { st ->
            st.curve?.let { c -> c.achievedDb.indices.all { kotlin.math.abs(c.achievedDb[it] - expected.achievedDb[it]) < 1e-3f } } == true
        }
        val computations = h.curveComputations.get() - before
        android.util.Log.i("EQ-TEST", "conflation: 100 changes -> $computations curve computations")
        assertTrue("expected a handful of computations, got $computations", computations <= 25)
        assertEquals(finalGain, s.graphicGainsDb[6], 0f)
    }

    // ---- tone and balance ----------------------------------------------------------------

    private fun undoDepth(h: UiStateHolder): Int {
        var n = 0
        while (h.ui.value.canUndo && n < 50) { h.undo(); n++ }
        return n
    }

    @Test fun bassTracer() {
        val h = newHolder()
        val before = await(h) { it.curve != null }.curve!!
        fun peak(c: CurvePoints): Float {
            var best = -100f
            for (i in c.freqsHz.indices) if (c.freqsHz[i] in 60f..160f) best = maxOf(best, c.idealDb[i])
            return best
        }
        val peakBefore = peak(before)
        h.setBass(6f)
        assertEquals(6f, h.ui.value.bassDb, 0f)
        assertEquals(6f, ctrl.snapshot().bassDb, 0f)
        val after = await(h) { s -> s.curve != null && peak(s.curve!!) > peakBefore + 1f }.curve!!
        assertTrue(peak(after) > peakBefore + 1f)
        h.undo()
        assertEquals(0f, h.ui.value.bassDb, 0f)
        assertEquals(0f, ctrl.snapshot().bassDb, 0f)
    }

    @Test fun bassDragIsOneUndoStep() {
        val h = newHolder()
        h.bassDragStart()
        for (db in listOf(1f, 2.5f, 4f)) h.setBass(db)
        h.bassDragEnd()
        assertEquals(4f, h.ui.value.bassDb, 0f)
        assertEquals(1, undoDepth(h))
        assertEquals(0f, h.ui.value.bassDb, 0f)
    }

    @Test fun toneGesturesHaveSeparateSources() {
        val h = newHolder()
        h.bassDragStart()
        h.balanceDragStart()
        h.setBass(3f)
        h.setBalance(40)
        h.bassDragEnd()
        assertTrue("balance group stays open after bass ends", h.ui.value.gestureOpen)
        h.undo() // inert while a gesture is open
        assertEquals(3f, h.ui.value.bassDb, 0f)
        h.setBalance(50)
        h.balanceDragEnd()
        assertFalse(h.ui.value.gestureOpen)
        assertEquals(0.5f, h.ui.value.balance, 1e-6f)
        assertEquals("overlapping drags make one step", 1, undoDepth(h))
        assertEquals(0f, h.ui.value.bassDb, 0f)
        assertEquals(0f, h.ui.value.balance, 0f)
    }

    @Test fun resetsAtDefaultAddNoUndoStep() {
        val h = newHolder()
        h.resetBass(); h.resetTreble(); h.resetBalance()
        assertFalse(h.ui.value.canUndo)
        assertEquals(0, undoDepth(h))
    }

    @Test fun resetsFromNonDefaultAreOneStepEach() {
        val h = newHolder()
        h.setBass(5f); h.setTreble(-4f); h.setBalance(-25)
        val base = undoDepth(h)
        assertEquals(3, base)
        h.redo(); h.redo(); h.redo()
        assertEquals(5f, h.ui.value.bassDb, 0f)
        h.resetBass(); h.resetTreble(); h.resetBalance()
        assertEquals(0f, h.ui.value.bassDb, 0f)
        assertEquals(0f, h.ui.value.trebleDb, 0f)
        assertEquals(0f, h.ui.value.balance, 0f)
        h.undo()
        assertEquals(-0.25f, h.ui.value.balance, 1e-6f)
        h.undo()
        assertEquals(-4f, h.ui.value.trebleDb, 0f)
        h.undo()
        assertEquals(5f, h.ui.value.bassDb, 0f)
    }

    @Test fun undoRedoUpdateToneState() {
        val h = newHolder()
        h.setBass(2f); h.setTreble(3f); h.setBalance(30)
        h.undo()
        assertEquals(0f, h.ui.value.balance, 0f)
        h.undo()
        assertEquals(0f, h.ui.value.trebleDb, 0f)
        h.undo()
        assertEquals(0f, h.ui.value.bassDb, 0f)
        h.redo(); h.redo(); h.redo()
        assertEquals(2f, h.ui.value.bassDb, 0f)
        assertEquals(3f, h.ui.value.trebleDb, 0f)
        assertEquals(0.3f, h.ui.value.balance, 1e-6f)
    }

    @Test fun balanceClampsAndDefaultsAreZero() {
        assertEquals(0f, EqUiState().bassDb, 0f)
        assertEquals(0f, EqUiState().trebleDb, 0f)
        assertEquals(0f, EqUiState().balance, 0f)
        val h = newHolder()
        h.setBalance(250)
        assertEquals(1f, h.ui.value.balance, 0f)
        assertEquals(1f, ctrl.snapshot().balance, 0f)
        h.setBass(99f)
        assertEquals(12f, ctrl.snapshot().bassDb, 0f)
        h.setTreble(-99f)
        assertEquals(-12f, ctrl.snapshot().trebleDb, 0f)
    }

    // ---- presets ---------------------------------------------------------------------------

    /** Collects holder messages; call [collectMessages] before the action under test. */
    private fun collectMessages(h: UiStateHolder): Pair<CopyOnWriteArrayList<UiMessage>, CoroutineScope> {
        val got = CopyOnWriteArrayList<UiMessage>()
        val ready = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { h.messages.onSubscription { ready.complete(Unit) }.collect { got.add(it) } }
        runBlocking { withTimeout(2000) { ready.await() } }
        return got to scope
    }

    private fun waitFor(got: List<UiMessage>, n: Int) {
        val end = System.currentTimeMillis() + 2000
        while (got.size < n && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun freshStateListsBuiltIns() {
        val s = newHolder().ui.value
        assertEquals(listOf("Flat", "Bass", "Treble", "Vocal"), s.presets.take(4).map { it.name })
        assertTrue(s.presets.take(4).all { it.builtin })
        assertNull(s.activePresetKey)
        assertFalse(s.presetModified)
    }

    @Test fun applyPreset_emitsAppliedWithUndo() {
        val h = newHolder()
        val (got, scope) = collectMessages(h)
        h.applyPreset("builtin:Bass")
        waitFor(got, 1)
        scope.cancel()
        assertEquals(listOf(UiMessage("Applied Bass", undoable = true)), got.toList())
        val s = h.ui.value
        assertEquals("Bass", s.activePresetName)
        assertEquals(EqMode.GRAPHIC, s.mode)
        assertEquals(6f, s.graphicGainsDb[0], 1e-4f)
        assertNull(s.selectedBand)
    }

    @Test fun applyPreset_undoRestoresMode() {
        val h = newHolder()
        h.setMode(EqMode.PARAMETRIC)
        h.applyPreset("builtin:Bass")
        assertEquals(EqMode.GRAPHIC, h.ui.value.mode)
        h.undo()
        assertEquals(EqMode.PARAMETRIC, h.ui.value.mode)
        assertNull(h.ui.value.activePresetKey)
        assertEquals(0f, h.ui.value.graphicGainsDb[0], 1e-4f)
    }

    @Test fun applyPreset_sameTwice_isNotUndoable() {
        val h = newHolder()
        h.applyPreset("builtin:Bass")
        val (got, scope) = collectMessages(h)
        val undoBefore = h.ui.value.canUndo
        h.applyPreset("builtin:Bass")
        waitFor(got, 1)
        scope.cancel()
        assertEquals(listOf(UiMessage("Applied Bass", undoable = false)), got.toList())
        assertEquals(undoBefore, h.ui.value.canUndo)
    }

    @Test fun applyPreset_inertDuringGesture() {
        val h = newHolder()
        h.onBandDragStart(2)
        h.applyPreset("builtin:Bass")
        assertNull(h.ui.value.activePresetKey)
        h.onBandDragEnd()
        assertNull(ctrl.snapshot().activePresetKey)
    }

    @Test fun savePresetAs_activatesAndEmitsWithoutBumpingSerial() {
        val h = newHolder()
        h.setBass(2f)
        val serial = h.editSerial.value
        val (got, scope) = collectMessages(h)
        assertEquals(PresetResult.Ok, h.savePresetAs("  Night "))
        waitFor(got, 1)
        scope.cancel()
        assertEquals(listOf(UiMessage("Saved Night")), got.toList())
        assertEquals("Night", h.ui.value.activePresetName)
        assertFalse(h.ui.value.presetModified)
        assertEquals(serial, h.editSerial.value)
        assertTrue(h.ui.value.presets.any { it.name == "Night" && !it.builtin })
    }

    @Test fun savePresetAs_duplicateIsRejectedWithoutOverwrite() {
        val h = newHolder()
        h.setBass(2f)
        h.savePresetAs("Night")
        h.setBass(5f)
        assertEquals(PresetResult.Rejected(PresetNames.NAME_TAKEN), h.savePresetAs("night"))
        assertEquals(PresetResult.Rejected(PresetNames.NAME_TAKEN), h.savePresetAs("bass"))
        assertEquals(PresetResult.Rejected(PresetNames.NAME_BLANK), h.savePresetAs("  "))
        assertEquals(PresetResult.Rejected(PresetNames.NAME_INVALID), h.savePresetAs("x".repeat(41)))
        assertTrue(h.ui.value.presetModified)
        assertEquals(1, h.ui.value.presets.count { it.name == "Night" })
    }

    @Test fun savePreset_overwritesActiveUserPreset() {
        val h = newHolder()
        h.setBass(2f)
        h.savePresetAs("Night")
        h.setBass(5f)
        assertTrue(h.ui.value.presetModified)
        val serial = h.editSerial.value
        val (got, scope) = collectMessages(h)
        assertEquals(PresetResult.Ok, h.savePreset())
        waitFor(got, 1)
        scope.cancel()
        assertEquals(listOf(UiMessage("Saved Night")), got.toList())
        assertFalse(h.ui.value.presetModified)
        assertEquals(serial, h.editSerial.value)
    }

    @Test fun savePreset_noOpForBuiltInOrNone() {
        val h = newHolder()
        assertTrue(h.savePreset() is PresetResult.Rejected)
        h.applyPreset("builtin:Bass")
        h.setBass(3f)
        assertEquals(PresetResult.Rejected(PresetNames.READ_ONLY), h.savePreset())
    }

    @Test fun renamePreset_keepsActiveAndStaysSilent() {
        val h = newHolder()
        h.setBass(2f)
        h.savePresetAs("Night")
        val (got, scope) = collectMessages(h)
        assertEquals(PresetResult.Ok, h.renamePreset("user:Night", "Day"))
        Thread.sleep(150)
        scope.cancel()
        assertTrue(got.isEmpty())
        assertEquals("Day", h.ui.value.activePresetName)
        assertEquals(PresetResult.Rejected(PresetNames.NAME_TAKEN), h.renamePreset("user:Day", "Flat"))
        assertEquals(PresetResult.Ok, h.renamePreset("user:Day", "DAY"))
    }

    @Test fun renamePreset_staleKeyIsRejected() {
        val h = newHolder()
        assertEquals(PresetResult.Rejected(PresetNames.GONE), h.renamePreset("user:Ghost", "Whatever"))
    }

    @Test fun deletePreset_activeLeavesSoundUnchanged() {
        val h = newHolder()
        h.setBass(2f)
        h.savePresetAs("Night")
        val before = ctrl.engineParams(48000u, 85.3f)
        val serial = h.editSerial.value
        val (got, scope) = collectMessages(h)
        assertEquals(PresetResult.Ok, h.deletePreset("user:Night"))
        waitFor(got, 1)
        scope.cancel()
        assertEquals(listOf(UiMessage("Deleted Night")), got.toList())
        assertNull(h.ui.value.activePresetKey)
        assertEquals(before, ctrl.engineParams(48000u, 85.3f))
        assertEquals(serial, h.editSerial.value)
    }

    @Test fun stalePreset_applyAndDeleteShowGoneMessageWithoutCrash() {
        val h = newHolder()
        val (got, scope) = collectMessages(h)
        h.applyPreset("user:Ghost")
        waitFor(got, 1)
        assertEquals(UiMessage(PresetNames.GONE), got[0])
        assertTrue(h.deletePreset("user:Ghost") is PresetResult.Rejected)
        waitFor(got, 2)
        scope.cancel()
        assertEquals(UiMessage(PresetNames.GONE), got[1])
    }

    @Test fun deletingBuiltInIsReadOnly() {
        val h = newHolder()
        assertEquals(PresetResult.Rejected(PresetNames.READ_ONLY), h.deletePreset("builtin:Bass"))
    }

    @Test fun presetSaveRenameDelete_doNotInvalidateAppliedSnackbar() {
        val h = newHolder()
        h.applyPreset("builtin:Bass")
        val serial = h.editSerial.value
        h.savePresetAs("A")
        h.renamePreset("user:A", "B")
        h.deletePreset("user:B")
        assertEquals(serial, h.editSerial.value)
    }

    @Test fun undoableMessageCarriesItsEmitSerial_andUndoIfUnchangedIsANoOpAfterAnotherEdit() {
        val h = newHolder()
        val (got, scope) = collectMessages(h)
        h.applyPreset("builtin:Bass")
        waitFor(got, 1)
        val serial = got[0].serial
        assertEquals(h.editSerial.value, serial)
        h.setPreamp(-4f) // a later edit bumps the serial
        h.undoIfUnchanged(serial)
        scope.cancel()
        assertEquals("the stale Undo must not run", -4f, h.ui.value.preampDb)
        assertEquals("builtin:Bass", h.ui.value.activePresetKey)
    }

    @Test fun deleteMessageShowsTheNameNotTheKey() {
        val h = newHolder()
        h.savePresetAs("Night")
        val (got, scope) = collectMessages(h)
        h.deletePreset("user:Night")
        waitFor(got, 1)
        scope.cancel()
        assertEquals("Deleted Night", got[0].text)
    }
}
