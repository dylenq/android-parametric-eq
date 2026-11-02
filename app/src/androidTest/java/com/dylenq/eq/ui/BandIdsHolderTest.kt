package com.dylenq.eq.ui

import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.RuntimeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The band row id rules of the holder: which actions keep the ids, which add or drop one, which replace them all. */
class BandIdsHolderTest {
    @get:Rule val rt = RuntimeRule()

    private val holders = mutableListOf<UiStateHolder>()
    private val ctrl get() = rt.runtime.controller

    private fun newHolder(): UiStateHolder {
        ctrl.edit { it.setMode(EqMode.PARAMETRIC) }
        return UiStateHolder(rt.runtime).also { holders += it }
    }

    @After fun tearDown() {
        holders.forEach { it.close() }
    }

    private fun ids(h: UiStateHolder) = h.ui.value.bandIds

    /** One distinct id per band. */
    private fun assertAligned(h: UiStateHolder) {
        val s = h.ui.value
        assertEquals(s.bands.size, s.bandIds.size)
        assertEquals(s.bandIds.size, s.bandIds.toSet().size)
    }

    private fun assertAllNew(before: List<Long>, after: List<Long>) {
        assertEquals(before.size, after.size)
        assertTrue("some ids survived: ${before.filter { it in after }}", after.none { it in before })
    }

    @Test fun theInitialIdsMatchTheBandCountAndAreDistinct() {
        val h = newHolder()
        assertEquals(7, ids(h).size)
        assertAligned(h)
    }

    @Test fun addBandFromListKeepsEveryOldIdAndAddsOne() {
        val h = newHolder()
        val before = ids(h)
        h.addBandFromList()
        assertEquals(8, ids(h).size)
        assertEquals(before, ids(h).take(7))
        assertAligned(h)
    }

    @Test fun addBandAtKeepsEveryOldIdAndAddsOne() {
        val h = newHolder()
        val before = ids(h)
        h.addBandAt(1500f, 3f)
        assertEquals(8, ids(h).size)
        assertEquals(before, ids(h).take(7))
        assertAligned(h)
    }

    @Test fun addingToAnEmptyListGivesOneId() {
        val h = newHolder()
        h.removeAllBands()
        h.addBandFromList()
        assertEquals(1, ids(h).size)
        assertAligned(h)
    }

    @Test fun removeBandDropsExactlyTheIdAtThatIndex() {
        val h = newHolder()
        val before = ids(h)
        h.removeBand(2)
        assertEquals(before.filterIndexed { i, _ -> i != 2 }, ids(h))
        h.removeBand(0)
        assertEquals(before.filterIndexed { i, _ -> i != 2 && i != 0 }, ids(h))
        assertAligned(h)
    }

    @Test fun removingAnIndexThatDoesNotExistKeepsTheIds() {
        val h = newHolder()
        val before = ids(h)
        h.removeBand(40)
        assertEquals(before, ids(h))
    }

    @Test fun removeAllBandsLeavesNoIds() {
        val h = newHolder()
        h.removeAllBands()
        assertTrue(ids(h).isEmpty())
    }

    @Test fun undoGivesEveryRowANewIdEvenWhenTheBandCountStays() {
        val h = newHolder()
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val before = ids(h)
        h.undo()
        assertEquals(FilterKind.PEAK, h.ui.value.bands[2].kind)
        assertAllNew(before, ids(h))
        assertAligned(h)
    }

    @Test fun undoOfADeleteGivesEveryRowANewId() {
        val h = newHolder()
        h.removeBand(0)
        val before = ids(h)
        h.undo()
        assertEquals(7, ids(h).size)
        assertTrue(ids(h).none { it in before })
        assertAligned(h)
    }

    @Test fun redoGivesEveryRowANewId() {
        val h = newHolder()
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        h.undo()
        val before = ids(h)
        h.redo()
        assertEquals(FilterKind.HIGH_SHELF, h.ui.value.bands[2].kind)
        assertAllNew(before, ids(h))
        assertAligned(h)
    }

    @Test fun undoIfUnchangedGivesEveryRowANewId() {
        val h = newHolder()
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val before = ids(h)
        h.undoIfUnchanged(h.editSerial.value)
        assertEquals(FilterKind.PEAK, h.ui.value.bands[2].kind)
        assertAllNew(before, ids(h))
    }

    @Test fun anUndoThatTheSerialRefusesKeepsTheIds() {
        val h = newHolder()
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val before = ids(h)
        h.undoIfUnchanged(h.editSerial.value - 1)
        assertEquals(before, ids(h))
    }

    @Test fun applyingAUserPresetGivesEveryRowANewId() {
        val h = newHolder()
        h.savePresetAs("Mine")
        val key = h.ui.value.presets.first { it.key.startsWith("user:") }.key
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val before = ids(h)
        h.applyPreset(key)
        assertEquals(FilterKind.PEAK, h.ui.value.bands[2].kind)
        assertAllNew(before, ids(h))
        assertAligned(h)
    }

    @Test fun resetAllInParametricModeGivesEveryRowANewId() {
        val h = newHolder()
        h.removeBand(1)
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val before = ids(h)
        h.resetAll()
        assertEquals(7, h.ui.value.bands.size)
        assertEquals(7, ids(h).size)
        assertTrue(ids(h).none { it in before })
        assertAligned(h)
    }

    @Test fun resetAllThatKeepsTheBandCountStillGivesEveryRowANewId() {
        val h = newHolder()
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        val before = ids(h)
        h.resetAll()
        assertEquals(FilterKind.PEAK, h.ui.value.bands[2].kind)
        assertAllNew(before, ids(h))
    }

    @Test fun anIdListThatDisagreesWithTheBandCountIsReplacedWhole() {
        val h = newHolder()
        val before = ids(h)
        // A band the holder did not add: the next republish finds 8 bands and 7 ids.
        ctrl.edit { it.addBand() }
        h.setBandEnabled(0, false)
        assertEquals(8, ids(h).size)
        assertTrue(ids(h).none { it in before })
        assertAligned(h)
    }

    @Test fun plainEditsKeepTheIdsExactly() {
        val h = newHolder()
        val before = ids(h)
        h.setBandKind(2, FilterKind.HIGH_SHELF)
        assertEquals(before, ids(h))
        h.setBandEnabled(3, false)
        assertEquals(before, ids(h))
        h.parametricGestureStart(1)
        h.parametricNodeChange(1, 400f, 2f)
        h.setBandQ(1, 2f)
        h.parametricGestureEnd()
        assertEquals(before, ids(h))
        h.setBandFromField(4, freqHz = 3000f)
        assertEquals(before, ids(h))
        h.flipBandGainSign(1)
        h.selectBand(5)
        h.selectBand(null)
        assertEquals(before, ids(h))
    }
}
