package com.dylenq.eq.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.MainActivity
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PRE-02..PRE-04: save-as, rename and delete dialogs, their validation copy and rotation survival. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PresetDialogsTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose: ComposeTestRule get() = rule.compose
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun launch() {
        scenario = rule.launch()
        compose.waitForIdle()
    }

    private fun openSheet() {
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun typeName(text: String) {
        compose.onNodeWithTag("preset-name-field").performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun fieldText(): String =
        compose.onNodeWithTag("preset-name-field").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun chipDesc(): String =
        compose.onNodeWithTag("preset-chip").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun dialogClosed() = compose.onAllNodesWithTag("preset-name-field").assertCountEquals(0)

    private fun openSaveAs() {
        openSheet()
        click("preset-save-as")
    }

    private fun openRename(key: String) {
        click("preset-menu-$key")
        click("preset-rename")
    }

    private fun withNight() {
        holder.setPreamp(-1f)
        holder.savePresetAs("Night")
        compose.waitForIdle()
    }

    @Test fun saveAsBlankDisablesConfirmWithoutAnError() {
        launch()
        openSaveAs()
        compose.onNodeWithTag("preset-dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithText("Up to 40 characters").assertIsDisplayed()
        typeName("   ")
        compose.onNodeWithTag("preset-dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithText("Enter a name").assertIsDisplayed()
    }

    @Test fun saveAsTakenNameIsRejectedAnyCaseAndDialogStays() {
        launch()
        openSaveAs()
        typeName("flat")
        compose.onNodeWithText("A preset with that name already exists").assertIsDisplayed()
        compose.onNodeWithTag("preset-dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("preset-name-field").assertIsDisplayed()
    }

    @Test fun saveAsCapsPastedTextAtFortyCharacters() {
        launch()
        openSaveAs()
        typeName("x".repeat(41))
        assertEquals(40, fieldText().length)
        compose.onNodeWithTag("preset-dialog-confirm").assertIsEnabled()
    }

    @Test fun saveAsSuccessClosesDialogAndSheetAndChipReadsNewName() {
        launch()
        holder.setPreamp(-2f)
        compose.waitForIdle()
        openSaveAs()
        typeName("Night")
        click("preset-dialog-confirm")
        dialogClosed()
        compose.onAllNodesWithTag("preset-sheet").assertCountEquals(0)
        compose.waitUntil(3000) { compose.onAllNodes(hasText("Saved Night")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Preset: Night. Opens preset list", chipDesc())
        compose.onAllNodesWithTag("preset-edited-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun saveAsAtTheCapShowsTheCapMessageAndStaysOpen() {
        launch()
        var i = 0
        while (i < 120 && holder.savePresetAs("P$i") == PresetResult.Ok) i++
        compose.waitForIdle()
        openSaveAs()
        typeName("Overflow")
        click("preset-dialog-confirm")
        compose.onNodeWithText(PresetNames.TOO_MANY).assertIsDisplayed()
        compose.onNodeWithTag("preset-name-field").assertIsDisplayed()
    }

    @Test fun cancelClosesDialogAndLeavesSheetOpen() {
        launch()
        openSaveAs()
        click("preset-dialog-cancel")
        dialogClosed()
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
    }

    @Test fun renameIsPrefilledAndCaseOnlyChangeKeepsItActive() {
        launch()
        withNight()
        openSheet()
        openRename("user:Night")
        assertEquals("Night", fieldText())
        // Unchanged: confirm disabled.
        compose.onNodeWithTag("preset-dialog-confirm").assertIsNotEnabled()
        typeName("night")
        compose.onNodeWithTag("preset-dialog-confirm").assertIsEnabled()
        click("preset-dialog-confirm")
        dialogClosed()
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
        assertEquals("night", holder.ui.value.activePresetName)
        assertEquals("Preset: night. Opens preset list", chipDesc())
    }

    @Test fun renameToAnotherNameKeepsCheckAndUpdatesChip() {
        launch()
        withNight()
        openSheet()
        openRename("user:Night")
        typeName("Evening")
        click("preset-dialog-confirm")
        dialogClosed()
        val active = holder.ui.value.activePresetKey
        assertTrue(active != null && holder.ui.value.activePresetName == "Evening")
        assertEquals("Preset: Evening. Opens preset list", chipDesc())
        compose.onNodeWithTag("preset-row-$active").assertIsDisplayed()
    }

    @Test fun renameToATakenNameShowsErrorAndStaysOpen() {
        launch()
        withNight()
        openSheet()
        openRename("user:Night")
        typeName("Bass")
        compose.onNodeWithText("A preset with that name already exists").assertIsDisplayed()
        compose.onNodeWithTag("preset-dialog-confirm").assertIsNotEnabled()
    }

    @Test fun deleteConfirmationTextIsExactAndCancelKeepsThePreset() {
        launch()
        withNight()
        openSheet()
        click("preset-menu-user:Night")
        click("preset-delete")
        compose.onNodeWithText("Delete preset?").assertIsDisplayed()
        compose.onNodeWithText("Delete Night? This can't be undone.").assertIsDisplayed()
        click("preset-dialog-cancel")
        compose.onAllNodes(hasText("Delete Night? This can't be undone.")).assertCountEquals(0)
        assertTrue(holder.ui.value.presets.any { it.name == "Night" })
    }

    @Test fun deleteRemovesPresetLeavesSheetOpenAndActiveBecomesNoPreset() {
        launch()
        withNight()
        openSheet()
        click("preset-menu-user:Night")
        click("preset-delete")
        click("preset-dialog-confirm")
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
        assertTrue(holder.ui.value.presets.none { it.name == "Night" })
        assertNull(holder.ui.value.activePresetKey)
        compose.onNodeWithTag("preset-empty").assertIsDisplayed()
        assertEquals("Preset: none. Opens preset list", chipDesc())
    }

    @Test fun recreationKeepsTheRenameDialogAndTypedText() {
        launch()
        withNight()
        openSheet()
        openRename("user:Night")
        typeName("Evening")
        scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("preset-name-field").assertIsDisplayed()
        assertEquals("Evening", fieldText())
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
    }
}
