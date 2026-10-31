package com.dylenq.eq.ui

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 09-UI-SPEC backstop cases: rapid input, an open gesture and a stale preset key. */
@RunWith(AndroidJUnit4::class)
class PresetSheetEdgeTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose

    private fun launchAndOpenSheet() {
        rule.launch()
        compose.waitForIdle()
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
    }

    @Test fun doubleTapOnARowYieldsOneUndoStep() {
        launchAndOpenSheet()
        compose.onNodeWithTag("preset-row-builtin:Bass").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals("builtin:Bass", holder.ui.value.activePresetKey)
        assertTrue(holder.ui.value.canUndo)
        holder.undo()
        compose.waitForIdle()
        assertNull("one undo returns to the pre-apply state", holder.ui.value.activePresetKey)
        assertFalse("no second step", holder.ui.value.canUndo)
        assertEquals(0f, holder.ui.value.bassDb, 0f)
    }

    @Test fun tappingARowWhileAGestureIsOpenDoesNothing() {
        launchAndOpenSheet()
        holder.onBandDragStart(0)
        compose.onNodeWithTag("preset-row-builtin:Bass").performClick()
        compose.waitForIdle()
        assertNull(holder.ui.value.activePresetKey)
        assertEquals(0f, holder.ui.value.bassDb, 0f)
        holder.onBandDragEnd()
        compose.waitForIdle()
        assertNull(holder.ui.value.activePresetKey)
    }

    @Test fun aRowRemovedWhileTheSheetIsOpenShowsNoLongerExistsAndDoesNotCrash() {
        rule.launch()
        compose.waitForIdle()
        holder.savePresetAs("Night")
        compose.waitForIdle()
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("preset-row-user:Night").assertExists()
        holder.deletePreset("user:Night")
        compose.waitForIdle()
        // The stale row's tap is the same call the row makes.
        holder.applyPreset("user:Night")
        // The snackbar sits behind the modal sheet, so close the sheet to see it.
        compose.onAllNodesWithTag("preset-row-user:Night").assertCountEquals(0)
        Espresso.pressBack()
        compose.waitForIdle()
        compose.waitUntil(3000) { compose.onAllNodes(hasText("That preset no longer exists.")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(holder.ui.value.activePresetKey)
    }
}
