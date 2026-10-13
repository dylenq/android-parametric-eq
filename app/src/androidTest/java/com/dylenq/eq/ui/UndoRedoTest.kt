package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PEQ-06: Undo and Redo in the top bar (both modes), the overflow menu, shortcuts and the undoable snackbars. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class UndoRedoTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose: ComposeTestRule get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    private fun launch(mode: EqMode = EqMode.GRAPHIC) {
        ctrl.edit { it.setMode(mode) }
        rule.launch()
        compose.waitForIdle()
    }

    private fun gone(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    private fun editGraphic() {
        holder.onBandDragStart(0)
        holder.onBandDragChange(0, 4f)
        holder.onBandDragEnd()
        compose.waitForIdle()
    }

    private fun editParametric() {
        holder.setBandKind(2, com.dylenq.eq.core.FilterKind.HIGH_SHELF)
        compose.waitForIdle()
    }

    // ----- Task 1: buttons, overflow, shortcuts -----

    @Test fun bothDisabledOnAFreshStateInGraphic() {
        launch(EqMode.GRAPHIC)
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("redo").assertIsNotEnabled()
    }

    @Test fun bothDisabledOnAFreshStateInParametric() {
        launch(EqMode.PARAMETRIC)
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("redo").assertIsNotEnabled()
    }

    @Test fun editEnablesUndoThenUndoEnablesRedoAndRedoReapplies() {
        launch(EqMode.GRAPHIC)
        editGraphic()
        compose.onNodeWithTag("undo").assertIsEnabled()
        compose.onNodeWithTag("redo").assertIsNotEnabled()
        compose.onNodeWithTag("undo").performClick()
        compose.waitForIdle()
        assertEquals(0f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("redo").assertIsEnabled()
        compose.onNodeWithTag("redo").performClick()
        compose.waitForIdle()
        assertEquals(4f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        compose.onNodeWithTag("redo").assertIsNotEnabled()
    }

    @Test fun parametricEditEnablesUndoAndTheButtonRevertsIt() {
        launch(EqMode.PARAMETRIC)
        editParametric()
        compose.onNodeWithTag("undo").assertIsEnabled()
        compose.onNodeWithTag("undo").performClick()
        compose.waitForIdle()
        assertEquals(com.dylenq.eq.core.FilterKind.PEAK, ctrl.snapshot().bands[2].kind)
    }

    @Test fun actionsAreOrderedUndoRedoToggleOverflowWithNamesAndSizes() {
        launch(EqMode.PARAMETRIC)
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val tags = listOf("undo", "redo", "equalizer-toggle", "overflow")
        val rects = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        for (i in 0 until rects.size - 1) {
            assertTrue("${tags[i]} left of ${tags[i + 1]}", rects[i].right <= rects[i + 1].left + 0.5f)
        }
        for ((i, r) in rects.withIndex()) {
            assertEquals("${tags[i]} is 48 dp wide", 48f, r.width / d, 0.6f)
            assertEquals("${tags[i]} is 48 dp tall", 48f, r.height / d, 0.6f)
        }
        compose.onNodeWithTag("undo").assertContentDescriptionEquals("Undo")
        compose.onNodeWithTag("redo").assertContentDescriptionEquals("Redo")
    }

    @Test fun fourActionsFitAt360DpWithoutTruncatingTitle() {
        launch(EqMode.GRAPHIC)
        compose.runOnIdle { }
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            Box(Modifier.width(360.dp)) { EqTopBar({ ui }, holder::setBypass, onResetAll = {}) }
        }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("EQ").fetchSemanticsNode().boundsInRoot
        val undo = compose.onNodeWithTag("undo").fetchSemanticsNode().boundsInRoot
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("title inside the root", title.left >= root.left && title.right <= root.right)
        assertTrue("title ends before Undo starts: ${title.right} vs ${undo.left}", title.right <= undo.left)
        assertTrue("title is wide enough for EQ: ${title.width / d} dp", title.width / d >= 20f)
    }

    @Test fun overflowListsResetAllThenSettingsAndSettingsIsOpened() {
        launch(EqMode.GRAPHIC)
        compose.onNodeWithTag("overflow").performClick()
        compose.waitForIdle()
        val reset = compose.onNodeWithTag("reset-all").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag("settings-item").fetchSemanticsNode().boundsInRoot
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Reset all above Settings", reset.top < settings.top)
        assertTrue("Settings at least 48 dp tall: ${settings.height / d}", settings.height / d >= 47.5f)
        compose.onNodeWithText("Settings").assertExists()
    }

    @Test fun settingsItemCallsOnOpenSettings() {
        launch(EqMode.GRAPHIC)
        var opened = 0
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            EqTopBar({ ui }, holder::setBypass, onResetAll = {}, onOpenSettings = { opened++ })
        }
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("settings-item").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        assertTrue("menu closed", compose.onAllNodes(androidx.compose.ui.test.hasTestTag("settings-item")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun resetAllInParametricIsEnabledOnDefaultsAndRestoresDefaults() {
        launch(EqMode.PARAMETRIC)
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").assertIsEnabled()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        // Nothing to reset: no change, so no message.
        assertTrue(gone("Bands and preamp reset"))
        holder.setBandKind(1, com.dylenq.eq.core.FilterKind.HIGH_SHELF)
        holder.setPreamp(-3f)
        compose.waitForIdle()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        assertEquals(com.dylenq.eq.core.FilterKind.PEAK, ctrl.snapshot().bands[1].kind)
        assertEquals(0f, ctrl.snapshot().preampDb, 0f)
        assertTrue(gone(RESET_ALL_MESSAGE))
    }

    @Test fun resetAllInGraphicKeepsItsOwnSnackbar() {
        launch(EqMode.GRAPHIC)
        editGraphic()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(RESET_ALL_MESSAGE).assertExists()
        assertEquals("exactly one snackbar", 1, compose.onAllNodesWithText("Undo").fetchSemanticsNodes().size)
    }

    @Test fun ctrlZUndoesAndCtrlShiftZAndCtrlYRedo() {
        launch(EqMode.GRAPHIC)
        editGraphic()
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        assertEquals("Ctrl+Z undoes", 0f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.Z); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        assertEquals("Ctrl+Shift+Z redoes", 4f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Y); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        assertEquals("Ctrl+Y redoes", 4f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
    }

    // ----- Task 2: undoable snackbars -----

    private fun waitGone(text: String) = compose.waitUntil(8_000) { gone(text) }

    @Test fun deletingABandShowsItsSnackbarAndUndoRestoresTheBand() {
        launch(EqMode.PARAMETRIC)
        val before = ctrl.snapshot().bands[3]
        holder.removeBand(3)
        compose.waitForIdle()
        assertEquals(6, ctrl.snapshot().bands.size)
        compose.onNodeWithText("Band 4 deleted").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(7, ctrl.snapshot().bands.size)
        assertEquals(before, ctrl.snapshot().bands[3])
    }

    @Test fun removeAllShowsItsSnackbarAndUndoRestoresEveryBand() {
        launch(EqMode.PARAMETRIC)
        val before = ctrl.snapshot().bands
        holder.removeAllBands()
        compose.waitForIdle()
        assertEquals(0, ctrl.snapshot().bands.size)
        compose.onNodeWithText("All bands removed").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(before, ctrl.snapshot().bands)
    }

    @Test fun resetShowsItsSnackbarAndUndoRestoresTheOldState() {
        launch(EqMode.PARAMETRIC)
        holder.setBandKind(1, com.dylenq.eq.core.FilterKind.HIGH_SHELF)
        holder.setPreamp(-4f)
        compose.waitForIdle()
        holder.resetAll()
        compose.waitForIdle()
        compose.onNodeWithText("Bands and preamp reset").assertExists()
        assertEquals(0f, ctrl.snapshot().preampDb, 0f)
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(com.dylenq.eq.core.FilterKind.HIGH_SHELF, ctrl.snapshot().bands[1].kind)
        assertEquals(-4f, ctrl.snapshot().preampDb, 0f)
    }

    @Test fun resetFromTheOverflowMenuInParametricShowsTheNewMessageOnce() {
        launch(EqMode.PARAMETRIC)
        holder.setBandKind(1, com.dylenq.eq.core.FilterKind.HIGH_SHELF)
        compose.waitForIdle()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Bands and preamp reset").assertExists()
        assertTrue("old Graphic text is not used", gone(RESET_ALL_MESSAGE))
        assertEquals("one Undo action", 1, compose.onAllNodesWithText("Undo").fetchSemanticsNodes().size)
    }

    @Test fun aLaterEditDismissesTheSnackbarSoItsUndoCannotRevertThatEdit() {
        launch(EqMode.PARAMETRIC)
        holder.removeBand(3)
        compose.waitForIdle()
        compose.onNodeWithText("Band 4 deleted").assertExists()
        holder.setPreamp(-2f)
        waitGone("Band 4 deleted")
        assertEquals(-2f, ctrl.snapshot().preampDb, 0f)
        assertEquals("the delete stays", 6, ctrl.snapshot().bands.size)
    }

    @Test fun anUndoOrRedoAlsoDismissesTheSnackbar() {
        launch(EqMode.PARAMETRIC)
        holder.removeBand(3)
        compose.waitForIdle()
        compose.onNodeWithTag("undo").performClick()
        waitGone("Band 4 deleted")
        assertEquals(7, ctrl.snapshot().bands.size)
        compose.onNodeWithTag("redo").performClick()
        compose.waitForIdle()
        assertEquals("redo re-applies the delete without a snackbar", 6, ctrl.snapshot().bands.size)
        assertTrue(gone("Band 4 deleted"))
    }

    @Test fun redoAfterTheSnackbarUndoIsAvailableFromTheTopBar() {
        launch(EqMode.PARAMETRIC)
        holder.removeBand(3)
        compose.waitForIdle()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("redo").assertIsEnabled()
        compose.onNodeWithTag("redo").performClick()
        compose.waitForIdle()
        assertEquals(6, ctrl.snapshot().bands.size)
    }

    @Test fun theSixteenBandMessageHasNoUndoAndIsPlain() {
        launch(EqMode.PARAMETRIC)
        while (ctrl.snapshot().bands.size < 16) holder.addBandFromList()
        compose.waitForIdle()
        // The last successful add also showed nothing undoable; clear any snackbar first.
        compose.waitForIdle()
        holder.addBandFromList()
        compose.waitForIdle()
        compose.onNodeWithText("All 16 bands are in use. Remove one to add another.").assertExists()
        assertTrue("no Undo action on the limit message", gone("Undo"))
        assertEquals(16, ctrl.snapshot().bands.size)
    }

    @Test fun aNewMessageReplacesTheOneOnScreen() {
        launch(EqMode.PARAMETRIC)
        holder.removeBand(3)
        compose.waitForIdle()
        compose.onNodeWithText("Band 4 deleted").assertExists()
        holder.removeAllBands()
        compose.waitUntil(8_000) { !gone("All bands removed") }
        waitGone("Band 4 deleted")
        assertEquals(1, compose.onAllNodesWithText("Undo").fetchSemanticsNodes().size)
    }

    // ----- Task 3: behaviour on the real screen -----

    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private val layer get() = compose.onNodeWithTag("curve-interaction")

    private fun plot(): androidx.compose.ui.geometry.Rect {
        val size = layer.fetchSemanticsNode().size
        return PlotGeometry.plotRect(size.width.toFloat(), size.height.toFloat(), density)
    }

    private fun nodeCenter(index: Int): androidx.compose.ui.geometry.Offset {
        val b = ctrl.snapshot().bands[index]
        return ParametricMath.nodePosition(b.freq, b.gainDb, b.kind.hasGain(), plot(), AxisScale.PARAMETRIC_Y_HALF_SPAN_DB)
    }

    @Test fun aDragIsUndoneAsOneStepFromTheTopBar() {
        launch(EqMode.PARAMETRIC)
        val original = ctrl.snapshot().bands[3]
        val start = nodeCenter(3)
        layer.performTouchInput {
            down(start)
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(8f * density, -6f * density)) }
            up()
        }
        compose.waitForIdle()
        val moved = ctrl.snapshot().bands[3]
        assertTrue("the node moved", moved.freq != original.freq && moved.gainDb != original.gainDb)
        compose.onNodeWithTag("undo").assertIsEnabled()
        compose.onNodeWithTag("undo").performClick()
        compose.waitForIdle()
        assertEquals("one tap restores the exact original band", original, ctrl.snapshot().bands[3])
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("redo").assertIsEnabled()
    }

    @Test fun undoIsDisabledAndInertWhileADragIsInProgress() {
        launch(EqMode.PARAMETRIC)
        editParametric() // gives Undo something to do
        val start = nodeCenter(4)
        val during: com.dylenq.eq.core.Band
        layer.performTouchInput {
            down(start)
            repeat(4) { moveBy(androidx.compose.ui.geometry.Offset(6f * density, -5f * density)) }
        }
        compose.waitForIdle()
        assertTrue("a gesture is open", holder.ui.value.gestureOpen)
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("redo").assertIsNotEnabled()
        during = ctrl.snapshot().bands[4]
        holder.undo()
        holder.redo()
        compose.waitForIdle()
        assertEquals("undo and redo changed nothing mid-gesture", during, ctrl.snapshot().bands[4])
        assertEquals("the earlier edit is still applied", com.dylenq.eq.core.FilterKind.HIGH_SHELF, ctrl.snapshot().bands[2].kind)
        layer.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("undo").assertIsEnabled()
    }

    @Test fun removeAllThenSnackbarUndoThenTopBarRedoRemovesThemAgain() {
        launch(EqMode.PARAMETRIC)
        val before = ctrl.snapshot().bands
        holder.removeAllBands()
        compose.waitForIdle()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(before, ctrl.snapshot().bands)
        compose.onNodeWithTag("redo").performClick()
        compose.waitForIdle()
        assertEquals(0, ctrl.snapshot().bands.size)
        holder.setPreamp(-1f)
        compose.waitForIdle()
        assertTrue("no stale snackbar remains", gone("All bands removed"))
    }

    private fun selectedOverlay() = holder.ui.value.curve?.selectedBandDb

    @Test fun undoingAnAddClearsTheSelectionAndTheOverlay() {
        launch(EqMode.PARAMETRIC)
        val p = plot()
        val spot = androidx.compose.ui.geometry.Offset(p.left + p.width * 0.62f, p.top + p.height * 0.15f)
        layer.performTouchInput { doubleClick(spot) }
        compose.waitForIdle()
        assertEquals(8, ctrl.snapshot().bands.size)
        assertEquals("the added band is selected", 7, holder.ui.value.selectedBand)
        compose.waitUntil(5_000) { selectedOverlay() != null }
        compose.onNodeWithTag("undo").performClick()
        compose.waitForIdle()
        assertEquals(7, ctrl.snapshot().bands.size)
        assertEquals("the index no longer exists: selection cleared", null, holder.ui.value.selectedBand)
        compose.waitUntil(5_000) { selectedOverlay() == null }
        compose.onNodeWithTag("redo").performClick()
        compose.waitForIdle()
        assertEquals(8, ctrl.snapshot().bands.size)
        val sel = holder.ui.value.selectedBand
        assertTrue("selection after redo is null or a valid index: $sel", sel == null || sel in 0 until 8)
    }

    /**
     * L-07 / v1 limitation, pinned as fact: Graphic and Parametric share ONE linear history. A Graphic gain edit,
     * a switch to Parametric, then Undo reverts the Graphic gain even though it is not on screen (an invisible step
     * in Parametric). The mode switch itself is not a history step. Documented in plan 12.
     */
    @Test fun crossModeUndoRevertsTheOtherModesEdit_L07() {
        launch(EqMode.GRAPHIC)
        editGraphic()
        assertEquals(4f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        holder.setMode(EqMode.PARAMETRIC)
        compose.waitForIdle()
        assertEquals(EqMode.PARAMETRIC, ctrl.snapshot().mode)
        compose.onNodeWithTag("undo").assertIsEnabled()
        compose.onNodeWithTag("undo").performClick()
        compose.waitForIdle()
        assertEquals("the Graphic edit was undone from Parametric", 0f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        assertEquals("and the mode stayed Parametric", EqMode.PARAMETRIC, ctrl.snapshot().mode)
        // Switching back and redoing from Graphic re-applies the same Graphic edit: one history, both directions.
        holder.setMode(EqMode.GRAPHIC)
        compose.waitForIdle()
        compose.onNodeWithTag("redo").performClick()
        compose.waitForIdle()
        assertEquals("redo re-applies the Graphic edit", 4f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
    }
}
