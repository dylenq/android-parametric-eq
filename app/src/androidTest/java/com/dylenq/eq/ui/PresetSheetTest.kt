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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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

    // ----- Task 3: sheet interactions -----

    private fun descOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun applyBassAndEdit() {
        holder.applyPreset("builtin:Bass")
        holder.setPreamp(-3f)
        compose.waitForIdle()
    }

    @Test fun emptyStateShownWithNoUserPresets() {
        launch()
        openSheet()
        compose.onNodeWithTag("preset-empty").assertIsDisplayed()
        compose.onNodeWithText("Save the current sound to reuse it").assertIsDisplayed()
        compose.onNodeWithText("Built-in").assertIsDisplayed()
        compose.onNodeWithText("Your presets").assertIsDisplayed()
    }

    @Test fun builtInRowsHaveNoOverflowButUserRowsDo() {
        launch()
        holder.savePresetAs("Night")
        compose.waitForIdle()
        openSheet()
        for (n in listOf("Flat", "Bass", "Treble", "Vocal")) {
            compose.onAllNodesWithTag("preset-menu-builtin:$n").assertCountEquals(0)
        }
        compose.onAllNodesWithTag("preset-empty").assertCountEquals(0)
        compose.onNodeWithTag("preset-menu-user:Night").assertIsDisplayed()
        assertEquals("More options for Night", descOf("preset-menu-user:Night"))
    }

    @Test fun saveDisabledWithNoPresetCarriesStateDescription() {
        launch()
        openSheet()
        compose.onNodeWithTag("preset-save").assertIsNotEnabled()
        compose.onNodeWithTag("preset-save-as").assertIsEnabled()
        compose.onNodeWithTag("preset-save").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Save, choose Save as new to name this sound"),
        )
    }

    @Test fun editedBuiltInShowsDotEditedLineAndKeepsSaveDisabled() {
        launch()
        applyBassAndEdit()
        compose.onNodeWithTag("preset-edited-dot", useUnmergedTree = true).assertIsDisplayed()
        openSheet()
        compose.onNodeWithTag("preset-row-builtin:Bass").assertIsDisplayed()
        compose.onNode(hasText("Edited") and hasAnyAncestor(hasTestTag("preset-row-builtin:Bass")), useUnmergedTree = true)
            .assertIsDisplayed()
        assertEquals("Bass, active, edited", descOf("preset-row-builtin:Bass"))
        compose.onNodeWithTag("preset-save").assertIsNotEnabled()
        compose.onNodeWithTag("preset-save").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Save, choose Save as new to name this sound"),
        )
    }

    @Test fun activeRowDescriptionWithoutEditIsActiveOnly() {
        launch()
        holder.applyPreset("builtin:Bass")
        compose.waitForIdle()
        openSheet()
        assertEquals("Bass, active", descOf("preset-row-builtin:Bass"))
        assertEquals("Flat", descOf("preset-row-builtin:Flat"))
    }

    @Test fun saveOverwriteClearsEditedAndKeepsSheetOpen() {
        launch()
        holder.setPreamp(-1f)
        holder.savePresetAs("Night")
        holder.setPreamp(-4f)
        compose.waitForIdle()
        assertTrue(holder.ui.value.presetModified)
        openSheet()
        compose.onNodeWithTag("preset-save").assertIsEnabled()
        compose.onNodeWithTag("preset-save").performClick()
        compose.waitForIdle()
        assertFalse(holder.ui.value.presetModified)
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("preset-edited-dot", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("preset-save").assertIsNotEnabled()
        compose.onNodeWithTag("preset-save").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Save, no changes to save"),
        )
    }

    @Test fun staleKeyApplyDoesNotCrash() {
        launch()
        holder.applyPreset("user:Gone")
        compose.waitForIdle()
        compose.waitUntil(3000) { compose.onAllNodes(hasText("That preset no longer exists.")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rowIsInertWhileSliderGestureIsOpen() {
        launch()
        holder.onBandDragStart(0)
        holder.applyPreset("builtin:Bass")
        compose.waitForIdle()
        assertNull(holder.ui.value.activePresetKey)
        holder.onBandDragEnd()
    }
}
