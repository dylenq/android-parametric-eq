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
}
