package com.dylenq.eq.ui

import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.RuntimeRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The shared Parametric band actions of the holder: undo steps, selection upkeep, messages, index safety. */
class ParametricActionsTest {
    @get:Rule val rt = RuntimeRule()

    private val holders = mutableListOf<UiStateHolder>()
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
    private val ctrl get() = rt.runtime.controller
    private val messages = CopyOnWriteArrayList<UiMessage>()

    private fun newHolder(): UiStateHolder {
        ctrl.edit { it.setMode(EqMode.PARAMETRIC) }
        val h = UiStateHolder(rt.runtime).also { holders += it }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { h.messages.collect { messages.add(it) } }
        return h
    }

    @After fun tearDown() {
        scope.cancel()
        holders.forEach { it.close() }
    }

    private fun undoDepth(): Int {
        var n = 0
        while (ctrl.snapshot().canUndo && n < 50) {
            ctrl.edit { it.undo() }
            n++
        }
        return n
    }

    private fun bands() = ctrl.snapshot().bands

    @Test fun kindChangeKeepsFreqQGainAndIsOneStep() {
        val h = newHolder()
        val before = bands()[2]
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val after = bands()[2]
        assertEquals(FilterKind.HIGH_SHELF, after.kind)
        assertEquals(before.freq, after.freq, 0f)
        assertEquals(before.q, after.q, 0f)
        assertEquals(before.gainDb, after.gainDb, 0f)
        assertEquals(1, undoDepth())
        assertEquals(before, bands()[2])
    }

    @Test fun passKindRoundTripKeepsTheStoredGain() {
        val h = newHolder()
        h.parametricGestureStart(2)
        h.parametricNodeChange(2, 500f, 6.5f)
        h.parametricGestureEnd()
        h.setBandKind(2, FilterKind.HIGH_PASS)
        h.setBandKind(2, FilterKind.PEAK)
        assertEquals(6.5f, bands()[2].gainDb, 0f)
        assertEquals(FilterKind.PEAK, bands()[2].kind)
    }

    @Test fun enableDisableIsOneStepAndKeepsTheBand() {
        val h = newHolder()
        h.setBandEnabled(1, false)
        assertEquals(false, bands()[1].enabled)
        assertEquals(7, bands().size)
        h.selectBand(1)
        assertEquals(1, h.ui.value.selectedBand)
        assertEquals(1, undoDepth())
    }

    @Test fun removeBandSelectionRules() {
        val h = newHolder()
        h.selectBand(3)
        h.removeBand(3) // the selected one goes
        assertNull(h.ui.value.selectedBand)
        assertEquals(6, bands().size)
        h.selectBand(4)
        h.removeBand(1) // an earlier one goes
        assertEquals(3, h.ui.value.selectedBand)
        h.removeBand(4) // a later one goes
        assertEquals(3, h.ui.value.selectedBand)
        assertEquals(4, bands().size)
    }

    @Test fun removeBandEmitsAnUndoableMessageAndIsOneUndoableStep() {
        val h = newHolder()
        val removed = bands()[2]
        h.removeBand(2)
        assertEquals(listOf(UiMessage("Band 3 deleted", undoable = true)), messages.toList())
        h.undo()
        assertEquals(7, bands().size)
        assertEquals(removed, bands()[2])
    }

    @Test fun removeAllBandsIsOneStepSelectionNullAndMessage() {
        val h = newHolder()
        val original = bands()
        h.selectBand(2)
        h.removeAllBands()
        assertEquals(0, bands().size)
        assertNull(h.ui.value.selectedBand)
        assertEquals(listOf(UiMessage("All bands removed", undoable = true)), messages.toList())
        h.undo()
        assertEquals("a single undo restores all of them", original, bands())
        assertEquals("and nothing older was touched", 0, undoDepth())
    }

    @Test fun removeAllWithNoBandsIsANoOp() {
        val h = newHolder()
        h.removeAllBands()
        messages.clear()
        val steps = h.ui.value.canUndo
        h.removeAllBands()
        assertEquals(0, bands().size)
        assertTrue(messages.isEmpty())
        assertEquals(steps, h.ui.value.canUndo)
    }

    @Test fun addBandFromListUsesTheFirstFreeFrequencyAndSelects() {
        val h = newHolder()
        h.removeAllBands()
        messages.clear()
        h.addBandFromList()
        val b = bands().single()
        assertEquals(FilterKind.PEAK, b.kind)
        assertEquals(1000f, b.freq, 0f)
        assertEquals(1f, b.q, 0f)
        assertEquals(0f, b.gainDb, 0f)
        assertEquals(0, h.ui.value.selectedBand)
        h.addBandFromList() // 1 kHz is taken: next in the sequence is 250 Hz
        assertEquals(250f, bands()[1].freq, 0f)
        assertEquals(1, h.ui.value.selectedBand)
    }

    @Test fun addBandFromListIsOneUndoStep() {
        val h = newHolder()
        h.addBandFromList()
        assertEquals(8, bands().size)
        h.undo()
        assertEquals("one undo removes the added band", 7, bands().size)
        assertEquals("and nothing more was recorded", 0, undoDepth())
    }

    @Test fun addAtSixteenBandsAddsNothingAndSaysSo() {
        val h = newHolder()
        while (bands().size < 16) h.addBandFromList()
        messages.clear()
        h.addBandFromList()
        assertEquals(16, bands().size)
        assertEquals("All 16 bands are in use. Remove one to add another.", messages.single().text)
    }

