package com.dylenq.eq.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.MainActivity
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two paths of the band fields that only the real screen reaches: a draft left in a field when the
 * activity is recreated, and Ctrl+Z while a field has focus. Each test fails if the code it guards is removed.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class BandFieldPathsTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    private fun launchParametric(): ActivityScenario<MainActivity> {
        ctrl.edit { it.setMode(EqMode.PARAMETRIC) }
        return rule.launch().also { compose.waitForIdle() }
    }

    private fun fieldText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /**
     * Selects band [index], expands its row and sets [text] in its frequency field without focusing it. A
     * focused field commits when the window loses focus, before the state is saved, so only a draft in an
     * unfocused field reaches the restore path.
     */
    private fun typeDraft(index: Int, text: String) {
        holder.selectBand(index)
        compose.waitForIdle()
        val tag = "band-field-freq-$index"
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsNotFocused()
    }

    @Test fun aClampedDraftLeftInTheFieldIsCommittedAfterRotation() {
        val scenario = launchParametric()
        val before = ctrl.snapshot().bands[2].freq
        typeDraft(2, "99999")
        assertEquals("the premise: the clamped text is not applied while typing", before, ctrl.snapshot().bands[2].freq, 0f)
        assertEquals("99999", fieldText("band-field-freq-2"))

        scenario.recreate()
        compose.waitForIdle()

        assertEquals(20000f, ctrl.snapshot().bands[2].freq, 0f)
        compose.onNodeWithTag("band-field-freq-2").performScrollTo()
        assertEquals("20000", fieldText("band-field-freq-2"))
        compose.onNodeWithTag("band-field-message-2").assertTextEquals(NumericField.FREQ_MESSAGE)
    }

    @Test fun anUnparsableDraftLeftInTheFieldIsResetAndExplainedAfterRotation() {
        val scenario = launchParametric()
        val before = ctrl.snapshot().bands[2].freq
        val shown = NumericField.formatFrequency(before)
        typeDraft(2, "abc")
        assertEquals("the premise: the draft is in the field", "abc", fieldText("band-field-freq-2"))

        scenario.recreate()
        compose.waitForIdle()

        assertEquals("the band is unchanged", before, ctrl.snapshot().bands[2].freq, 0f)
        compose.onNodeWithTag("band-field-freq-2").performScrollTo()
        assertEquals(shown, fieldText("band-field-freq-2"))
        compose.onNodeWithTag("band-field-message-2").assertTextEquals(NumericField.INVALID_MESSAGE)
    }

    private fun ctrlZ() = compose.onRoot().performKeyInput {
        keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft)
    }

    @Test fun ctrlZInAFocusedBandFieldIsLeftToTheField() {
        launchParametric()
        holder.setBandKind(2, FilterKind.HIGH_SHELF)
        compose.waitForIdle()

        // Positive control: with nothing focused the shortcuts reach the screen.
        assertFalse(holder.ui.value.gestureOpen)
        ctrlZ()
        compose.waitForIdle()
        assertEquals("Ctrl+Z undoes", FilterKind.PEAK, ctrl.snapshot().bands[2].kind)
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.Z); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        assertEquals("Ctrl+Shift+Z redoes", FilterKind.HIGH_SHELF, ctrl.snapshot().bands[2].kind)

        // Now with a field focused and holding a gesture open.
        holder.selectBand(3)
        compose.waitForIdle()
        val tag = "band-field-freq-3"
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
        assertTrue("the premise: the focused field holds a gesture open", holder.ui.value.gestureOpen)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("1234")) }
        compose.waitForIdle()
        assertEquals("1234", fieldText(tag))
        val kind = ctrl.snapshot().bands[2].kind
        val canUndo = ctrl.snapshot().canUndo
        val canRedo = ctrl.snapshot().canRedo

        compose.onNodeWithTag(tag).performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()

        assertEquals("the EQ did not undo", kind, ctrl.snapshot().bands[2].kind)
        assertEquals(canUndo, ctrl.snapshot().canUndo)
        assertEquals(canRedo, ctrl.snapshot().canRedo)
        assertTrue("the field took the key itself", fieldText(tag) != "1234")
    }
}
