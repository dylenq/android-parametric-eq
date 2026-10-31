package com.dylenq.eq.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PRE-01..PRE-04: the preset chip, the picker sheet and the Applied/Undo snackbar. */
@RunWith(AndroidJUnit4::class)
class PresetSheetTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose: ComposeTestRule get() = rule.compose

    private fun launch() {
        rule.launch()
        compose.waitForIdle()
    }

    private fun openSheet() {
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
    }

    private fun chipText(): String =
        compose.onNodeWithTag("preset-chip", useUnmergedTree = true).let {
            it.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty().joinToString()
        }

    @Test fun chipShowsNoPresetOnFreshState() {
        launch()
        compose.onNodeWithTag("preset-chip").assertIsDisplayed()
        compose.onNode(hasText("No preset")).assertIsDisplayed()
        assertEquals("Preset: none. Opens preset list", chipText())
        compose.onAllNodesWithTag("preset-edited-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun tapChipOpensSheetWithBuiltIns() {
        launch()
        compose.onAllNodesWithTag("preset-sheet").assertCountEquals(0)
        openSheet()
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
        for (n in listOf("Flat", "Bass", "Treble", "Vocal")) {
            compose.onNodeWithTag("preset-row-builtin:$n").assertIsDisplayed()
        }
    }

    @Test fun tappingBassClosesSheetShowsAppliedSnackbarAndChipReadsBass() {
        launch()
        openSheet()
        compose.onNodeWithTag("preset-row-builtin:Bass").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("preset-sheet").assertCountEquals(0)
        compose.waitUntil(3000) { compose.onAllNodes(hasText("Applied Bass")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").assertIsDisplayed()
        assertEquals("Bass", holder.ui.value.activePresetName)
        assertEquals("Preset: Bass. Opens preset list", chipText())
    }

    @Test fun undoFromSnackbarRestoresPriorStateAndChipReturnsToNoPreset() {
        launch()
        openSheet()
        compose.onNodeWithTag("preset-row-builtin:Bass").performClick()
        compose.waitForIdle()
        compose.waitUntil(3000) { compose.onAllNodes(hasText("Applied Bass")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertNull(holder.ui.value.activePresetKey)
        compose.waitUntil(3000) { chipText() == "Preset: none. Opens preset list" }
    }
}