    @Test fun resetAllInParametricRestoresDefaultsInOneStepWithMessage() {
        val h = newHolder()
        val defaults = bands()
        h.parametricGestureStart(2)
        h.parametricNodeChange(2, 700f, 5f)
        h.parametricGestureEnd()
        h.removeBand(0)
        h.setPreamp(-4f)
        messages.clear()
        h.resetAll()
        assertEquals(defaults, bands())
        assertEquals(0f, ctrl.snapshot().preampDb, 0f)
        assertEquals(listOf(UiMessage("Bands and preamp reset", undoable = true)), messages.toList())
        h.undo()
        assertEquals(6, bands().size)
        assertEquals(-4f, ctrl.snapshot().preampDb, 0f)
    }

    @Test fun resetAllWhenAlreadyDefaultMakesNoStepAndNoMessage() {
        val h = newHolder()
        messages.clear()
        h.resetAll()
        assertTrue(messages.isEmpty())
        assertEquals(0, undoDepth())
    }

    @Test fun resetAllWithOnlyToneSetTellsWhereToResetItAndChangesNothing() {
        val h = newHolder()
        h.setBass(4f)
        h.setBalance(30)
        messages.clear()
        h.resetAll()
        assertEquals(listOf(UiMessage(TONE_RESET_HINT)), messages.toList())
        assertEquals(4f, ctrl.snapshot().bassDb, 0f)
        assertEquals(0.3f, ctrl.snapshot().balance, 1e-6f)
    }

    @Test fun graphicResetAllWithOnlyToneSetTellsWhereToResetIt() {
        ctrl.edit { it.setMode(EqMode.GRAPHIC) }
        val h = UiStateHolder(rt.runtime).also { holders += it }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { h.messages.collect { messages.add(it) } }
        h.setTreble(-3f)
        messages.clear()
        h.resetAll()
        assertEquals(listOf(UiMessage(TONE_RESET_HINT)), messages.toList())
        assertEquals(-3f, ctrl.snapshot().trebleDb, 0f)
    }

    @Test fun graphicResetAllIsUnchanged() {
        ctrl.edit { it.setMode(EqMode.GRAPHIC) }
        val h = UiStateHolder(rt.runtime).also { holders += it }
        h.onBandDragStart(0)
        h.onBandDragChange(0, 5f)
        h.onBandDragEnd()
        h.resetAll()
        assertTrue(ctrl.snapshot().graphicGainsDb.all { it == 0f })
        assertEquals(2, undoDepth())
    }

    @Test fun nudgeFrequencyMovesByTwelfthOctaveSnappedAndClamped() {
        val h = newHolder()
        h.nudgeBandFrequency(3, 1) // 1000 Hz * 2^(1/12) = 1059.46 -> 1060
        assertEquals(1060f, bands()[3].freq, 0f)
        assertEquals(1, undoDepth())
        h.nudgeBandFrequency(3, -1)
        assertEquals(944f, bands()[3].freq, 0f) // undoDepth() rolled back to 1000; 1000 / 2^(1/12) = 943.9 -> 944
        h.nudgeBandFrequency(0, -100)
        assertEquals(20f, bands()[0].freq, 0f)
    }

    @Test fun nudgeGainStepsHalfDbClampsAndPassKindsIgnoreIt() {
        val h = newHolder()
        h.nudgeBandGain(3, 0.5f)
        assertEquals(0.5f, bands()[3].gainDb, 0f)
        assertEquals(1, undoDepth())
        repeat(60) { h.nudgeBandGain(3, 0.5f) }
        assertEquals(24f, bands()[3].gainDb, 0f)
        h.setBandKind(3, FilterKind.LOW_PASS)
        val g = bands()[3].gainDb
        h.nudgeBandGain(3, 0.5f)
        assertEquals(g, bands()[3].gainDb, 0f)
    }

    @Test fun scaleQMultipliesRoundsAndClamps() {
        val h = newHolder()
        h.scaleBandQ(3, 1.12f)
        assertEquals(1.12f, bands()[3].q, 0.001f)
        assertEquals(1, undoDepth())
        repeat(60) { h.scaleBandQ(3, 1.12f) }
        assertEquals(18f, bands()[3].q, 0f)
        repeat(100) { h.scaleBandQ(3, 1f / 1.12f) }
        assertEquals(0.1f, bands()[3].q, 0f)
    }

    @Test fun outOfRangeIndicesAreIgnoredWithoutExceptionsOrSteps() {
        val h = newHolder()
        messages.clear()
        for (i in listOf(-1, 7, 99)) {
            h.setBandKind(i, FilterKind.LOW_PASS)
            h.setBandEnabled(i, false)
            h.removeBand(i)
            h.nudgeBandFrequency(i, 1)
            h.nudgeBandGain(i, 0.5f)
            h.scaleBandQ(i, 1.12f)
        }
        assertEquals(7, bands().size)
        assertTrue(messages.isEmpty())
        assertEquals(0, undoDepth())
    }

    @Test fun actionsInsideAnOpenGestureJoinItsUndoStep() {
        val h = newHolder()
        h.parametricGestureStart(2)
        h.parametricNodeChange(2, 600f, 2f)
        h.nudgeBandGain(2, 0.5f)
        h.parametricGestureEnd()
        assertEquals(1, undoDepth())
    }
}
