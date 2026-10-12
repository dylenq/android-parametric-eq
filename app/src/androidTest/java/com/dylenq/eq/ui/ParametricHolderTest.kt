package com.dylenq.eq.ui

import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.RuntimeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Gesture accounting, undo/redo/mode locking and selection rules of the Parametric holder state. */
class ParametricHolderTest {
    @get:Rule val rt = RuntimeRule()

    private val holders = mutableListOf<UiStateHolder>()
    private val ctrl get() = rt.runtime.controller

    private fun newHolder() = UiStateHolder(rt.runtime).also { holders += it }

    @After fun closeHolders() { holders.forEach { it.close() } }

    /** Undoes until nothing is left through the core (the holder may be locked) and counts the steps. */
    private fun undoDepth(): Int {
        var n = 0
        while (ctrl.snapshot().canUndo && n < 50) {
            ctrl.edit { it.undo() }
            n++
        }
        return n
    }

    /** Switches to Parametric through the core (the mode is not part of the undo history). */
    private fun parametricSetup() = ctrl.edit { it.setMode(EqMode.PARAMETRIC) }

    private fun gestureSteps() = undoDepth()

    @Test fun overlappingGesturesFromTwoControlsAreOneUndoStep() {
        for (parametricFirst in listOf(true, false)) {
            parametricSetup()
            val h = newHolder()
            if (parametricFirst) h.parametricGestureStart(2) else h.onBandDragStart(0)
            if (parametricFirst) h.onBandDragStart(0) else h.parametricGestureStart(2)
            assertTrue(h.ui.value.gestureOpen)
            h.onBandDragChange(0, 4f)
            h.parametricNodeChange(2, 300f, 3f)
            h.onBandDragChange(0, 6f)
            if (parametricFirst) h.parametricGestureEnd() else h.onBandDragEnd()
            assertTrue("still open until the last control ends", h.ui.value.gestureOpen)
            if (parametricFirst) h.onBandDragEnd() else h.parametricGestureEnd()
            assertFalse(h.ui.value.gestureOpen)
            assertEquals("two controls, one undo step (parametricFirst=$parametricFirst)", 1, gestureSteps())
            assertEquals(500f, ctrl.snapshot().bands[2].freq, 0.01f)
            assertEquals(0f, ctrl.snapshot().graphicGainsDb[0], 0f)
        }
    }

    @Test fun preampAndParametricOverlapAreOneStepAndDoubleStartsAreHarmless() {
        parametricSetup()
        val h = newHolder()
        h.parametricGestureStart(1)
        h.parametricGestureStart(1) // second start from the same source: ignored
        h.preampDragStart()
        h.setPreamp(-3f)
        h.parametricNodeChange(1, 250f, 2f)
        h.parametricGestureEnd()
        h.parametricGestureEnd() // unmatched end: ignored
        assertTrue(h.ui.value.gestureOpen)
        h.preampDragEnd()
        assertFalse(h.ui.value.gestureOpen)
        // The first gesture is one step; undo it so the next count starts clean.
        ctrl.edit { it.undo() }
        assertEquals(200f, ctrl.snapshot().bands[1].freq, 0.01f)
        assertEquals(0f, ctrl.snapshot().preampDb, 0f)
        // A stray end after everything closed must not corrupt the next, separate gesture.
        h.parametricGestureEnd()
        h.parametricGestureStart(1)
        h.parametricNodeChange(1, 260f, 2f)
        h.parametricGestureEnd()
        assertEquals(1, gestureSteps())
    }

    @Test fun undoRedoAndModeSwitchAreInertWhileAGestureIsOpen() {
        parametricSetup()
        ctrl.edit { it.setPreamp(-2f) } // something to undo and redo
        ctrl.edit { it.undo() }
        val h = newHolder()
        h.parametricGestureStart(2)
        h.parametricNodeChange(2, 300f, 3f)
        val serial = h.editSerial.value
        val bands = ctrl.snapshot().bands

        h.undo()
        h.redo()
        h.setMode(EqMode.GRAPHIC)
        assertEquals(serial, h.editSerial.value)
        assertEquals(bands, ctrl.snapshot().bands)
        assertEquals("redo did nothing", 0f, ctrl.snapshot().preampDb, 0f)
        assertEquals(EqMode.PARAMETRIC, ctrl.snapshot().mode)
        assertEquals(EqMode.PARAMETRIC, h.ui.value.mode)

        h.parametricGestureEnd()
        h.undo() // now works: undoes the node drag
        assertEquals(500f, ctrl.snapshot().bands[2].freq, 0.01f)
        h.setMode(EqMode.GRAPHIC)
        assertEquals(EqMode.GRAPHIC, ctrl.snapshot().mode)
    }

    @Test fun leavingParametricClearsTheSelection() {
        parametricSetup()
        val h = newHolder()
        h.selectBand(4)
        assertEquals(4, h.ui.value.selectedBand)
        h.selectBand(40) // out of range: ignored
        assertEquals(4, h.ui.value.selectedBand)
        h.setMode(EqMode.GRAPHIC)
        assertNull(h.ui.value.selectedBand)
        h.setMode(EqMode.PARAMETRIC)
        assertNull("not restored on return", h.ui.value.selectedBand)
    }

    @Test fun selectionIsClampedWhenUndoRemovesTheBand() {
        parametricSetup()
        ctrl.edit { it.addBand() } // 8 bands, one undo step
        val h = newHolder()
        assertEquals(8, h.ui.value.bands.size)
        h.selectBand(7)
        assertEquals(7, h.ui.value.selectedBand)
        h.undo() // the add is undone: band 7 no longer exists
        assertEquals(7, h.ui.value.bands.size)
        assertNull(h.ui.value.selectedBand)
        h.redo()
        assertEquals(8, h.ui.value.bands.size)
        assertNull("never resurrected by redo", h.ui.value.selectedBand)
    }
}
